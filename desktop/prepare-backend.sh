#!/usr/bin/env bash
# Builds the backend the desktop app ships on Linux and macOS, in src-tauri/resources/backend (or
# $JARVIS_RESOURCES/backend): the Spring Boot jar and a Java runtime reduced with jlink, for the system it runs on.
# Windows: prepare-backend.ps1. JARVIS_SKIP_BUILD=1 reuses target/jarvis-*.jar (the jar is the same on every system).
#
#   JAVA_HOME=/path/to/jdk-25 desktop/prepare-backend.sh
set -euo pipefail

DIR=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$DIR/.." && pwd)
OUT="${JARVIS_RESOURCES:-$DIR/src-tauri/resources}/backend"
[ -x "${JAVA_HOME:-}/bin/jlink" ] || { echo "Serve un JDK 25: imposta JAVA_HOME" >&2; exit 1; }

# As prepare-backend.ps1, without the Windows certificate store (jdk.crypto.mscapi exists only there)
MODULES=java.base,java.compiler,java.desktop,java.instrument,java.net.http,java.prefs,java.scripting,\
java.security.jgss,java.sql.rowset,jdk.jfr,jdk.management,jdk.unsupported,jdk.localedata,java.naming,jdk.charsets,jdk.zipfs

if [ "${JARVIS_SKIP_BUILD:-}" != 1 ]; then
    "$ROOT/mvnw" -q -f "$ROOT/pom.xml" package -DskipTests
fi
JAR=$(ls "$ROOT"/target/jarvis-*.jar | grep -v -- -plain.jar | head -1)
rm -rf "$OUT"
mkdir -p "$OUT"
cp "$JAR" "$OUT/jarvis.jar"
"$JAVA_HOME/bin/jlink" --add-modules "$MODULES" --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
    --output "$OUT/runtime"
echo "Backend pronto in $OUT ($(du -sh "$OUT" | cut -f1))"
