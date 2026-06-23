"""Tests du relais de signaling de la diffusion en direct (WebRTC).

Le PC ne fait que relayer offre/réponse/ICE entre le navigateur (récepteur) et la
caméra (émetteur) ; ces tests valident le routage et l'activation au choix, sans WebRTC.
"""
from receiver import protocol
from receiver.app import _handle_signal_message, create_app
from receiver.config import Config
from receiver.hub import BrowserSignalSession, CameraConnection, CameraHub


def _drain(q):
    out = []
    while not q.empty():
        out.append(q.get_nowait())
    return out


def test_live_constructors_and_types():
    req = protocol.live_request("sess1")
    assert req["type"] == protocol.MsgType.LIVE_REQUEST and req["session_id"] == "sess1"
    stop = protocol.live_stop("sess1")
    assert stop["type"] == protocol.MsgType.LIVE_STOP
    for t in (protocol.MsgType.LIVE_OFFER, protocol.MsgType.LIVE_ANSWER,
              protocol.MsgType.LIVE_ICE, protocol.MsgType.LIVE_STOP,
              protocol.MsgType.LIVE_REQUEST):
        assert t in protocol.LIVE_TYPES


def test_hub_relays_signaling_both_ways():
    hub = CameraHub()
    cam = CameraConnection({"id": "cam1", "name": "Cam 1"})
    hub.add(cam)
    browser = BrowserSignalSession()

    hub.live_open("s1", cam, browser)
    assert "s1" in browser.live_sessions

    # navigateur -> caméra
    assert hub.route_from_browser("s1", {"type": "LIVE_ANSWER", "session_id": "s1", "sdp": "a"})
    assert _drain(cam.outgoing)[-1]["type"] == "LIVE_ANSWER"

    # caméra -> navigateur
    assert hub.route_from_camera("s1", {"type": "LIVE_OFFER", "session_id": "s1", "sdp": "o"})
    assert _drain(browser.outgoing)[-1]["type"] == "LIVE_OFFER"

    # session fermée -> plus de relais
    hub.live_close("s1")
    assert "s1" not in browser.live_sessions
    assert hub.route_from_browser("s1", {"type": "LIVE_ICE"}) is False
    assert hub.route_from_camera("s1", {"type": "LIVE_ICE"}) is False


def test_camera_live_message_routed_to_browser():
    hub = CameraHub()
    cam = CameraConnection({"id": "cam1"})
    hub.add(cam)
    browser = BrowserSignalSession()
    hub.live_open("s2", cam, browser)

    hub.handle_camera_message(cam, {"type": protocol.MsgType.LIVE_OFFER,
                                    "session_id": "s2", "sdp": "v=0"})
    assert _drain(browser.outgoing)[-1]["sdp"] == "v=0"


def test_live_start_refused_when_disabled(tmp_path):
    cfg = Config()
    cfg.data_dir = tmp_path
    _, ctx = create_app(cfg)
    cam = CameraConnection({"id": "cam1"})
    ctx.hub.add(cam)
    session = BrowserSignalSession()

    # live désactivé par défaut -> refus, aucune LIVE_REQUEST envoyée à la caméra
    _handle_signal_message(session, ctx, {"type": "LIVE_START", "device_id": "cam1"})
    msgs = _drain(session.outgoing)
    assert msgs and msgs[0]["type"] == "LIVE_ERROR"
    assert cam.outgoing.empty()


def test_live_start_when_enabled_requests_camera(tmp_path):
    cfg = Config()
    cfg.data_dir = tmp_path
    _, ctx = create_app(cfg)
    ctx.live_enabled = True
    cam = CameraConnection({"id": "cam1"})
    ctx.hub.add(cam)
    session = BrowserSignalSession()

    _handle_signal_message(session, ctx, {"type": "LIVE_START", "device_id": "cam1"})
    started = _drain(session.outgoing)
    assert started[-1]["type"] == "LIVE_STARTED"
    sid = started[-1]["session_id"]
    cam_msgs = _drain(cam.outgoing)
    assert cam_msgs[-1]["type"] == protocol.MsgType.LIVE_REQUEST
    assert cam_msgs[-1]["session_id"] == sid


def test_api_live_toggle_persisted(tmp_path):
    cfg = Config()
    cfg.data_dir = tmp_path
    app, ctx = create_app(cfg)
    client = app.test_client()

    assert client.get("/api/state").get_json()["live_enabled"] is False
    r = client.post("/api/live", json={"enabled": True})
    assert r.status_code == 200 and r.get_json()["live_enabled"] is True
    assert ctx.config.load_live_enabled() is True
    assert client.get("/api/state").get_json()["live_enabled"] is True
