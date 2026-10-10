#!/usr/bin/env python3
"""Generate docs/stdlib.html from src/std/std.ktmh.

std.ktmh is what every program includes, so it is the real public API.
Each declaration is grouped by the library file that defines it, and
documented with the comment above it in std.ktmh or, failing that, the
comment above its definition in the library source.

Usage: python3 docs/tools/gen-stdlib.py
"""
import html
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIB = os.path.join(ROOT, "src", "std")
OUT = os.path.join(ROOT, "docs", "stdlib.html")

# Library file -> (section title, one-line summary), in page order.
MODULES = [
    ("memory.ktm",    "Memory",            "Allocation and raw memory operations."),
    ("strings.ktm",   "Strings",           "Formatting, comparison, searching and conversion of NUL-terminated strings."),
    ("io.ktm",        "Files and I/O",     "Reading and writing files."),
    ("dir.ktm",       "Directories",       "Listing and creating directories."),
    ("stat.ktm",      "File status",       "Portable file metadata."),
    ("system.ktm",    "System",            "Process exit, shell commands and raw output."),
    ("list.ktm",      "Lists",             "Circular doubly linked lists of U0 * values."),
    ("vector.ktm",    "Vectors",           "Growable arrays."),
    ("hashtable.ktm", "Hash tables",       "String-keyed and integer-keyed hash tables."),
    ("set.ktm",       "Sets",              "Integer and string sets."),
    ("bitvec.ktm",    "Bit vectors",       "Growable bit sets."),
    ("math.ktm",      "Maths",             "Numeric helpers and libm bindings."),
    ("date.ktm",      "Dates and time",    "CDate values and conversions."),
    ("json.ktm",      "JSON",              "Parsing, querying and printing JSON."),
    ("csv.ktm",       "CSV",               "A streaming CSV tokenizer."),
    ("fzf.ktm",       "Fuzzy matching",    "fzf-style fuzzy string matching."),
    ("except.ktm",    "Exceptions",        "The runtime behind try / catch / throw."),
    ("threads.ktm",   "Threads",           "pthreads bindings and helpers."),
    ("net.ktm",       "Networking",        "Sockets and address resolution."),
    ("signals.ktm",   "Signals",           "sigaction-based handlers and a clean-shutdown helper for poll loops."),
    ("process.ktm",   "Processes",         "Running programs by argv without a shell, with pipes."),
    ("coroutines.ktm","Coroutines",        "Functions that pause and resume on their own stacks."),
    ("sqllite.ktm",   "SQLite",            "SQLite bindings, available when ktm is built with sqlite."),
    (None,           "Other declarations","Declared in std.ktmh but not defined in a library .ktm file."),
]


def strip_comments_keep(text):
    """Remove /* */ and // comments, preserving newlines."""
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("\n" * text.count("\n", i, j))
            i = j
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text[i] == '"':
            j = i + 1
            while j < n and text[j] != '"':
                j += 2 if text[j] == "\\" else 1
            out.append(text[i:j + 1])
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def comment_above(lines, idx):
    """The /* */ or // comment block directly above line idx (0-based)."""
    j = idx - 1
    while j >= 0 and lines[j].strip() == "":
        return ""  # a blank line separates it: not this declaration's doc
    block = []
    if j >= 0 and lines[j].strip().endswith("*/"):
        while j >= 0:
            block.insert(0, lines[j])
            if "/*" in lines[j]:
                break
            j -= 1
    elif j >= 0 and lines[j].strip().startswith("//"):
        while j >= 0 and lines[j].strip().startswith("//"):
            block.insert(0, lines[j])
            j -= 1
    text = "\n".join(block)
    text = re.sub(r"^\s*/\*+ ?", "", text)
    text = re.sub(r"\*+/\s*$", "", text)
    text = "\n".join(re.sub(r"^\s*(\*|//) ?", "", l) for l in text.split("\n"))
    text = text.strip()
    if re.match(r"^(XXX|TODO|FIXME)", text):
        return ""
    return text


DECL_FN = re.compile(r"""^(?:public\s+)?
    (?P<link>extern\s+"c"\s+|_extern\s+\w+\s+)?
    (?P<sig>(?:const\s+)?[A-Za-z_]\w*[\s*]+(?P<name>[A-Za-z_]\w*)\s*\((?P<params>.*)\))\s*;\s*$""",
    re.X | re.S)


