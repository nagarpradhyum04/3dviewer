import importlib
import io
import time

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient


def jpeg(seed=0):
    rng = np.random.default_rng(seed)
    img = rng.integers(0, 255, (60, 80, 3), np.uint8)
    return cv2.imencode(".jpg", img)[1].tobytes()


@pytest.fixture()
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("SCAN3D_DATA_DIR", str(tmp_path))
    monkeypatch.setenv("SCAN3D_MIN_IMAGES", "3")
    monkeypatch.setenv("SCAN3D_MAX_IMAGES", "5")
    monkeypatch.setenv("SCAN3D_API_KEY", "secret")
    import app.main
    importlib.reload(app.main)
    with TestClient(app.main.app, headers={"X-API-Key": "secret"}) as c:
        yield c


def test_health_reports_capabilities(client):
    h = client.get("/api/v1/health").json()
    assert h["status"] == "ok" and h["dense_method"] in ("cpu", "cuda")
    assert all(m["status"] == "planned" for m in h["inspection_modules"])


def test_auth_required(client):
    r = client.post("/api/v1/jobs", json={}, headers={"X-API-Key": "wrong"})
    assert r.status_code == 401


def test_upload_validation(client):
    jid = client.post("/api/v1/jobs", json={"expected_images": 3}).json()["id"]
    assert client.put(f"/api/v1/jobs/{jid}/images/..%2Fevil.jpg", content=jpeg()).status_code in (404, 422)
    assert client.put(f"/api/v1/jobs/{jid}/images/a.txt", content=jpeg()).status_code == 422
    assert client.put(f"/api/v1/jobs/{jid}/images/a.jpg", content=b"hello world").status_code == 415
    assert client.put(f"/api/v1/jobs/{jid}/images/a.jpg", content=jpeg()).status_code == 200
    # idempotent re-upload (resume after network failure)
    assert client.put(f"/api/v1/jobs/{jid}/images/a.jpg", content=jpeg()).json()["received"] == 1
    r = client.post(f"/api/v1/jobs/{jid}/start")
    assert r.status_code == 422 and "At least 3" in r.json()["detail"]


def test_missing_announced_images_blocks_start(client):
    jid = client.post("/api/v1/jobs", json={"expected_images": 5}).json()["id"]
    for i in range(3):
        client.put(f"/api/v1/jobs/{jid}/images/{i}.jpg", content=jpeg(i))
    r = client.post(f"/api/v1/jobs/{jid}/start")
    assert r.status_code == 409 and "missing" in r.json()["detail"]


def test_unknown_job_and_file(client):
    assert client.get("/api/v1/jobs/" + "0" * 32).status_code == 404
    assert client.get("/api/v1/jobs/not-an-id").status_code == 404


def test_job_runs_to_honest_failure_and_cancel(client):
    """Random-noise photos cannot be reconstructed; the job must fail with an explanation, never fake success."""
    jid = client.post("/api/v1/jobs", json={"expected_images": 3}).json()["id"]
    for i in range(3):
        client.put(f"/api/v1/jobs/{jid}/images/{i}.jpg", content=jpeg(i))
    assert client.post(f"/api/v1/jobs/{jid}/start").json()["state"] in ("queued", "running")
    deadline = time.time() + 120
    while time.time() < deadline:
        s = client.get(f"/api/v1/jobs/{jid}").json()
        if s["state"] in ("succeeded", "failed"):
            break
        time.sleep(0.5)
    assert s["state"] == "failed"
    assert s["result"]["error"] and s["result"]["remedy"]
    assert client.get(f"/api/v1/jobs/{jid}/files/model.glb").status_code == 404
    # cancel of a finished job is a no-op; delete removes it
    assert client.post(f"/api/v1/jobs/{jid}/cancel").json()["state"] == "failed"
    assert client.delete(f"/api/v1/jobs/{jid}").status_code == 204
    assert client.get(f"/api/v1/jobs/{jid}").status_code == 404


def test_cancel_queued_job(client):
    jid = client.post("/api/v1/jobs", json={}).json()["id"]
    for i in range(3):
        client.put(f"/api/v1/jobs/{jid}/images/{i}.jpg", content=jpeg(i))
    client.post(f"/api/v1/jobs/{jid}/start")
    assert client.post(f"/api/v1/jobs/{jid}/cancel").json()["state"] == "cancelled"
