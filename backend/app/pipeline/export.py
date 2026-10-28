"""Write model files. Only formats that were actually produced are listed."""
from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
import open3d as o3d
import trimesh

log = logging.getLogger(__name__)

FORMATS = {
    "model.glb": ("glb", "Mesh with vertex colours, simplified for interactive viewing (glTF binary)"),
    "model.obj": ("obj", "Full-resolution mesh with vertex colours (Wavefront OBJ)"),
    "model.ply": ("ply", "Full-resolution mesh with vertex colours (PLY)"),
    "model.stl": ("stl", "Geometry only, for CAD / 3D printing (STL)"),
    "pointcloud.ply": ("ply", "Dense coloured point cloud of the object and its surroundings"),
    "sparse.ply": ("ply", "Sparse Structure-from-Motion points"),
}


def _trimesh(mesh: o3d.geometry.TriangleMesh, scale: float, linear_colours: bool = False) -> trimesh.Trimesh:
    """Convert to trimesh. glTF defines vertex colours as linear; OBJ/PLY viewers expect sRGB."""
    v = np.asarray(mesh.vertices) * scale
    f = np.asarray(mesh.triangles)
    colours = None
    if mesh.has_vertex_colors():
        c = np.clip(np.asarray(mesh.vertex_colors), 0, 1)
        if linear_colours:
            c = np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
        c = (c * 255).round().astype(np.uint8)
        colours = np.hstack([c, np.full((len(c), 1), 255, np.uint8)])
    return trimesh.Trimesh(v, f, vertex_colors=colours, process=False)


def _write_points(path: Path, xyz: np.ndarray, rgb: np.ndarray, scale: float) -> None:
    pc = trimesh.PointCloud(xyz * scale, colors=np.hstack([rgb, np.full((len(rgb), 1), 255, np.uint8)]))
    pc.export(path)


def write_all(out: Path, mesh_full: o3d.geometry.TriangleMesh | None, mesh_view: o3d.geometry.TriangleMesh | None,
              dense_xyz: np.ndarray | None, dense_rgb: np.ndarray | None,
              sparse_xyz: np.ndarray, sparse_rgb: np.ndarray, scale: float) -> list[dict]:
    """Write every available output, scaled by `scale`. Returns the file manifest."""
    out.mkdir(parents=True, exist_ok=True)
    written = []
    if mesh_view is not None and len(mesh_view.triangles):
        _trimesh(mesh_view, scale, linear_colours=True).export(out / "model.glb")
        written.append("model.glb")
    if mesh_full is not None and len(mesh_full.triangles):
        tm = _trimesh(mesh_full, scale)
        tm.export(out / "model.obj", include_color=True, include_normals=False)
        tm.export(out / "model.ply")
        tm.export(out / "model.stl")
        written += ["model.obj", "model.ply", "model.stl"]
    if dense_xyz is not None and len(dense_xyz):
        _write_points(out / "pointcloud.ply", dense_xyz, dense_rgb, scale)
        written.append("pointcloud.ply")
    if len(sparse_xyz):
        _write_points(out / "sparse.ply", sparse_xyz, sparse_rgb, scale)
        written.append("sparse.ply")
    return [{"name": n, "format": FORMATS[n][0], "description": FORMATS[n][1],
             "bytes": (out / n).stat().st_size} for n in written]
