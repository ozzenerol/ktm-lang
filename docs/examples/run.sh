#!/usr/bin/env bash
# Build and run every example project, compiled and through the JIT, and
# compare the output with its expected.txt.
#
# Usage: docs/examples/run.sh [project...]
# Env:   KTM (default: the repo's ./ktm, else ktm on PATH)
#        KTM_INSTALL_DIR (default: the repo's build/prefix when present)
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KTM="${KTM:-}"
if [ -z "$KTM" ]; then
    if [ -x "$ROOT/ktm" ]; then KTM="$ROOT/ktm"; else KTM="$(command -v ktm || true)"; fi
fi
[ -n "$KTM" ] || { echo "ktm not found: run ./build.sh or set KTM" >&2; exit 1; }

flags=()
dir="${KTM_INSTALL_DIR:-}"
[ -z "$dir" ] && [ -d "$ROOT/build/prefix/include" ] && dir="$ROOT/build/prefix"
[ -n "$dir" ] && flags=(--install-dir="$dir")

projects=("$@")
if [ ${#projects[@]} -eq 0 ]; then
    for d in "$HERE"/*/; do projects+=("$(basename "$d")"); done
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail=0

check() { # name mode output-file
    if diff -u "$HERE/$1/expected.txt" "$3" > "$tmp/diff"; then
        printf '  ok    %-10s %s\n' "$1" "$2"
    else
        printf '  FAIL  %-10s %s\n' "$1" "$2"
        sed 's/^/        /' "$tmp/diff"
        fail=1
    fi
}

for p in "${projects[@]}"; do
    cd "$HERE/$p" || { echo "no such project: $p"; fail=1; continue; }
    if "$KTM" "${flags[@]}" main.ktm -o "$tmp/$p" > "$tmp/build.log" 2>&1; then
        "$tmp/$p" > "$tmp/$p.aot" 2>&1
        check "$p" compiled "$tmp/$p.aot"
    else
        printf '  FAIL  %-10s compile\n' "$p"
        sed 's/^/        /' "$tmp/build.log"
        fail=1
    fi
    "$KTM" "${flags[@]}" -jit main.ktm > "$tmp/$p.jit" 2>&1
    check "$p" jit "$tmp/$p.jit"
done
exit $fail
