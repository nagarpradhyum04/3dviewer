"""Scan3D AI reconstruction pipeline.

Stages (each in its own module):
    validate  -> per-image quality checks (blur, exposure, resolution)
    sfm       -> SIFT features, matching, incremental Structure-from-Motion (pycolmap)
    scale     -> optional metric scale from an ArUco marker of known size
    dense     -> dense point cloud (CUDA PatchMatch if available, else CPU SGBM)
    geometry  -> ground plane, object segmentation, alignment, dimensions
    mesh      -> Poisson surface reconstruction (Open3D)
    export    -> GLB / OBJ / PLY / STL via trimesh
"""