def parse_header():
    raw = open(os.path.join(LIB, "std.ktmh")).read()
    lines = raw.split("\n")
    code = strip_comments_keep(raw).split("\n")
    decls = {}           # name -> decl (first wins: arch variants are duplicates)
    order = []
    i, n = 0, len(code)
    cond = []            # #ifdef stack, for platform notes
    while i < n:
        line = code[i].strip()
        if not line:
            i += 1
            continue
        if line.startswith("#"):
            m = re.match(r"#\s*(ifdef|ifndef|if|else|endif|define)\b\s*(.*)", line)
            if m:
                kind, rest = m.groups()
                if kind in ("ifdef", "ifndef", "if"):
                    cond.append(rest.strip())
                elif kind == "else" and cond:
                    cond[-1] = "not " + cond[-1]
                elif kind == "endif" and cond:
                    cond.pop()
                elif kind == "define":
                    dm = re.match(r"([A-Za-z_]\w*)\s*(.*)", rest)
                    if dm:
                        name, value = dm.groups()
                        add(decls, order, name, "define",
                            f"#define {name} {value.strip()}".strip(), comment_above(lines, i), i, cond)
            i += 1
            continue
        # class / union: read to the closing `};` (or a forward `;`)
        cm = re.match(r"(?:public\s+)?(class|union)\s+([A-Za-z_]\w*)", line)
        if cm:
            start = i
            buf = []
            depth = 0
            while i < n:
                buf.append(code[i])
                depth += code[i].count("{") - code[i].count("}")
                if depth <= 0 and code[i].rstrip().endswith(";"):
                    break
                i += 1
            text = "\n".join(buf).strip()
            if "{" in text:
                text = re.sub(r"^public\s+", "", text)
                add(decls, order, cm.group(2), cm.group(1), tidy_class(text),
                    comment_above(lines, start), start, cond)
            i += 1
            continue
        # function declaration, possibly spanning lines
        start = i
        buf = [line]
        while not buf[-1].rstrip().endswith(";") and i + 1 < n:
            i += 1
            buf.append(code[i].strip())
        stmt = " ".join(buf)
        fm = DECL_FN.match(stmt)
        if fm:
            link = (fm.group("link") or "").strip()
            sig = re.sub(r"\s+", " ", fm.group("sig")).replace("( ", "(").replace(" )", ")")
            kind = "cfunc" if link.startswith("extern") else "func"
            add(decls, order, fm.group("name"), kind, sig + ";", comment_above(lines, start), start, cond)
        i += 1
    return decls, order


def tidy_class(text):
    body = []
    for l in text.split("\n"):
        l = l.rstrip()
        if l.strip():
            body.append(l)
    return "\n".join(body)


def add(decls, order, name, kind, sig, doc, line, cond):
    if name in decls:
        if not decls[name]["doc"] and doc:
            decls[name]["doc"] = doc
        return
    platform = [c for c in cond if re.match(r"(not )?IS_(LINUX|MACOS)$", c)]
    decls[name] = {"name": name, "kind": kind, "sig": sig, "doc": doc,
                   "line": line + 1, "platform": platform}
    order.append(name)


def find_definitions(decls):
    """name -> (module file, doc comment above its definition)."""
    where = {}
    for fname in sorted(os.listdir(LIB)):
        if not fname.endswith(".ktm") or fname == "all.ktm":
            continue
        raw = open(os.path.join(LIB, fname)).read()
        lines = raw.split("\n")
        for idx, l in enumerate(lines):
            s = l.strip()
            for m in re.finditer(r"([A-Za-z_]\w*)\s*\(", s):
                name = m.group(1)
                d = decls.get(name)
                if d is None or d["kind"] == "define" or name in where:
                    continue
                before = s[:m.start()]
                # a definition or prototype: type words before the name, not a
                # call (`return StrPrint(...)` is a call, not a definition)
                if re.match(r"^\s*(return|else|case|goto|do|sizeof)\b", before):
                    continue
                if re.match(r"^(public\s+)?(static\s+)?(inline\s+)?(extern\s+\"c\"\s+|_extern\s+\w+\s+)?[A-Za-z_]\w*[\s*]+$", before):
                    where[name] = (fname, comment_above(lines, idx))
            cm = re.match(r"(?:public\s+)?(?:class|union)\s+([A-Za-z_]\w*)", s)
            if cm and cm.group(1) in decls and cm.group(1) not in where and "{" in s + (lines[idx + 1] if idx + 1 < len(lines) else ""):
                where[cm.group(1)] = (fname, comment_above(lines, idx))
            dm = re.match(r"#\s*define\s+([A-Za-z_]\w*)", s)
            if dm and dm.group(1) in decls and dm.group(1) not in where:
                where[dm.group(1)] = (fname, "")
    return where


def main():
    decls, order = parse_header()
    where = find_definitions(decls)
    groups = {m[0]: [] for m in MODULES}
    for name in order:
        d = decls[name]
        mod, src_doc = where.get(name, (None, ""))
        if not d["doc"] and src_doc:
            d["doc"] = src_doc
        if mod not in groups:
            mod = None
        # plain libc bindings that live nowhere else: their own section
        d["module"] = mod
        groups[mod].append(d)
    if "--json" in sys.argv:
        json.dump({k or "other": v for k, v in groups.items()}, sys.stdout, indent=1)
        return
    write_html(groups)


