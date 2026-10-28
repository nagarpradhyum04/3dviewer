import cv2
import numpy as np

from app.pipeline import validate


def textured(seed=0, size=(1200, 1600)):
    rng = np.random.default_rng(seed)
    img = np.full((*size, 3), 128, np.uint8)
    for _ in range(1500):
        c = tuple(int(x) for x in rng.integers(0, 255, 3))
        cv2.circle(img, (int(rng.integers(0, size[1])), int(rng.integers(0, size[0]))),
                   int(rng.integers(3, 40)), c, -1)
    return img


def test_blurry_image_is_flagged_and_excluded(tmp_path):
    paths = []
    for i in range(6):
        p = tmp_path / f"s{i}.jpg"
        cv2.imwrite(str(p), textured(i))
        paths.append(p)
    blurry = tmp_path / "blurry.jpg"
    cv2.imwrite(str(blurry), cv2.GaussianBlur(textured(9), (0, 0), 12))
    res = {q.name: q for q in validate.validate_set(paths + [blurry])}
    assert "blurry" in res["blurry.jpg"].issues and res["blurry.jpg"].excluded
    assert all(not res[p.name].excluded for p in paths)


def test_dark_low_texture_and_unreadable(tmp_path):
    dark = tmp_path / "dark.jpg"
    cv2.imwrite(str(dark), np.full((1200, 1600, 3), 20, np.uint8))
    bad = tmp_path / "bad.jpg"
    bad.write_bytes(b"not an image")
    q = validate.assess(dark)
    assert "too_dark" in q.issues and "low_texture" in q.issues
    assert validate.assess(bad).excluded


def test_highlights_flagged(tmp_path):
    img = textured(3)
    img[200:800, 300:1300] = 255
    p = tmp_path / "shiny.jpg"
    cv2.imwrite(str(p), img)
    assert "overexposed_or_reflective" in validate.assess(p).issues
