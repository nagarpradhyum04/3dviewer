# Scan3D AI

**Turn real objects into 3D models using your phone.**

Scan3D AI is an Android app (Kotlin, Jetpack Compose, CameraX) plus a Python reconstruction server
(FastAPI, COLMAP/pycolmap, OpenCV, Open3D). You photograph a stationary object from many angles with
live guidance; the photos are reconstructed into a coloured 3D mesh by real photogrammetry, shown in
an interactive viewer, measured (when a scale reference is present) and exported as GLB/OBJ/PLY/STL.

No step fakes a result. Progress comes from the running pipeline. A scan is only marked complete
after the server reports success and the model file has been downloaded. Without a scale
reference, dimensions are shown as proportions only.

---

## Status at a glance

| Area | Status | Verified how |
|---|---|---|
| Reconstruction server: validation → SIFT → matching → SfM → marker scale → dense stereo → Poisson mesh → export | ✅ Implemented | Unit/API tests; end-to-end on a rendered scene with known dimensions (errors below 1 %, see [docs/ACCURACY.md](docs/ACCURACY.md)) |
| Job API: resumable upload, queue, honest progress + ETA, cancel, delete, re-calibrate, download | ✅ Implemented | pytest + live HTTP runs |
| App core logic: API client, upload/resume/poll/download, photo-quality metrics, coverage guidance, scan library, units | ✅ Implemented | 19 JVM unit tests, plus an integration test that drove the **real server** through all 8 stages |
| 3D viewer (three.js in WebView, offline): load GLB/PLY, orbit/zoom/pan, reset, wireframe, tap-to-measure, snapshot thumbnail, error states | ✅ Implemented | Headless Chromium test with a reconstructed model (`android/tools/viewer-test.js`) |
| Android UI: splash, home, new scan, camera capture, photo review, processing, viewer, library, settings, help | ✅ Written, ⚠️ **not compiled or run in the development sandbox** | Google's Maven repository (AGP, AndroidX, CameraX) was blocked there. CI (`.github/workflows/ci.yml`) builds the APK. No physical-device test yet |
| Metric dimensions via printed ArUco marker | ✅ Implemented | Synthetic test only. Real-object validation still to be done (Phase 7 protocol below) |
| Manual calibration (two picked points + known distance) | ✅ Implemented | Server rescale tested over HTTP |
| GPU dense reconstruction (COLMAP PatchMatch, CUDA) | ⚠️ Implemented, **untested** | No GPU was available |
| UV texture atlas | ❌ Not implemented | Models carry per-vertex colour |
| On-device reconstruction | ❌ Not implemented (by design) | See "What runs where" |
| Depth-sensor (ToF/LiDAR) capture | ❌ Not implemented | The app only reports whether a depth camera exists |
| AI defect detection / CAD comparison / inspection reports | ❌ Planned only | Interfaces + catalogue in `backend/app/inspection`; the app shows them as "planned" |
| iOS | ❌ Not started | Architecture keeps platform-independent logic in `android/core` (pure Kotlin) and all heavy work on the server |

---

## Architecture

```
 Android phone                                              Reconstruction server (Python)
┌───────────────────────────────────────────┐   HTTPS     ┌────────────────────────────────────────────┐
│ Compose UI  (ui/screens/*)                │  JSON +     │ FastAPI  app/main.py                       │
│  ├─ CameraX capture + tap-to-focus        │  JPEG       │  ├─ JobManager app/jobs.py (queue, 1 proc  │
│  ├─ live blur/light hints (Y plane)       │ ──────────► │  │   per job, cancel = kill, retention)    │
│  ├─ coverage ring (rotation-vector sensor)│             │  └─ pipeline subprocess app/pipeline/run.py│
│  ├─ processing screen (server progress)   │ ◄────────── │      validate → SIFT → match → SfM         │
│  └─ 3D viewer: WebView + three.js (assets)│  progress,  │      (pycolmap) → ArUco scale → dense       │
│                                           │  GLB/OBJ/   │      (SGBM CPU / PatchMatch GPU) → plane +  │
│ android/core  (pure Kotlin, unit-tested)  │  PLY/STL    │      object segmentation → dimensions →     │
│  ├─ ReconstructionClient (OkHttp)         │             │      Poisson mesh (Open3D) → export         │
│  ├─ ScanProcessor (upload/resume/poll)    │             │      (trimesh)                             │
│  ├─ ImageQuality, CoverageTracker, Units  │             │  app/inspection: future defect modules      │
│  └─ ScanStore (files: photos, model, json)│             └────────────────────────────────────────────┘
└───────────────────────────────────────────┘
```

