# Roadmap and incomplete work

## Not done yet (in priority order)

1. **Run the app on physical Android devices.** The APK compiles in CI, but CameraX capture, the
   sensor-based coverage ring, the WebView bridge, sharing and Save-as still need testing on 2–3
   phones (one older, low-RAM device).
2. **Real-object accuracy study** (Phase 7): follow the protocol in [ACCURACY.md](ACCURACY.md).
3. **Background reliability:** move upload/poll into a WorkManager job (with a foreground
   notification for long uploads) so processing survives Android's background limits.
4. **Texture mapping:** per-vertex colour limits visual detail. Options: OpenMVS `TextureMesh`
   (AGPL – needs legal review), or a custom UV atlas with view selection.
5. **GPU path:** run and benchmark the implemented COLMAP PatchMatch/fusion path on an NVIDIA GPU.
6. **Instrumented UI tests** (Compose testing) once the app builds in CI.
7. **iOS:** reuse the server unchanged; port `android/core` logic (or move it to Kotlin
   Multiplatform: it already has no Android dependencies) and write SwiftUI/ARKit capture.

## Future manufacturing inspection module (Phase 8 – design only)

Nothing below is implemented. `GET /api/v1/health` lists these modules with `status: "planned"`
so clients can show them as unavailable.

Two analysis families are kept separate because they see different defects:

| Family | Input | Detects | Needs |
|---|---|---|---|
| **Image-based** (`ImageDefectDetector`) | original high-resolution photos | cracks, scratches, discolouration, surface anomalies – fine detail lost in a mesh | a trained segmentation / anomaly model and labelled defect images |
| **Geometry-based** (`GeometryAnalyzer`) | metrically calibrated mesh | dents, warping, missing material, deviation from CAD | calibrated scans, a reference CAD model, ICP alignment and tolerances |

Integration points that already exist:

* Photos are preserved unmodified with known camera poses (`work/sparse`). An image finding
  (pixel polygon) can be projected onto the mesh by casting rays from the recovered camera
  (`Finding.mesh_vertices`) so suspicious areas can be highlighted in the 3D viewer.
* The aligned, metric mesh (`work/master_mesh.ply` × scale) is the input for geometry analysis.
* The job directory and result JSON can grow an `inspection` section; the app's `JobResult` parser
  ignores unknown keys, so this does not break older clients.
* Reports: a future `inspection_report` module would render findings, photos, dimensions and
  calibration status into a PDF.

Accuracy claims for any detector must come from a held-out evaluation set. None exists today.
