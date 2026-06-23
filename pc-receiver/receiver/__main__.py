"""Point d'entrée de l'application de réception.

Usage :
    python -m receiver [--port 8766] [--name "Salon PC"] [--tls] [--data-dir DIR]
"""
from __future__ import annotations

import signal
import sys

from .app import create_app
from .config import parse_args
from .discovery import DiscoveryPublisher


def main(argv: list[str] | None = None) -> int:
    config = parse_args(argv)
    app, ctx = create_app(config)

    publisher = DiscoveryPublisher(config.server_name, config.http_port, config.ws_port, config.tls)
    local_ip = publisher.start()

    ssl_context = None
    scheme = "http"
    if config.tls:
        from .tls import ensure_self_signed_cert

        cert, key = ensure_self_signed_cert(config.cert_path, config.key_path, local_ip)
        ssl_context = (str(cert), str(key))
        scheme = "https"

    ws_scheme = "wss" if config.tls else "ws"
    print("=" * 64)
    print(f"  Application de réception : {config.server_name}")
    print(f"  Interface de pilotage   : {scheme}://{local_ip}:{config.http_port}/")
    print(f"  Canal de commandes      : {ws_scheme}://{local_ip}:{config.ws_port}/ws")
    print(f"  Découverte mDNS         : _soundcam._tcp  (IP {local_ip})")
    print(f"  Données / vidéos        : {config.data_dir}")
    print(f"  CODE D'APPAIRAGE (PIN)  : {ctx.pairing.pin}")
    print("=" * 64)
    print("  Saisissez ce PIN dans l'app caméra pour autoriser le pilotage.")
    print("  Ctrl+C pour arrêter.")
    print("=" * 64)

    def _shutdown(*_):
        publisher.stop()
        ctx.storage.close()
        sys.exit(0)

    signal.signal(signal.SIGINT, _shutdown)
    signal.signal(signal.SIGTERM, _shutdown)

    try:
        app.run(
            host=config.host,
            port=config.http_port,
            threaded=True,
            ssl_context=ssl_context,
            use_reloader=False,
        )
    finally:
        publisher.stop()
        ctx.storage.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
