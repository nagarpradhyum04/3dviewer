import numpy as np

from app.pipeline import geometry


def box_scene(L=1.2, W=0.8, H=0.6, yaw=0.4, noise=0.002, seed=0):
    """Points on a table plane + the visible faces of a box, plus a ring of cameras looking at it."""
    rng = np.random.default_rng(seed)
    pts = []
    g = rng.uniform(-3, 3, (60000, 2))
    pts.append(np.stack([g[:, 0], np.zeros(len(g)), g[:, 1]], 1))
    n = 20000
    top = np.stack([rng.uniform(-L / 2, L / 2, n), np.full(n, H), rng.uniform(-W / 2, W / 2, n)], 1)
    sx = np.stack([np.full(n, L / 2) * rng.choice([-1, 1], n), rng.uniform(0, H, n), rng.uniform(-W / 2, W / 2, n)], 1)
    sz = np.stack([rng.uniform(-L / 2, L / 2, n), rng.uniform(0, H, n), np.full(n, W / 2) * rng.choice([-1, 1], n)], 1)
    box = np.concatenate([top, sx, sz])
    c, s = np.cos(yaw), np.sin(yaw)
    R = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    pts.append(box @ R.T)
    xyz = np.concatenate(pts) + rng.normal(0, noise, (sum(len(p) for p in pts), 3))
    az = np.linspace(0, 2 * np.pi, 24, endpoint=False)
    centres = np.stack([4 * np.cos(az), np.full(24, 2.5), 4 * np.sin(az)], 1)
    target = np.array([0, H / 2, 0])
    dirs = target - centres
    # apply an arbitrary similarity transform: reconstruction frames are arbitrary
    rot = np.linalg.qr(rng.normal(size=(3, 3)))[0]
    if np.linalg.det(rot) < 0:
        rot[:, 0] *= -1
    k, t = 0.37, np.array([3.0, -1.0, 2.0])
    return (k * xyz) @ rot.T + t, (k * centres) @ rot.T + t, dirs @ rot.T, k


def test_dimensions_recovered_in_arbitrary_frame():
    xyz, centres, dirs, k = box_scene()
    g = geometry.analyse(xyz, centres, dirs)
    assert g.plane_found
    L, W, H = (d / k for d in g.dims_aligned)
    assert abs(L - 1.2) < 0.02 and abs(W - 0.8) < 0.02 and abs(H - 0.6) < 0.02
    # aligned frame: object sits on y=0 and is centred
    a = g.apply(xyz[g.object_mask]) / k
    assert abs(np.median(a[:, 0])) < 0.05 and abs(np.median(a[:, 2])) < 0.05
    assert a[:, 1].min() > -0.02


def test_robust_bound_ignores_sparse_tail():
    rng = np.random.default_rng(1)
    wall = rng.normal(1.0, 0.002, 5000)
    inside = rng.uniform(-1, 1, 20000)
    tail = rng.uniform(1.0, 1.05, 150)
    c = np.concatenate([wall, inside, tail])
    assert abs(geometry.robust_bound(c, 1) - 1.0) < 0.004
    assert np.percentile(c, 99.9) > 1.01   # a plain percentile would be biased outwards


def test_fixation_point():
    centres = np.array([[1, 0, 0], [0, 1, 0], [-1, 0, 0.0]])
    target = np.array([0.1, 0.2, 0.3])
    assert np.allclose(geometry.fixation_point(centres, target - centres), target, atol=1e-6)
