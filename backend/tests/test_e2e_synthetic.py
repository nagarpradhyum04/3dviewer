"""Slow end-to-end accuracy test on a rendered dataset with known ground truth.

    node tools/synthetic/render.js --marker marker_plain.png --out /tmp/ds
    SCAN3D_E2E_DATASET=/tmp/ds pytest tests/test_e2e_synthetic.py -s
"""
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
import trimesh

DATASET = os.environ.get("SCAN3D_E2E_DATASET")


@pytest.mark.skipif(not DATASET, reason="set SCAN3D_E2E_DATASET to a rendered dataset")
def test_reconstruction_and_measurement_accuracy(tmp_path):
    ds = Path(DATASET)
    gt = json.loads((ds / "ground_truth.json").read_text())
    job = tmp_path / "job"
    shutil.copytree(ds / "images", job / "images")
    names = sorted(p.name for p in (job / "images").iterdir())
    (job / "job.json").write_text(json.dumps({"id": "e2e", "images": names, "options": {
        "marker_size_mm": gt["marker"]["size_m"] * 1000, "dense": "cpu"}}))
    root = Path(__file__).resolve().parent.parent
    subprocess.run([sys.executable, "-m", "app.pipeline.run", str(job)], cwd=root, check=True)
    result = json.loads((job / "result.json").read_text())
    assert result["status"] == "succeeded"
    assert result["calibration"]["status"] == "calibrated"
    dims = result["dimensions"]
    errors = {k: dims[k] / gt["object"][k] - 1 for k in ("length", "width", "height")}
    print("relative errors", {k: f"{v * 100:+.2f}%" for k, v in errors.items()})
    assert all(abs(e) < 0.03 for e in errors.values())
    mesh = trimesh.load(job / "output" / "model.glb", force="mesh")
    assert len(mesh.faces) > 1000
    ext = mesh.bounds[1] - mesh.bounds[0]
    assert abs(ext[1] / gt["object"]["height"] - 1) < 0.05
