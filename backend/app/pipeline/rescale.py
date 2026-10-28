"""Apply a user-supplied scale reference to a finished job and re-export.

The user picks two points on the model in the app and enters the real distance
between them (e.g. a ruler or the known length of an edge). Model files are
regenerated in metres from the unscaled master data saved by the pipeline.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

import numpy as np
import open3d as o3d

from . import export
from .run import calibration_block, dimensions_block


class RescaleError(ValueError):
    pass


def apply(job_dir: Path, point_a: list[float], point_b: list[float], distance_m: float) -> dict:
    result = json.loads((job_dir / "result.json").read_text())
    if result.get("status") != "succeeded":
        raise RescaleError("Only successful reconstructions can be calibrated")
    if not (1e-4 <= distance_m <= 100):
        raise RescaleError("Reference distance must be between 0.1 mm and 100 m")
    measured = float(np.linalg.norm(np.asarray(point_a, float) - np.asarray(point_b, float)))
    if measured <= 0:
        raise RescaleError("The two points must be different")
    current = float(result["calibration"]["scale_factor"])
    new_scale = current * distance_m / measured

    work = job_dir / "work"
    geo = json.loads((work / "geometry.json").read_text())
    pts = np.load(work / "master_points.npz")
    mesh_full = mesh_view = None
    if geo.get("has_mesh"):
        mesh_full = o3d.io.read_triangle_mesh(str(work / "master_mesh.ply"))
        mesh_view = o3d.io.read_triangle_mesh(str(work / "master_view.ply"))
    files = export.write_all(job_dir / "output", mesh_full, mesh_view, pts["dense_xyz"], pts["dense_rgb"],
                             pts["sparse_xyz"], pts["sparse_rgb"], new_scale)
    manual = {"reference_distance_m": distance_m, "picked_distance_model_units": measured,
              "previous_method": result["calibration"].get("method")}
    result["calibration"] = calibration_block(None, new_scale, manual=manual)
    result["dimensions"] = dimensions_block(geo["dims_aligned"], new_scale, result["calibration"],
                                            geo.get("plane_found", False), geo.get("coverage_complete", True))
    result["files"] = files
    tmp = job_dir / "result.json.tmp"
    tmp.write_text(json.dumps(result, indent=2))
    os.replace(tmp, job_dir / "result.json")
    return result
