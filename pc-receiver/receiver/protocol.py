"""Définition et construction des messages du canal de commandes (protocole v1).

Source de vérité du format : ../../protocol/PROTOCOL.md. Ce module fournit les
constantes de types de messages et des fonctions de construction/validation afin
d'éviter toute chaîne « magique » dans le reste du code.
"""
from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any

PROTOCOL_VERSION = 1


class MsgType:
    """Types de messages du canal de commandes (EF-22)."""

    HELLO = "HELLO"
    HELLO_ACK = "HELLO_ACK"
    START_RECORDING = "START_RECORDING"
    STOP_RECORDING = "STOP_RECORDING"
    SET_SETTINGS = "SET_SETTINGS"
    SOUND_TRIGGERED = "SOUND_TRIGGERED"
    STATUS = "STATUS"
    ACK = "ACK"


# Messages émis par la caméra vers le PC.
CAMERA_TO_PC = {MsgType.HELLO, MsgType.SOUND_TRIGGERED, MsgType.STATUS, MsgType.ACK}
# Messages émis par le PC vers la caméra.
PC_TO_CAMERA = {
    MsgType.HELLO_ACK,
    MsgType.START_RECORDING,
    MsgType.STOP_RECORDING,
    MsgType.SET_SETTINGS,
}

VALID_STATES = {"idle", "listening", "recording", "transferring"}
VALID_QUALITIES = {"SD_480P", "HD_720P", "FHD_1080P"}


def utc_now_iso() -> str:
    """Horodatage ISO-8601 UTC, ex. ``2026-06-21T14:32:10Z``."""
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def loads(raw: str) -> dict[str, Any]:
    """Parse une trame texte WebSocket en dict, en validant la présence de ``type``."""
    data = json.loads(raw)
    if not isinstance(data, dict):
        raise ValueError("message must be a JSON object")
    if "type" not in data or not isinstance(data["type"], str):
        raise ValueError("message missing string 'type' field")
    return data


def dumps(message: dict[str, Any]) -> str:
    """Sérialise un message en JSON compact UTF-8."""
    return json.dumps(message, separators=(",", ":"), ensure_ascii=False)


# --- Constructeurs de messages PC -> caméra ------------------------------------

def hello_ack(
    accepted: bool,
    server_name: str,
    reason: str | None = None,
    token: str | None = None,
) -> dict[str, Any]:
    msg: dict[str, Any] = {
        "type": MsgType.HELLO_ACK,
        "accepted": accepted,
        "protocol": PROTOCOL_VERSION,
        "server_name": server_name,
    }
    if reason is not None:
        msg["reason"] = reason
    # Token fort émis après appairage réussi : la caméra le mémorise (voir pairing.py).
    if accepted and token is not None:
        msg["token"] = token
    return msg


def start_recording(request_id: str, max_duration_s: float = 0) -> dict[str, Any]:
    return {
        "type": MsgType.START_RECORDING,
        "origin": "manual",
        "request_id": request_id,
        "timestamp": utc_now_iso(),
        "max_duration_s": max_duration_s,
    }


def stop_recording(request_id: str) -> dict[str, Any]:
    return {"type": MsgType.STOP_RECORDING, "request_id": request_id}


def set_settings(request_id: str, settings: dict[str, Any]) -> dict[str, Any]:
    msg: dict[str, Any] = {"type": MsgType.SET_SETTINGS, "request_id": request_id}
    msg.update(settings)
    return msg
