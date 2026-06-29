#!/usr/bin/env bash
set -euo pipefail
SRC="$(cd "$(dirname "$0")/.." && pwd)/orchestra-manifests"
DST="$(cd "$(dirname "$0")" && pwd)/app/src/main/assets"
mkdir -p "$DST/devices"
rm -f "$DST/devices/"*.json
# flatten manifests/<mfr>/<id>.json -> assets/devices/<id>.json (the app addresses by id)
find "$SRC/manifests" -name '*.json' -exec cp {} "$DST/devices/" \;
cp "$SRC/index.json" "$DST/index.json"
echo "synced $(ls "$DST/devices" | wc -l | tr -d ' ') manifest(s) + index.json"
