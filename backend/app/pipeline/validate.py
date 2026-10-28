"""Per-photo quality checks.

The same metrics are computed on the phone (for live guidance) and here (as the
authoritative gate before reconstruction). They are heuristics: a low score is a
strong hint of a problem, not proof of one.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path

import cv2
import numpy as np

ANALYSIS_WIDTH = 800
MIN_SIDE_PX = 720
BLUR_ABS_THRESHOLD = 40.0     # variance of Laplacian at 800 px width
BLUR_REL_THRESHOLD = 0.30     # fraction of the median sharpness of the set
DARK_MEAN = 45.0
BRIGHT_MEAN = 215.0
CLIPPED_FRACTION = 0.12       # fraction of pixels >= 250 (specular highlights / overexposure)
LOW_TEXTURE_EDGE_DENSITY = 0.015


@dataclass
class ImageQuality:
    name: str
    readable: bool
    width: int = 0
    height: int = 0
    sharpness: float = 0.0
    brightness: float = 0.0
    clipped_fraction: float = 0.0
    edge_density: float = 0.0
    issues: list[str] = field(default_factory=list)
    excluded: bool = False

    def to_dict(self) -> dict:
        d = asdict(self)
        for k in ("sharpness", "brightness", "clipped_fraction", "edge_density"):
            d[k] = round(d[k], 4)
        return d


def analyse_gray(gray: np.ndarray) -> tuple[float, float, float, float]:
    """Return (sharpness, mean brightness, clipped fraction, edge density) of a grayscale image."""
    h, w = gray.shape[:2]
    if w > ANALYSIS_WIDTH:
        gray = cv2.resize(gray, (ANALYSIS_WIDTH, int(h * ANALYSIS_WIDTH / w)), interpolation=cv2.INTER_AREA)
    sharp = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    bright = float(gray.mean())
    clipped = float((gray >= 250).mean())
    gx = cv2.Sobel(gray, cv2.CV_32F, 1, 0)
    gy = cv2.Sobel(gray, cv2.CV_32F, 0, 1)
    edges = float((cv2.magnitude(gx, gy) > 100).mean())
    return sharp, bright, clipped, edges


def assess(path: Path) -> ImageQuality:
    gray = cv2.imread(str(path), cv2.IMREAD_GRAYSCALE)
    if gray is None:
        return ImageQuality(name=path.name, readable=False, issues=["unreadable"], excluded=True)
    h, w = gray.shape
    sharp, bright, clipped, edges = analyse_gray(gray)
    q = ImageQuality(path.name, True, w, h, sharp, bright, clipped, edges)
    if min(w, h) < MIN_SIDE_PX:
        q.issues.append("low_resolution")
    if bright < DARK_MEAN:
        q.issues.append("too_dark")
    if bright > BRIGHT_MEAN:
        q.issues.append("too_bright")
    if clipped > CLIPPED_FRACTION:
        q.issues.append("overexposed_or_reflective")
    if edges < LOW_TEXTURE_EDGE_DENSITY:
        q.issues.append("low_texture")
    return q


def validate_set(paths: list[Path]) -> list[ImageQuality]:
    """Assess all photos; mark the ones that should be excluded from reconstruction."""
    results = [assess(p) for p in paths]
    readable = [r for r in results if r.readable]
    if readable:
        median = float(np.median([r.sharpness for r in readable]))
        for r in readable:
            rel = r.sharpness / median if median > 0 else 1.0
            if r.sharpness < BLUR_ABS_THRESHOLD or rel < BLUR_REL_THRESHOLD:
                r.issues.append("blurry")
            # Only exclude photos that are blurry both absolutely and relative to the set,
            # so a uniformly soft (but consistent) set is not discarded wholesale.
            if r.sharpness < BLUR_ABS_THRESHOLD and rel < BLUR_REL_THRESHOLD:
                r.excluded = True
            if r.brightness < 15:
                r.excluded = True
                r.issues.append("almost_black")
    return results
