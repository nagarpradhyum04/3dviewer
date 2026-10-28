"""Catalogue of planned inspection modules. None is implemented yet."""

PLANNED = [
    {"key": "crack_detection", "family": "image", "status": "planned",
     "requires": "trained segmentation model and labelled defect images"},
    {"key": "scratch_detection", "family": "image", "status": "planned",
     "requires": "trained segmentation model and labelled defect images"},
    {"key": "surface_anomaly", "family": "image", "status": "planned",
     "requires": "anomaly-detection model trained on defect-free parts"},
    {"key": "dent_detection", "family": "geometry", "status": "planned",
     "requires": "metrically calibrated scan; local curvature / deviation analysis"},
    {"key": "cad_comparison", "family": "geometry", "status": "planned",
     "requires": "reference CAD model, ICP alignment, tolerance specification"},
    {"key": "inspection_report", "family": "report", "status": "planned",
     "requires": "at least one implemented detector"},
]


def describe() -> list[dict]:
    return PLANNED
