"""Application Flask de réception : interface web, canal de commandes WebSocket,
upload vidéo HTTP. Tout reste sur le réseau local (EF-24 / ENF-08).
"""
from __future__ import annotations

import json
import queue
import time
import uuid
from pathlib import Path
from typing import Any

from flask import Flask, Response, abort, jsonify, render_template, request, send_file
from flask_sock import Sock
from simple_websocket import ConnectionClosed

from . import protocol
from .config import Config
from .hub import CameraConnection, CameraHub
from .pairing import PairingManager
from .storage import Storage

_WEB_DIR = Path(__file__).resolve().parent / "web"


class AppContext:
    """Regroupe les dépendances partagées par les routes."""

    def __init__(self, config: Config):
        self.config = config
        config.ensure_dirs()
        self.storage = Storage(config.db_path, config.recordings_dir)
        self.pairing = PairingManager(config.pairing_path)
        self.hub = CameraHub(config.offline_after_s, config.ack_timeout_s)
        self.hub.on_event = self._log_event
        self.camera_settings: dict[str, Any] = config.load_camera_settings()

    def _log_event(self, type_: str, device_id: str | None, detail: str | None) -> None:
        self.storage.add_event(type_, device_id, detail)

    def bearer_token(self) -> str | None:
        auth = request.headers.get("Authorization", "")
        if auth.startswith("Bearer "):
            return auth[len("Bearer "):].strip()
        return None


def create_app(config: Config) -> tuple[Flask, AppContext]:
    app = Flask(
        __name__,
        template_folder=str(_WEB_DIR / "templates"),
        static_folder=str(_WEB_DIR / "static"),
    )
    app.config["MAX_CONTENT_LENGTH"] = 4 * 1024 * 1024 * 1024  # 4 Go max par upload
    sock = Sock(app)
    ctx = AppContext(config)

    # ------------------------------------------------------------------ UI ----
    @app.get("/")
    def dashboard():
        return render_template("index.html", server_name=config.server_name)

    # ------------------------------------------------- canal de commandes -----
    @sock.route("/ws")
    def ws_commands(ws):  # noqa: ANN001
        conn = _handshake(ws, ctx)
        if conn is None:
            return
        ctx.hub.add(conn)
        # Pousse les réglages courants dès la connexion (EF-10/EF-18).
        conn.enqueue(protocol.set_settings(uuid.uuid4().hex[:8], dict(ctx.camera_settings)))
        try:
            _serve_connection(ws, conn, ctx)
        finally:
            ctx.hub.remove(conn.device_id)

    # ------------------------------------------------- upload vidéo HTTP ------
    @app.post("/upload")
    def upload():
        if not ctx.pairing.verify(ctx.bearer_token()):
            abort(401, description="Appareil non appairé")
        if "file" not in request.files:
            abort(400, description="Champ 'file' manquant")
        try:
            meta = json.loads(request.form.get("metadata", "{}"))
        except json.JSONDecodeError:
            abort(400, description="metadata JSON invalide")

        recording_id = meta.get("recording_id") or f"rec_{int(time.time())}"
        meta["recording_id"] = recording_id
        if ctx.storage.recording_exists(recording_id):
            return jsonify({"stored": True, "duplicate": True, "recording_id": recording_id})

        ext = "mp4"
        mime = meta.get("mime", "video/mp4")
        if "webm" in mime:
            ext = "webm"
        dest = ctx.storage.target_path(recording_id, meta.get("started_at"), ext)
        request.files["file"].save(str(dest))
        size = dest.stat().st_size
        ctx.storage.add_recording(meta, dest, size)
        ctx.storage.add_event(
            "recording_received",
            meta.get("device_id"),
            f"{recording_id} ({meta.get('origin', '?')}, {size} octets)",
        )
        return jsonify({"stored": True, "recording_id": recording_id, "bytes": size})

    # ------------------------------------------------------------- API --------
    @app.get("/api/state")
    def api_state():
        return jsonify(
            {
                "server_name": config.server_name,
                "pairing_pin": ctx.pairing.pin,
                "tls": config.tls,
                "cameras": ctx.hub.snapshots(),
                "settings": ctx.camera_settings,
            }
        )

    @app.post("/api/trigger")
    def api_trigger():
        device_id = (request.json or {}).get("device_id")
        max_duration = float((request.json or {}).get("max_duration_s", 0))
        msg = protocol.start_recording(uuid.uuid4().hex[:8], max_duration)
        res = ctx.hub.send_command_await_ack(msg, device_id)
        ctx.storage.add_event(
            "manual_trigger", res.get("device_id"),
            "Déclenchement manuel " + ("OK" if res["ok"] else f"échec: {res.get('reason')}"),
        )
        return jsonify(res), (200 if res["ok"] else 409)

    @app.post("/api/stop")
    def api_stop():
        device_id = (request.json or {}).get("device_id")
        msg = protocol.stop_recording(uuid.uuid4().hex[:8])
        res = ctx.hub.send_command_await_ack(msg, device_id)
        ctx.storage.add_event(
            "stop", res.get("device_id"),
            "Arrêt manuel " + ("OK" if res["ok"] else f"échec: {res.get('reason')}"),
        )
        return jsonify(res), (200 if res["ok"] else 409)

    @app.post("/api/settings")
    def api_settings():
        body = request.json or {}
        device_id = body.pop("device_id", None)
        allowed = {"threshold", "video_quality", "use_classifier", "target_labels",
                   "transfer_mode", "active_hours"}
        updates = {k: v for k, v in body.items() if k in allowed}
        ctx.camera_settings.update(updates)
        config.save_camera_settings(ctx.camera_settings)
        # Pousse aux caméras connectées (à toutes si aucune n'est ciblée).
        msg = protocol.set_settings(uuid.uuid4().hex[:8], dict(updates))
        res = ctx.hub.send_command_await_ack(dict(msg), device_id)
        ctx.storage.add_event("settings", res.get("device_id"), json.dumps(updates))
        return jsonify({"saved": True, "push": res, "settings": ctx.camera_settings})

    @app.get("/api/recordings")
    def api_recordings():
        include_archived = request.args.get("archived") == "1"
        return jsonify(ctx.storage.list_recordings(include_archived))

    @app.get("/api/recordings/<recording_id>/file")
    def api_recording_file(recording_id: str):
        rec = ctx.storage.get_recording(recording_id)
        if not rec or not Path(rec["file_path"]).exists():
            abort(404)
        return send_file(rec["file_path"], mimetype=rec.get("mime", "video/mp4"),
                         conditional=True)

    @app.post("/api/recordings/<recording_id>/archive")
    def api_recording_archive(recording_id: str):
        archived = bool((request.json or {}).get("archived", True))
        ok = ctx.storage.archive_recording(recording_id, archived)
        return jsonify({"ok": ok}), (200 if ok else 404)

    @app.delete("/api/recordings/<recording_id>")
    def api_recording_delete(recording_id: str):
        ok = ctx.storage.delete_recording(recording_id)
        return jsonify({"ok": ok}), (200 if ok else 404)

    @app.get("/api/events")
    def api_events():
        limit = int(request.args.get("limit", 200))
        return jsonify(ctx.storage.list_events(limit))

    @app.post("/api/pairing/regenerate")
    def api_pairing_regenerate():
        pin = ctx.pairing.regenerate()
        ctx.storage.add_event("pairing", None, "Code d'appairage régénéré (appairages révoqués)")
        return jsonify({"pairing_pin": pin})

    @app.errorhandler(401)
    @app.errorhandler(400)
    @app.errorhandler(404)
    @app.errorhandler(409)
    def _json_error(err):  # noqa: ANN001
        return jsonify({"error": getattr(err, "description", str(err))}), err.code

    return app, ctx


