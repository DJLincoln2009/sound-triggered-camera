#!/usr/bin/env bash
# Installe et prépare l'application de réception PC (environnement virtuel + dépendances).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/pc-receiver"

python3 -m venv .venv
# shellcheck disable=SC1091
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt
echo "OK. Lancer le serveur : cd pc-receiver && ./scripts/run.sh"
