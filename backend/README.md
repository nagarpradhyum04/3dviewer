# Scan3D AI – reconstruction server

FastAPI service that turns a set of overlapping photos into a measurable 3D model.
The Android app uploads photos here; everything compute-heavy happens on this server.

## Pipeline

| Stage | Implementation | Notes |
|---|---|---|
| Photo validation | OpenCV: Laplacian-variance sharpness, exposure, highlight and texture heuristics | Very blurry/black photos are excluded and reported |
| Features + matching | pycolmap (COLMAP 4.x) SIFT, exhaustive matching (sequential above 80 photos) | CPU by default, CUDA if pycolmap was built with it |
| Camera poses / sparse | pycolmap incremental Structure-from-Motion | Largest connected model is used; split models are reported |
| Metric scale | ArUco marker `DICT_4X4_50` id 0, corners refined in linear light, triangulated with SfM poses | Optional; without it the model has arbitrary scale |
| Dense geometry | **CPU:** rectified two-view OpenCV SGBM per view + left/right check + ≥3-view voxel consistency. **GPU:** COLMAP PatchMatch + fusion | GPU path implemented but **not tested** (no GPU available during development) |
| Object segmentation + alignment | Open3D RANSAC support plane, DBSCAN cluster nearest the cameras' aiming point; Y-up frame on the table | Falls back to a minimal oriented bounding box when no plane is found |
| Dimensions | Minimum-area footprint rectangle + height above plane; face positions located from point-density peaks | Estimates, see `docs/ACCURACY.md` |
| Mesh | Open3D screened Poisson (depth 9), trimmed to measured data, cut at the table | Per-vertex colour; no UV texture atlas |
| Export | trimesh: `model.glb` (≤250k triangles, for the viewer), `model.obj`, `model.ply`, `model.stl`, `pointcloud.ply`, `sparse.ply` | Only files that were actually produced are listed |

Units of all exported files are metres when the scan is calibrated, otherwise arbitrary.

## Run locally

```bash
python3 -m venv .venv
```
```bash
.venv/bin/pip install -r requirements-dev.txt
```
```bash
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Linux needs `libegl1 libgl1 libgomp1` for Open3D (`sudo apt-get install -y libegl1 libgl1 libgomp1`).
Tested with Python 3.13 on x86-64 Linux. Point the app at `http://<your-computer-ip>:8000`
(debug builds allow plain HTTP; the Android emulator reaches the host at `http://10.0.2.2:8000`).

### Docker

```bash
docker build -t scan3d-backend backend
```
```bash
docker run -p 8000:8000 -v scan3d-data:/data scan3d-backend
```

### GPU (optional)

The pycolmap wheel installed from PyPI by `requirements.txt` is CPU-only. For COLMAP PatchMatch on an NVIDIA GPU build pycolmap
with CUDA (see the COLMAP docs) and set `SCAN3D_DENSE=cuda`. `/api/v1/health` reports which
method is active. This path is implemented but was not executed during development.

## Configuration (environment variables)

| Variable | Default | Meaning |
|---|---|---|
| `SCAN3D_DATA_DIR` | `./data` | Job storage (photos, intermediate data, outputs) |
| `SCAN3D_API_KEY` | unset | If set, every request needs header `X-API-Key: <value>` (enter it in the app's Settings) |
| `SCAN3D_MIN_IMAGES` / `SCAN3D_MAX_IMAGES` | 8 / 150 | Photos per job |
| `SCAN3D_MAX_IMAGE_MB` | 25 | Max size per photo |
| `SCAN3D_MAX_CONCURRENT_JOBS` | 1 | Jobs reconstructed in parallel (each uses all CPU cores) |
| `SCAN3D_DENSE` | `auto` | `auto`, `cpu`, `cuda` or `none` |
| `SCAN3D_JOB_RETENTION_HOURS` | 72 | Finished/abandoned jobs are deleted after this time (0 = keep) |

No external services or third-party API credentials are needed. For deployment on the internet,
put the server behind HTTPS (reverse proxy) and set `SCAN3D_API_KEY`.

## Resource needs (measured)

42 photos at 1600×1200 on 4 CPU cores: ~3 minutes, ~2.5 GB peak RAM. Phone photos (12 MP) are
downscaled to 2000 px for SIFT and 1000 px for stereo; expect ~5–10 minutes for 40–60 photos on
a 4–8 core machine.

## API

| Method | Path | |
|---|---|---|
| GET | `/api/v1/health` | version, CPU/GPU, limits, stage list, planned inspection modules |
| POST | `/api/v1/jobs` | `{"expected_images": 42, "options": {"marker_size_mm": 50}}` → `{id}` |
| PUT | `/api/v1/jobs/{id}/images/{name}` | raw JPEG/PNG body; idempotent (resumable uploads) |
| POST | `/api/v1/jobs/{id}/start` | queue the job (checks image count) |
| GET | `/api/v1/jobs/{id}` | state, progress (stage, fraction when measurable, ETA when measurable, warnings), result |
| POST | `/api/v1/jobs/{id}/cancel` | kills the reconstruction process |
| POST | `/api/v1/jobs/{id}/calibrate` | `{"point_a":[x,y,z],"point_b":[x,y,z],"distance_m":0.1}` rescales and re-exports |
| GET | `/api/v1/jobs/{id}/files/{name}` | download an output file |
| DELETE | `/api/v1/jobs/{id}` | delete photos and results from the server |

Interactive docs: `http://localhost:8000/docs`.

Each job runs in a separate process (`python -m app.pipeline.run <job_dir>`), so a crash or
out-of-memory in native code fails only that job, and cancel is immediate.

## Tests

```bash
.venv/bin/python -m pytest -q
```

End-to-end accuracy test on a rendered scene with known dimensions (needs Node + Playwright):

```bash
cd tools/synthetic && npm ci && npx playwright install chromium
```
```bash
python tools/make_marker.py --size-mm 50 --plain --dpi 150 --out /tmp/marker.png
```
```bash
node tools/synthetic/render.js --marker /tmp/marker.png --out /tmp/synthetic
```
```bash
SCAN3D_E2E_DATASET=/tmp/synthetic .venv/bin/python -m pytest -s tests/test_e2e_synthetic.py
```

## Printable calibration marker

```bash
python tools/make_marker.py --size-mm 50 --out marker_50mm.png
```

Print at 100 % (no "fit to page"), measure the black square with a ruler and enter the measured
value in the app.
