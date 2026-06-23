#!/usr/bin/env bash
# Construit un exécutable autonome de l'application de réception via PyInstaller.
# Produit dist/soundcam-receiver (Linux/macOS) ou dist/soundcam-receiver.exe (Windows).
set -euo pipefail
cd "$(dirname "$0")/.."

python3 -m venv .venv-build
./.venv-build/bin/pip install --upgrade pip >/dev/null
./.venv-build/bin/pip install -r requirements.txt pyinstaller >/dev/null

./.venv-build/bin/pyinstaller \
  --name soundcam-receiver \
  --onefile \
  --add-data "receiver/web:receiver/web" \
  --collect-all zeroconf \
  --hidden-import simple_websocket \
  --hidden-import flask_sock \
  -p . \
  run.py

echo "Exécutable généré dans dist/"
