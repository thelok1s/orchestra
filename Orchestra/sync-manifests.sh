#!/usr/bin/env bash
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)/orchestra-manifests"
DST="$(cd "$(dirname "$0")" && pwd)/app/src/main/assets"
# When the canonical manifest repo isn't checked out (e.g. CI building the app repo alone, or a
# fresh clone), keep the committed seed under assets/ untouched — do NOT wipe it. Must come BEFORE
# the rm below, or a missing repo would delete the seed and ship an APK with no manifests.
if [ ! -d "$SRC/manifests" ]; then
  echo "orchestra-manifests not present — keeping committed seed in $DST"
  exit 0
fi
mkdir -p "$DST/devices"
rm -f "$DST/devices/"*.json
# flatten manifests/<mfr>/<id>.json -> assets/devices/<id>.json (the app addresses by id)
find "$SRC/manifests" -name '*.json' -exec cp {} "$DST/devices/" \;
cp "$SRC/index.json" "$DST/index.json"
echo "synced $(ls "$DST/devices" | wc -l | tr -d ' ') manifest(s) + index.json"
