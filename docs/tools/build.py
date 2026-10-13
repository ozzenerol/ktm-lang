#!/usr/bin/env python3
"""Build docs/*.html from the page sources in docs/pages/.

Each page source starts with `<!-- title: Page title -->`, optionally
`<!-- ref: mod1 mod2 -->` (the library reference modules it covers),
followed by the page body; this wraps it in the shared layout (tools/page.py), regenerates
the library reference (tools/gen-stdlib.py) and writes the search index
(assets/search-index.js) from what the pages recorded.

Usage: python3 docs/tools/build.py
"""
import html
import json
import os
import re
import runpy
import sys

TOOLS = os.path.dirname(os.path.abspath(__file__))
DOCS = os.path.dirname(TOOLS)
sys.path.insert(0, TOOLS)
import page as layout  # noqa: E402
from page import page  # noqa: E402


# Code blocks are written raw in the page sources (so examples read and
# copy exactly as typed); escape them for HTML here.
CODE = re.compile(r'(<pre[^>]*>(?:<code[^>]*>)?)(.*?)((?:</code>)?</pre>)', re.S)


def escape_code(body):
    def one(m):
        inner = m.group(2)
        if inner.startswith("\n"):  # allow the code to start on its own line
            inner = inner[1:]
        return m.group(1) + html.escape(inner, quote=False) + m.group(3)
    return CODE.sub(one, body)


# `<!-- include: examples/x/main.ktm -->` pulls a file's text in raw, so
# the example pages always show the code and output that run.sh checks.
INCLUDE = re.compile(r"<!--\s*include:\s*(\S+)\s*-->")


def include_files(body):
    return INCLUDE.sub(lambda m: open(os.path.join(DOCS, m.group(1))).read(), body)


def main():
    pages = os.path.join(DOCS, "pages")
    for name in sorted(os.listdir(pages)):
        if not name.endswith(".html"):
            continue
        src = open(os.path.join(pages, name)).read()
        m = re.match(r"\s*<!--\s*title:\s*(.*?)\s*-->\s*", src)
        if not m:
            sys.exit(f"{name}: missing <!-- title: ... --> header")
        rest = src[m.end():]
        refs = None
        r = re.match(r"<!--\s*ref:\s*(.*?)\s*-->\s*", rest)
        if r:
            refs = r.group(1).split()
            rest = rest[r.end():]
        body = include_files(rest)
        out = os.path.join(DOCS, name)
        open(out, "w").write(page(m.group(1), name, escape_code(body), refs))
        print(f"wrote docs/{name}")
    runpy.run_path(os.path.join(TOOLS, "gen-stdlib.py"), run_name="__main__")
    write_search_index()


def write_search_index():
    """A script, not JSON: fetch() doesn't work for pages opened from
    file://, a <script src> does."""
    out = os.path.join(DOCS, "assets", "search-index.js")
    data = json.dumps(layout.SEARCH, separators=(",", ":"), ensure_ascii=False)
    open(out, "w").write("window.KTM_SEARCH=" + data + ";\n")
    print(f"wrote docs/assets/search-index.js: {len(layout.SEARCH)} entries")


if __name__ == "__main__":
    main()
