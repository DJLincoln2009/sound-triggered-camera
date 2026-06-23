"""Simulateur de caméra — client de référence du protocole.

Permet de tester l'application de réception PC sans téléphone réel : il découvre le PC
par mDNS (ou via --url), s'appaire avec le PIN, envoie des STATUS périodiques, répond
aux commandes (START/STOP/SET_SETTINGS) par un ACK, et téléverse une vidéo de test.

Usage :
    python scripts/camera_simulator.py --pin 123456
    python scripts/camera_simulator.py --pin 123456 --url ws://127.0.0.1:8766 --upload sample.mp4

Ce fichier sert aussi de documentation vivante du protocole côté caméra (voir l'app
Android pour l'implémentation native équivalente).
"""
from __future__ import annotations

import argparse
import io
import json
import mimetypes
import threading
import time
import urllib.request
import uuid
from datetime import datetime, timezone

import simple_websocket
from zeroconf import ServiceBrowser, Zeroconf

SERVICE_TYPE = "_soundcam._tcp.local."


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def discover(timeout: float = 8.0) -> dict | None:
    """Découvre le premier service _soundcam._tcp via mDNS."""
    found: dict = {}
    done = threading.Event()

    class _Listener:
        def add_service(self, zc, type_, name):
            info = zc.get_service_info(type_, name)
            if info and info.addresses:
                import socket
                found["host"] = socket.inet_ntoa(info.addresses[0])
                props = {k.decode(): v.decode() for k, v in info.properties.items()}
                found["http"] = int(props.get("http", info.port))
                found["tls"] = props.get("tls") == "1"
                found["name"] = props.get("name", name)
                done.set()

        def update_service(self, *a):
            pass

        def remove_service(self, *a):
            pass

    zc = Zeroconf()
    ServiceBrowser(zc, SERVICE_TYPE, _Listener())
    done.wait(timeout)
    zc.close()
    return found or None


def http_upload(base_url: str, token: str, meta: dict, data: bytes, filename: str) -> dict:
    """POST multipart/form-data vers /upload (canal vidéo, EF-09)."""
    boundary = "----soundcam" + uuid.uuid4().hex
    ctype = mimetypes.guess_type(filename)[0] or "video/mp4"
    buf = io.BytesIO()

    def part(headers: str, body: bytes):
        buf.write(f"--{boundary}\r\n".encode())
        buf.write(headers.encode())
        buf.write(b"\r\n\r\n")
        buf.write(body)
        buf.write(b"\r\n")

    part('Content-Disposition: form-data; name="metadata"\r\nContent-Type: application/json',
         json.dumps(meta).encode())
    part(f'Content-Disposition: form-data; name="file"; filename="{filename}"\r\n'
         f'Content-Type: {ctype}', data)
    buf.write(f"--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        base_url + "/upload", data=buf.getvalue(), method="POST",
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}",
                 "Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


