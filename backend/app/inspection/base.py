"""Interfaces for future inspection modules."""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Finding:
    kind: str                       # e.g. "crack", "scratch", "dent", "deviation"
    confidence: float               # model probability, 0..1 (not a guarantee)
    image: str | None = None        # source photo for image-based findings
    polygon_px: list[tuple[float, float]] | None = None
    mesh_vertices: list[int] = field(default_factory=list)   # highlighted region on the 3D model
    value_m: float | None = None    # e.g. deviation magnitude for geometry findings


class ImageDefectDetector(ABC):
    """2D detector operating on individual photos."""
    name: str
    family = "image"

    @abstractmethod
    def detect(self, image_path: Path) -> list[Finding]: ...


class GeometryAnalyzer(ABC):
    """3D analysis operating on the reconstructed (scaled) mesh."""
    name: str
    family = "geometry"

    @abstractmethod
    def analyse(self, mesh_path: Path, reference_cad: Path | None = None) -> list[Finding]: ...
