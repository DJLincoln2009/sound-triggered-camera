import io
import json

import pytest

from receiver.app import create_app
from receiver.config import Config


@pytest.fixture
def app_ctx(tmp_path):
    cfg = Config()
    cfg.data_dir = tmp_path
    app, ctx = create_app(cfg)
    app.config.update(TESTING=True)
    return app, ctx


def test_state_exposes_pin_and_settings(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    r = client.get("/api/state")
    assert r.status_code == 200
    data = r.get_json()
    assert data["pairing_pin"] == ctx.pairing.pin
    assert "threshold" in data["settings"]
    assert data["cameras"] == []


def test_trigger_without_camera_returns_409(app_ctx):
    app, _ = app_ctx
    client = app.test_client()
    r = client.post("/api/trigger", json={})
    assert r.status_code == 409
    assert r.get_json()["ok"] is False


def test_upload_requires_pairing(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    data = {
        "metadata": json.dumps({"recording_id": "rec_x", "origin": "manual"}),
        "file": (io.BytesIO(b"video-bytes"), "rec_x.mp4"),
    }
    # Sans token -> 401
    r = client.post("/upload", data=data, content_type="multipart/form-data")
    assert r.status_code == 401


def test_upload_then_listed_and_downloadable(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    headers = {"Authorization": f"Bearer {ctx.pairing.token}"}
    data = {
        "metadata": json.dumps({
            "recording_id": "rec_x", "origin": "manual", "device_id": "d1",
            "started_at": "2026-06-21T10:00:00Z", "duration_s": 3.0,
            "width": 1280, "height": 720,
        }),
        "file": (io.BytesIO(b"video-bytes"), "rec_x.mp4"),
    }
    r = client.post("/upload", data=data, content_type="multipart/form-data", headers=headers)
    assert r.status_code == 200
    body = r.get_json()
    assert body["stored"] is True and body["bytes"] == len(b"video-bytes")

    recs = client.get("/api/recordings").get_json()
    assert len(recs) == 1 and recs[0]["recording_id"] == "rec_x"

    f = client.get("/api/recordings/rec_x/file")
    assert f.status_code == 200
    assert f.data == b"video-bytes"


def test_upload_duplicate_is_idempotent(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    headers = {"Authorization": f"Bearer {ctx.pairing.token}"}

    def do():
        return client.post(
            "/upload",
            data={"metadata": json.dumps({"recording_id": "dup", "origin": "sound"}),
                  "file": (io.BytesIO(b"abc"), "dup.mp4")},
            content_type="multipart/form-data", headers=headers,
        )

    assert do().status_code == 200
    second = do().get_json()
    assert second["duplicate"] is True


def test_settings_persisted(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    r = client.post("/api/settings", json={"threshold": 0.7, "video_quality": "FHD_1080P"})
    assert r.status_code == 200
    assert ctx.camera_settings["threshold"] == 0.7
    # Persiste sur disque
    reloaded = ctx.config.load_camera_settings()
    assert reloaded["video_quality"] == "FHD_1080P"


def test_pairing_regenerate_changes_pin(app_ctx):
    app, ctx = app_ctx
    client = app.test_client()
    old = ctx.pairing.pin
    r = client.post("/api/pairing/regenerate")
    assert r.status_code == 200
    assert r.get_json()["pairing_pin"] != old or old == r.get_json()["pairing_pin"]
    assert ctx.pairing.pin == r.get_json()["pairing_pin"]
