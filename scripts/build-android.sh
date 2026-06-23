#!/usr/bin/env bash
# Construit l'APK de l'application caméra Android.
# Prérequis : JDK 17 et un SDK Android (API 34). Renseignez ANDROID_SDK_ROOT
# ou android/local.properties (sdk.dir=...).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/android"

if [[ ! -f local.properties && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  echo "sdk.dir=${ANDROID_SDK_ROOT}" > local.properties
fi
if [[ ! -f local.properties ]]; then
  echo "ERREUR : SDK Android introuvable. Créez android/local.properties avec :"
  echo "  sdk.dir=/chemin/vers/Android/Sdk"
  exit 1
fi

VARIANT="${1:-debug}"   # debug | release
case "$VARIANT" in
  debug)   ./gradlew assembleDebug ;;
  release) ./gradlew assembleRelease ;;
  *) echo "Variante inconnue: $VARIANT (debug|release)"; exit 1 ;;
esac
echo "APK généré dans android/app/build/outputs/apk/$VARIANT/"