### What runs where

| On the phone | On the server |
|---|---|
| Camera capture, original JPEGs kept unmodified with EXIF (focal length is used by SfM) | Authoritative photo validation (excludes unusable photos) |
| Live hints: sharpness (Laplacian variance), exposure, highlights, texture: same formulas and thresholds as the server | Feature detection & matching, camera pose estimation, sparse cloud |
| Coverage guidance from device heading/tilt (a proxy; the server measures the true camera positions) | Marker detection & metric scale, dense reconstruction, meshing |
| Scan library, viewer, two-point measurement, unit conversion, export/share (Android share sheet, "Save as…") | Object segmentation, dimension estimation, file export, re-scaling |

Why not reconstruct on the phone? Photogrammetry at useful quality needs several GB of RAM and
minutes of multi-core CPU (or a CUDA GPU) even for 40 photos. On phones that means thermal
throttling, memory kills and very different results per device. COLMAP has no supported Android
build. A server gives the same reproducible result for every phone. The trade-off is that photos
leave the device: the app asks for explicit consent first, shows the server address, and can delete
the server copy automatically.

### Reconstruction engine selection

| Candidate | Licence | Verdict |
|---|---|---|
| **COLMAP via pycolmap 4.x** | BSD-3 | **Chosen.** Mature, pip-installable CPU wheels, Python API for SIFT/matching/SfM/undistortion; PatchMatch MVS when built with CUDA |
| OpenMVS (densify/mesh/texture) | **AGPL-3.0** | Not used. Best open texturing, but AGPL obligations for a network service are a risk for a commercial app. Could be added as an optional separate binary after a legal review |
| AliceVision / Meshroom | MPL-2.0 | Not used: heavy install, depth maps require CUDA, no Python API |
| OpenMVG | MPL-2.0 | Viable SfM alternative; no advantage over COLMAP here |
| Cloud photogrammetry APIs | Commercial | Not used: accounts, per-scan cost, data leaves your control. The API boundary makes swapping one in possible |
| Apple Object Capture | Proprietary | iOS/macOS only, not usable on Android |

Without a CUDA GPU, COLMAP has no dense stereo. For that case the server implements its own CPU
multi-view stereo: rectified two-view OpenCV SGBM for each view and its best neighbours, a
left/right consistency check, and a voxel-level filter that keeps only geometry confirmed by ≥3
independent reference views.

### Licensing and compatibility risks

* **App:** three.js (MIT), AndroidX/CameraX/Compose (Apache-2.0), OkHttp (Apache-2.0),
  kotlinx (Apache-2.0). three.js is bundled under `app/src/main/assets/viewer` with its licence.
* **Server:** pycolmap/COLMAP (BSD-3), OpenCV-contrib (Apache-2.0), Open3D (MIT), trimesh (MIT),
  FastAPI (MIT). No GPL/AGPL code is used. COLMAP bundles third-party code under its own terms. The
  CPU SIFT used here comes from VLFeat (BSD). COLMAP's *GPU* SIFT derives from SiftGPU, whose
  licence restricts commercial use, so review COLMAP's third-party notices before shipping a CUDA
  build commercially. The SIFT patent expired in 2020.
* **Compatibility:** pycolmap 4.2 / OpenCV 5.0 / Open3D 0.20 were tested on Python 3.13, x86-64
  Linux. Open3D needs `libEGL`/`libGL` even headless. CPU wheels only (no CUDA).
* **Android:** minSdk 26 (Android 8.0), target/compile SDK 35. The viewer needs WebGL in Android
  System WebView with ES-module/import-map support (Chrome 89+); old, never-updated WebViews will
  show an error message instead of the model.
* **Platform assumptions:** reflective, transparent, textureless, thin or moving objects reconstruct
  poorly with any photogrammetry engine. The app warns about this but cannot fix it.

### Development tools

Android Studio (Ladybug or newer) with JDK 17 and Android SDK 35; Gradle 8.14 wrapper, AGP 8.9,
Kotlin 2.1. Python 3.13 with the packages in `backend/requirements.txt`. Optional Node 20 +
Playwright for the synthetic test-data renderer and the headless viewer test. Docker for deployment.

---

## Phases

| Phase | Content | State |
|---|---|---|
| 1 | App structure, navigation, camera permission, real photo capture | Code complete; CI compile; **device test pending** |
| 2 | Photo validation (phone + server), guided multi-angle capture, coverage warnings | Done (core logic unit-tested) |
| 3 | Real reconstruction pipeline + job API | Done, tested end-to-end |
| 4 | Model loading + interactive viewer | Done, tested headless with a real reconstruction |
| 5 | Scale calibration (marker + manual) and dimensions | Done, synthetic accuracy measured |
| 6 | Library, rename/delete, export, Android share, save-as | Code complete; **device test pending** |
| 7 | Accuracy vs real reference objects | Synthetic done; **real-object protocol defined, not yet run** |
| 8 | Architecture for defect detection | Interfaces and catalogue only ([docs/ROADMAP.md](docs/ROADMAP.md)) |

