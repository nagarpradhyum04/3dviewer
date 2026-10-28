"""Surface reconstruction: screened Poisson (Open3D, MIT licence)."""
from __future__ import annotations

import logging

import numpy as np
import open3d as o3d

log = logging.getLogger(__name__)

VIEWER_MAX_TRIANGLES = 250_000


def poisson(xyz: np.ndarray, rgb: np.ndarray, normals: np.ndarray, dims: tuple[float, float, float],
            depth: int = 9) -> o3d.geometry.TriangleMesh:
    """Mesh the (aligned) object points. Y is up and y=0 is the supporting surface.

    The underside of an object standing on a table is never photographed; Poisson
    closes it with a smooth guess, which we then cut at the supporting plane.
    """
    pc = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(xyz))
    pc.colors = o3d.utility.Vector3dVector(rgb.astype(np.float64) / 255.0)
    pc.normals = o3d.utility.Vector3dVector(normals)
    mesh, density = o3d.geometry.TriangleMesh.create_from_point_cloud_poisson(pc, depth=depth, linear_fit=True)
    density = np.asarray(density)
    mesh.remove_vertices_by_mask(density < np.quantile(density, 0.002))
    # Remove surface that Poisson invented far away from any measured point, but keep the
    # interpolation across small gaps (sparsely observed walls) so the model stays closed.
    verts = o3d.geometry.PointCloud(mesh.vertices)
    far = np.asarray(verts.compute_point_cloud_distance(pc)) > 0.08 * max(dims)
    mesh.remove_vertices_by_mask(far)
    L, W, H = dims
    pad = 0.04 * max(dims)
    box = o3d.geometry.AxisAlignedBoundingBox(np.array([-L / 2 - pad, 0.0, -W / 2 - pad]),
                                              np.array([L / 2 + pad, H + pad, W / 2 + pad]))
    mesh = mesh.crop(box)
    mesh = _largest_components(mesh)
    mesh.remove_degenerate_triangles()
    mesh.remove_unreferenced_vertices()
    mesh.compute_vertex_normals()
    log.info("poisson mesh: %d vertices, %d triangles", len(mesh.vertices), len(mesh.triangles))
    return mesh


def _largest_components(mesh: o3d.geometry.TriangleMesh, min_fraction: float = 0.05) -> o3d.geometry.TriangleMesh:
    clusters, counts, _ = mesh.cluster_connected_triangles()
    clusters, counts = np.asarray(clusters), np.asarray(counts)
    if len(counts) == 0:
        return mesh
    small = counts[clusters] < min_fraction * counts.max()
    mesh.remove_triangles_by_mask(small)
    mesh.remove_unreferenced_vertices()
    return mesh


def simplify(mesh: o3d.geometry.TriangleMesh, max_triangles: int = VIEWER_MAX_TRIANGLES) -> o3d.geometry.TriangleMesh:
    if len(mesh.triangles) <= max_triangles:
        return mesh
    out = mesh.simplify_quadric_decimation(max_triangles)
    out.compute_vertex_normals()
    return out
