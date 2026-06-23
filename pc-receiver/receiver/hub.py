"""Concentrateur des connexions caméra (canal de commandes).

Chaque caméra connectée est représentée par une :class:`CameraConnection`. Le hub permet :
  - d'enregistrer/désenregistrer les connexions,
  - d'envoyer une commande à une caméra depuis un thread HTTP et d'attendre son ACK
    (EF-06, ENF-04) de façon thread-safe,
  - de suivre l'état (en ligne / hors-ligne / enregistrement) pour l'UI (EF-13, ENF-10).

Le serveur WebSocket (flask-sock) sert chaque connexion dans son propre thread ; les
échanges sortants passent par une file pour éviter d'appeler ``ws.send`` depuis un autre
thread que celui qui possède la socket.
"""
from __future__ import annotations

import queue
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable

from . import protocol


@dataclass
class PendingCommand:
    event: threading.Event = field(default_factory=threading.Event)
    result: dict[str, Any] | None = None


class CameraConnection:
    """Représente une caméra connectée via WebSocket."""

    def __init__(self, device: dict[str, Any]):
        self.device_id: str = device.get("id") or uuid.uuid4().hex[:8]
        self.device = device
        self.outgoing: "queue.Queue[dict[str, Any]]" = queue.Queue()
        self.pending: dict[str, PendingCommand] = {}
        self._pending_lock = threading.Lock()
        self.last_status: dict[str, Any] = {"state": "idle", "recording": False}
        self.last_seen: float = time.time()
        self.connected_at: float = time.time()
        self.active_recording_id: str | None = None

    @property
    def name(self) -> str:
        return self.device.get("name") or self.device_id

    def enqueue(self, message: dict[str, Any]) -> None:
        self.outgoing.put(message)

    def register_pending(self, request_id: str) -> PendingCommand:
        pc = PendingCommand()
        with self._pending_lock:
            self.pending[request_id] = pc
        return pc

    def resolve_pending(self, request_id: str, result: dict[str, Any]) -> None:
        with self._pending_lock:
            pc = self.pending.pop(request_id, None)
        if pc:
            pc.result = result
            pc.event.set()

    def is_online(self, offline_after_s: float) -> bool:
        return (time.time() - self.last_seen) <= offline_after_s

    def snapshot(self, offline_after_s: float) -> dict[str, Any]:
        online = self.is_online(offline_after_s)
        state = self.last_status.get("state", "idle")
        return {
            "device_id": self.device_id,
            "name": self.name,
            "platform": self.device.get("platform"),
            "model": self.device.get("model"),
            "manufacturer": self.device.get("manufacturer"),
            "app_version": self.device.get("app_version"),
            "online": online,
            "state": state if online else "offline",
            "recording": bool(self.last_status.get("recording")) and online,
            "battery_level": self.last_status.get("battery_level"),
            "recording_id": self.last_status.get("recording_id"),
            "connected_at": self.connected_at,
            "last_seen": self.last_seen,
        }


class CameraHub:
    def __init__(self, offline_after_s: float = 6.0, ack_timeout_s: float = 5.0):
        self._cameras: dict[str, CameraConnection] = {}
        self._lock = threading.RLock()
        self.offline_after_s = offline_after_s
        self.ack_timeout_s = ack_timeout_s
        # Callback(event_type, device_id, detail) appelé pour journaliser (EF-19).
        self.on_event: Callable[[str, str | None, str | None], None] | None = None

    def add(self, conn: CameraConnection) -> None:
        with self._lock:
            self._cameras[conn.device_id] = conn
        self._emit("connected", conn.device_id, f"{conn.name} connectée")

    def remove(self, device_id: str) -> None:
        with self._lock:
            conn = self._cameras.pop(device_id, None)
        if conn:
            self._emit("disconnected", device_id, f"{conn.name} déconnectée")

    def get(self, device_id: str) -> CameraConnection | None:
        with self._lock:
            return self._cameras.get(device_id)

    def first(self) -> CameraConnection | None:
        with self._lock:
            return next(iter(self._cameras.values()), None)

    def all(self) -> list[CameraConnection]:
        with self._lock:
            return list(self._cameras.values())

    def snapshots(self) -> list[dict[str, Any]]:
        return [c.snapshot(self.offline_after_s) for c in self.all()]

    def _emit(self, type_: str, device_id: str | None, detail: str | None) -> None:
        if self.on_event:
            self.on_event(type_, device_id, detail)

    def _resolve_target(self, device_id: str | None) -> CameraConnection | None:
        return self.get(device_id) if device_id else self.first()

    def send_command_await_ack(
        self, message: dict[str, Any], device_id: str | None = None
    ) -> dict[str, Any]:
        """Envoie une commande et attend l'ACK (EF-06, ENF-04).

        Retourne un dict ``{ok, result, reason, recording_id, device_id}``. Une commande
        non acquittée (ex. caméra hors-ligne) produit ``ok=False`` plutôt qu'un silence.
        """
        conn = self._resolve_target(device_id)
        if conn is None:
            return {"ok": False, "reason": "Aucune caméra connectée", "result": "error"}
        if not conn.is_online(self.offline_after_s):
            return {"ok": False, "reason": "Caméra hors-ligne", "result": "error",
                    "device_id": conn.device_id}

        request_id = message.get("request_id") or uuid.uuid4().hex[:8]
        message["request_id"] = request_id
        pending = conn.register_pending(request_id)
        conn.enqueue(message)

        if not pending.event.wait(timeout=self.ack_timeout_s):
            with conn._pending_lock:
                conn.pending.pop(request_id, None)
            return {"ok": False, "reason": "Pas d'accusé de réception (timeout)",
                    "result": "error", "device_id": conn.device_id}

        result = pending.result or {}
        return {
            "ok": result.get("result") == "ok",
            "result": result.get("result"),
            "reason": result.get("reason"),
            "recording_id": result.get("recording_id"),
            "device_id": conn.device_id,
        }

    # --- réception de messages caméra ----------------------------------------

    def handle_camera_message(self, conn: CameraConnection, msg: dict[str, Any]) -> None:
        conn.last_seen = time.time()
        mtype = msg.get("type")

        if mtype == protocol.MsgType.STATUS:
            conn.last_status = {
                "state": msg.get("state", "idle"),
                "recording": bool(msg.get("recording")),
                "battery_level": msg.get("battery_level"),
                "recording_id": msg.get("recording_id"),
            }
        elif mtype == protocol.MsgType.ACK:
            rid = msg.get("request_id")
            if rid:
                conn.resolve_pending(rid, msg)
        elif mtype == protocol.MsgType.SOUND_TRIGGERED:
            conn.active_recording_id = msg.get("recording_id")
            label = msg.get("sound_label") or "?"
            conf = msg.get("confidence")
            detail = f"Déclenchement sonore ({label}, conf={conf})"
            self._emit("sound_trigger", conn.device_id, detail)
