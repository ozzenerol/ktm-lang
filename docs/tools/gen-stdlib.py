#!/usr/bin/env python3
"""Generate docs/stdlib.html from the standard library's modules (src/std/<module>).

A module's `pub` declarations are its API: functions, methods, structs,
unions, enums and constants (and the prelude's macros). Each is documented
with the comment above it in the module source.

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

# Module -> (section title, one-line summary), in page order. rt is the
# runtime's own and has no API.
MODULES = [
    ("prelude", "Prelude",           "In every program without an import: the Error struct, EXIT_* and the integer limits."),
    ("mem",     "Memory",            "Allocation and raw memory operations."),
    ("str",     "Strings",           "String, the owned growable string, and the functions that read any text (u8[])."),
    ("cstr",    "C strings",         "NUL-terminated u8 * strings: formatting, comparison, copying and conversion."),
    ("fs",      "Files",             "Reading and writing files, directories, file status and path names."),
    ("os",      "System",            "Process exit, errno and the environment."),
    ("proc",    "Processes",         "Shell commands, and running programs by argv without a shell, with pipes."),
    ("list",    "Lists",             "Circular doubly linked lists of void * values."),
    ("vec",     "Vectors",           "Growable arrays and bit vectors."),
    ("sort",    "Sorting",           "Sorting a slice in place."),
    ("map",     "Hash tables",       "Hash tables, and ordered maps (red-black trees)."),
    ("set",     "Sets",              "Hash sets of any type."),
    ("bytes",   "Byte buffers",      "A queue of bytes for binary data that comes and goes in pieces."),
    ("hash",    "Hashing",           "SHA-256, CRC-32, FNV-1a and a constant-time compare."),
    ("math",    "Maths",             "Numeric helpers, bit operations and libm bindings."),
    ("date",    "Dates and time",    "date.Date values and conversions."),
    ("json",    "JSON",              "Parsing, querying and printing JSON."),
    ("yaml",    "YAML",              "Reading and writing YAML documents."),
    ("csv",     "CSV",               "A streaming CSV tokenizer."),
    ("fzf",     "Fuzzy matching",    "fzf-style fuzzy string matching."),
    ("reflect", "Reflection",        "What the compiler tells a program about its types."),
    ("thread",  "Threads",           "pthreads helpers."),
    ("atomic",  "Atomics",           "Atomic operations on integers and pointers shared between threads."),
    ("net",     "Networking",        "Sockets and address resolution."),
    ("signal",  "Signals",           "sigaction-based handlers and a clean-shutdown helper for poll loops."),
    ("coro",    "Coroutines",        "Functions that pause and resume on their own stacks."),
    ("sql",     "SQLite",            "SQLite bindings, available when ktm is built with sqlite."),
    ("libc",    "C library",         "The C functions, structs and constants ktm programs call by their C names."),
]

# Module -> the page that explains it; each module's section in the reference links there.
GUIDE = {
    "mem": "lib-memory.html", "str": "lib-strings.html",
    "cstr": "lib-cstrings.html", "fs": "lib-files.html",
    "os": "lib-environment.html", "proc": "lib-processes.html",
    "list": "lib-lists.html", "vec": "lib-vectors.html",
    "sort": "lib-vectors.html", "map": "lib-maps.html",
    "set": "lib-sets.html", "bytes": "lib-bytes.html",
    "hash": "lib-hashing.html", "math": "lib-maths.html",
    "date": "lib-dates.html", "json": "lib-json.html",
    "yaml": "lib-yaml.html",
    "csv": "lib-csv.html", "fzf": "lib-fzf.html",
    "reflect": "lang-reflection.html", "thread": "lib-threads.html",
    "atomic": "lib-threads.html#atomics", "net": "lib-tcp.html",
    "signal": "lib-signals.html", "coro": "lib-coroutines.html",
    "sql": "lib-sqlite.html", "libc": "lang-calling-c.html",
    "prelude": "lang-modules.html#the-prelude",
}


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
        elif text[i] == "'":
            j = i + 1
            while j < n and text[j] not in "'\n":
                j += 2 if text[j] == "\\" else 1
            out.append("' '")
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


def comment_inside(lines, idx):
    """A HolyC-style doc comment at the top of a function's body: `{// ...`
    on line idx (the signature's last line), continued by `//` lines."""
    if idx >= len(lines) or not lines[idx].lstrip().startswith("{//"):
        return ""
    block = [lines[idx].lstrip()[3:]]
    j = idx + 1
    while j < len(lines) and lines[j].lstrip().startswith("//"):
        block.append(lines[j].lstrip()[2:])
        j += 1
    return " ".join(l.strip() for l in block).strip()


def parse_module(mod):
    """The `pub` declarations of one module, in file order."""
    decls, order = {}, []
    d = os.path.join(LIB, mod)
    for fname in sorted(f for f in os.listdir(d) if f.endswith(".ktm")):
        raw = open(os.path.join(d, fname)).read()
        lines = raw.split("\n")
        code = strip_comments_keep(raw).split("\n")
        i, n, depth, cond = 0, len(code), 0, []
        last_doc = ""
        while i < n:
            line = code[i].strip()
            if line.startswith("#"):
                m = re.match(r"#\s*(ifdef|ifndef|if|else|endif|define)\b\s*(.*)", line)
                if m:
                    kind, rest = m.groups()
                    if kind in ("ifdef", "ifndef", "if"):
                        cond.append(("not " if kind == "ifndef" else "") + rest.strip())
                    elif kind == "else" and cond:
                        cond[-1] = cond[-1][4:] if cond[-1].startswith("not ") else "not " + cond[-1]
                    elif kind == "endif" and cond:
                        cond.pop()
                    elif kind == "define" and mod == "prelude" and depth == 0:
                        dm = re.match(r"([A-Za-z_]\w*)\s+(.*)", rest)
                        if dm and not dm.group(1).startswith("__"):
                            add(decls, order, mod, dm.group(1), "define", dm.group(2).strip(),
                                comment_above(lines, i), i, cond)
                i += 1
                continue
            if depth == 0 and line.startswith("pub "):
                start = i
                buf = [line]
                cm = re.match(r"pub\s+(?:[a-z0-9]+\s+)?(struct|union|enum)\s+([A-Za-z_]\w*)", line)
                if cm:
                    dd = line.count("{") - line.count("}")
                    while (dd > 0 or not buf[-1].rstrip().endswith(";")) and i + 1 < n:
                        i += 1
                        buf.append(code[i].rstrip())
                        dd += code[i].count("{") - code[i].count("}")
                    text = tidy_class("\n".join(buf)[4:])
                    add(decls, order, mod, cm.group(2), cm.group(1), text,
                        comment_above(lines, start), start, cond)
                    i += 1
                    continue
                km = re.match(r"pub\s+const\s+.*?([A-Za-z_]\w*)\s*=\s*(.*?);", line)
                if km:
                    add(decls, order, mod, km.group(1), "define", km.group(2).strip(),
                        comment_above(lines, start), start, cond)
                    i += 1
                    continue
                # a function, method or binding: read to its `{` or `;`
                # (a one-line body, `{ return ...; }`, ends at its `{` too)
                while "{" not in buf[-1] and not buf[-1].rstrip().endswith(";") and i + 1 < n:
                    i += 1
                    buf.append(code[i].strip())
                stmt = " ".join(buf)
                stmt = (stmt[:stmt.index("{")] if "{" in stmt else stmt.rstrip()[:-1]).rstrip()[4:]
                cfunc = stmt.startswith('extern "c"')
                stmt = re.sub(r'^extern\s+"c"\s+|^_extern\s+\S+\s+', "", stmt)
                fm = re.match(r"(.*?)\b([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)?)\s*(?:<[^()<>]*>)?\s*\((.*)\)\s*$", stmt, re.S)
                if fm:
                    sig = re.sub(r"\s+", " ", stmt).replace("( ", "(").replace(" )", ")") + ";"
                    doc = comment_above(lines, start) or comment_inside(lines, i)
                    if not doc and start > 0 and lines[start - 1].lstrip().startswith("pub "):
                        doc = last_doc  # one comment above a run of declarations
                    add(decls, order, mod, fm.group(2), "cfunc" if cfunc else "func", sig,
                        doc, start, cond)
                    last_doc = doc
                for l in buf:
                    depth += l.count("{") - l.count("}")
                i += 1
                continue
            depth += line.count("{") - line.count("}")
            i += 1
    return decls, order


def tidy_class(text):
    body = []
    for l in text.split("\n"):
        l = l.rstrip()
        if l.strip():
            body.append(l)
    return "\n".join(body)


def param_types(sig):
    """A signature's parameter types: an overload differs in them, while a
    prototype, its definition and a platform's variant don't."""
    params = sig[sig.index("(") + 1:sig.rindex(")")]
    return tuple(re.sub(r"\s*\b[A-Za-z_]\w*\s*(=.*)?$", "", p).replace(" ", "")
                 for p in params.split(",") if p.strip())


def add(decls, order, mod, name, kind, sig, doc, line, cond):
    key = name if mod == "prelude" else f"{mod}.{name}"
    if key in decls:
        d = decls[key]
        if kind == d["kind"] and kind in ("struct", "union") and "{" not in d["sig"]:
            d["sig"] = sig  # the definition after a forward declaration
            d["doc"] = d["doc"] or doc
            return
        if kind == "func" and d["kind"] == "func" and \
                param_types(sig) not in map(param_types, d["sig"].split("\n")):
            # an overload: one entry with every signature, each one's doc
            d["sig"] += "\n" + sig
            if doc:
                d["doc"] = (d["doc"] + "\n\n" + doc) if d["doc"] else doc
        elif not d["doc"] and doc:
            d["doc"] = doc
        return
    platform = [c for c in cond if re.match(r"(not )?(IS_LINUX|IS_MACOS|IS_X86_64|IS_ARM_64|__APPLE__)$", c)]
    decls[key] = {"name": key, "kind": kind, "sig": sig, "doc": doc,
                  "line": line + 1, "platform": platform}
    order.append(key)


def main():
    groups = {}
    for mod, _, _ in MODULES:
        decls, order = parse_module(mod)
        groups[mod] = [decls[k] for k in order]
    if "--json" in sys.argv:
        json.dump(groups, sys.stdout, indent=1)
        return
    write_html(groups)


def write_html(groups):
    from page import page  # shared layout
    kinds = {"func": "function", "cfunc": "C function", "struct": "struct",
             "union": "union", "enum": "enum", "define": "constant"}
    toc, body = ['<li><a class="all" href="#">All modules</a></li>'], []
    total = 0
    for mod, title, summary in MODULES:
        items = groups.get(mod) or []
        if not items:
            continue
        anchor = mod
        toc.append(f'<li><a href="#{anchor}">{html.escape(title)}</a> <span class="count">{len(items)}</span></li>')
        body.append(f'<section id="{anchor}"><h2>{html.escape(title)}</h2>')
        imp = "" if mod == "prelude" else f" <code>import {mod};</code>"
        guide = (f' <a class="guide" href="{GUIDE[mod]}">How to use it</a>'
                 if mod in GUIDE else "")
        body.append(f'<p class="lede">{html.escape(summary)}{imp}{guide}</p>')
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
            for para in d["doc"].split("\n\n") if d["doc"] else []:
                text = re.sub(r"`([^`\n]+)`", r"<code>\1</code>", html.escape(para))
                body.append(f'<p class="doc">{text}</p>')
            body.append("</div>")
        if defs:
            total += len(defs)
            body.append('<table class="consts"><thead><tr><th>Constant</th><th>Value</th></tr></thead><tbody>')
            for d in defs:
                value = d["sig"]
                body.append(f'<tr id="{html.escape(d["name"])}"><td><code>{html.escape(d["name"])}</code></td>'
                            f'<td><code>{html.escape(value)}</code></td></tr>')
            body.append("</tbody></table>")
        body.append("</section>")
    content = f'''<h1>Standard library reference</h1>
<p class="lede">The public names of every module of the standard library: {total} functions,
methods, types and constants. A program imports a module (<code>import str;</code>) and
uses its names with the module's prefix (<code>str.format</code>); the prelude's need no
import. Generated from the modules' source by <code>docs/tools/gen-stdlib.py</code>; re-run
it after changing <code>src/std</code>.</p>
<input id="filter" type="search" placeholder="Filter by name, e.g. str.format" aria-label="Filter declarations">
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
