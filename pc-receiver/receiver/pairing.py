"""Appairage et authentification des caméras (ENF-06).

Modèle d'appairage :
  - Le PC génère, au premier lancement, un **PIN** à 6 chiffres (affiché dans l'UI) et un
    **token** aléatoire fort (32 octets hex) persistés localement.
  - Pour s'appairer, l'opérateur saisit le PIN dans l'app caméra. La caméra présente ce
    PIN dans son ``HELLO``. Le PC le valide et renvoie le **token** fort dans ``HELLO_ACK``.
  - La caméra mémorise le token et l'utilise ensuite pour ses reconnexions WebSocket et
    pour l'upload HTTP (en-tête ``Authorization: Bearer``), au lieu du PIN.
  - PIN comme token sont acceptés comme identifiants valides ; régénérer le couple
    (``regenerate``) révoque tout appairage existant.

Le PIN reste un secret partagé hors-bande (affiché à l'écran, saisi par l'opérateur) ce
qui empêche un appareil tiers du réseau de piloter la caméra (ENF-06).
"""
from __future__ import annotations

import hmac
import json
import secrets
from pathlib import Path


class PairingManager:
    def __init__(self, path: Path):
        self._path = path
        self._pin: str = ""
        self._token: str = ""
        self._load_or_create()

    def _load_or_create(self) -> None:
        if self._path.exists():
            data = json.loads(self._path.read_text(encoding="utf-8"))
            self._pin = data["pin"]
            self._token = data["token"]
        else:
            self.regenerate()

    def regenerate(self) -> str:
        """Génère un nouveau PIN + token (révocation de l'appairage existant)."""
        self._pin = f"{secrets.randbelow(1_000_000):06d}"
        self._token = secrets.token_hex(32)
        self._save()
        return self._pin

    def _save(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._path.write_text(
            json.dumps({"pin": self._pin, "token": self._token}, indent=2),
            encoding="utf-8",
        )

    @property
    def pin(self) -> str:
        return self._pin

    @property
    def token(self) -> str:
        return self._token

    def verify(self, credential: str | None) -> bool:
        """Accepte le PIN d'appairage initial *ou* le token fort déjà émis.

        Comparaison à temps constant pour éviter les attaques temporelles.
        """
        if not credential:
            return False
        return hmac.compare_digest(credential, self._token) or hmac.compare_digest(
            credential, self._pin
        )
