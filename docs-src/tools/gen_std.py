#!/usr/bin/env python3
"""The standard library's API, from its source: every `pub` declaration of
every module in docs-src/std/modules.json, with the comment above it.

modules() returns the modules in order, each with its declarations; build.py
writes them to docs/std/data.js for the library browser. The parser is the
old site's (docs-old/tools/gen-stdlib.py), with each declaration's source
file added.
"""
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LIB = os.path.join(ROOT, "src", "std")
SPEC = os.path.join(ROOT, "docs-src", "std", "modules.json")


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
    d = os.path.join(LIB, mod.replace(".", "/"))   # net.http: the directory net/http
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
                                comment_above(lines, i), i, cond, fname)
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
                        comment_above(lines, start), start, cond, fname)
                    i += 1
                    continue
                km = re.match(r"pub\s+const\s+.*?([A-Za-z_]\w*)\s*=\s*(.*?);", line)
                if km:
                    add(decls, order, mod, km.group(1), "define", km.group(2).strip(),
                        comment_above(lines, start), start, cond, fname)
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
                    name = fm.group(2)
                    # a generic type's method, `void Vec<T>.push(T x)`: Vec.push
                    gm = re.search(r"\b([A-Za-z_]\w*)\s*<[^()<>]*(?:<[^()<>]*>[^()<>]*)*>\s*\.\s*([A-Za-z_]\w*)\s*(?:<[^()<>]*>)?\s*\(", stmt)
                    if gm and "." not in name:
                        name = gm.group(1) + "." + gm.group(2)
                    add(decls, order, mod, name, "cfunc" if cfunc else "func", sig,
                        doc, start, cond, fname)
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


def add(decls, order, mod, name, kind, sig, doc, line, cond, fname):
    key = name if mod == "prelude" else f"{mod.split('.')[-1]}.{name}"
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
                  "file": mod.replace(".", "/") + "/" + fname, "line": line + 1,
                  "platform": platform}
    order.append(key)



def kind_of(d):
    """function, method, C function, struct, union, enum or constant"""
    k = d["kind"]
    if k == "func":
        short = d["name"].split(".", 1)[1] if "." in d["name"] else d["name"]
        return "method" if "." in short else "function"
    return {"cfunc": "C function", "define": "constant"}.get(k, k)


def modules():
    """[{name, title, group, summary, decls}] in the order of modules.json"""
    spec = json.load(open(SPEC))
    out = []
    for group in spec["groups"]:
        for m in group["modules"]:
            decls, order = parse_module(m["name"])
            items = []
            for k in order:
                d = decls[k]
                d["kind"] = kind_of(d)
                items.append(d)
            out.append({"name": m["name"], "title": m["title"], "group": group["title"],
                        "summary": m["summary"], "decls": items})
    return out


if __name__ == "__main__":
    mods = modules()
    print(sum(len(m["decls"]) for m in mods), "declarations in", len(mods), "modules")
