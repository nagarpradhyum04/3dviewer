"""Dense point cloud from calibrated views.

Two implementations:

* ``cuda``  – COLMAP PatchMatch stereo + fusion (pycolmap built with CUDA). Best
  quality; requires an NVIDIA GPU. Not exercised by the automated tests (no GPU
  in CI).
* ``cpu``   – Plane-sweep-free two-view stereo: each registered view is paired
  with its best neighbours, the pair is rectified with the poses recovered by
  SfM, OpenCV semi-global block matching (SGBM) computes a disparity map with a
  left/right consistency check, and depths are back-projected to world space.
  Points are kept only where at least two independent reference views agree
  (voxel-level multi-view consistency). Lower quality than PatchMatch, but runs
  anywhere.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from pathlib import Path

import cv2
import numpy as np
import pycolmap

from .progress import Progress

log = logging.getLogger(__name__)

STEREO_MAX_SIDE = 1000           # px, images are downscaled for SGBM
NEIGHBOURS = 2
MIN_TRI_ANGLE = np.deg2rad(4)
MAX_TRI_ANGLE = np.deg2rad(30)


@dataclass
class DenseResult:
    xyz: np.ndarray        # (N, 3) float64, reconstruction units
    rgb: np.ndarray        # (N, 3) uint8
    normals: np.ndarray | None
    method: str

    def stats(self) -> dict:
        return {"dense_points": int(len(self.xyz)), "dense_method": self.method}


def available_method(preferred: str = "auto") -> str:
    if preferred == "none":
        return "none"
    if preferred in ("auto", "cuda") and pycolmap.has_cuda:
        return "cuda"
    if preferred == "cuda":
        log.warning("CUDA dense reconstruction requested but pycolmap has no CUDA support; using CPU")
    return "cpu"


def run(model_dir: Path, image_dir: Path, work: Path, progress: Progress, method: str) -> DenseResult:
    mvs = work / "mvs"
    progress.update(message="Undistorting photos")
    pycolmap.undistort_images(mvs, model_dir, image_dir)
    if method == "cuda":
        return _run_cuda(mvs, progress)
    return _run_cpu(mvs, progress)


# --------------------------------------------------------------------------- CUDA
def _run_cuda(mvs: Path, progress: Progress) -> DenseResult:
    progress.update(message="PatchMatch stereo on GPU")
    opts = pycolmap.PatchMatchOptions()
    opts.max_image_size = 2000
    opts.geom_consistency = True
    pycolmap.patch_match_stereo(mvs, options=opts)
    progress.update(0.8, "Fusing depth maps")
    fused = mvs / "fused.ply"
    pycolmap.stereo_fusion(fused, mvs, output_type="PLY")
    import open3d as o3d
    pc = o3d.io.read_point_cloud(str(fused))
    return DenseResult(np.asarray(pc.points), (np.asarray(pc.colors) * 255).astype(np.uint8),
                       np.asarray(pc.normals) if pc.has_normals() else None, "colmap_patchmatch_cuda")


# --------------------------------------------------------------------------- CPU
@dataclass
class _View:
    iid: int
    name: str
    R: np.ndarray      # cam_from_world rotation
    t: np.ndarray
    K: np.ndarray      # at stereo resolution
    size: tuple[int, int]
    centre: np.ndarray
    depths: np.ndarray  # depths of sparse points seen by this view
    point_ids: set


def _load_views(rec: pycolmap.Reconstruction) -> dict[int, _View]:
    views = {}
    for iid in rec.reg_image_ids():
        img = rec.image(iid)
        cam = rec.camera(img.camera_id)
        s = min(1.0, STEREO_MAX_SIDE / max(cam.width, cam.height))
        K = cam.calibration_matrix().copy()
        K[:2] *= s
        pose = img.cam_from_world()
        R, t = pose.rotation.matrix(), np.asarray(pose.translation)
        ids, depths = set(), []
        for p2 in img.points2D:
            if p2.has_point3D():
                ids.add(p2.point3D_id)
                depths.append((R @ rec.points3D[p2.point3D_id].xyz + t)[2])
        views[iid] = _View(iid, img.name, R, t, K, (round(cam.width * s), round(cam.height * s)),
                           img.projection_center(), np.array(depths), ids)
    return views


def _select_neighbours(v: _View, views: dict[int, _View]) -> list[int]:
    if len(v.depths) == 0:
        return []
    depth = float(np.median(v.depths))
    scored = []
    for o in views.values():
        if o.iid == v.iid:
            continue
        shared = len(v.point_ids & o.point_ids)
        if shared < 30:
            continue
        angle = 2 * np.arctan(np.linalg.norm(o.centre - v.centre) / (2 * depth))
        if not (MIN_TRI_ANGLE <= angle <= MAX_TRI_ANGLE):
            continue
        # prefer ~12 degree triangulation angle and many shared points
        score = shared * np.exp(-((np.rad2deg(angle) - 12) / 8) ** 2)
        scored.append((score, o.iid))
    return [iid for _, iid in sorted(scored, reverse=True)[:NEIGHBOURS]]


def _sgbm(num_disp: int, min_disp: int, block: int = 5) -> cv2.StereoSGBM:
    return cv2.StereoSGBM_create(minDisparity=min_disp, numDisparities=num_disp, blockSize=block,
                                 P1=8 * 3 * block ** 2, P2=32 * 3 * block ** 2, disp12MaxDiff=1,
                                 uniquenessRatio=10, speckleWindowSize=100, speckleRange=2,
                                 mode=cv2.STEREO_SGBM_MODE_SGBM_3WAY)


def _pair_points(a: _View, b: _View, img_a: np.ndarray, img_b: np.ndarray, rec) -> tuple[np.ndarray, np.ndarray]:
    R = b.R @ a.R.T
    T = b.t - R @ a.t
    size = a.size
    zero = np.zeros(5)
    R1, R2, P1, P2, _, _, _ = cv2.stereoRectify(a.K, zero, b.K, zero, size, R, T.reshape(3, 1), alpha=0)
    if abs(P2[1, 3]) > abs(P2[0, 3]):
        return np.empty((0, 3)), np.empty((0, 3), np.uint8)    # vertical pair; skipped
    f, cx, cy = P1[0, 0], P1[0, 2], P1[1, 2]
    baseline = abs(P2[0, 3] / P2[0, 0])
    m1 = cv2.initUndistortRectifyMap(a.K, zero, R1, P1, size, cv2.CV_32FC1)
    m2 = cv2.initUndistortRectifyMap(b.K, zero, R2, P2, size, cv2.CV_32FC1)
    ra = cv2.remap(img_a, *m1, cv2.INTER_LINEAR)
    rb = cv2.remap(img_b, *m2, cv2.INTER_LINEAR)
    flip = P2[0, 3] > 0          # second camera is on the left; mirror so it becomes the right one
    if flip:
        ra, rb = ra[:, ::-1].copy(), rb[:, ::-1].copy()

    # disparity search range from the sparse points seen by `a`
    z = a.depths[a.depths > 0]
    if len(z) < 20:
        return np.empty((0, 3)), np.empty((0, 3), np.uint8)
    zmin, zmax = np.percentile(z, 1) * 0.85, np.percentile(z, 99) * 1.15
    dmin, dmax = f * baseline / zmax, f * baseline / zmin
    min_disp = int(np.floor(dmin))
    num = int(np.ceil((dmax - min_disp) / 16.0) * 16)
    num = max(16, min(num, 320))

    ga, gb = cv2.cvtColor(ra, cv2.COLOR_BGR2GRAY), cv2.cvtColor(rb, cv2.COLOR_BGR2GRAY)
    left = _sgbm(num, min_disp)
    right = cv2.ximgproc.createRightMatcher(left)
    dl = left.compute(ga, gb).astype(np.float32) / 16.0
    dr = right.compute(gb, ga).astype(np.float32) / 16.0
    h, w = dl.shape
    ys, xs = np.nonzero(dl > min_disp)
    d = dl[ys, xs]
    xr = np.round(xs - d).astype(int)
    ok = (xr >= 0) & (xr < w)
    ys, xs, d, xr = ys[ok], xs[ok], d[ok], xr[ok]
    ok = np.abs(dr[ys, xr] + d) <= 1.0           # left/right consistency
    ys, xs, d = ys[ok], xs[ok], d[ok]
    colors = ra[ys, xs][:, ::-1]                 # BGR -> RGB
    if flip:
        xs = (w - 1) - xs
    Z = f * baseline / d
    X = (xs - cx) * Z / f
    Y = (ys - cy) * Z / f
    p_rect = np.stack([X, Y, Z], axis=1)
    keep = (Z > zmin) & (Z < zmax)
    p_cam = p_rect[keep] @ R1                    # R1^T applied to row vectors
    p_world = (p_cam - a.t) @ a.R                # R^T (p - t)
    return p_world, colors[keep]


def _run_cpu(mvs: Path, progress: Progress) -> DenseResult:
    rec = pycolmap.Reconstruction(mvs / "sparse")
    views = _load_views(rec)
    cache: dict[int, np.ndarray] = {}

    def image(v: _View) -> np.ndarray:
        if v.iid not in cache:
            im = cv2.imread(str(mvs / "images" / v.name), cv2.IMREAD_COLOR)
            cache[v.iid] = cv2.resize(im, v.size, interpolation=cv2.INTER_AREA)
        return cache[v.iid]

    all_xyz, all_rgb, all_src = [], [], []
    ids = list(views)
    t0 = time.time()
    for n, iid in enumerate(ids):
        v = views[iid]
        for nb in _select_neighbours(v, views):
            try:
                xyz, rgb = _pair_points(v, views[nb], image(v), image(views[nb]), rec)
            except cv2.error as e:  # degenerate rectification
                log.warning("stereo pair %s/%s failed: %s", v.name, views[nb].name, e)
                continue
            if len(xyz):
                all_xyz.append(xyz)
                all_rgb.append(rgb)
                all_src.append(np.full(len(xyz), iid, np.int32))
        done = n + 1
        rate = (time.time() - t0) / done
        progress.update(done / len(ids), f"Depth computed for {done} of {len(ids)} views",
                        eta_seconds=rate * (len(ids) - done))
        if len(cache) > 12:
            cache.clear()
    if not all_xyz:
        return DenseResult(np.empty((0, 3)), np.empty((0, 3), np.uint8), None, "opencv_sgbm_cpu")
    xyz = np.concatenate(all_xyz)
    rgb = np.concatenate(all_rgb)
    src = np.concatenate(all_src)
    fx, frgb, fsrc = _multi_view_filter(xyz, rgb, src, rec, min_views=3)
    if len(fx) < 20000:   # sparse capture: relax to two agreeing views
        fx, frgb, fsrc = _multi_view_filter(xyz, rgb, src, rec, min_views=2)
    xyz, rgb, src = fx, frgb, fsrc
    # keep the region the photographer circled; far background only costs time
    from .geometry import fixation_point
    centres = np.array([v.centre for v in views.values()])
    dirs = np.array([rec.image(i).viewing_direction() for i in views])
    fix = fixation_point(centres, dirs)
    dist = float(np.median(np.linalg.norm(centres - fix, axis=1)))
    near = np.linalg.norm(xyz - fix, axis=1) < 1.0 * dist
    xyz, rgb, src = xyz[near], rgb[near], src[near]
    xyz, rgb, src = _downsample(xyz, rgb, src, dist / 600)
    normals = _oriented_normals(xyz, src, views)
    return DenseResult(xyz, rgb, normals, "opencv_sgbm_cpu")


def _downsample(xyz, rgb, src, voxel):
    """One averaged point per voxel (the source view of the first point is kept for normal orientation)."""
    keys = np.floor(xyz / voxel).astype(np.int64)
    keys -= keys.min(axis=0)
    dims = keys.max(axis=0) + 1
    lin = (keys[:, 0] * dims[1] + keys[:, 1]) * dims[2] + keys[:, 2]
    _, first, inverse, counts = np.unique(lin, return_index=True, return_inverse=True, return_counts=True)
    inverse = inverse.ravel()
    n = len(first)
    out_xyz = np.zeros((n, 3))
    out_rgb = np.zeros((n, 3))
    np.add.at(out_xyz, inverse, xyz)
    np.add.at(out_rgb, inverse, rgb.astype(np.float64))
    out_xyz /= counts[:, None]
    out_rgb /= counts[:, None]
    return out_xyz, out_rgb.round().astype(np.uint8), src[first]


def _voxel_size(rec: pycolmap.Reconstruction) -> float:
    centres = np.array([rec.image(i).projection_center() for i in rec.reg_image_ids()])
    pts = np.array([p.xyz for p in rec.points3D.values()])
    d = np.median(np.linalg.norm(pts - np.median(pts, axis=0), axis=1))
    spread = np.median(np.linalg.norm(centres - centres.mean(0), axis=1))
    return float(min(d, spread) / 250.0)


def _multi_view_filter(xyz, rgb, src, rec, min_views: int = 2):
    """Keep points whose voxel is supported by >= min_views different reference views."""
    vs = _voxel_size(rec)
    keys = np.floor(xyz / vs).astype(np.int64)
    keys -= keys.min(axis=0)
    dims = keys.max(axis=0) + 1
    lin = (keys[:, 0] * dims[1] + keys[:, 1]) * dims[2] + keys[:, 2]
    pair = np.unique(np.stack([lin, src.astype(np.int64)], axis=1), axis=0)
    vox, counts = np.unique(pair[:, 0], return_counts=True)
    good = vox[counts >= min_views]
    keep = np.isin(lin, good)
    log.info("multi-view filter kept %d / %d points (voxel %.5f)", keep.sum(), len(keep), vs)
    return xyz[keep], rgb[keep], src[keep]


def _oriented_normals(xyz: np.ndarray, src: np.ndarray, views: dict[int, _View]) -> np.ndarray:
    import open3d as o3d
    pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(xyz))
    pc.estimate_normals(o3d.geometry.KDTreeSearchParamKNN(knn=24))
    n = np.asarray(pc.normals)
    centres = np.array([views[i].centre for i in src])
    flip = np.einsum("ij,ij->i", n, centres - xyz) < 0
    n[flip] *= -1
    return n
