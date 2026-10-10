#!/usr/bin/env bash
# Convert a ktm tree (and other project dirs) to the new syntax in place:
#   tools/convert/convert-tree.sh TREE [PROJECT_DIR...]
# TREE: src/std (parsed through all.ktm), src/tests (+ lsp), docs/pages,
# docs/examples. Each PROJECT_DIR: every .ktm file under it. Notes and
# anything it couldn't do go to stderr. Run it on a copy first.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CONV="$HERE/ktm-convert"
I="--install-dir=$(cd "$HERE/../.." && pwd)/build/prefix"
TREE="$(cd "$1" && pwd)"; shift
rc=0
run() { "$CONV" "$I" "$@" || rc=1; }
(cd "$TREE/src/std" && run --root all.ktm *.ktm *.ktmh)
(cd "$TREE/src/tests" && run *.ktm)
(cd "$TREE/src/tests/lsp" && run *.ktm)
(cd "$TREE/docs/pages" && run *.html)
for d in "$TREE"/docs/examples/*/; do (cd "$d" && run *.ktm); done
for p in "$@"; do
  (cd "$p" && find . -name '*.ktm' -o -name '*.ktmh' | sort | xargs -r "$CONV" "$I") || rc=1
done
exit $rc
