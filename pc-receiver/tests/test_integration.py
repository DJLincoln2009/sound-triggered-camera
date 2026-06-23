"""Test d'intégration bout-en-bout : serveur réel + caméra simulée.

Couvre le canal de commandes WebSocket (EF-21), l'appairage (ENF-06), le déclenchement
manuel avec ACK (EF-05/EF-06/EF-14), l'arrêt (EF-15) et le transfert vidéo HTTP (EF-09/EF-23).
"""
import sys
import threading
import time
from pathlib import Path

import pytest
from werkzeug.serving import make_server

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))

from receiver.app import create_app  # noqa: E402
from receiver.config import Config  # noqa: E402


class _ServerThread(threading.Thread):
    def __init__(self, app, port):
        super().__init__(daemon=True)
        self.srv = make_server("127.0.0.1", port, app, threaded=True)
        self.port = port

    def run(self):
        self.srv.serve_forever()

    def stop(self):
        self.srv.shutdown()


@pytest.fixture
def running_server(tmp_path):
    cfg = Config()
    cfg.data_dir = tmp_path
    cfg.ack_timeout_s = 5.0
    app, ctx = create_app(cfg)
    server = _ServerThread(app, port=8799)
    server.start()
    time.sleep(0.3)
    yield ctx, server.port
    server.stop()
    ctx.storage.close()


def _wait(predicate, timeout=5.0, interval=0.1):
    end = time.time() + timeout
    while time.time() < end:
        if predicate():
            return True
        time.sleep(interval)
    return False


def test_end_to_end_trigger_and_upload(running_server):
    from camera_simulator import CameraSimulator

    ctx, port = running_server
    base = f"127.0.0.1:{port}"
    sim = CameraSimulator(f"ws://{base}/ws", f"http://{base}", ctx.pairing.pin)

    assert sim.connect() is True, "appairage par PIN doit réussir"
    # La caméra a reçu le token fort.
    assert sim.token == ctx.pairing.token

    runner = threading.Thread(target=sim.run, kwargs={"auto_upload": b"FAKE-MP4-DATA"}, daemon=True)
    runner.start()

    # EF-13 : la caméra apparaît en ligne côté PC.
    assert _wait(lambda: any(c["online"] for c in ctx.hub.snapshots())), "caméra non détectée"

    # EF-05/EF-06/EF-14 : déclenchement manuel acquitté.
    res = ctx.hub.send_command_await_ack(
        __import__("receiver.protocol", fromlist=["start_recording"]).start_recording("t1")
    )
    assert res["ok"] is True, f"trigger échec: {res}"
    assert _wait(lambda: sim.recording is True)

    # EF-15 : arrêt acquitté -> déclenche l'upload côté simulateur.
    res = ctx.hub.send_command_await_ack(
        __import__("receiver.protocol", fromlist=["stop_recording"]).stop_recording("t2")
    )
    assert res["ok"] is True

    # EF-09/EF-16 : la vidéo est stockée côté PC.
    assert _wait(lambda: len(ctx.storage.list_recordings()) == 1, timeout=8), "upload non reçu"
    rec = ctx.storage.list_recordings()[0]
    assert rec["origin"] == "manual"
    assert Path(rec["file_path"]).read_bytes() == b"FAKE-MP4-DATA"

    sim.stop()


def test_unpaired_camera_rejected(running_server):
    from camera_simulator import CameraSimulator

    ctx, port = running_server
    base = f"127.0.0.1:{port}"
    sim = CameraSimulator(f"ws://{base}/ws", f"http://{base}", "wrong-pin")
    assert sim.connect() is False, "une caméra non appairée doit être rejetée (ENF-06)"
