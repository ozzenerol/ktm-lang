#!/usr/bin/env python3
"""Compile and run every example in docs/pages/*.html.

A runnable example is `<pre data-run><code>...</code></pre>`, a complete
program. The `<pre class="output">` right after it is its exact expected
output. Each example is built ahead-of-time and run, then run again
through the JIT; both must print the expected output.

Optional attributes on the <pre data-run>:
  data-args="-DVERBOSE"   extra ktm flags
  data-mode="aot"         only check one mode ("aot" or "jit")

Usage: python3 docs/tools/check-examples.py [--ktm PATH] [--install-dir DIR]
Defaults to ./ktm and ./build/prefix (what ./build.sh produces).
"""
import argparse
import os
import re
import subprocess
import sys
import tempfile

DOCS = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(DOCS)

EXAMPLE = re.compile(
    r'<pre data-run(?P<attrs>[^>]*)><code[^>]*>(?P<code>.*?)</code></pre>\s*'
    r'(?:<pre class="output">(?P<out>.*?)</pre>)?', re.S)


def attr(attrs, name):
    m = re.search(name + r'="([^"]*)"', attrs)
    return m.group(1) if m else None


def run(cmd, cwd, timeout=30):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ktm", default=os.path.join(ROOT, "ktm"))
    ap.add_argument("--install-dir", default=os.path.join(ROOT, "build", "prefix"))
    ap.add_argument("pages", nargs="*")
    args = ap.parse_args()
    if not os.access(args.ktm, os.X_OK):
        sys.exit(f"ktm not found at {args.ktm}; run ./build.sh first")
    base = [args.ktm, f"--install-dir={args.install_dir}"]

    pages_dir = os.path.join(DOCS, "pages")
    names = args.pages or sorted(n for n in os.listdir(pages_dir) if n.endswith(".html"))
    passed = failed = 0
    for name in names:
        src = open(os.path.join(pages_dir, os.path.basename(name))).read()
        for i, m in enumerate(EXAMPLE.finditer(src), 1):
            # Page sources hold code raw; build.py does the escaping.
            code = m.group("code")
            want = m.group("out") or ""
            if want.startswith("\n"):  # matches build.py's leading-newline rule
                want = want[1:]
            line = src.count("\n", 0, m.start()) + 1
            label = f"{name}:{line}"
            extra = (attr(m.group("attrs"), "data-args") or "").split()
            mode = attr(m.group("attrs"), "data-mode")
            with tempfile.TemporaryDirectory() as tmp:
                path = os.path.join(tmp, "example.ktm")
                open(path, "w").write(code)
                results = []
                if mode in (None, "aot"):
                    rc, out, err = run(base + extra + ["example.ktm", "-o", "example"], tmp)
                    if rc != 0:
                        results.append(("aot", f"compile failed:\n{err or out}"))
                    else:
                        rc, out, err = run(["./example"], tmp)
                        results.append(("aot", None if out == want else out + err))
                if mode in (None, "jit"):
                    rc, out, err = run(base + extra + ["-jit", "example.ktm"], tmp)
                    results.append(("jit", None if out == want else out + err))
            bad = [(k, v) for k, v in results if v is not None]
            if bad:
                failed += 1
                print(f"FAIL {label}")
                for k, got in bad:
                    print(f"  [{k}] expected:\n{indent(want)}\n  [{k}] got:\n{indent(got)}")
            else:
                passed += 1
    print(f"\n{passed} passed, {failed} failed")
    sys.exit(1 if failed else 0)


def indent(s):
    return "\n".join("    | " + l for l in s.rstrip("\n").split("\n"))


if __name__ == "__main__":
    main()
