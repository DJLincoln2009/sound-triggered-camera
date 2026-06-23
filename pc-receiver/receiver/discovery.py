"""Découverte réseau mDNS (EF-12).

Le PC publie un service ``_soundcam._tcp`` que l'app caméra résout (NSD/Bonjour),
évitant à l'utilisateur de saisir une adresse IP. Voir PROTOCOL.md §2.
"""
from __future__ import annotations

import socket

from zeroconf import ServiceInfo, Zeroconf

SERVICE_TYPE = "_soundcam._tcp.local."


def _primary_ipv4() -> str:
    """Meilleure estimation de l'IP locale (sans contacter Internet)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        # L'adresse n'est jamais réellement jointe ; sert à choisir l'interface sortante.
        s.connect(("192.168.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


class DiscoveryPublisher:
    def __init__(self, server_name: str, http_port: int, ws_port: int, tls: bool):
        self._zc: Zeroconf | None = None
        self._info: ServiceInfo | None = None
        self._server_name = server_name
        self._http_port = http_port
        self._ws_port = ws_port
        self._tls = tls

    def start(self) -> str:
        ip = _primary_ipv4()
        safe_name = self._server_name.replace(".", "-")
        info = ServiceInfo(
            SERVICE_TYPE,
            name=f"{safe_name}.{SERVICE_TYPE}",
            addresses=[socket.inet_aton(ip)],
            port=self._ws_port,
            properties={
                "ws": str(self._ws_port),
                "http": str(self._http_port),
                "tls": "1" if self._tls else "0",
                "proto": "1",
                "name": self._server_name,
            },
            server=f"{socket.gethostname().split('.')[0]}.local.",
        )
        self._zc = Zeroconf()
        self._zc.register_service(info)
        self._info = info
        return ip

    def stop(self) -> None:
        if self._zc and self._info:
            try:
                self._zc.unregister_service(self._info)
            finally:
                self._zc.close()
        self._zc = None
        self._info = None
