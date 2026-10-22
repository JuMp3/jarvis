#!/usr/bin/env bash
# Prepares the model of the long-term memory on Linux and macOS, in src-tauri/resources/memory (or
# $JARVIS_RESOURCES/memory): multilingual-e5-small, ONNX quantized. The same files on every system.
# Windows: prepare-memory.ps1.
#
#   desktop/prepare-memory.sh
set -euo pipefail

MODEL=${MODEL:-Xenova/multilingual-e5-small}
DIR=$(cd "$(dirname "$0")" && pwd)
CACHE="$DIR/.cache/${MODEL//\//-}"
OUT="${JARVIS_RESOURCES:-$DIR/src-tauri/resources}/memory"
mkdir -p "$CACHE"

for file in onnx/model_quantized.onnx tokenizer.json; do
    target="$CACHE/$(basename "$file")"
    [ -f "$target" ] || { curl -fsSL -o "$target.part" "https://huggingface.co/$MODEL/resolve/main/$file" && mv "$target.part" "$target"; }
done
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$CACHE/model_quantized.onnx" "$OUT/model.onnx"
cp "$CACHE/tokenizer.json" "$OUT/tokenizer.json"
echo "Modello della memoria pronto in $OUT ($(du -sh "$OUT" | cut -f1))"
