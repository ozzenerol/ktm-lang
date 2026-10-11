#!/usr/bin/env bash
# S2 (a): the old stdlib into module directories, converted:
#   tools/convert/convert-std.sh OLD_STD_DIR STD_DIR [ERR_FILE]
# OLD_STD_DIR: the old src/std (*.ktm, std.ktmh, defs.ktmh); STD_DIR: the
# new src/std, whose module directories (all but prelude/) are replaced.
# split.py places every item, then one ktm-convert run converts every
# module through a root that imports them all (sqlite's part included),
# then the hand fixes in std-fixes/ are applied.
# Needs ./build.sh first (the converter and its parse aid).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OLD="$(cd "$1" && pwd)"; STD="$(cd "$2" && pwd)"; ERR="${3:-/dev/stderr}"
cd "$STD"
for d in */; do [ "$d" = prelude/ ] || rm -rf "$d"; done
python3 -I "$HERE/split.py" "$HERE/stdlib-modules.tsv" "$OLD" . | grep -v '^dropped' >&2
{ echo '#define __KTM_LINK_SQLITE3__'
  for d in */; do d=${d%/}; [ "$d" = prelude ] || echo "import $d;"; done; } > _root.ktm
trap 'rm -f "$STD/_root.ktm"' EXIT
"$HERE/ktm-convert" --install-dir="$ROOT/build/prefix" --map "$HERE/stdlib-modules.tsv" --names \
    --renames "$HERE/renames.tsv" --renames "$HERE/renames-locals.tsv" --layout \
    --root _root.ktm $(ls */*.ktm | grep -v '^prelude/') 2> "$ERR"
# the hand fixes, in order (std-fixes/NN-what.patch, paths src/std/...)
for p in "$HERE"/std-fixes/*.patch; do
    [ -e "$p" ] || continue
    patch -s -p1 -d "$STD/../.." < "$p" || { echo "convert-std.sh: $p doesn't apply" >&2; exit 1; }
done
