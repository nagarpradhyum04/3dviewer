"""Server configuration from environment variables (see README)."""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    api_key: str | None
    max_images: int
    min_images: int
    max_image_bytes: int
    max_concurrent_jobs: int
    dense_method: str
    job_retention_hours: float

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            data_dir=Path(os.environ.get("SCAN3D_DATA_DIR", "./data")).resolve(),
            api_key=os.environ.get("SCAN3D_API_KEY") or None,
            max_images=int(os.environ.get("SCAN3D_MAX_IMAGES", "150")),
            min_images=int(os.environ.get("SCAN3D_MIN_IMAGES", "8")),
            max_image_bytes=int(os.environ.get("SCAN3D_MAX_IMAGE_MB", "25")) * 1024 * 1024,
            max_concurrent_jobs=int(os.environ.get("SCAN3D_MAX_CONCURRENT_JOBS", "1")),
            dense_method=os.environ.get("SCAN3D_DENSE", "auto"),
            job_retention_hours=float(os.environ.get("SCAN3D_JOB_RETENTION_HOURS", "72")),
        )
