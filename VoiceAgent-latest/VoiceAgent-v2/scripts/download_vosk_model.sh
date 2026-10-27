#!/bin/sh
# Download the existing Vosk model. Do not replace an installed copy unless the new archive validates.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/model-ru-small"
TMP="$(mktemp -d)"
trap 'test -z "${STAGED:-}" || rm -rf "$STAGED"; rm -rf "$TMP"' EXIT
trap 'exit 1' HUP INT TERM
curl --fail --show-error --location --connect-timeout 20 --max-time 600 -o "$TMP/m.zip" https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip
unzip -tq "$TMP/m.zip"
unzip -q "$TMP/m.zip" -d "$TMP"
MODEL="$TMP/vosk-model-small-ru-0.22"
for FILE in am/final.mdl conf/mfcc.conf; do
    test -s "$MODEL/$FILE" || { echo "Incomplete Vosk model archive" >&2; exit 1; }
done
if ! test -s "$MODEL/graph/HCLG.fst"; then
    test -s "$MODEL/graph/HCLr.fst" && test -s "$MODEL/graph/Gr.fst" || { echo "Missing Vosk decoding graph" >&2; exit 1; }
fi
# New marker forces the existing StorageService.sync to refresh previously unpacked files.
(cat /proc/sys/kernel/random/uuid 2>/dev/null || date +%s) > "$MODEL/uuid"
mkdir -p "$(dirname "$DEST")"
# Keep the previous model until the validated copy is ready on the destination filesystem.
STAGED="$(mktemp -d "$(dirname "$DEST")/.model-stage.XXXXXX")"
if ! cp -R "$MODEL/." "$STAGED/"; then rm -rf "$STAGED"; exit 1; fi
if test -e "$DEST.previous"; then rm -rf "$STAGED"; echo "Resolve existing model-ru-small.previous before replacing the model" >&2; exit 1; fi
if test -d "$DEST"; then mv "$DEST" "$DEST.previous"; fi
if mv "$STAGED" "$DEST"; then
    rm -rf "$DEST.previous"
else
    if test -d "$DEST.previous"; then mv "$DEST.previous" "$DEST"; fi
    rm -rf "$STAGED"
    exit 1
fi
echo "Готово: $DEST"
