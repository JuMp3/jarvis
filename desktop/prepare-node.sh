#!/usr/bin/env bash
# Prepares the Node the desktop app ships on Linux and macOS, in src-tauri/resources/node (or $JARVIS_RESOURCES/node):
# the node binary and, already installed, the npx MCP servers of the Settings (Outlook / Microsoft 365), at the version
# read from MailTemplates.java. Run it on the system the app is built for: native modules (keytar) are installed for
# it. Windows: prepare-node.ps1.
#
#   desktop/prepare-node.sh
set -euo pipefail

NODE_VERSION=${NODE_VERSION:-24.22.1}
DIR=$(cd "$(dirname "$0")" && pwd)
CACHE="$DIR/.cache"
OUT="${JARVIS_RESOURCES:-$DIR/src-tauri/resources}/node"
mkdir -p "$CACHE"

PACKAGE=$(sed -n 's/.*MS365_PACKAGE = "\([^"]*\)".*/\1/p' "$DIR/../src/main/java/dev/jarvis/settings/MailTemplates.java")
[ -n "$PACKAGE" ] || { echo "Versione del pacchetto MCP non trovata in MailTemplates.java" >&2; exit 1; }
case "$(uname -s)-$(uname -m)" in
    Linux-x86_64) NAME=node-v$NODE_VERSION-linux-x64 ;;
    Linux-aarch64) NAME=node-v$NODE_VERSION-linux-arm64 ;;
    Darwin-arm64) NAME=node-v$NODE_VERSION-darwin-arm64 ;;
    Darwin-x86_64) NAME=node-v$NODE_VERSION-darwin-x64 ;;
    *) echo "Sistema non supportato: $(uname -s) $(uname -m)" >&2; exit 1 ;;
esac
[ -f "$CACHE/$NAME.tar.gz" ] || { curl -fsSL -o "$CACHE/$NAME.tar.gz.part" "https://nodejs.org/dist/v$NODE_VERSION/$NAME.tar.gz" \
    && mv "$CACHE/$NAME.tar.gz.part" "$CACHE/$NAME.tar.gz"; }

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
tar -xzf "$CACHE/$NAME.tar.gz" -C "$BUILD" "$NAME/bin/node" "$NAME/lib/node_modules/npm"
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$BUILD/$NAME/bin/node" "$OUT/"
# npm only to install: the app needs node and the installed package. The system certificates also for npm.
NODE_OPTIONS=--use-system-ca "$OUT/node" "$BUILD/$NAME/lib/node_modules/npm/bin/npm-cli.js" install --prefix "$OUT" \
    --omit=dev --no-audit --no-fund "$PACKAGE"
echo "Node $NODE_VERSION con $PACKAGE pronto in $OUT ($(du -sh "$OUT" | cut -f1))"
