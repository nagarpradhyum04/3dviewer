"""Scene analysis: supporting plane, object segmentation, alignment and dimensions.

Assumptions (documented to the user): the object rests on a flat surface
(table/floor) and the photographer circled it, so camera optical axes converge
on the object. When no supporting plane is found we fall back to a minimal
oriented bounding box and say so.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field

import cv2
import numpy as np
import open3d as o3d

log = logging.getLogger(__name__)


@dataclass
class SceneGeometry:
    # Rigid transform (recon units) -> aligned frame: Y up, origin at object base centre.
    rotation: np.ndarray
    origin: np.ndarray
    object_mask: np.ndarray
    dims_aligned: tuple[float, float, float]     # length (X), width (Z), height (Y) in recon units
    plane_found: bool
    method: str
    warnings: list[str] = field(default_factory=list)

    def apply(self, xyz: np.ndarray) -> np.ndarray:
        return (xyz - self.origin) @ self.rotation.T


def fixation_point(centres: np.ndarray, directions: np.ndarray) -> np.ndarray:
    """Least-squares point closest to all camera optical axes (where the user aimed)."""
    a, b = np.zeros((3, 3)), np.zeros(3)
    for c, d in zip(centres, directions):
        d = d / np.linalg.norm(d)
        m = np.eye(3) - np.outer(d, d)
        a += m
        b += m @ c
    return np.linalg.solve(a + 1e-9 * np.eye(3), b)


def _pcd(xyz: np.ndarray) -> o3d.geometry.PointCloud:
    return o3d.geometry.PointCloud(o3d.utility.Vector3dVector(np.ascontiguousarray(xyz, dtype=np.float64)))


def _find_support_plane(xyz, centres, fix, thr):
    """Return (normal, d) with normal pointing towards the cameras, or None."""
    rest = _pcd(xyz).voxel_down_sample(thr)
    total = len(rest.points)
    for _ in range(3):
        if len(rest.points) < 100:
            break
        (a, b, c, d), inl = rest.segment_plane(distance_threshold=thr, ransac_n=3, num_iterations=2000)
        n = np.array([a, b, c])
        norm = np.linalg.norm(n)
        n, d = n / norm, d / norm
        side = centres @ n + d
        if np.median(side) < 0:
            n, d, side = -n, -d, -side
        fix_h = fix @ n + d
        cams_above = (side > 0).mean()
        if len(inl) > 0.10 * total and cams_above > 0.8 and fix_h > -3 * thr:
            return n, d
        rest = rest.select_by_index(inl, invert=True)
    return None


def robust_bound(c: np.ndarray, side: int) -> float:
    """Outer boundary of a coordinate distribution.

    Flat faces of an object show up as a sharp density peak at the boundary,
    while stereo noise forms a thin tail beyond it. If such a peak exists we
    return its density-weighted centre; otherwise (curved surfaces) a high
    percentile.
    """
    x = side * c
    lo_q, hi_q = np.percentile(x, [0.1, 99.9])
    rng = hi_q - lo_q
    fallback = side * float(np.percentile(x, 99.5))
    if rng <= 0 or len(x) < 500:
        return fallback
    window = x[x > hi_q - 0.08 * rng]
    hist, edges = np.histogram(window, bins=32, range=(hi_q - 0.08 * rng, hi_q + 0.02 * rng))
    peak = int(np.argmax(hist))
    if hist[peak] < 3 * max(1.0, float(np.median(hist))):
        return fallback
    l, r = peak, peak
    while l > 0 and hist[l - 1] >= hist[peak] / 2:
        l -= 1
    while r < len(hist) - 1 and hist[r + 1] >= hist[peak] / 2:
        r += 1
    centres = (edges[:-1] + edges[1:]) / 2
    w = hist[l:r + 1]
    return side * float((centres[l:r + 1] * w).sum() / w.sum())


def _in_plane_basis(n: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    helper = np.array([1.0, 0, 0]) if abs(n[0]) < 0.9 else np.array([0, 0, 1.0])
    u = np.cross(n, helper)
    u /= np.linalg.norm(u)
    return u, np.cross(n, u)


def analyse(xyz: np.ndarray, centres: np.ndarray, view_dirs: np.ndarray) -> SceneGeometry:
    fix = fixation_point(centres, view_dirs)
    dist = float(np.median(np.linalg.norm(centres - fix, axis=1)))
    thr = dist * 0.004
    plane = _find_support_plane(xyz, centres, fix, thr)
    if plane is None:
        return _fallback_obb(xyz, centres, fix, dist)

    n, d = plane
    h = xyz @ n + d
    u, v = _in_plane_basis(n)
    rel = xyz - fix
    horiz = np.stack([rel @ u, rel @ v], axis=1)
    cand = (h > 3 * thr) & (np.linalg.norm(horiz, axis=1) < 0.75 * dist)
    if cand.sum() < 200:
        g = _fallback_obb(xyz, centres, fix, dist)
        g.warnings.append("Very little geometry was found above the supporting surface; the object may be "
                          "too flat, too small in the photos, or not textured enough.")
        return g

    idx = np.nonzero(cand)[0]
    sub = _pcd(xyz[idx])
    labels = np.array(sub.cluster_dbscan(eps=6 * thr, min_points=8))
    valid = labels >= 0
    if not valid.any():
        return _fallback_obb(xyz, centres, fix, dist)
    # the cluster nearest to the aiming point is the object
    hd = np.linalg.norm(horiz[idx], axis=1)
    best_label = labels[valid][np.argmin(hd[valid])]
    sizes = np.bincount(labels[valid])
    if sizes[best_label] < 0.05 * valid.sum():
        best_label = int(np.argmax(sizes))
    obj_idx = idx[labels == best_label]

    # remove isolated noise before measuring
    _, keep = _pcd(xyz[obj_idx]).remove_statistical_outlier(nb_neighbors=20, std_ratio=2.5)
    obj_idx = obj_idx[np.asarray(keep, dtype=int)]
    mask = np.zeros(len(xyz), bool)
    mask[obj_idx] = True

    # footprint: minimum-area rectangle of the projection onto the plane, then robust extents
    p2 = np.stack([xyz[obj_idx] @ u, xyz[obj_idx] @ v], axis=1).astype(np.float32)
    (_, _), (_, _), angle = cv2.minAreaRect(p2)
    a = np.deg2rad(angle)
    ax1 = np.cos(a) * u + np.sin(a) * v
    ax2 = np.cross(n, ax1)
    c1, c2 = xyz[obj_idx] @ ax1, xyz[obj_idx] @ ax2
    lo1, hi1 = robust_bound(c1, -1), robust_bound(c1, 1)
    lo2, hi2 = robust_bound(c2, -1), robust_bound(c2, 1)
    e1, e2 = hi1 - lo1, hi2 - lo2
    if e2 > e1:                        # X = longest horizontal axis
        ax1, ax2 = ax2, -ax1
        lo1, hi1, lo2, hi2 = lo2, hi2, -hi1, -lo1
        e1, e2 = e2, e1
    height = robust_bound(h[obj_idx], 1)
    centre_plane = ((lo1 + hi1) / 2) * ax1 + ((lo2 + hi2) / 2) * ax2
    origin = centre_plane - (centre_plane @ n + d) * n            # foot of perpendicular on the plane
    # rows: X = ax1, Y = up (n), Z = X x Y  (right-handed, glTF convention)
    rot = np.stack([ax1, n, np.cross(ax1, n)])
    warnings = []
    if mask.sum() < 2000:
        warnings.append("The object is represented by few points; dimensions may be unreliable.")
    return SceneGeometry(rot, origin, mask, (float(e1), float(e2), height), True,
                         "support_plane_min_area_rect")


def _fallback_obb(xyz, centres, fix, dist) -> SceneGeometry:
    near = np.linalg.norm(xyz - fix, axis=1) < 0.5 * dist
    idx = np.nonzero(near)[0]
    if len(idx) < 50:
        idx = np.arange(len(xyz))
    _, keep = _pcd(xyz[idx]).remove_statistical_outlier(nb_neighbors=20, std_ratio=2.0)
    idx = idx[np.asarray(keep, dtype=int)]
    mask = np.zeros(len(xyz), bool)
    mask[idx] = True
    obb = _pcd(xyz[idx]).get_minimal_oriented_bounding_box()
    R = np.asarray(obb.R)
    ext = np.asarray(obb.extent)
    # choose the OBB axis closest to the average camera "up" as Y
    up_guess = centres.mean(0) - fix
    up_guess /= np.linalg.norm(up_guess)
    yi = int(np.argmax(np.abs(R.T @ up_guess)))
    y = R[:, yi] * np.sign(R[:, yi] @ up_guess)
    others = [i for i in range(3) if i != yi]
    xi = max(others, key=lambda i: ext[i])
    zi = [i for i in others if i != xi][0]
    x = R[:, xi]
    rot = np.stack([x, y, np.cross(x, y)])
    centre = np.asarray(obb.center)
    origin = centre - y * ext[yi] / 2
    return SceneGeometry(rot, origin, mask, (float(ext[xi]), float(ext[zi]), float(ext[yi])), False,
                         "oriented_bounding_box",
                         ["No supporting surface (table/floor) was detected, so the object could not be "
                          "separated reliably from its surroundings. Dimensions come from a bounding box and "
                          "may include background."])