def write_html(groups):
    from page import page  # shared layout
    kinds = {"func": "function", "cfunc": "C binding", "class": "class",
             "union": "union", "define": "constant"}
    toc, body = ['<li><a class="all" href="#">All modules</a></li>'], []
    total = 0
    for mod, title, summary in MODULES:
        items = groups.get(mod) or []
        if not items:
            continue
        anchor = (mod or "other").replace(".ktm", "").lower()
        toc.append(f'<li><a href="#{anchor}">{html.escape(title)}</a> <span class="count">{len(items)}</span></li>')
        body.append(f'<section id="{anchor}"><h2>{html.escape(title)}</h2>')
        src = f' <code>src/std/{mod}</code>' if mod else ""
        body.append(f'<p class="lede">{html.escape(summary)}{" Defined in" + src + "." if mod else ""}</p>')
        # constants as one compact table
        defs = [d for d in items if d["kind"] == "define"]
        rest = [d for d in items if d["kind"] != "define"]
        for d in rest:
            total += 1
            plat = f' <span class="tag">{html.escape(", ".join(d["platform"]))}</span>' if d["platform"] else ""
            body.append(f'<div class="decl" id="{html.escape(d["name"])}">'
                        f'<div class="decl-head"><a class="decl-name" href="#{html.escape(d["name"])}">{html.escape(d["name"])}</a>'
                        f' <span class="kind">{kinds[d["kind"]]}</span>{plat}</div>'
                        f'<pre><code class="language-ktm">{html.escape(d["sig"])}</code></pre>')
            if d["doc"]:
                body.append(f'<p class="doc">{html.escape(d["doc"])}</p>')
            body.append("</div>")
        if defs:
            total += len(defs)
            body.append('<table class="consts"><thead><tr><th>Constant</th><th>Value</th></tr></thead><tbody>')
            for d in defs:
                value = d["sig"].split(None, 2)[2] if len(d["sig"].split(None, 2)) > 2 else ""
                body.append(f'<tr id="{html.escape(d["name"])}"><td><code>{html.escape(d["name"])}</code></td>'
                            f'<td><code>{html.escape(value)}</code></td></tr>')
            body.append("</tbody></table>")
        body.append("</section>")
    content = f'''<h1>Standard library reference</h1>
<p class="lede">Everything declared in <code>std.ktmh</code>, the header every ktm program
includes automatically: {total} functions, classes and constants. Generated from the
library source by <code>docs/tools/gen-stdlib.py</code>; re-run it after changing
<code>src/std</code>.</p>
<input id="filter" type="search" placeholder="Filter by name, e.g. StrPrint" aria-label="Filter declarations">
<nav class="modules"><ul>{"".join(toc)}</ul></nav>
{"".join(body)}
<script>
// Opening a module or a declaration shows only that module; the filter
// and "All modules" show everything.
const f = document.getElementById("filter");
const sections = [...document.querySelectorAll("main section")];
const items = s => s.querySelectorAll(".decl, .consts tr[id]");
function showAll() {{
  sections.forEach(s => {{ s.style.display = ""; items(s).forEach(e => e.style.display = ""); }});
}}
function showModule() {{
  f.value = "";
  const id = decodeURIComponent(location.hash.slice(1));
  const target = id && document.getElementById(id);
  const sec = target && target.closest("main section");
  document.querySelectorAll(".modules a").forEach(a =>
    a.classList.toggle("active", sec ? a.getAttribute("href").endsWith("#" + sec.id) : a.classList.contains("all")));
  if (!sec) {{ showAll(); return; }}
  showAll();
  sections.forEach(s => s.style.display = s === sec ? "" : "none");
  if (target === sec) window.scrollTo(0, document.querySelector(".modules").offsetTop - 70);
  else target.scrollIntoView();
}}
f.addEventListener("input", () => {{
  const q = f.value.trim().toLowerCase();
  sections.forEach(s => {{
    let any = false;
    items(s).forEach(el => {{
      const hit = !q || el.id.toLowerCase().includes(q);
      el.style.display = hit ? "" : "none";
      any = any || hit;
    }});
    s.style.display = any ? "" : "none";
  }});
}});
window.addEventListener("hashchange", showModule);
// Re-clicking the current module after filtering doesn't change the hash.
document.querySelectorAll(".modules a").forEach(a => a.addEventListener("click", () => setTimeout(showModule)));
showModule();
</script>'''
    open(OUT, "w").write(page("Standard library", "stdlib.html", content))
    print(f"wrote {OUT}: {total} declarations")


if __name__ == "__main__":
    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    main()
