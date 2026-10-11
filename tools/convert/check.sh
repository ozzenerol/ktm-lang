#!/usr/bin/env bash
# tools/convert's own check (not part of ktm's test suite):
#  1. every corpus/NAME.ktm (old syntax) converts to exactly NAME.expected;
#  2. when ./ktm accepts the new syntax: the original and the converted
#     program print the same and exit the same, ahead of time and in the
#     JIT, and converting the converted program changes nothing.
set -uo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KTM="$ROOT/ktm"
DIR="$ROOT/build/prefix"
CONV="$HERE/ktm-convert"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
TO="$(command -v timeout >/dev/null 2>&1 && echo timeout 60)"
[ -x "$CONV" ] || "$HERE/build.sh" || exit 1

pass=0; fail=0
ok()  { pass=$((pass + 1)); }
bad() { fail=$((fail + 1)); echo "FAIL $1"; }

for src in "$HERE"/corpus/*.ktm; do
  name="$(basename "$src" .ktm)"
  # `// map` on the first line: converted with the stdlib map too
  MAP=""
  head -1 "$src" | grep -q '^// map' && MAP="--map $HERE/stdlib-modules.tsv"
  # `// drop-breaks` in the first lines: with S3's --drop-breaks (Y8)
  head -3 "$src" | grep -q '^// drop-breaks' && MAP="$MAP --drop-breaks"
  # `// keep-defines`: the file's #defines stay macros (--keep-defines)
  if head -3 "$src" | grep -q '^// keep-defines'; then
    echo "$name.ktm" > "$TMP/$name.keep"; MAP="$MAP --keep-defines $TMP/$name.keep"
  fi
  if $TO "$CONV" --install-dir="$DIR" $MAP --stdout "$src" > "$TMP/$name.got" 2> "$TMP/$name.err" &&
     cmp -s "$TMP/$name.got" "$HERE/corpus/$name.expected"; then
    ok
  else
    bad "$name: converted text differs from $name.expected"
    diff "$HERE/corpus/$name.expected" "$TMP/$name.got" | head -10
  fi
done

cat > "$TMP/probe.ktm" <<'EOF'
struct S { i64 x; };
i32 main()
{
  S s;
  s.x = (i64)2.5;
  printf("%d\n", s.x);
  return 0;
}
EOF
if ! $TO "$KTM" --install-dir="$DIR" -jit "$TMP/probe.ktm" > /dev/null 2>&1; then
  echo "SKIP running the programs: ./ktm doesn't accept the new syntax yet"
else
  run() { # run NAME FILE MODE: stdout and exit code into $TMP/NAME.MODE
    if [ "$3" = jit ]; then
      (cd "$TMP" && $TO "$KTM" --install-dir="$DIR" -jit "$2" > "$1.$3" 2>/dev/null; echo "exit $?" >> "$1.$3")
    else
      (cd "$TMP" && $TO "$KTM" --install-dir="$DIR" "$2" -o "$1.bin" > /dev/null 2>&1 &&
        $TO "./$1.bin" > "$1.$3" 2>/dev/null; echo "exit $?" >> "$1.$3")
    fi
  }
  for src in "$HERE"/corpus/*.ktm; do
    name="$(basename "$src" .ktm)"
    # `// pending: why` on the first line: its output needs a compiler
    # change that isn't on this branch yet; only its text is checked
    if head -1 "$src" | grep -q '^// pending:\|^// map'; then
      echo "PENDING $name: $(head -1 "$src" | sed 's|^// pending: *||')"
      continue
    fi
    cp "$src" "$TMP/$name.old.ktm"
    cp "$HERE/corpus/$name.expected" "$TMP/$name.new.ktm"
    for mode in aot jit; do
      run "$name.old" "$name.old.ktm" "$mode"
      run "$name.new" "$name.new.ktm" "$mode"
      if cmp -s "$TMP/$name.old.$mode" "$TMP/$name.new.$mode"; then ok
      else bad "$name ($mode): the converted program behaves differently"
        diff "$TMP/$name.old.$mode" "$TMP/$name.new.$mode" | head -6
      fi
    done
    if $TO "$CONV" --install-dir="$DIR" --check "$TMP/$name.new.ktm" > /dev/null 2>&1; then ok
    else bad "$name: converting the converted program changes it"
    fi
  done
fi
echo "== tools/convert: $pass passed, $fail failed"
[ "$fail" = 0 ]
