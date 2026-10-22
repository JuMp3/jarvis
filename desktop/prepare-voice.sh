#!/usr/bin/env bash
# Prepares the local voice on Linux and macOS, in src-tauri/resources/voice (or $JARVIS_RESOURCES/voice): the server
# of whisper.cpp, built from source (there are no official binaries for these systems; cmake and a C++ compiler are
# needed), Piper from its official release, and the models. Jarvis runs them itself (LocalVoice), with the whisper
# folder in LD_LIBRARY_PATH / DYLD_LIBRARY_PATH for its libraries. Windows: prepare-voice.ps1.
#
#   desktop/prepare-voice.sh
set -euo pipefail

WHISPER_VERSION=${WHISPER_VERSION:-1.9.5}
PIPER_VERSION=${PIPER_VERSION:-2023.11.14-2}
VOICE=${VOICE:-it/it_IT/paola/medium/it_IT-paola-medium}
DIR=$(cd "$(dirname "$0")" && pwd)
CACHE="$DIR/.cache"
OUT="${JARVIS_RESOURCES:-$DIR/src-tauri/resources}/voice"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
HF=https://huggingface.co
mkdir -p "$CACHE"

download() { [ -f "$2" ] || { curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) PIPER=piper_linux_x86_64.tar.gz ;;
    Linux-aarch64) PIPER=piper_linux_aarch64.tar.gz ;;
    Darwin-arm64) PIPER=piper_macos_aarch64.tar.gz ;;
    Darwin-x86_64) PIPER=piper_macos_x64.tar.gz ;;
    *) echo "Sistema non supportato: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
download "https://github.com/ggml-org/whisper.cpp/archive/refs/tags/v$WHISPER_VERSION.tar.gz" "$CACHE/whisper.cpp-$WHISPER_VERSION.tar.gz"
download "https://github.com/rhasspy/piper/releases/download/$PIPER_VERSION/$PIPER" "$CACHE/piper-$PIPER_VERSION-$PIPER"
# Whisper small quantized (more precise, the default) and base (faster): Settings → Voce chooses
for model in ggml-small-q5_1.bin ggml-base.bin; do
    download "$HF/ggerganov/whisper.cpp/resolve/main/$model" "$CACHE/$model"
done
NAME=$(basename "$VOICE")
for extension in .onnx .onnx.json; do
    download "$HF/rhasspy/piper-voices/resolve/main/$VOICE$extension" "$CACHE/$NAME$extension"
done

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
tar -xzf "$CACHE/whisper.cpp-$WHISPER_VERSION.tar.gz" -C "$BUILD"
# All the CPU variants as loadable libraries, picked at runtime for the processor (as the official Windows build):
# a build for this machine only (GGML_NATIVE) could crash on another one. On macOS Metal is on by default.
cmake -S "$BUILD/whisper.cpp-$WHISPER_VERSION" -B "$BUILD/build" -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF \
    -DBUILD_SHARED_LIBS=ON -DWHISPER_BUILD_TESTS=OFF > "$BUILD/cmake.log" \
    $([ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] && echo "-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON")
cmake --build "$BUILD/build" --target whisper-server -j "$JOBS" > "$BUILD/build.log"

rm -rf "$OUT"
mkdir -p "$OUT/whisper" "$OUT/models"
cp "$BUILD/build/bin/whisper-server" "$OUT/whisper/"
find "$BUILD/build" \( -name "*.so*" -o -name "*.dylib" \) -exec cp -P {} "$OUT/whisper/" \;
tar -xzf "$CACHE/piper-$PIPER_VERSION-$PIPER" -C "$OUT"
cp "$CACHE/ggml-small-q5_1.bin" "$CACHE/ggml-base.bin" "$CACHE/$NAME.onnx" "$CACHE/$NAME.onnx.json" "$OUT/models/"
echo "Voce locale pronta in $OUT ($(du -sh "$OUT" | cut -f1))"
