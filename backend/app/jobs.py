"""Job storage and execution.

Each job is a directory under ``<data_dir>/jobs/<id>``. ``job.json`` is owned by
the API; the pipeline subprocess writes ``progress.json`` and ``result.json``.
A small scheduler thread starts queued jobs, at most ``max_concurrent_jobs`` at
a time, and records the final state when a subprocess exits.
"""
from __future__ import annotations

import json
import logging
import os
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid
from pathlib import Path

from .config import Settings

log = logging.getLogger("scan3d.jobs")

# Job states
UPLOADING, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED = (
    "uploading", "queued", "running", "succeeded", "failed", "cancelled")
TERMINAL = {SUCCEEDED, FAILED, CANCELLED}
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,99}$")
IMAGE_EXT = {".jpg", ".jpeg", ".png"}


class JobError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def _atomic_json(path: Path, data: dict) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2))
    os.replace(tmp, path)


def _read_json(path: Path) -> dict | None:
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return None


class JobManager:
    def __init__(self, settings: Settings):
        self.s = settings
        self.root = settings.data_dir / "jobs"
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.procs: dict[str, subprocess.Popen] = {}
        self._stop = threading.Event()
        self._recover()
        self.thread = threading.Thread(target=self._loop, name="job-scheduler", daemon=True)
        self.thread.start()

    # ------------------------------------------------------------------ paths
    def dir(self, job_id: str) -> Path:
        if not re.fullmatch(r"[0-9a-f]{32}", job_id):
            raise JobError(404, "Unknown job")
        d = self.root / job_id
        if not (d / "job.json").exists():
            raise JobError(404, "Unknown job")
        return d

    def load(self, job_id: str) -> dict:
        return json.loads((self.dir(job_id) / "job.json").read_text())

    def _save(self, job: dict) -> None:
        _atomic_json(self.root / job["id"] / "job.json", job)

    # --------------------------------------------------------------- creation
    def create(self, options: dict, expected_images: int | None) -> dict:
        job_id = uuid.uuid4().hex
        d = self.root / job_id
        (d / "images").mkdir(parents=True)
        job = {"id": job_id, "state": UPLOADING, "created_at": time.time(), "updated_at": time.time(),
               "options": options, "images": [], "expected_images": expected_images}
        self._save(job)
        log.info("job %s created", job_id)
        return job

    def add_image(self, job_id: str, name: str, stream, declared_size: int | None = None) -> dict:
        if not SAFE_NAME.match(name) or Path(name).suffix.lower() not in IMAGE_EXT:
            raise JobError(422, "Image name must be a simple file name ending in .jpg, .jpeg or .png")
        with self.lock:
            job = self.load(job_id)
            if job["state"] != UPLOADING:
                raise JobError(409, f"Job is {job['state']}; images can only be added while uploading")
            if name not in job["images"] and len(job["images"]) >= self.s.max_images:
                raise JobError(413, f"A job may contain at most {self.s.max_images} images")
        if shutil.disk_usage(self.root).free < 512 * 1024 * 1024:
            raise JobError(507, "The server is low on storage; try again later")
        target = self.root / job_id / "images" / name
        tmp = target.with_suffix(target.suffix + ".part")
        size = 0
        with open(tmp, "wb") as f:
            while chunk := stream.read(1024 * 1024):
                size += len(chunk)
                if size > self.s.max_image_bytes:
                    f.close()
                    tmp.unlink(missing_ok=True)
                    raise JobError(413, f"Image exceeds {self.s.max_image_bytes // (1024 * 1024)} MB")
                f.write(chunk)
        if size == 0:
            tmp.unlink(missing_ok=True)
            raise JobError(422, "Empty image upload")
        with open(tmp, "rb") as f:
            head = f.read(8)
        if not (head.startswith(b"\xff\xd8\xff") or head.startswith(b"\x89PNG")):
            tmp.unlink(missing_ok=True)
            raise JobError(415, "Unsupported image format; upload JPEG or PNG photos")
        os.replace(tmp, target)
        with self.lock:
            job = self.load(job_id)
            if name not in job["images"]:
                job["images"].append(name)
            job["updated_at"] = time.time()
            self._save(job)
        return {"name": name, "bytes": size, "received": len(job["images"])}

    def start(self, job_id: str) -> dict:
        with self.lock:
            job = self.load(job_id)
            if job["state"] != UPLOADING:
                raise JobError(409, f"Job is already {job['state']}")
            n = len(job["images"])
            if n < self.s.min_images:
                raise JobError(422, f"At least {self.s.min_images} images are required, received {n}")
            if job.get("expected_images") and n < job["expected_images"]:
                raise JobError(409, f"Only {n} of {job['expected_images']} announced images were received; "
                                    "upload the missing images first")
            job["images"].sort()
            job["state"] = QUEUED
            job["queued_at"] = job["updated_at"] = time.time()
            self._save(job)
        log.info("job %s queued with %d images", job_id, n)
        return job

    # -------------------------------------------------------------- execution
    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                self._reap()
                self._launch()
                self._cleanup_old()
            except Exception:  # noqa: BLE001
                log.exception("scheduler error")
            self._stop.wait(1.0)

    def _launch(self) -> None:
        with self.lock:
            if len(self.procs) >= self.s.max_concurrent_jobs:
                return
            queued = []
            for d in self.root.iterdir():
                job = _read_json(d / "job.json")
                if job and job["state"] == QUEUED:
                    queued.append(job)
            if not queued:
                return
            job = min(queued, key=lambda j: j.get("queued_at", 0))
            d = self.root / job["id"]
            job["options"].setdefault("dense", self.s.dense_method)
            job["state"] = RUNNING
            job["started_at"] = job["updated_at"] = time.time()
            self._save(job)
            env = dict(os.environ, SCAN3D_MIN_IMAGES=str(self.s.min_images))
            backend_root = Path(__file__).resolve().parent.parent
            with open(d / "pipeline.stdout.log", "ab") as out:
                self.procs[job["id"]] = subprocess.Popen(
                    [sys.executable, "-m", "app.pipeline.run", str(d)], cwd=backend_root, env=env,
                    stdout=out, stderr=subprocess.STDOUT, start_new_session=True)
            log.info("job %s started (pid %d)", job["id"], self.procs[job["id"]].pid)

    def _reap(self) -> None:
        with self.lock:
            for job_id, p in list(self.procs.items()):
                if p.poll() is None:
                    continue
                del self.procs[job_id]
                d = self.root / job_id
                job = _read_json(d / "job.json")
                if job is None or job["state"] == CANCELLED:
                    continue
                result = _read_json(d / "result.json")
                if result and result.get("status") == SUCCEEDED:
                    job["state"] = SUCCEEDED
                else:
                    job["state"] = FAILED
                    if not result:
                        _atomic_json(d / "result.json", {
                            "status": FAILED,
                            "error": f"The reconstruction process stopped unexpectedly (exit code {p.returncode}).",
                            "remedy": "This usually means the server ran out of memory. Try fewer photos."})
                job["finished_at"] = job["updated_at"] = time.time()
                self._save(job)
                log.info("job %s finished: %s", job_id, job["state"])

    def cancel(self, job_id: str) -> dict:
        with self.lock:
            job = self.load(job_id)
            if job["state"] in TERMINAL:
                return job
            p = self.procs.pop(job_id, None)
            if p and p.poll() is None:
                try:
                    os.killpg(p.pid, signal.SIGTERM)
                    p.wait(timeout=10)
                except (ProcessLookupError, subprocess.TimeoutExpired):
                    os.killpg(p.pid, signal.SIGKILL)
            job["state"] = CANCELLED
            job["finished_at"] = job["updated_at"] = time.time()
            self._save(job)
        log.info("job %s cancelled", job_id)
        return job

    def delete(self, job_id: str) -> None:
        d = self.dir(job_id)
        self.cancel(job_id)
        shutil.rmtree(d, ignore_errors=True)
        log.info("job %s deleted", job_id)

    def _recover(self) -> None:
        """Jobs that were running when the server stopped cannot be resumed: re-queue them."""
        for d in self.root.iterdir():
            job = _read_json(d / "job.json")
            if job and job["state"] == RUNNING:
                job["state"] = QUEUED
                for f in ("progress.json", "result.json"):
                    (d / f).unlink(missing_ok=True)
                _atomic_json(d / "job.json", job)
                log.warning("job %s re-queued after restart", job["id"])

    def _cleanup_old(self) -> None:
        if self.s.job_retention_hours <= 0:
            return
        cutoff = time.time() - self.s.job_retention_hours * 3600
        for d in self.root.iterdir():
            job = _read_json(d / "job.json")
            if job and job["updated_at"] < cutoff and job["state"] in TERMINAL | {UPLOADING}:
                shutil.rmtree(d, ignore_errors=True)
                log.info("job %s expired and was deleted", job["id"])

    def shutdown(self) -> None:
        self._stop.set()

    # ----------------------------------------------------------------- status
    def status(self, job_id: str) -> dict:
        d = self.dir(job_id)
        job = json.loads((d / "job.json").read_text())
        out = {k: job.get(k) for k in ("id", "state", "created_at", "started_at", "finished_at", "options")}
        out["images_received"] = len(job["images"])
        out["expected_images"] = job.get("expected_images")
        if job["state"] == QUEUED:
            with self.lock:
                ahead = sum(1 for p in self.root.iterdir()
                            if (j := _read_json(p / "job.json")) and j["state"] == QUEUED
                            and j.get("queued_at", 0) < job.get("queued_at", 0))
            out["queue_position"] = ahead + 1
        out["progress"] = _read_json(d / "progress.json")
        if job["state"] in TERMINAL:
            out["result"] = _read_json(d / "result.json")
        return out

    def output_file(self, job_id: str, name: str) -> Path:
        d = self.dir(job_id)
        if not SAFE_NAME.match(name):
            raise JobError(404, "Unknown file")
        p = d / "output" / name
        if not p.is_file():
            raise JobError(404, "File not available for this job")
        return p
