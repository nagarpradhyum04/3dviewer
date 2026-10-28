"""Run the full reconstruction for one job directory.

    python -m app.pipeline.run <job_dir>

The API starts this as a subprocess so that a job can be cancelled by
terminating the process, and so a crash in native code cannot take the API
down. Communication is file based:

    job.json       (read)  options and image list written by the API
    progress.json  (write) live stage/progress, see progress.py
    result.json    (write) final outcome; present only once the run has ended
    pipeline.log   (write) detailed log
"""
from __future__ import annotations

import json
import logging
import os
import sys
import time
import traceback
from pathlib import Path

import numpy as np
import open3d as o3d

from . import dense, export, geometry, mesh, scale, sfm, validate
from .progress import Progress
from .sfm import PipelineError

log = logging.getLogger("scan3d.pipeline")

MIN_IMAGES = int(os.environ.get("SCAN3D_MIN_IMAGES", "8"))
MIN_OBJECT_POINTS_FOR_MESH = 3000


def _write_json(path: Path, data: dict) -> None:
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(data, indent=2))
    os.replace(tmp, path)


def coverage(centres_aligned: np.ndarray) -> dict:
    """Angular coverage of the camera positions around the object (aligned frame, Y up)."""
    az = np.sort(np.degrees(np.arctan2(centres_aligned[:, 2], centres_aligned[:, 0])) % 360)
    gaps = np.diff(np.concatenate([az, [az[0] + 360]]))
    horiz = np.linalg.norm(centres_aligned[:, [0, 2]], axis=1)
    elev = np.degrees(np.arctan2(centres_aligned[:, 1], horiz))
    largest = float(gaps.max()) if len(gaps) else 360.0
    return {
        "largest_azimuth_gap_deg": round(largest, 1),
        "elevation_min_deg": round(float(elev.min()), 1),
        "elevation_max_deg": round(float(elev.max()), 1),
        "complete_ring": bool(largest < 60),
    }


def quality_grade(info: dict) -> tuple[str, list[str]]:
    reasons = []
    grade = 2  # 2 good, 1 fair, 0 poor
    sp = info["sparse"]
    if sp["images_registered"] < 0.8 * sp["images_used"]:
        grade -= 1
        reasons.append("Not all photos could be placed in 3D.")
    if sp["mean_reprojection_error_px"] > 1.5:
        grade -= 1
        reasons.append("High reprojection error: camera poses are less certain.")
    cov = info.get("coverage")
    if cov and not cov["complete_ring"]:
        grade -= 1
        reasons.append(f"Gap of {cov['largest_azimuth_gap_deg']:.0f}° in the photo coverage around the object.")
    if info.get("object_points", 0) < 10000:
        grade -= 1
        reasons.append("Few dense points on the object.")
    return ["poor", "fair", "good"][max(grade, 0)], reasons


