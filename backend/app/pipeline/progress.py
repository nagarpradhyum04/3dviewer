"""Honest progress reporting for a reconstruction job.

The pipeline runs in a subprocess and writes ``progress.json`` atomically; the
API process only reads it. Progress is reported per stage. Within a stage a
fraction is only reported when it is actually measurable (e.g. number of views
densified); otherwise the stage is marked ``indeterminate`` and the overall
fraction stays at the stage's start value. ETA is only reported when it can be
derived from measured throughput.
"""
from __future__ import annotations

import json
import os
import time
from dataclasses import dataclass, field
from pathlib import Path

# (key, human label, relative weight, explanation shown in the app)
STAGES: list[tuple[str, str, float, str]] = [
    ("validate", "Validating photos", 0.04,
     "Checking every photo for blur, exposure and resolution. Unusable photos are excluded."),
    ("features", "Detecting features", 0.12,
     "Finding thousands of distinctive points (SIFT features) in every photo."),
    ("matching", "Matching features", 0.16,
     "Comparing photos pairwise to find the same surface points seen from different angles."),
    ("sfm", "Estimating camera positions", 0.20,
     "Structure-from-Motion: solving where each photo was taken and building a sparse 3D point cloud."),
    ("scale", "Calibrating scale", 0.03,
     "Looking for the printed reference marker to convert the model into real-world units."),
    ("dense", "Densifying geometry", 0.30,
     "Multi-view stereo: computing depth for most pixels to create a dense point cloud."),
    ("mesh", "Building surface mesh", 0.10,
     "Poisson surface reconstruction turns the dense points into a watertight triangle mesh."),
    ("export", "Exporting model files", 0.05,
     "Writing GLB, OBJ, PLY and STL files and measuring the object."),
]
STAGE_KEYS = [s[0] for s in STAGES]


class Cancelled(Exception):
    pass


@dataclass
class Progress:
    path: Path
    started: float = field(default_factory=time.time)
    stage: str | None = None
    stage_fraction: float | None = None
    stage_started: float = 0.0
    message: str = ""
    eta_seconds: float | None = None
    completed: list[str] = field(default_factory=list)
    skipped: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)

    def start(self, stage: str, message: str = "") -> None:
        if self.stage and self.stage not in self.completed and self.stage not in self.skipped:
            self.completed.append(self.stage)
        self.stage, self.stage_fraction, self.eta_seconds = stage, None, None
        self.stage_started = time.time()
        self.message = message
        self._write()

    def update(self, fraction: float | None = None, message: str | None = None,
               eta_seconds: float | None = None) -> None:
        if fraction is not None:
            self.stage_fraction = max(0.0, min(1.0, fraction))
        if message is not None:
            self.message = message
        self.eta_seconds = eta_seconds
        self._write()

    def skip(self, stage: str, reason: str) -> None:
        self.skipped.append(stage)
        self.warn(f"{dict((k, l) for k, l, *_ in STAGES)[stage]} skipped: {reason}")

    def warn(self, text: str) -> None:
        if text not in self.warnings:
            self.warnings.append(text)
        self._write()

    def finish(self) -> None:
        if self.stage and self.stage not in self.completed:
            self.completed.append(self.stage)
        self.stage, self.stage_fraction = None, None
        self._write()

    def overall(self) -> float:
        done = sum(w for k, _, w, _ in STAGES if k in self.completed or k in self.skipped)
        if self.stage and self.stage_fraction is not None:
            done += dict((k, w) for k, _, w, _ in STAGES)[self.stage] * self.stage_fraction
        return round(min(done, 1.0), 4)

    def to_dict(self) -> dict:
        cur = next((s for s in STAGES if s[0] == self.stage), None)
        return {
            "stage": self.stage,
            "stage_label": cur[1] if cur else None,
            "stage_explanation": cur[3] if cur else None,
            "stage_fraction": self.stage_fraction,
            "indeterminate": self.stage is not None and self.stage_fraction is None,
            "overall_fraction": self.overall(),
            "message": self.message,
            "eta_seconds": None if self.eta_seconds is None else round(self.eta_seconds),
            "elapsed_seconds": round(time.time() - self.started),
            "completed_stages": self.completed,
            "skipped_stages": self.skipped,
            "warnings": self.warnings,
            "updated_at": time.time(),
        }

    def _write(self) -> None:
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.to_dict()))
        os.replace(tmp, self.path)
