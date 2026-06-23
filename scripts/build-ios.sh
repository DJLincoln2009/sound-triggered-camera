#!/usr/bin/env bash
# Génère le projet Xcode de l'app iOS et le compile (macOS + Xcode requis).
# Prérequis : brew install xcodegen
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/ios"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "ERREUR : xcodegen introuvable. Installez-le : brew install xcodegen"
  exit 1
fi

xcodegen generate
echo "Projet généré : ios/SoundCam.xcodeproj"

if command -v xcodebuild >/dev/null 2>&1; then
  xcodebuild -project SoundCam.xcodeproj -scheme SoundCam \
    -destination 'generic/platform=iOS' -configuration Debug build
else
  echo "xcodebuild absent (non-macOS) : ouvrez le projet dans Xcode pour compiler."
fi