def run(job_dir: Path) -> dict:
    job = json.loads((job_dir / "job.json").read_text())
    opts = job.get("options", {})
    image_dir = job_dir / "images"
    work = job_dir / "work"
    out = job_dir / "output"
    work.mkdir(exist_ok=True)
    progress = Progress(job_dir / "progress.json")
    t0 = time.time()

    # 1. validation ---------------------------------------------------------
    progress.start("validate", "Checking photo quality")
    names = [n for n in job["images"] if (image_dir / n).exists()]
    qualities = validate.validate_set([image_dir / n for n in names])
    usable = [q.name for q in qualities if not q.excluded]
    excluded = [q.name for q in qualities if q.excluded]
    if excluded:
        progress.warn(f"{len(excluded)} photo(s) excluded as unusable (very blurry or black): "
                      f"{', '.join(excluded[:6])}{'…' if len(excluded) > 6 else ''}")
    dark = sum("too_dark" in q.issues for q in qualities)
    if dark > len(qualities) / 3:
        progress.warn("Many photos are dark. Better, diffuse lighting improves detail and reduces noise.")
    shiny = sum("overexposed_or_reflective" in q.issues for q in qualities)
    if shiny > len(qualities) / 3:
        progress.warn("Many photos contain large bright highlights. Reflective surfaces reconstruct poorly; "
                      "use diffuse light or a matte spray.")
    flat = sum("low_texture" in q.issues for q in qualities)
    if flat > len(qualities) / 3:
        progress.warn("Many photos show little surface texture. Plain or transparent objects are hard to "
                      "reconstruct; a textured background and placemat help.")
    if len(usable) < MIN_IMAGES:
        raise PipelineError(f"Only {len(usable)} usable photos; at least {MIN_IMAGES} are required.",
                            "Capture 25-60 sharp, overlapping photos while circling the object.")

    # 2-4. structure from motion -------------------------------------------
    sparse_res = sfm.run(image_dir, usable, work, progress)
    rec = sparse_res.reconstruction
    ids = rec.reg_image_ids()
    centres = np.array([rec.image(i).projection_center() for i in ids])
    view_dirs = np.array([rec.image(i).viewing_direction() for i in ids])
    sparse_xyz, sparse_rgb = sfm.sparse_points(rec)

    # 5. scale ----------------------------------------------------------------
    marker_mm = opts.get("marker_size_mm")
    scale_res = None
    if marker_mm:
        progress.start("scale", "Searching photos for the calibration marker")
        scale_res = scale.estimate(rec, image_dir, float(marker_mm) / 1000.0, int(opts.get("marker_id", 0)))
        if not scale_res.found:
            progress.warn("Calibration marker not found: " + scale_res.message +
                          " The model has arbitrary scale; you can calibrate later with a known distance.")
    else:
        progress.skip("scale", "no reference marker was used, so absolute size is unknown")

    # 6. dense ----------------------------------------------------------------
    method = dense.available_method(opts.get("dense", "auto"))
    dense_res = None
    if method == "none":
        progress.skip("dense", "disabled in options")
    else:
        progress.start("dense", f"Dense reconstruction ({'GPU' if method == 'cuda' else 'CPU'})")
        try:
            dense_res = dense.run(sparse_res.model_dir, image_dir, work, progress, method)
        except Exception as e:  # keep going with sparse points
            log.exception("dense reconstruction failed")
            progress.warn(f"Dense reconstruction failed ({e.__class__.__name__}); continuing with sparse points only.")
        if dense_res is not None and len(dense_res.xyz) < 1000:
            progress.warn("Dense reconstruction produced almost no points; continuing with sparse points only.")
            dense_res = None

    # 7. geometry + mesh --------------------------------------------------------
    progress.start("mesh", "Separating the object from its surroundings")
    pts = dense_res.xyz if dense_res is not None else sparse_xyz
    rgb = dense_res.rgb if dense_res is not None else sparse_rgb
    geo = geometry.analyse(pts, centres, view_dirs)
    for w in geo.warnings:
        progress.warn(w)
    aligned = geo.apply(pts)
    obj = geo.object_mask
    cov = coverage(geo.apply(centres))
    if not cov["complete_ring"]:
        progress.warn(f"Photos leave a {cov['largest_azimuth_gap_deg']:.0f}° gap around the object; that side "
                      "will be incomplete.")
    if cov["elevation_max_deg"] < 35:
        progress.warn("No photos from higher angles; the top of the object may be incomplete.")

    mesh_full = mesh_view = None
    if dense_res is not None and obj.sum() >= MIN_OBJECT_POINTS_FOR_MESH:
        progress.update(message="Poisson surface reconstruction")
        normals = dense_res.normals @ geo.rotation.T
        mesh_full = mesh.poisson(aligned[obj], rgb[obj], normals[obj], geo.dims_aligned)
        if len(mesh_full.triangles) == 0:
            mesh_full = None
            progress.warn("Surface reconstruction produced an empty mesh; only point clouds are available.")
        else:
            mesh_view = mesh.simplify(mesh_full)
            o3d.io.write_triangle_mesh(str(work / "master_mesh.ply"), mesh_full)
            o3d.io.write_triangle_mesh(str(work / "master_view.ply"), mesh_view)
    else:
        progress.skip("mesh", "not enough dense points on the object to build a surface")

    np.savez_compressed(work / "master_points.npz", dense_xyz=aligned, dense_rgb=rgb,
                        sparse_xyz=geo.apply(sparse_xyz), sparse_rgb=sparse_rgb)

    # 8. export -----------------------------------------------------------------
    progress.start("export", "Writing model files")
    s = scale_res.scale if scale_res and scale_res.found else 1.0
    files = export.write_all(out, mesh_full, mesh_view, aligned, rgb, geo.apply(sparse_xyz), sparse_rgb, s)

    info = {
        "sparse": sparse_res.stats(),
        "dense": dense_res.stats() if dense_res is not None else {"dense_points": 0, "dense_method": None},
        "mesh": {"triangles": len(mesh_full.triangles) if mesh_full else 0,
                 "viewer_triangles": len(mesh_view.triangles) if mesh_view else 0,
                 "colour": "per-vertex" if mesh_full else None},
        "object_points": int(obj.sum()),
        "segmentation": geo.method,
        "coverage": cov,
        "images": [q.to_dict() for q in qualities],
        "processing_seconds": round(time.time() - t0, 1),
    }
    grade, reasons = quality_grade(info)
    info["grade"], info["grade_reasons"] = grade, reasons

    meta = {
        "dims_aligned": list(geo.dims_aligned),
        "plane_found": geo.plane_found,
        "has_mesh": mesh_full is not None,
        "coverage_complete": cov["complete_ring"],
    }
    _write_json(work / "geometry.json", meta)
    calibration = calibration_block(scale_res, s)
    result = {
        "status": "succeeded",
        "files": files,
        "calibration": calibration,
        "dimensions": dimensions_block(geo.dims_aligned, s, calibration, geo.plane_found, cov["complete_ring"]),
        "quality": info,
        "warnings": progress.warnings,
    }
    progress.finish()
    return result


