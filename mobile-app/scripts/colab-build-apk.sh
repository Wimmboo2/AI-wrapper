#!/usr/bin/env bash
# Omni Android APK with custom launcher (Colab or Linux host).
# Usage: from mobile-app/  →  ./scripts/colab-build-apk.sh [skip-dx]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKIP_DX="${1:-}"

cd "$ROOT"
chmod +x scripts/*.sh

if [[ "$SKIP_DX" != "skip-dx" ]]; then
  cd app
  rustup target add aarch64-linux-android >/dev/null 2>&1 || true
  # dx 0.7: --target, not --arch. Colab is x86_64 — force arm64 for phones.
  dx bundle --platform android --target aarch64-linux-android
  cd ..
fi

PROFILE=debug
if [[ -d target/dx/omni-mobile/release/android ]] || [[ -d app/target/dx/omni-mobile/release/android ]]; then
  PROFILE=release
fi

./scripts/patch-android-launcher.sh "$PROFILE"

GRADLE_ROOT="$(find . app -path "*/$PROFILE/android/app/gradlew" 2>/dev/null | head -1)"
if [[ -z "$GRADLE_ROOT" ]]; then
  echo "No gradlew under target/dx — run dx bundle first (or pass skip-dx only if tree exists)." >&2
  exit 1
fi
GRADLE_ROOT="$(dirname "$GRADLE_ROOT")"
chmod +x "$GRADLE_ROOT/gradlew"
cd "$GRADLE_ROOT"
./gradlew assembleDebug

APK="$(find . -path "*/apk/debug/app-debug.apk" | head -1)"
OUT="${OMNI_APK_OUT:-/content/Omni-mobile-debug-with-icon.apk}"
cp "$APK" "$OUT"
echo "Wrote $OUT"
unzip -l "$OUT" | grep mipmap | head -15
sha256sum "$OUT"