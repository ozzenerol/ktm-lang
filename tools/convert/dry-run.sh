#!/usr/bin/env bash
# The dry run: what converting a ktm tree would do, writing nothing.
#   tools/convert/dry-run.sh TREE [PROJECT_DIR...] > report.txt
# Per file: its edits by kind; then the totals by kind, the list of what
# it can't convert (or needs a look) with file:line, and the other notes.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
CONV="$HERE/ktm-convert"
I="--install-dir=$(cd "$HERE/../.." && pwd)/build/prefix"
TREE="$(cd "$1" && pwd)"; shift
RAW="$(mktemp)"
trap 'rm -f "$RAW"' EXIT
run() { "$CONV" "$I" --report "$@" 2>&1 | sed "s|^EDITS |EDITS $PWD/|;s|^CANNOT |CANNOT $PWD/|;s|^NOTE |NOTE $PWD/|" >> "$RAW"; }
(cd "$TREE/src/std" && run --root all.ktm *.ktm *.ktmh)
(cd "$TREE/src/tests" && run *.ktm)
(cd "$TREE/src/tests/lsp" && run *.ktm)
(cd "$TREE/docs/pages" && run *.html)
for d in "$TREE"/docs/examples/*/; do (cd "$d" && run *.ktm); done
for p in "$@"; do
  (cd "$p" && run $(find . -name '*.ktm' -o -name '*.ktmh' | sort))
done
sed "s|$TREE/||g" "$RAW" | awk '
  /^EDITS / { files++; edits += $3; if ($3 > 0) changed++;
              line = $2 ": " $3; sep = " (";
              for (i = 4; i <= NF; i++) { split($i, kv, "="); tot[kv[1]] += kv[2];
                                          line = line sep kv[1] " " kv[2]; sep = ", " }
              if (NF >= 4) line = line ")"; per[files] = line; next }
  /^CANNOT / { sub(/^CANNOT /, ""); cannot[++nc] = $0; next }
  /^NOTE / { sub(/^NOTE /, ""); note[++nn] = $0; next }
  { other[++no] = $0 }
  END {
    print "== per file"; for (i = 1; i <= files; i++) print per[i]
    printf "\n== totals: %d file(s), %d would change, %d edit(s)\n", files, changed, edits
    n = 0; for (k in tot) keys[++n] = k
    for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (tot[keys[j]] > tot[keys[i]]) { t = keys[i]; keys[i] = keys[j]; keys[j] = t }
    for (i = 1; i <= n; i++) printf "%-20s %d\n", keys[i], tot[keys[i]]
    printf "\n== cannot convert / check (%d)\n", nc; for (i = 1; i <= nc; i++) print cannot[i]
    printf "\n== notes (%d)\n", nn; for (i = 1; i <= nn; i++) print note[i]
    if (no) { printf "\n== other output (%d)\n", no; for (i = 1; i <= no; i++) print other[i] }
  }'
