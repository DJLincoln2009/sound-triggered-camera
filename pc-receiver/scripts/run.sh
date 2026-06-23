#!/usr/bin/env bash
# Lance l'application de réception PC (Linux / macOS).
set -euo pipefail
cd "$(dirname "$0")/.."

if [ ! -d ".venv" ]; then
  echo "Création de l'environnement virtuel…"
  python3 -m venv .venv
  ./.venv/bin/pip install --upgrade pip >/dev/null
  ./.venv/bin/pip install -r requirements.txt
fi

exec ./.venv/bin/python -m receiver "$@"
