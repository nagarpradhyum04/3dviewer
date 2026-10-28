"""Scan3D AI reconstruction API (FastAPI).

Upload flow used by the Android app:

    POST /api/v1/jobs                       -> {id}            create, announce image count
    PUT  /api/v1/jobs/{id}/images/{name}    (raw JPEG body)     once per photo, idempotent/resumable
    POST /api/v1/jobs/{id}/start                                 validate + queue
    GET  /api/v1/jobs/{id}                                       poll state/progress/result
    GET  /api/v1/jobs/{id}/files/{name}                          download outputs
    POST /api/v1/jobs/{id}/cancel | /calibrate,  DELETE /api/v1/jobs/{id}
"""
from __future__ import annotations

import logging
import platform
from contextlib import asynccontextmanager

import pycolmap
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, Field

from . import __version__
from .config import Settings
from .inspection import registry as inspection_registry
from .jobs import SUCCEEDED, JobError, JobManager
from .pipeline import dense
from .pipeline.progress import STAGES
from .pipeline.rescale import RescaleError, apply as rescale

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("scan3d.api")

settings = Settings.from_env()
manager: JobManager | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global manager
    manager = JobManager(settings)
    log.info("data dir %s, dense method %s", settings.data_dir, dense.available_method(settings.dense_method))
    yield
    manager.shutdown()


app = FastAPI(title="Scan3D AI reconstruction API", version=__version__, lifespan=lifespan)


def require_key(x_api_key: str | None = Header(default=None)) -> None:
    if settings.api_key and x_api_key != settings.api_key:
        raise HTTPException(401, "Missing or invalid API key (X-API-Key header)")


@app.exception_handler(JobError)
async def job_error_handler(_: Request, exc: JobError):
    return JSONResponse(status_code=exc.status, content={"detail": exc.message})


class JobOptions(BaseModel):
    marker_size_mm: float | None = Field(default=None, gt=5, lt=1000,
                                         description="Printed side length of the ArUco marker's black square")
    marker_id: int = Field(default=0, ge=0, lt=50)
    dense: str | None = Field(default=None, pattern="^(auto|cpu|cuda|none)$")
    object_name: str | None = Field(default=None, max_length=100)


class CreateJob(BaseModel):
    expected_images: int | None = Field(default=None, ge=1, le=1000)
    options: JobOptions = JobOptions()


class Calibrate(BaseModel):
    point_a: list[float] = Field(min_length=3, max_length=3)
    point_b: list[float] = Field(min_length=3, max_length=3)
    distance_m: float = Field(gt=0)


@app.get("/api/v1/health")
def health():
    return {
        "status": "ok",
        "version": __version__,
        "pycolmap": pycolmap.__version__,
        "cuda": bool(pycolmap.has_cuda),
        "dense_method": dense.available_method(settings.dense_method),
        "min_images": settings.min_images,
        "max_images": settings.max_images,
        "auth_required": settings.api_key is not None,
        "platform": platform.machine(),
        "stages": [{"key": k, "label": l, "explanation": e} for k, l, _, e in STAGES],
        "output_formats": ["glb", "obj", "ply", "stl"],
        "inspection_modules": inspection_registry.describe(),
    }


@app.post("/api/v1/jobs", status_code=201, dependencies=[Depends(require_key)])
def create_job(body: CreateJob):
    opts = body.options.model_dump(exclude_none=True)
    if body.expected_images and body.expected_images > settings.max_images:
        raise HTTPException(413, f"At most {settings.max_images} images per job")
    job = manager.create(opts, body.expected_images)
    return {"id": job["id"], "state": job["state"], "max_images": settings.max_images,
            "max_image_bytes": settings.max_image_bytes}


@app.put("/api/v1/jobs/{job_id}/images/{name}", dependencies=[Depends(require_key)])
async def upload_image(job_id: str, name: str, request: Request):
    import io
    # Bodies are bounded by max_image_bytes; buffering one photo at a time is fine.
    body = bytearray()
    async for chunk in request.stream():
        body += chunk
        if len(body) > settings.max_image_bytes:
            raise HTTPException(413, f"Image exceeds {settings.max_image_bytes // (1024 * 1024)} MB")
    return await run_in_threadpool(manager.add_image, job_id, name, io.BytesIO(bytes(body)))


@app.post("/api/v1/jobs/{job_id}/start", dependencies=[Depends(require_key)])
def start_job(job_id: str):
    manager.start(job_id)
    return manager.status(job_id)


@app.get("/api/v1/jobs/{job_id}", dependencies=[Depends(require_key)])
def job_status(job_id: str):
    return manager.status(job_id)


@app.post("/api/v1/jobs/{job_id}/cancel", dependencies=[Depends(require_key)])
def cancel_job(job_id: str):
    manager.cancel(job_id)
    return manager.status(job_id)


@app.delete("/api/v1/jobs/{job_id}", status_code=204, dependencies=[Depends(require_key)])
def delete_job(job_id: str):
    manager.delete(job_id)


@app.get("/api/v1/jobs/{job_id}/files/{name}", dependencies=[Depends(require_key)])
def download(job_id: str, name: str):
    path = manager.output_file(job_id, name)
    media = {"glb": "model/gltf-binary", "obj": "model/obj", "stl": "model/stl", "ply": "application/octet-stream"}
    return FileResponse(path, media_type=media.get(path.suffix[1:], "application/octet-stream"), filename=name)


@app.post("/api/v1/jobs/{job_id}/calibrate", dependencies=[Depends(require_key)])
async def calibrate(job_id: str, body: Calibrate):
    if manager.load(job_id)["state"] != SUCCEEDED:
        raise HTTPException(409, "Job has not finished successfully")
    try:
        await run_in_threadpool(rescale, manager.dir(job_id), body.point_a, body.point_b, body.distance_m)
    except RescaleError as e:
        raise HTTPException(422, str(e)) from e
    return manager.status(job_id)