def calibration_block(scale_res, s: float, manual: dict | None = None) -> dict:
    if manual:
        return {"status": "calibrated", "method": "manual_reference", "units": "m", "scale_factor": s,
                "details": manual,
                "note": "Scaled from a distance you entered between two points you picked on the model. "
                        "Accuracy depends on how precisely the points were picked."}
    if scale_res is not None and scale_res.found:
        return {"status": "calibrated", "method": "aruco_marker", "units": "m", "scale_factor": s,
                "details": scale_res.to_dict(),
                "note": "Scaled using the printed reference marker. Dimensions are estimates; verify "
                        "critical measurements with a calliper or ruler."}
    return {"status": "uncalibrated", "method": None, "units": "arbitrary", "scale_factor": 1.0,
            "details": scale_res.to_dict() if scale_res is not None else None,
            "note": "This scan has no scale reference, so absolute dimensions cannot be determined. "
                    "Proportions are still valid. Calibrate by entering a known distance between two "
                    "points on the model, or scan again with the printed marker."}


def dimensions_block(dims_aligned, s: float, calibration: dict, plane_found: bool,
                     coverage_complete: bool = True) -> dict:
    L, W, H = (float(d) * s for d in dims_aligned)
    calibrated = calibration["status"] == "calibrated"
    warnings = []
    if not coverage_complete:
        warnings.append("The photos do not go all the way around the object, so unseen sides are missing "
                        "and length/width are probably underestimated.")
    if not plane_found:
        warnings.append("No supporting surface was found; the bounding box may include background.")
    return {
        "reliable": coverage_complete and plane_found,
        "warnings": warnings,
        "length": L, "width": W, "height": H,
        "units": "m" if calibrated else "arbitrary",
        "kind": "estimated" if calibrated else "relative_only",
        "method": "min-area footprint on support plane + height above plane" if plane_found
        else "minimal oriented bounding box",
        "note": ("Length is the longer horizontal side, width the shorter, height is measured from the "
                 "supporting surface. These are estimates from photogrammetry, not verified measurements."),
    }


def main() -> None:
    job_dir = Path(sys.argv[1]).resolve()
    logging.basicConfig(filename=job_dir / "pipeline.log", level=logging.INFO,
                        format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    try:
        result = run(job_dir)
    except PipelineError as e:
        log.warning("pipeline error: %s", e.message)
        result = {"status": "failed", "error": e.message, "remedy": e.remedy}
    except MemoryError:
        log.exception("out of memory")
        result = {"status": "failed", "error": "The server ran out of memory.",
                  "remedy": "Try fewer photos or ask the operator to use a larger machine."}
    except Exception as e:  # noqa: BLE001
        log.error("unexpected failure\n%s", traceback.format_exc())
        result = {"status": "failed", "error": f"Reconstruction failed unexpectedly ({e.__class__.__name__}).",
                  "remedy": "Try again; if it keeps failing, send the job id to the server operator."}
    try:
        prog = json.loads((job_dir / "progress.json").read_text())
        result.setdefault("warnings", prog.get("warnings", []))
    except (OSError, ValueError):
        pass
    _write_json(job_dir / "result.json", result)
    sys.exit(0 if result["status"] == "succeeded" else 1)


if __name__ == "__main__":
    main()
