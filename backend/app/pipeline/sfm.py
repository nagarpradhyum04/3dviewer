"""Sparse reconstruction (Structure-from-Motion) with pycolmap (COLMAP, BSD-3)."""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pycolmap

from .progress import Progress

log = logging.getLogger(__name__)

MAX_IMAGE_SIZE = 2000          # px, longest side used for SIFT
MAX_FEATURES = 8192
EXHAUSTIVE_LIMIT = 80          # above this, sequential matching is used (capture order = walk order)
LOW_FEATURE_COUNT = 400


@dataclass
class SparseResult:
    reconstruction: pycolmap.Reconstruction
    model_dir: Path
    num_input: int
    num_registered: int
    num_points: int
    mean_reprojection_error: float
    mean_track_length: float
    low_feature_images: list[str]

    def stats(self) -> dict:
        return {
            "images_used": self.num_input,
            "images_registered": self.num_registered,
            "sparse_points": self.num_points,
            "mean_reprojection_error_px": round(self.mean_reprojection_error, 3),
            "mean_track_length": round(self.mean_track_length, 2),
        }


def device() -> pycolmap.Device:
    return pycolmap.Device.cuda if pycolmap.has_cuda else pycolmap.Device.cpu


def run(image_dir: Path, image_names: list[str], work: Path, progress: Progress) -> SparseResult:
    work.mkdir(parents=True, exist_ok=True)
    db = work / "database.db"
    if db.exists():
        db.unlink()

    progress.start("features", f"Extracting SIFT features from {len(image_names)} photos")
    extraction = pycolmap.FeatureExtractionOptions()
    extraction.max_image_size = MAX_IMAGE_SIZE
    extraction.sift.max_num_features = MAX_FEATURES
    reader = pycolmap.ImageReaderOptions()
    reader.camera_model = "SIMPLE_RADIAL"   # one phone camera; EXIF focal length used as prior when present
    pycolmap.extract_features(db, image_dir, image_names=image_names,
                              camera_mode=pycolmap.CameraMode.SINGLE, reader_options=reader,
                              extraction_options=extraction, device=device())

    database = pycolmap.Database.open(str(db)) if hasattr(pycolmap.Database, "open") else pycolmap.Database(str(db))
    low = []
    try:
        for img in database.read_all_images():
            if database.num_keypoints_for_image(img.image_id) < LOW_FEATURE_COUNT:
                low.append(img.name)
    finally:
        database.close()
    if low:
        progress.warn(f"{len(low)} photo(s) have very few visual features (plain, shiny or out-of-focus "
                      f"surfaces): {', '.join(sorted(low)[:6])}{'…' if len(low) > 6 else ''}")

    progress.start("matching", "Matching features between photos")
    matching = pycolmap.FeatureMatchingOptions()
    if len(image_names) <= EXHAUSTIVE_LIMIT:
        pycolmap.match_exhaustive(db, matching_options=matching, device=device())
    else:
        pairing = pycolmap.SequentialPairingOptions()
        pairing.overlap = 20
        pairing.quadratic_overlap = True
        pycolmap.match_sequential(db, matching_options=matching, pairing_options=pairing, device=device())

    progress.start("sfm", "Estimating camera poses")
    out = work / "sparse"
    out.mkdir(exist_ok=True)
    opts = pycolmap.IncrementalPipelineOptions()
    opts.multiple_models = True
    opts.min_model_size = 3
    registered = [0]

    def on_next_image() -> None:
        registered[0] += 1
        progress.update(min(registered[0] / len(image_names), 0.99),
                        f"Registered {registered[0] + 1} of {len(image_names)} photos")

    maps = pycolmap.incremental_mapping(db, image_dir, out, options=opts, next_image_callback=on_next_image)
    if not maps:
        raise PipelineError(
            "Could not determine camera positions from these photos.",
            "Make sure consecutive photos overlap by roughly 60-80%, the object is textured and "
            "stationary, and the photos are sharp. Plain, shiny or transparent objects cannot be "
            "reconstructed reliably by photogrammetry.")
    idx, rec = max(maps.items(), key=lambda kv: kv[1].num_reg_images())
    model_dir = out / str(idx)
    rec.write(model_dir)
    n_reg = rec.num_reg_images()
    if len(maps) > 1:
        progress.warn(f"Photos split into {len(maps)} disconnected groups; only the largest "
                      f"({n_reg} photos) was used. Capture more overlapping views between groups.")
    if n_reg < 0.5 * len(image_names):
        progress.warn(f"Only {n_reg} of {len(image_names)} photos could be placed. Coverage and "
                      "detail of the model will be reduced.")
    if n_reg < 3:
        raise PipelineError("Too few photos could be placed in 3D.",
                            "Capture more overlapping photos while moving slowly around the object.")
    return SparseResult(rec, model_dir, len(image_names), n_reg, rec.num_points3D(),
                        rec.compute_mean_reprojection_error(), rec.compute_mean_track_length(), low)


def camera_centers(rec: pycolmap.Reconstruction) -> dict[int, np.ndarray]:
    return {iid: rec.image(iid).projection_center() for iid in rec.reg_image_ids()}


def sparse_points(rec: pycolmap.Reconstruction) -> tuple[np.ndarray, np.ndarray]:
    pts = rec.points3D
    xyz = np.array([p.xyz for p in pts.values()], dtype=np.float64).reshape(-1, 3)
    rgb = np.array([p.color for p in pts.values()], dtype=np.uint8).reshape(-1, 3)
    return xyz, rgb


class PipelineError(Exception):
    """A failure with a user-facing explanation and remedy."""

    def __init__(self, message: str, remedy: str = ""):
        super().__init__(message)
        self.message = message
        self.remedy = remedy
