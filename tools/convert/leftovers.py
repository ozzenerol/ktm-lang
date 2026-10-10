#!/usr/bin/env python3
"""The final check of the conversion: no old-language references left.

Usage: tools/convert/leftovers.py [--renames renames.tsv] ROOT...

Scans the .ktm/.ktmh files and the docs pages under each ROOT and prints
every leftover as path:line: what. Exit status 1 if there is any.

  - old type names (U0 I64 Bool ...) and TRUE/FALSE/NULL: anywhere, code,
    comments and strings alike
  - `class` and `public` used in code
  - postfix casts (`x(i64)`), print statements (a statement that starts
    with a string), `Main`
  - TempleOS mentions (the origin is told only in the README, COPYING and
    the docs' History and credits section)
  - with --renames, the old names of renamed library functions, types,
    constants and fields

Calls without parentheses and code at file scope can't be found by a grep:
the compiler rejects both once the old syntax is gone, so the test suite
proves those.
"""
import argparse
import os
import re
import sys

OLD_WORDS = re.compile(r"\b(U0|I8|U8|I16|U16|I32|U32|I64|U64|F32|F64|Bool|TRUE|FALSE|NULL)\b")
CODE_WORDS = re.compile(r"\b(class|public|Main)\b")
TYPES = r"(?:void|bool|[iu](?:8|16|32|64)|f32|f64)"
POSTFIX_CAST = re.compile(r"[\w\)\]]\s*\(\s*" + TYPES + r"\s*\**\s*\)(?!\s*[\w\(\"'])")
NOT_CAST_BEFORE = re.compile(r"\b(sizeof|alignof|typeof|return|case|if|while|for|switch)\s*$")
SQL = re.compile(r"\b(SELECT|INSERT|UPDATE|CREATE TABLE|IS NULL)\b")
PRINT_STMT = re.compile(r"(\A|[{};)]|\belse)\s*(\"|')")


def blank(src):
    """`src` with comments and the insides of strings blanked (newlines
    kept): code only"""
    out, i, n = list(src), 0, len(src)
    while i < n:
        if src.startswith("//", i):
            while i < n and src[i] != "\n":
                out[i] = " "
                i += 1
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                if src[k] != "\n":
                    out[k] = " "
            i = j
        elif src[i] in "\"'":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                if q == "'" and src[j] == "\n":
                    break  # not a character constant: an apostrophe
                j += 2 if src[j] == "\\" else 1
            if q == "'" and (j >= n or src[j] != q):
                i += 1
                continue
            for k in range(i + 1, min(j, n)):
                if src[k] != "\n":
                    out[k] = " "
            i = j + 1
        else:
            i += 1
    return "".join(out)


def match_back(text, i):
    """The `(` that opens the group closed at text[i]"""
    depth = 0
    while i >= 0:
        if text[i] == ")":
            depth += 1
        elif text[i] == "(":
            depth -= 1
            if depth == 0:
                return i
        i -= 1
    return 0


def html_code(page):
    """The code of a docs page (<pre> blocks and inline <code>) with the
    rest blanked, so line numbers stay"""
    out = re.sub(r"[^\n]", " ", page)
    out = list(out)
    for m in re.finditer(r"<pre[^>]*>(?:<code[^>]*>)?(.*?)</pre>|<code>(.*?)</code>", page, re.S):
        g = 1 if m.group(1) is not None else 2
        for k in range(m.start(g), m.end(g)):
            out[k] = page[k]
    return "".join(out)


def history_span(page):
    m = re.search(r'<h2 id="history".*?(?=<h2|\Z)', page, re.S)
    return (m.start(), m.end()) if m else (0, 0)


def line_of(text, pos):
    return text.count("\n", 0, pos) + 1


def scan(path, renames, hits):
    text = open(path, encoding="utf-8", errors="replace").read()
    is_html = path.endswith(".html")
    code_text = html_code(text) if is_html else text
    code_only = blank(code_text)

    def hit(pos, what):
        hits.append(f"{path}:{line_of(text, pos)}: {what}")

    for m in OLD_WORDS.finditer(code_text):
        line = code_text[code_text.rfind("\n", 0, m.start()) + 1:code_text.find("\n", m.start())]
        if m.group(1) == "NULL" and code_only[m.start()] == " " and (
                SQL.search(line) or re.search(r'strstr\([^;)]*"[^"]*\bNULL\b', line)):
            continue  # SQL, or a test looking for the compiler's own (C) message
        hit(m.start(), f"old word `{m.group(1)}`")
    for m in CODE_WORDS.finditer(code_only):
        hit(m.start(), f"`{m.group(1)}` in code")
    for m in POSTFIX_CAST.finditer(code_only):
        if NOT_CAST_BEFORE.search(code_only[max(0, m.start() - 12):m.start() + 1]):
            continue
        if re.fullmatch(r".\s*\(\s*void\s*\)", m.group(0)):
            continue  # F(void): an empty parameter list
        if m.group(0)[0] == ")" and code_only[match_back(code_only, m.start()) + 1:].lstrip().startswith("*"):
            continue  # a function pointer: void (*f)(void *), (i64 (*)(i64))
        hit(m.start(), "postfix cast")
    # code_only keeps the quotes, so a string's insides can't match
    for m in PRINT_STMT.finditer(code_only):
        if m.group(1) == ")":
            # only `if (...) "x";`, `while (...)`, `for (...)`
            o = match_back(code_only, m.start(1))
            if not re.search(r"\b(if|while|for)\s*$", code_only[max(0, o - 8):o]):
                continue
        j = m.end(2) - 1
        k = j + 1
        q = code_only[j]
        while k < len(code_only) and code_only[k] != q:
            k += 1
        after = code_only[k + 1:k + 40].lstrip()
        if after[:1] not in (",", ";"):
            continue
        # an initialiser's item ({"a", "b"}) reaches a `}` before its `;`
        depth, end = 0, k + 1
        while end < len(code_only) and code_only[end] != ";":
            if code_only[end] in "({[":
                depth += 1
            elif code_only[end] in ")]":
                depth -= 1
            elif code_only[end] == "}":
                if depth == 0:
                    break
                depth -= 1
            end += 1
        if end < len(code_only) and code_only[end] == ";":
            hit(j, "print statement")
    hs, he = history_span(text) if is_html else (0, 0)
    for m in re.finditer(r"TempleOS", text):
        if not (hs <= m.start() < he):
            hit(m.start(), "TempleOS mention")
    if renames:
        for m in re.finditer(r"\b[A-Za-z_]\w*\b", code_only):
            if m.group(0) in renames:
                hit(m.start(), f"old library name `{m.group(0)}` (now `{renames[m.group(0)]}`)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--renames")
    ap.add_argument("roots", nargs="+")
    args = ap.parse_args()
    renames = {}
    if args.renames:
        for line in open(args.renames):
            cols = line.rstrip("\n").split("\t")
            if len(cols) >= 3 and cols[0] != "kind" and cols[1] != cols[2]:
                renames[cols[1].split(".")[-1]] = cols[2]
    hits = []
    for root in args.roots:
        for dirpath, dirs, files in os.walk(root):
            dirs[:] = [d for d in dirs if d not in ("build", ".git", "convert")]
            for name in sorted(files):
                if name.endswith((".ktm", ".ktmh")) or (
                        name.endswith(".html") and os.path.basename(dirpath) == "pages"):
                    scan(os.path.join(dirpath, name), renames, hits)
    for h in hits:
        print(h)
    print(f"{len(hits)} leftover(s)", file=sys.stderr)
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
