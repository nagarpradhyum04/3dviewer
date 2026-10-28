"""Future manufacturing-inspection modules (NOT IMPLEMENTED).

Two families are kept deliberately separate because they detect different
things from different data:

* ``image``    – 2D defect detection on the original photos (cracks, scratches,
                 surface anomalies). Needs a trained model + labelled data.
                 Findings are mapped onto the 3D model through the known camera
                 poses (pixel -> ray -> mesh intersection).
* ``geometry`` – 3D shape analysis of the reconstructed mesh (dents, warping,
                 deviation from a reference CAD model after ICP alignment).
                 Needs a metrically calibrated scan and a tolerance spec.

Nothing here runs today. ``registry.describe()`` is exposed by /health so the
app can show these as "planned", never as available.
"""