class CameraSimulator:
    def __init__(self, ws_url: str, http_url: str, credential: str, name: str = "Caméra simulée"):
        self.ws_url = ws_url
        self.http_url = http_url
        # PIN au premier appairage ; remplacé par le token fort renvoyé dans HELLO_ACK.
        self.token = credential
        self.name = name
        self.device_id = uuid.uuid4().hex[:8]
        self.recording = False
        self.recording_id = None
        self.ws = None
        self._stop = threading.Event()

    def _hello(self) -> dict:
        return {
            "type": "HELLO", "protocol": 1, "token": self.token,
            "device": {"id": self.device_id, "name": self.name, "platform": "android",
                       "model": "Simulator", "manufacturer": "SoundCam", "app_version": "1.0.0"},
        }

    def _status(self) -> dict:
        return {"type": "STATUS",
                "state": "recording" if self.recording else "listening",
                "recording": self.recording, "battery_level": 0.8,
                "recording_id": self.recording_id, "timestamp": utc_now()}

    def connect(self) -> bool:
        self.ws = simple_websocket.Client(self.ws_url)
        self.ws.send(json.dumps(self._hello()))
        ack = json.loads(self.ws.receive(timeout=10))
        if not ack.get("accepted"):
            print(f"[sim] Appairage refusé: {ack.get('reason')}")
            try:
                self.ws.close()
            except Exception:
                pass
            self.ws = None
            return False
        if ack.get("token"):
            self.token = ack["token"]  # mémorise le token fort pour l'upload/reconnexion
        print(f"[sim] Connecté à « {ack.get('server_name')} » (id={self.device_id})")
        return True

    def run(self, auto_upload: bytes | None = None):
        last_status = 0.0
        while not self._stop.is_set():
            now = time.time()
            if now - last_status > 2:
                self.ws.send(json.dumps(self._status()))
                last_status = now
            try:
                raw = self.ws.receive(timeout=0.2)
            except simple_websocket.ConnectionClosed:
                print("[sim] Connexion fermée")
                break
            if not raw:
                continue
            msg = json.loads(raw)
            self._handle(msg, auto_upload)

    def _handle(self, msg: dict, auto_upload: bytes | None):
        mtype = msg.get("type")
        if mtype == "START_RECORDING":
            if not self.recording:
                self.recording = True
                self.recording_id = "rec_" + datetime.now().strftime("%Y%m%d_%H%M%S")
                print(f"[sim] ▶ START ({msg.get('origin')}) -> {self.recording_id}")
            self._ack(msg.get("request_id"), "ok")
        elif mtype == "STOP_RECORDING":
            print("[sim] ■ STOP")
            self._ack(msg.get("request_id"), "ok")
            if self.recording and auto_upload is not None:
                self._do_upload(auto_upload, origin="manual")
            self.recording = False
            self.recording_id = None
        elif mtype == "SET_SETTINGS":
            print(f"[sim] ⚙ SET_SETTINGS {{threshold:{msg.get('threshold')}, "
                  f"quality:{msg.get('video_quality')}}}")
            self._ack(msg.get("request_id"), "ok")

    def _ack(self, request_id, result, reason=None):
        if not request_id:
            return
        self.ws.send(json.dumps({"type": "ACK", "request_id": request_id, "result": result,
                                 "reason": reason, "recording_id": self.recording_id}))

    def _do_upload(self, data: bytes, origin: str):
        meta = {"recording_id": self.recording_id, "device_id": self.device_id,
                "device_name": self.name, "origin": origin, "sound_label": None,
                "started_at": utc_now(), "duration_s": 5.0, "width": 1280, "height": 720,
                "mime": "video/mp4"}
        res = http_upload(self.http_url, self.token, meta, data, self.recording_id + ".mp4")
        print(f"[sim] ⇪ Upload: {res}")

    def stop(self):
        self._stop.set()
        # Ferme la connexion : libère le thread de réception interne de simple_websocket.
        if self.ws is not None:
            try:
                self.ws.close()
            except Exception:
                pass
            self.ws = None


def main():
    p = argparse.ArgumentParser(description="Simulateur de caméra (client de référence).")
    p.add_argument("--pin", help="Code d'appairage affiché par le PC.")
    p.add_argument("--token", help="Token d'appairage direct (au lieu du PIN).")
    p.add_argument("--url", help="URL WebSocket directe, ex. ws://127.0.0.1:8766")
    p.add_argument("--upload", help="Fichier vidéo à téléverser après un STOP.")
    args = p.parse_args()

    if args.url:
        ws_url = args.url.rstrip("/") + "/ws"
        http_url = args.url.replace("ws://", "http://").replace("wss://", "https://").rstrip("/")
    else:
        info = discover()
        if not info:
            print("Aucun PC trouvé via mDNS. Utilisez --url.")
            return 1
        scheme = "wss" if info["tls"] else "ws"
        hscheme = "https" if info["tls"] else "http"
        ws_url = f"{scheme}://{info['host']}:{info['http']}/ws"
        http_url = f"{hscheme}://{info['host']}:{info['http']}"
        print(f"[sim] PC découvert: {info['name']} @ {info['host']}:{info['http']}")

    credential = args.token or args.pin
    if not credential:
        print("Fournissez --pin ou --token.")
        return 1

    payload = None
    if args.upload:
        with open(args.upload, "rb") as f:
            payload = f.read()

    sim = CameraSimulator(ws_url, http_url, credential)
    if not sim.connect():
        return 1
    try:
        sim.run(auto_upload=payload)
    except KeyboardInterrupt:
        sim.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
