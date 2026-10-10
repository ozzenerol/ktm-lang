#!/usr/bin/env bash
# Build the dev-only converter, tools/convert/ktm-convert, from this tree:
# ./build.sh --sqlite first (the std headers it parses against, with the
# SQLite part so every test parses), then the converter from the compiler
# sources with -DKTM_CONVERT. Nothing here is part of ktm.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
"$ROOT/build.sh" --sqlite > "$HERE/build.log" 2>&1 || { tail -20 "$HERE/build.log"; exit 1; }
cmake -S "$ROOT/src" -B "$HERE/build" -G 'Unix Makefiles' \
    -DCMAKE_BUILD_TYPE=Release -DKTM_BUILD_CONVERT=on -DKTM_LINK_SQLITE3=1 \
    -DCMAKE_INSTALL_PREFIX="$ROOT/build/prefix" >> "$HERE/build.log" 2>&1
make -C "$HERE/build" -j"$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)" ktm-convert \
    >> "$HERE/build.log" 2>&1 || { grep -E "error|warning" "$HERE/build.log" | head -20; exit 1; }
echo "built $HERE/ktm-convert (parses against $ROOT/build/prefix)"
