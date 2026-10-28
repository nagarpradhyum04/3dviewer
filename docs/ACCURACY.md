# Reconstruction and measurement accuracy

> Summary: on a **synthetic** scene, marker-calibrated dimensions were within 0.6 % of ground truth.
> Accuracy on **real objects** has not been measured yet. Results there depend on print
> accuracy, optics, lighting and surface, and will be worse. Nothing in the app claims a fixed
> accuracy. All dimensions are labelled *estimated*.

## How dimensions are computed

1. Structure-from-Motion recovers camera poses and a sparse cloud in arbitrary units.
2. **Scale.** The printed ArUco marker (side *S*) is detected in every photo. Its edges are re-fitted
   in *linear* light, and the four corners are triangulated with the recovered poses. Then
   `scale = S / reconstructed side`. The consistency of the four sides and two diagonals is reported.
   * Why linear light: cameras blur in linear intensity and then gamma-encode. Taking the edge at
     mid-grey of the encoded image places it *inside* a dark square. On the synthetic data this made
     the marker look 0.9 % too small. Fitting edges in linear intensity reduced that to 0.08 %.
3. Dense points are computed and the supporting plane is found with RANSAC. The object is the
   point cluster above the plane nearest the point the cameras were aimed at.
4. **Height** is the top of the object above the plane. **Length/width** are the sides of the
   minimum-area rectangle around the footprint. Flat faces are located from point-density peaks,
   so thin stereo noise outside the true surface is ignored. Curved surfaces use the 99.5th percentile.

## Synthetic benchmark (reproducible)

Scene: a textured box of 120 × 80 × 60 mm on a textured table, plus a 50 mm marker. It was
rendered at 1600×1200 from 42 viewpoints (3 rings at 20°, 38° and 58° elevation, 0.42 m away)
by `backend/tools/synthetic/render.js`. Reproduce it with the commands in `backend/README.md`
(`tests/test_e2e_synthetic.py`, which also runs in CI).

| Quantity | Ground truth | Measured | Error |
|---|---|---|---|
| Focal length (px) | 1230.2 | 1230.3 | +0.01 % |
| Photos registered | 42 | 42 | – |
| Marker scale vs true scale | – | – | −0.08 % (after linear-light refinement; +0.93 % before) |
| Length | 120.0 mm | 120.3 mm | +0.27 % |
| Width | 80.0 mm | 80.5 mm | +0.59 % |
| Height | 60.0 mm | 59.8 mm | −0.36 % |

Mean SfM reprojection error 0.60 px; 4 CPU cores; ~3–4 min total.

### Degraded captures (same scene)

| Scenario | Outcome |
|---|---|
| No marker | Succeeds; calibration `uncalibrated`, units `arbitrary`, app shows proportions only |
| 12 photos covering only half the object | Succeeds; quality `fair`, warning "Photos leave a 209° gap…"; width **55.6 mm vs 80 mm**, so the dimensions are flagged `reliable: false` with an explanation |
| 3 random-noise images | Fails with "Could not determine camera positions…" plus a remedy; no model is produced (`tests/test_api.py`) |

Why synthetic data overstates real accuracy: perfect pinhole optics, no motion blur, no rolling
shutter, ideal texture, a perfectly flat and perfectly printed marker, and no reflections.

## Real-object validation protocol (Phase 7 – to do)

1. Pick 5 reference objects measured with a calliper (±0.05 mm): a matte textured box, a cylinder
   (mug), an organic shape (shoe), a small object (~5 cm) and a large one (~40 cm).
2. Print the marker at 100 % and measure the printed square with the calliper.
3. Scan each object 3 times with 2 different phones, following the in-app guidance (≥30 photos,
   two rings).
4. Record L/W/H from the app and three point-to-point distances measured in the viewer.
5. Report the mean and maximum relative error per object type, and repeatability between scans.
   Publish the numbers here before stating any accuracy in user-facing text.
