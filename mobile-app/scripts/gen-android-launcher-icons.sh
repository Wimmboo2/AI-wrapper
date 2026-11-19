#!/usr/bin/env bash
# Regenerate mipmap PNGs from app/assets/icon.png (Omni star).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/assets/icon.png"
OUT="$ROOT/app/assets/android-res"
command -v ffmpeg >/dev/null || { echo "ffmpeg required" >&2; exit 1; }
[[ -f "$SRC" ]] || { echo "missing $SRC" >&2; exit 1; }
rm -rf "$OUT"
for spec in "mipmap-mdpi:48" "mipmap-hdpi:72" "mipmap-xhdpi:96" "mipmap-xxhdpi:144" "mipmap-xxxhdpi:192"; do
  dir=${spec%%:*}; size=${spec##*:}
  mkdir -p "$OUT/$dir"
  ffmpeg -y -loglevel error -i "$SRC" -vf "scale=${size}:${size}" "$OUT/$dir/ic_launcher.png"
  ffmpeg -y -loglevel error -i "$SRC" -vf "scale=${size}:${size}" "$OUT/$dir/ic_launcher_round.png"
done
echo "Wrote launcher PNGs under $OUT"