def _handshake(ws, ctx: AppContext) -> CameraConnection | None:  # noqa: ANN001
    """Lit le HELLO, authentifie la caméra (ENF-06) et renvoie la connexion ou None."""
    try:
        raw = ws.receive(timeout=10)
    except ConnectionClosed:
        return None
    if not raw:
        return None
    try:
        hello = protocol.loads(raw)
    except (ValueError, json.JSONDecodeError):
        ws.send(protocol.dumps(protocol.hello_ack(False, ctx.config.server_name, "bad_hello")))
        return None
    if hello.get("type") != protocol.MsgType.HELLO:
        ws.send(protocol.dumps(protocol.hello_ack(False, ctx.config.server_name, "expected_hello")))
        return None
    if not ctx.pairing.verify(hello.get("token")):
        ws.send(protocol.dumps(protocol.hello_ack(False, ctx.config.server_name, "unpaired")))
        return None

    ws.send(protocol.dumps(
        protocol.hello_ack(True, ctx.config.server_name, token=ctx.pairing.token)
    ))
    return CameraConnection(hello.get("device", {}))


def _serve_connection(ws, conn: CameraConnection, ctx: AppContext) -> None:  # noqa: ANN001
    """Boucle d'E/S : draine la file sortante et reçoit les messages de la caméra."""
    while True:
        try:
            while True:
                out = conn.outgoing.get_nowait()
                ws.send(protocol.dumps(out))
        except queue.Empty:
            pass

        try:
            raw = ws.receive(timeout=0.05)
        except ConnectionClosed:
            break
        if raw is None:
            continue
        try:
            msg = protocol.loads(raw)
        except (ValueError, json.JSONDecodeError):
            continue
        ctx.hub.handle_camera_message(conn, msg)
