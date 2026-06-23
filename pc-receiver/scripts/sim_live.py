"""Extension WebRTC du simulateur de caméra (mode ``--live``).

Implémente le côté **émetteur** de la diffusion en direct, comme le feraient les apps
Android/iOS : à la réception d'un ``LIVE_REQUEST`` relayé par le PC, le simulateur crée
une ``RTCPeerConnection`` (sans STUN/TURN — 100 % local), y ajoute une piste vidéo de
synthèse animée, génère l'offre SDP et l'envoie au navigateur via le relais du PC.

Dépendance optionnelle (dev uniquement) : ``aiortc``. Voir requirements-dev.txt.

C'est un **outil de test** : il permet de valider le pipeline live complet
(navigateur ↔ PC ↔ caméra) sur une machine sans téléphone. Le rendu réel sur appareil
est assuré par ``LiveStreamer.kt`` (Android) et ``LiveStreamer.swift`` (iOS).
"""
from __future__ import annotations

import asyncio
import threading
from typing import Any, Callable

import numpy as np
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import VideoStreamTrack
from aiortc.sdp import candidate_from_sdp
from av import VideoFrame


class AnimatedTrack(VideoStreamTrack):
    """Piste vidéo de synthèse : barres de couleur défilantes + carré mobile.

    Sert de mire pour prouver visuellement que le flux en direct arrive au navigateur.
    """

    def __init__(self, width: int = 640, height: int = 480):
        super().__init__()
        self.width = width
        self.height = height
        self.frame = 0

    async def recv(self) -> VideoFrame:
        pts, time_base = await self.next_timestamp()
        w, h, t = self.width, self.height, self.frame
        img = np.zeros((h, w, 3), dtype=np.uint8)
        col = (np.linspace(0, 255, w, dtype=np.uint16) + t * 3) % 256
        row = (np.linspace(0, 255, h, dtype=np.uint16) + t * 2) % 256
        img[:, :, 0] = col[None, :]
        img[:, :, 1] = row[:, None]
        img[:, :, 2] = (t * 5) % 256
        # Carré blanc mobile (témoin de mouvement temps réel).
        bx = int((t * 6) % max(1, w - 60))
        by = int(h / 2 - 30)
        img[by:by + 60, bx:bx + 60] = 255
        out = VideoFrame.from_ndarray(img, format="bgr24")
        out.pts = pts
        out.time_base = time_base
        self.frame += 1
        return out


class LiveEngine:
    """Gère les ``RTCPeerConnection`` du simulateur sur une boucle asyncio dédiée.

    Les messages de signaling sortants sont déposés via ``send`` (callback fourni par le
    simulateur, qui les sérialise sur sa WebSocket). Les messages entrants sont poussés
    par le simulateur via les méthodes ``handle_*`` (thread-safe).
    """

    def __init__(self, send: Callable[[dict[str, Any]], None]):
        self._send = send
        self._pcs: dict[str, RTCPeerConnection] = {}
        self._loop = asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

    def _run_loop(self) -> None:
        asyncio.set_event_loop(self._loop)
        self._loop.run_forever()

    def _submit(self, coro) -> None:
        asyncio.run_coroutine_threadsafe(coro, self._loop)

    # --- API appelée depuis le thread principal du simulateur ----------------

    def handle(self, msg: dict[str, Any]) -> None:
        mtype = msg.get("type")
        sid = msg.get("session_id", "")
        if mtype == "LIVE_REQUEST":
            self._submit(self._on_request(sid))
        elif mtype == "LIVE_ANSWER":
            self._submit(self._on_answer(sid, msg.get("sdp", "")))
        elif mtype == "LIVE_ICE":
            self._submit(self._on_ice(sid, msg.get("candidate") or {}))
        elif mtype == "LIVE_STOP":
            self._submit(self._on_stop(sid))

    # --- coroutines (exécutées sur la boucle asyncio) ------------------------

    async def _on_request(self, sid: str) -> None:
        pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))  # 100 % local
        self._pcs[sid] = pc

        @pc.on("connectionstatechange")
        async def _on_state():  # noqa: ANN202
            print(f"[sim/live] {sid[:8]} état={pc.connectionState}")
            if pc.connectionState in ("failed", "closed"):
                await self._on_stop(sid)

        pc.addTrack(AnimatedTrack())
        offer = await pc.createOffer()
        await pc.setLocalDescription(offer)
        # aiortc embarque les candidats ICE dans le SDP (pas de trickle côté caméra).
        self._send({"type": "LIVE_OFFER", "session_id": sid,
                    "sdp": pc.localDescription.sdp})
        print(f"[sim/live] ▶ offre envoyée pour la session {sid[:8]}")

    async def _on_answer(self, sid: str, sdp: str) -> None:
        pc = self._pcs.get(sid)
        if pc:
            await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="answer"))
            print(f"[sim/live] réponse appliquée ({sid[:8]})")

    async def _on_ice(self, sid: str, c: dict[str, Any]) -> None:
        pc = self._pcs.get(sid)
        cand_str = c.get("candidate", "")
        if not pc or not cand_str:
            return
        candidate = candidate_from_sdp(cand_str.split(":", 1)[1])
        candidate.sdpMid = c.get("sdpMid")
        candidate.sdpMLineIndex = c.get("sdpMLineIndex")
        await pc.addIceCandidate(candidate)

    async def _on_stop(self, sid: str) -> None:
        pc = self._pcs.pop(sid, None)
        if pc:
            await pc.close()
            print(f"[sim/live] ■ session {sid[:8]} fermée")
