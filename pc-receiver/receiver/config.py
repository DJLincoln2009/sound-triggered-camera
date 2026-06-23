"""Configuration et chemins de l'application de réception.

Tous les chemins sont locaux à la machine ; aucun service externe n'est contacté.
"""
from __future__ import annotations

import argparse
import json
import os
import socket
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


def default_data_dir() -> Path:
    """Répertoire de données par défaut, dépendant de la plateforme."""
    env = os.environ.get("SOUNDCAM_DATA_DIR")
    if env:
        return Path(env).expanduser()
    home = Path.home()
    if os.name == "nt":  # Windows
        base = Path(os.environ.get("APPDATA", home))
        return base / "SoundCamReceiver"
    if os.uname().sysname == "Darwin":  # macOS
        return home / "Library" / "Application Support" / "SoundCamReceiver"
    return home / ".local" / "share" / "soundcam-receiver"  # Linux


@dataclass
class DefaultCameraSettings:
    """Réglages distants par défaut poussés vers la caméra (EF-10/EF-18)."""

    threshold: float = 0.35
    video_quality: str = "HD_720P"
    use_classifier: bool = False
    target_labels: list[str] = field(default_factory=lambda: ["Speech", "Dog", "Glass"])
    transfer_mode: str = "deferred"
    active_hours: dict[str, Any] = field(
        default_factory=lambda: {"start": "00:00", "end": "23:59", "enabled": False}
    )


@dataclass
class Config:
    host: str = "0.0.0.0"
    http_port: int = 8766
    ws_port: int = 8765  # informatif : le WebSocket est servi par le même serveur HTTP
    server_name: str = field(default_factory=lambda: f"SoundCam-{socket.gethostname()}")
    data_dir: Path = field(default_factory=default_data_dir)
    tls: bool = False
    offline_after_s: float = 6.0
    ack_timeout_s: float = 5.0

    @property
    def recordings_dir(self) -> Path:
        return self.data_dir / "recordings"

    @property
    def db_path(self) -> Path:
        return self.data_dir / "soundcam.sqlite3"

    @property
    def pairing_path(self) -> Path:
        return self.data_dir / "pairing.json"

    @property
    def settings_path(self) -> Path:
        return self.data_dir / "camera_settings.json"

    @property
    def live_path(self) -> Path:
        return self.data_dir / "live.json"

    @property
    def cert_path(self) -> Path:
        return self.data_dir / "cert.pem"

    @property
    def key_path(self) -> Path:
        return self.data_dir / "key.pem"

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.recordings_dir.mkdir(parents=True, exist_ok=True)

    def load_camera_settings(self) -> dict[str, Any]:
        if self.settings_path.exists():
            return json.loads(self.settings_path.read_text(encoding="utf-8"))
        return asdict(DefaultCameraSettings())

    def save_camera_settings(self, settings: dict[str, Any]) -> None:
        self.settings_path.write_text(
            json.dumps(settings, indent=2, ensure_ascii=False), encoding="utf-8"
        )

    def load_live_enabled(self) -> bool:
        """Diffusion en direct activée au choix par l'opérateur (off par défaut)."""
        if self.live_path.exists():
            try:
                return bool(json.loads(self.live_path.read_text(encoding="utf-8")).get("enabled"))
            except (ValueError, OSError):
                return False
        return False

    def save_live_enabled(self, enabled: bool) -> None:
        self.live_path.write_text(
            json.dumps({"enabled": bool(enabled)}, indent=2), encoding="utf-8"
        )


def parse_args(argv: list[str] | None = None) -> Config:
    p = argparse.ArgumentParser(
        prog="soundcam-receiver",
        description="Application de réception PC (caméra à déclenchement sonore et manuel).",
    )
    p.add_argument("--host", default="0.0.0.0", help="Adresse d'écoute (défaut: toutes).")
    p.add_argument("--port", type=int, default=8766, help="Port HTTP + WebSocket (défaut: 8766).")
    p.add_argument("--data-dir", default=None, help="Répertoire de données (vidéos, base, config).")
    p.add_argument("--name", default=None, help="Nom lisible de cet appareil de réception.")
    p.add_argument("--tls", action="store_true", help="Active TLS (wss/https) avec certificat auto-signé (ENF-07).")
    args = p.parse_args(argv)

    cfg = Config()
    cfg.host = args.host
    cfg.http_port = args.port
    cfg.ws_port = args.port
    cfg.tls = args.tls
    if args.data_dir:
        cfg.data_dir = Path(args.data_dir).expanduser()
    if args.name:
        cfg.server_name = args.name
    return cfg
