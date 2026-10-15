#!/usr/bin/env python3
"""Run every example of the docs (docs-src/language/*.md, docs-src/std/*.md).

Each example is the file example.ktm in a directory of its own (so what a
program prints about its own file is the same on every run). A ```ktm run
block is compiled and run, ahead of time and in the JIT; what it
prints (stdout, then stderr) must equal the ```output block after it. A
```ktm error block must fail to compile, and ktm's output must contain the
```output block's text. Exit code 1 when anything fails.

Usage: python3 docs-src/tools/check.py [--ktm PATH] [--install-dir DIR] [--jit-only] [file.md ...]
"""
import argparse
import concurrent.futures
import glob
import os
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import md  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def examples(path):
    """(kind, code, expected, line) for each run/error block of one file"""
    _, _, _, _, blocks = md.convert(open(path).read())
    out = []
    for i, (info, code, line) in enumerate(blocks):
        words = info.split()
        if words[:1] != ["ktm"] or len(words) < 2 or words[1] not in ("run", "error"):
            continue
        nxt = blocks[i + 1] if i + 1 < len(blocks) else None
        if not nxt or nxt[0].split()[:1] != ["output"]:
            out.append(("missing", code, "", line))
            continue
        out.append((words[1], code, nxt[1], line))
    return out


def run(cmd, cwd, timeout=60):
    try:
        p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, errors="replace")
        return p.returncode, p.stdout + p.stderr
    except subprocess.TimeoutExpired:
        return -1, f"timed out after {timeout} s"


def check_one(args, path, ex):
    kind, code, want, line = ex
    where = f"{os.path.relpath(path, ROOT)}:{line}"
    if kind == "missing":
        return where, "a ```ktm run/error block with no ```output block after it"
    ktm = [args.ktm] + ([f"--install-dir={args.install_dir}"] if args.install_dir else [])
    with tempfile.TemporaryDirectory(prefix="ktm-doc-") as tmp:
        src = os.path.join(tmp, "example.ktm")
        open(src, "w").write(code + "\n")
        if kind == "error":
            rc, out = run(ktm + ["-S", "example.ktm", "-o", os.devnull], tmp)
            if rc == 0:
                return where, "compiled, but it should not"
            if want.strip() not in out:
                return where, f"the error doesn't say:\n  {want.strip()}\nktm said:\n{out}"
            return where, None
        want = want.rstrip("\n")
        if not args.jit_only:
            rc, out = run(ktm + ["example.ktm", "-o", "example"], tmp)
            if rc != 0:
                return where, f"doesn't compile:\n{out}"
            rc, out = run([os.path.join(tmp, "example")], tmp)
            if out.rstrip("\n") != want:
                return where, f"compiled, it prints:\n{out}\nbut the page says:\n{want}"
        rc, out = run(ktm + ["-jit", "example.ktm"], tmp)
        if out.rstrip("\n") != want:
            return where, f"in the JIT, it prints:\n{out}\nbut the page says:\n{want}"
    return where, None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ktm", default="ktm")
    ap.add_argument("--install-dir", default=os.environ.get("KTM_INSTALL_DIR"))
    ap.add_argument("--jit-only", action="store_true")
    ap.add_argument("-j", "--jobs", type=int, default=os.cpu_count() or 4)
    ap.add_argument("files", nargs="*")
    args = ap.parse_args()
    files = args.files or sorted(glob.glob(os.path.join(ROOT, "docs-src", "language", "*.md")) +
                                 glob.glob(os.path.join(ROOT, "docs-src", "std", "*.md")))
    work = [(f, ex) for f in files for ex in examples(f)]
    failed = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        for where, err in pool.map(lambda w: check_one(args, *w), work):
            if err:
                failed += 1
                print(f"FAIL {where}: {err}\n")
    print(f"{len(work) - failed} passed, {failed} failed ({len(work)} examples in {len(files)} files)")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
