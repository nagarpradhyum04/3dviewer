import cv2
import numpy as np

from app.pipeline import scale


def render_marker(side_px=120.0, offset=(200.3, 150.7), sigma=1.2):
    """Render a black square on white with optical blur applied in linear light, then sRGB-encode."""
    ss = 8
    h, w = 500, 500
    big = np.ones((h * ss, w * ss), np.float32)
    x0, y0 = offset
    big[int(y0 * ss):int((y0 + side_px) * ss), int(x0 * ss):int((x0 + side_px) * ss)] = 0.0
    lin = cv2.resize(big, (w, h), interpolation=cv2.INTER_AREA)
    lin = cv2.GaussianBlur(lin, (0, 0), sigma)
    srgb = np.where(lin <= 0.0031308, lin * 12.92, 1.055 * lin ** (1 / 2.4) - 0.055)
    gray = (srgb * 255).round().astype(np.uint8)
    # OpenCV pixel-centre convention: the square spans [x0-0.5, x0+side-0.5]
    true = np.array([[x0, y0], [x0 + side_px, y0], [x0 + side_px, y0 + side_px], [x0, y0 + side_px]]) - 0.5
    return gray, true


def test_linear_light_refinement_removes_gamma_bias():
    gray, true = render_marker()
    rough = true + np.array([[0.6, 0.6], [-0.6, 0.6], [-0.6, -0.6], [0.6, -0.6]])  # biased inwards
    refined = scale.refine_corners_linear(scale._srgb_to_linear(gray), rough)
    side_true = np.linalg.norm(true[1] - true[0])
    side_ref = np.mean([np.linalg.norm(refined[(k + 1) % 4] - refined[k]) for k in range(4)])
    assert abs(side_ref / side_true - 1) < 0.003
    assert np.abs(refined - true).max() < 0.25


def test_triangulate_rays():
    p = np.array([0.3, -0.2, 2.0])
    origins = [np.array([0, 0, 0.0]), np.array([1, 0, 0.0]), np.array([0, 1, 0.0])]
    rays = [(o, (p - o) / np.linalg.norm(p - o)) for o in origins]
    assert np.allclose(scale._triangulate(rays), p)
