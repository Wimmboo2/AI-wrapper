#!/usr/bin/env bash
# Dioxus does not apply [bundle].icon on Android (see DioxusLabs/dioxus#3685).
# Run this after `dx bundle --platform android` and before Gradle assemble/install.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP_DIR="$ROOT/app"
ICON_RES="$APP_DIR/assets/android-res"

profile="${1:-debug}"
arch="${2:-}"

if [[ ! -d "$ICON_RES/mipmap-mdpi" ]]; then
  echo "Missing $ICON_RES — run scripts/gen-android-launcher-icons.sh first." >&2
  exit 1
fi

resolve_target() {
  local base="$APP_DIR/target/dx"
  if [[ ! -d "$base" ]]; then
    echo "No $base — run dx bundle --platform android from mobile-app/app first." >&2
    exit 1
  fi
  local -a candidates=()
  while IFS= read -r -d '' res; do
    candidates+=("$res")
  done < <(find "$base" -path "*/$profile/android/app/app/src/main/res" -type d -print0 2>/dev/null)
  if [[ ${#candidates[@]} -eq 0 ]]; then
    echo "Could not find .../$profile/android/app/app/src/main/res under $base" >&2
    exit 1
  fi
  if [[ ${#candidates[@]} -gt 1 && -n "$arch" ]]; then
  for c in "${candidates[@]}"; do
      if [[ "$c" == *"/$arch/"* ]]; then
        echo "$c"
        return
      fi
    done
  fi
  echo "${candidates[0]}"
}

RES="$(resolve_target)"
echo "Patching Android launcher icons in: $RES"

for dpi in mdpi hdpi xhdpi xxhdpi xxxhdpi; do
  rm -rf "$RES/mipmap-$dpi"
done
rm -f "$RES/mipmap-anydpi-v26/ic_launcher.xml" "$RES/mipmap-anydpi-v26/ic_launcher_round.xml" 2>/dev/null || true
rmdir "$RES/mipmap-anydpi-v26" 2>/dev/null || true

cp -R "$ICON_RES"/mipmap-* "$RES/"

echo "Copied custom mipmaps; removed adaptive ic_launcher.xml so PNGs are used."
echo "Next: cd \"$(dirname "$(dirname "$(dirname "$RES")")")\" && ./gradlew assemble$( [[ "$profile" == release ]] && echo Release || echo Debug )"