**First end-to-end milestone (achieved in development):** 42 photos of a known 120 × 80 × 60 mm
object with a 50 mm marker → upload through the app's Kotlin client → server reconstruction
(3–4 min on 4 CPU cores) → GLB downloaded → displayed in the bundled viewer → dimensions
120.3 × 80.5 × 59.8 mm reported as *estimated, marker-calibrated*. Running the same flow on a
phone with real photos is the next step.

---

## Getting started

### 1. Start the server

```bash
cd backend
```
```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
```
```bash
.venv/bin/uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Docker, GPU and configuration options are described in [backend/README.md](backend/README.md).
No external accounts or API credentials are needed.

### 2. Build and run the app

Open the `android/` folder in Android Studio and run the `app` configuration. Or from the command line:

```bash
cd android
```
```bash
./gradlew :app:installDebug
```

The pure-Kotlin core can be built and tested without the Android SDK:

```bash
./gradlew -p core test
```

In the app open **Settings › Reconstruction server**, enter `http://<computer-LAN-IP>:8000` (or
`http://10.0.2.2:8000` on the emulator) and tap **Save & test connection**. Debug builds allow
plain HTTP; release builds require HTTPS except for local addresses.

### 3. Scan

1. **New scan**: name it, and for real-world size print the marker (**Get printable marker**, print at
   100 %), measure its black square and enter the value. Put it flat beside the object.
2. **Capture** 25–60 photos: one ring at eye level, one from above, small steps. The ring
   indicator shows covered directions. Blurry photos are flagged immediately with a Retake button.
3. **Review**: inspect, delete or retake photos; quality and coverage warnings are listed.
4. **Create 3D model**: the first time, you are asked for consent before photos are uploaded.
5. **Processing**: shows the live stages from the server, the measured ETA during densification,
   and a Cancel button. You can leave the screen; uploads resume after network loss or restarts.
6. **Viewer**: rotate/zoom/pan, reset, wireframe, tap two points to measure, switch mm/cm/m,
   calibrate with a known distance, export or share GLB/OBJ/PLY/STL and the original photos.

---

## Measurements: what to expect

* With the marker, all files are in metres and dimensions are labelled **Estimated**.
  On the synthetic benchmark the errors were +0.2 %, +0.7 % and −0.3 % for length, width and
  height. Real-world accuracy has **not** been validated yet and will be worse. It depends on
  print accuracy, focus, lighting and surface. No accuracy guarantee is made; verify critical
  sizes with a calliper.
* Without the marker, the app says absolute dimensions cannot be determined and shows only
  proportions, until you calibrate with a known distance.
* Length and width are the sides of the minimum-area footprint rectangle on the supporting surface,
  and height is measured above that surface. Objects that do not stand on a flat surface fall back
  to an oriented bounding box, with a warning.

Details, the method and the real-object test protocol are in [docs/ACCURACY.md](docs/ACCURACY.md).

## Limitations

* Shiny, transparent, plain-coloured, very thin or moving objects often fail or produce holes.
* Undersides and cavities that no photo sees are missing; Poisson closes small gaps smoothly.
* Models have per-vertex colour, not a high-resolution texture map.
* Processing continues while the app process is alive and resumes on next launch. There is no
  foreground service or WorkManager job, so Android may pause it in the background.
* The coverage ring uses the compass heading. Magnetic interference can make it inaccurate; the
  server's real camera-coverage check is authoritative.

## Repository layout

```
backend/                 FastAPI server + reconstruction pipeline (see backend/README.md)
  app/pipeline/          validate, sfm, scale, dense, geometry, mesh, export, run, rescale
  app/inspection/        future defect-detection interfaces (not implemented)
  tests/                 unit, API and end-to-end accuracy tests
  tools/make_marker.py   printable ArUco calibration marker
  tools/synthetic/       renderer for ground-truth test datasets (three.js + Playwright)
android/
  core/                  pure-Kotlin logic shared by the app (standalone Gradle build, unit tests)
  app/                   Android app (Compose UI, CameraX, WebView viewer)
  app/src/main/assets/viewer/   three.js viewer (offline)
  tools/viewer-test.js   headless viewer test
docs/                    accuracy report, roadmap
```
