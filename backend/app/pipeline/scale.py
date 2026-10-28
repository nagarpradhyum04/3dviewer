"""Metric scale from a printed ArUco marker of known size.

Photogrammetry recovers shape only up to an unknown scale factor. If the user
places the Scan3D marker (DICT_4X4_50, id 0, printed side length S) next to the
object, we detect its four corners in every photo, triangulate them using the
recovered camera poses and compare the reconstructed side length with S.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import pycolmap

log = logging.getLogger(__name__)

MIN_VIEWS = 3
MAX_SIDE_SPREAD = 0.03     # relative std of the 4 sides beyond which the result is flagged


@dataclass
class ScaleResult:
    found: bool
    scale: float | None = None            # metres per reconstruction unit
    views: int = 0
    side_spread: float | None = None      # relative std-dev of the four triangulated sides
    reprojection_px: float | None = None
    corners_world: np.ndarray | None = None
    message: str = ""

    def to_dict(self) -> dict:
        return {
            "marker_found": self.found,
            "marker_views": self.views,
            "side_consistency": None if self.side_spread is None else round(self.side_spread, 4),
            "marker_reprojection_px": None if self.reprojection_px is None else round(self.reprojection_px, 3),
            "message": self.message,
        }


def _detector(dictionary: int) -> cv2.aruco.ArucoDetector:
    params = cv2.aruco.DetectorParameters()
    params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    return cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(dictionary), params)


def _srgb_to_linear(gray: np.ndarray) -> np.ndarray:
    c = gray.astype(np.float32) / 255.0
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def refine_corners_linear(lin: np.ndarray, quad: np.ndarray) -> np.ndarray:
    """Re-locate the marker's outer square edges in *linear* intensity.

    Camera optics blur in linear light; locating the edge at mid-grey of the
    gamma-encoded image places it inside the true boundary of a dark square
    (about 1 % size bias at typical marker sizes). We sample perpendicular
    profiles along each side, find the 50 % crossing in linear intensity, fit a
    line per side and intersect adjacent lines.
    """
    side_px = np.mean([np.linalg.norm(quad[k] - quad[(k + 1) % 4]) for k in range(4)])
    half = max(2.0, min(5.0, side_px / 6 / 2.5))      # stay within the 1-cell black border
    centre = quad.mean(axis=0)
    lines = []
    for k in range(4):
        a, b = quad[k], quad[(k + 1) % 4]
        t = (b - a) / np.linalg.norm(b - a)
        n = np.array([-t[1], t[0]])
        if np.dot(n, centre - (a + b) / 2) > 0:        # make n point outwards
            n = -n
        pts = []
        for s in np.linspace(0.15, 0.85, 24):
            p0 = a + s * (b - a)
            offs = np.linspace(-half, half, 41)
            xy = (p0[None, :] + offs[:, None] * n[None, :]).astype(np.float32)
            prof = cv2.remap(lin, xy[:, 0:1], xy[:, 1:2], cv2.INTER_LINEAR).ravel()
            lo, hi = prof[:8].mean(), prof[-8:].mean()      # inside (black), outside (white)
            if hi - lo < 0.15:
                continue
            mid = (lo + hi) / 2
            idx = np.nonzero((prof[:-1] < mid) & (prof[1:] >= mid))[0]
            if len(idx) != 1:
                continue
            i = idx[0]
            f = (mid - prof[i]) / (prof[i + 1] - prof[i])
            pts.append(p0 + (offs[i] + f * (offs[1] - offs[0])) * n)
        if len(pts) < 8:
            return quad
        vx, vy, x0, y0 = cv2.fitLine(np.array(pts, np.float32), cv2.DIST_HUBER, 0, 0.01, 0.01).ravel()
        lines.append((np.array([x0, y0]), np.array([vx, vy])))
    out = np.zeros_like(quad)
    for k in range(4):
        (p1, d1), (p2, d2) = lines[(k - 1) % 4], lines[k]
        m = np.array([d1, -d2]).T
        if abs(np.linalg.det(m)) < 1e-6:
            return quad
        s = np.linalg.solve(m, p2 - p1)
        out[k] = p1 + s[0] * d1
    if np.max(np.linalg.norm(out - quad, axis=1)) > 2.0:   # refinement went wrong; keep detector result
        return quad
    return out


def _triangulate(rays: list[tuple[np.ndarray, np.ndarray]]) -> np.ndarray:
    """Least-squares intersection of 3D rays (origin, unit direction)."""
    a = np.zeros((3, 3))
    b = np.zeros(3)
    for o, d in rays:
        m = np.eye(3) - np.outer(d, d)
        a += m
        b += m @ o
    return np.linalg.solve(a, b)


def estimate(rec: pycolmap.Reconstruction, image_dir: Path, marker_size_m: float,
             marker_id: int = 0, dictionary: int = cv2.aruco.DICT_4X4_50) -> ScaleResult:
    det = _detector(dictionary)
    observations: list[tuple[pycolmap.Image, np.ndarray]] = []
    for iid in rec.reg_image_ids():
        img = rec.image(iid)
        gray = cv2.imread(str(image_dir / img.name), cv2.IMREAD_GRAYSCALE)
        if gray is None:
            continue
        cam = rec.camera(img.camera_id)
        if gray.shape[1] != cam.width:   # should not happen; pycolmap reads the same files
            continue
        corners, ids, _ = det.detectMarkers(gray)
        if ids is None:
            continue
        for c, i in zip(corners, ids.flatten()):
            if int(i) == marker_id:
                quad = refine_corners_linear(_srgb_to_linear(gray), c.reshape(4, 2).astype(np.float64))
                # OpenCV puts pixel centres at integer coordinates, COLMAP at +0.5.
                observations.append((img, quad + 0.5))
    if len(observations) < MIN_VIEWS:
        return ScaleResult(False, views=len(observations),
                           message=f"Marker id {marker_id} detected in {len(observations)} photo(s); "
                                   f"at least {MIN_VIEWS} are needed.")

    def rays_for(obs, k):
        out = []
        for img, px in obs:
            cam = rec.camera(img.camera_id)
            xy = cam.cam_from_img(px[k])           # undistorted normalised coordinates
            d_cam = np.array([xy[0], xy[1], 1.0])
            w_from_c = img.cam_from_world().inverse()
            d = w_from_c.rotation.matrix() @ d_cam
            out.append((img.projection_center(), d / np.linalg.norm(d)))
        return out

    corners = np.array([_triangulate(rays_for(observations, k)) for k in range(4)])

    # Reject views whose observation disagrees strongly with the consensus (bad detection).
    def reproj(img, px):
        cam = rec.camera(img.camera_id)
        errs = []
        for k in range(4):
            pc = img.cam_from_world() * corners[k]
            if pc[2] <= 0:
                return np.inf
            errs.append(np.linalg.norm(cam.img_from_cam(pc) - px[k]))
        return float(np.mean(errs))

    errs = np.array([reproj(i, p) for i, p in observations])
    keep = errs < max(3.0, 3 * np.median(errs))
    if keep.sum() >= MIN_VIEWS and keep.sum() < len(observations):
        observations = [o for o, k in zip(observations, keep) if k]
        corners = np.array([_triangulate(rays_for(observations, k)) for k in range(4)])
        errs = np.array([reproj(i, p) for i, p in observations])

    sides = np.array([np.linalg.norm(corners[k] - corners[(k + 1) % 4]) for k in range(4)])
    diags = np.array([np.linalg.norm(corners[0] - corners[2]), np.linalg.norm(corners[1] - corners[3])])
    unit_side = float(np.mean(np.concatenate([sides, diags / np.sqrt(2)])))
    spread = float(np.std(np.concatenate([sides, diags / np.sqrt(2)])) / unit_side)
    res = ScaleResult(True, marker_size_m / unit_side, len(observations), spread, float(np.mean(errs)), corners)
    res.message = (f"Marker found in {res.views} photos; reconstructed square is consistent to "
                   f"{spread * 100:.1f}%.")
    if spread > MAX_SIDE_SPREAD:
        res.message += " The marker shape is distorted in the reconstruction, so scale is less reliable."
    log.info("marker scale %.6f m/unit from %d views (spread %.4f)", res.scale, res.views, spread)
    return res

