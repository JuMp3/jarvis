#!/usr/bin/env bash
# Prepares PostgreSQL with pgvector for the desktop app on Linux and macOS, in src-tauri/resources/postgres (or
# $JARVIS_RESOURCES/postgres). There are no official portable binaries for these systems, so it builds them from
# source: a C compiler, make, bison, flex, perl and zlib (headers) are needed (Debian/Ubuntu: build-essential bison
# flex zlib1g-dev; macOS: Xcode command line tools). PostgreSQL finds its share and lib folders relative to its own binaries, so
# the folder can move; the tools that use libpq get its folder in LD_LIBRARY_PATH / DYLD_LIBRARY_PATH from the shell
# (database.rs). Linux: build on the oldest distribution to support (glibc is only forward compatible).
# Windows: prepare-postgres.ps1.
#
#   desktop/prepare-postgres.sh
set -euo pipefail

PG_VERSION=${PG_VERSION:-18.4}
PGVECTOR_VERSION=${PGVECTOR_VERSION:-0.8.7}
DIR=$(cd "$(dirname "$0")" && pwd)
CACHE="$DIR/.cache"
OUT="${JARVIS_RESOURCES:-$DIR/src-tauri/resources}/postgres"
JOBS=$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)
mkdir -p "$CACHE"

download() { [ -f "$2" ] || { curl -fsSL -o "$2.part" "$1" && mv "$2.part" "$2"; }; }
download "https://ftp.postgresql.org/pub/source/v$PG_VERSION/postgresql-$PG_VERSION.tar.bz2" "$CACHE/postgresql-$PG_VERSION.tar.bz2"
download "https://github.com/pgvector/pgvector/archive/refs/tags/v$PGVECTOR_VERSION.tar.gz" "$CACHE/pgvector-$PGVECTOR_VERSION.tar.gz"

BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
tar -xjf "$CACHE/postgresql-$PG_VERSION.tar.bz2" -C "$BUILD"
tar -xzf "$CACHE/pgvector-$PGVECTOR_VERSION.tar.gz" -C "$BUILD"

rm -rf "$OUT"
(
    cd "$BUILD/postgresql-$PG_VERSION"
    # Only what Jarvis needs: no ICU (the cluster uses the builtin C.UTF-8 locale), no readline (no interactive psql),
    # no absolute rpath (the folder moves with the app)
    ./configure --prefix="$OUT" --without-icu --without-readline --disable-rpath > "$BUILD/configure.log"
    make -j"$JOBS" -s world-bin > "$BUILD/make.log"
    make -s install-world-bin > "$BUILD/install.log"
)
(
    cd "$BUILD/pgvector-$PGVECTOR_VERSION"
    make -s PG_CONFIG="$OUT/bin/pg_config" OPTFLAGS="" > "$BUILD/pgvector.log"
    make -s PG_CONFIG="$OUT/bin/pg_config" install >> "$BUILD/pgvector.log"
)
# Only what runs the server: no headers, no static libraries, server messages in English (built in) and Italian
rm -rf "$OUT/include" "$OUT"/lib/*.a "$OUT"/lib/postgresql/pgxs
find "$OUT/share" -path "*/locale/*" -mindepth 2 -maxdepth 2 -type d ! -name it -exec rm -rf {} + 2>/dev/null || true
cp "$BUILD/postgresql-$PG_VERSION/COPYRIGHT" "$OUT/server_license.txt"
cp "$BUILD/pgvector-$PGVECTOR_VERSION/LICENSE" "$OUT/pgvector_license.txt"
echo "PostgreSQL $PG_VERSION con pgvector $PGVECTOR_VERSION pronto in $OUT ($(du -sh "$OUT" | cut -f1))"
