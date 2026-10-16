"""The man pages: the language chapters and the standard library, as roff.

  man/man7/ktm.7               where the documentation is
  man/man7/ktm-guide.7         the chapters, listed
  man/man7/ktm-guide-<name>.7  docs-src/language/NN-<name>.md
  man/man3/ktm-std.3           the modules, listed
  man/man3/ktm-<module>.3      one module's API (gen_std.py)

build.py calls write(). The Markdown is the subset md.py reads; this file
turns the same blocks into man(7) requests instead of HTML. Linux installs
them into <prefix>/share/man.
"""
import os
import re
import shutil

import md

KINDS = [("Types", ("struct", "union", "enum")), ("Constants", ("constant",)),
         ("Functions", ("function",)), ("Methods", ("method",)),
         ("C functions", ("C function",))]


def esc(text):
    """Plain text for roff: backslashes, and a . or ' that would start a request"""
    text = text.replace("\\", "\\e")
    return "\n".join("\\&" + l if l[:1] in (".", "'") else l for l in text.split("\n"))


def code_text(text):
    """Code: escaped, and with real minus signs, so it can be copied"""
    return esc(text).replace("-", "\\-")


def page_of_link(href, chapters, modules):
    """The man page a site link points at, or None"""
    path, _, frag = href.partition("#")
    stem = os.path.basename(path)[:-5] if path.endswith(".html") else ""
    if stem in chapters:
        return f"ktm-guide-{chapters[stem]}(7)"
    if "std/" in path or path.startswith("../std"):
        mod = frag.split("/")[0]
        # #str.split: the longest module name in front of the symbol
        for m in sorted(modules, key=len, reverse=True):
            if mod == m or mod.startswith(m + "."):
                return f"ktm-{m}(3)"
        return "ktm-std(3)"
    return None


def inline(text, links):
    """One paragraph's inline Markdown: `code` and **bold** bold, *italic* italic,
    [text](link) as text and the page it points at"""
    out = []
    for p in re.split(r"(`[^`]+`)", text):
        if p.startswith("`") and p.endswith("`") and len(p) > 1:
            out.append("\\fB" + code_text(p[1:-1]) + "\\fR")
            continue
        p = esc(p)

        def link(m):
            label, href = m.group(1), m.group(2)
            if href.startswith(("http://", "https://")):
                return f"{label} <{href}>"
            page = links(href)
            return f"{label} (see {page})" if page else label
        p = re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", link, p)
        p = re.sub(r"\*\*(.+?)\*\*", r"\\fB\1\\fR", p)
        p = re.sub(r"(?<![\w*])\*(?!\s)(.+?)(?<!\s)\*(?![\w*])", r"\\fI\1\\fR", p)
        out.append(p)
    return "".join(out).replace("\n", " ")


def example(code, label=""):
    lines = [".PP", label] if label else [".PP"]
    return lines + [".RS 4", ".EX", code_text(code.rstrip("\n")), ".EE", ".RE"]


def table(rows, links):
    def cells(line):
        line = line.strip().strip("|")
        out, cur, code = [], "", False
        for ch in line:
            if ch == "`":
                code = not code
            if ch == "|" and not code and not cur.endswith("\\"):
                out.append(cur.strip())
                cur = ""
            else:
                cur += ch
        out.append(cur.strip())
        return [c.replace("\\|", "|") for c in out]
    head, body = cells(rows[0]), [cells(r) for r in rows[2:]]
    n = len(head)
    fmt = " ".join(["lb"] * n) + "\n" + " ".join(["lx" if i == n - 1 else "l" for i in range(n)]) + " ."
    out = [".PP", ".TS", "allbox tab(\t);", fmt]
    for r in [head] + body:
        r = (r + [""] * n)[:n]
        out.append("\t".join("T{\n" + inline(c, links) + "\nT}" for c in r))
    return out + [".TE"]


def markdown(text, links, skip_title=True):
    """docs-src Markdown (md.py's subset) as roff lines. ## is .SH, ### .SS"""
    out, para, items, kind, rows = [], [], [], None, []

    def flush():
        nonlocal para, items, kind, rows
        if para:
            out.extend([".PP", inline(" ".join(l.strip() for l in para), links)])
            para = []
        for n, it in enumerate(items, 1):
            out.extend([f".IP {n}. 4" if kind == "ol" else ".IP \\(bu 2", inline(it, links)])
        items, kind = [], None
        if rows:
            out.extend(table(rows, links))
            rows = []

    lines = text.split("\n")
    i = 0
    while i < len(lines):
        line = lines[i]
        fence = re.match(r"^```\s*(.*)$", line)
        if fence:
            flush()
            info = fence.group(1).split()
            j = i + 1
            while j < len(lines) and not lines[j].startswith("```"):
                j += 1
            code = "\n".join(lines[i + 1:j])
            if info[:1] == ["output"]:
                out.extend(example(code, "Output:"))
            elif info[1:2] == ["error"]:
                out.extend(example(code, "This does not compile:"))
            else:
                out.extend(example(code))
            i = j + 1
            continue
        h = re.match(r"^(#{1,3})\s+(.*)$", line)
        if h:
            flush()
            if len(h.group(1)) > 1 or not skip_title:
                req = ".SS" if len(h.group(1)) == 3 else ".SH"
                out.append(req + " " + inline(h.group(2).strip(), links).replace('"', "\\(dq"))
            i += 1
            continue
        if line.strip().startswith("|") and line.strip().endswith("|"):
            if para or items:
                flush()
            rows.append(line)
            i += 1
            continue
        li = re.match(r"^\s*(?:[-*]|(\d+)\.)\s+(.*)$", line)
        if li and not para:
            if rows:
                flush()
            k = "ol" if li.group(1) else "ul"
            if items and k != kind:
                flush()
            kind = k
            items.append(li.group(2))
            i += 1
            continue
        if items and line.startswith("  ") and line.strip():
            items[-1] += " " + line.strip()
            i += 1
            continue
        if line.strip().startswith("<") and not para:
            flush()  # raw HTML: the site's, not the terminal's
            i += 1
            continue
        if not line.strip():
            flush()
            i += 1
            continue
        if items or rows:
            flush()
        para.append(line)
        i += 1
    flush()
    return out


def doc_comment(text):
    """A declaration's doc comment: paragraphs split by blank lines, an
    indented line kept as code"""
    out, para, code = [], [], []
    for line in text.split("\n") + [""]:
        if line.startswith(("  ", "\t")) and line.strip():
            if para:
                out.extend([".PP", inline(" ".join(para), lambda h: None)])
                para = []
            code.append(line)
            continue
        if code:
            out.extend(example("\n".join(code)))
            code = []
        if line.strip():
            para.append(line.strip())
        elif para:
            out.extend([".PP", inline(" ".join(para), lambda h: None)])
            para = []
    return out


def summary(text):
    """A chapter's first paragraph, plain text: what it is about"""
    para = re.split(r"\n\s*\n", text.split("\n", 1)[1].strip(), 1)[0]
    plain = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", " ".join(para.split()))
    return re.sub(r"[`*]", "", plain)


def header(name, section, title, version, manual, desc):
    return ['.\\" Generated by docs-src/tools/build.py: edit the source, not this file.',
            ".ds AD l",
            f'.TH {name.upper()} {section} "" "ktm {version}" "{manual}"',
            ".ad l", ".nh", ".SH NAME", f"{name} \\- {esc(desc)}"]


def see_also(pages):
    return [".SH SEE ALSO", ",\n".join(f".BR {p.replace('(', ' (', 1)}" for p in pages)]


def chapter_page(stem, title, text, n, chapters, links, version, prev_next):
    name = f"ktm-guide-{chapters[stem]}"
    lines = header(name, 7, title, version, "The ktm language",
                   f"{title} (chapter {n} of the ktm language guide)")
    body = markdown(text, links)
    # the lede and anything else before the first ## goes under DESCRIPTION
    lines += [".SH DESCRIPTION"] + body
    lines += see_also(["ktm-guide(7)"] + [f"ktm-guide-{chapters[s]}(7)" for s in prev_next])
    return lines


def module_page(m, version):
    name = f"ktm-{m['name']}"
    lines = header(name, 3, m["title"], version, "ktm standard library", m["summary"])
    if m["name"] != "prelude":
        lines += [".SH SYNOPSIS", ".EX", f"import {m['name']};", ".EE"]
    lines += [".SH DESCRIPTION", ".PP", esc(m["summary"])]
    if m.get("intro_md"):
        lines += markdown(m["intro_md"], lambda h: None)
    for title, kinds in KINDS:
        decls = [d for d in m["decls"] if d["kind"] in kinds]
        if not decls:
            continue
        lines.append(".SH " + title.upper())
        for d in decls:
            short = d["name"] if m["name"] == "prelude" else d["name"].split(".", 1)[1]
            lines += [f".SS {code_text(short)}", ".EX", code_text(d["sig"]), ".EE"]
            if d["doc"]:
                lines += doc_comment(d["doc"])
            if d["platform"]:
                lines += [".PP", "Only when: " + esc(", ".join(d["platform"]))]
    return lines + see_also(["ktm-std(3)", "ktm(7)"])


def write(root, chs, mods, version):
    """chs: [(stem, title, lede, markdown text)], mods: gen_std.modules().
    Writes <root>/man (replacing it); returns the number of pages."""
    out = os.path.join(root, "man")
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(os.path.join(out, "man3"))
    os.makedirs(os.path.join(out, "man7"))
    chapters = {stem: stem.split("-", 1)[1] for stem, *_ in chs}
    names = [m["name"] for m in mods]
    links = lambda href: page_of_link(href, chapters, names)  # noqa: E731
    pages = {}

    for n, (stem, title, lede, text) in enumerate(chs):
        around = [chs[k][0] for k in (n - 1, n + 1) if 0 <= k < len(chs)]
        pages[f"man7/ktm-guide-{chapters[stem]}.7"] = chapter_page(
            stem, title, text, n + 1, chapters, links, version, around)

    guide = header("ktm-guide", 7, "", version, "The ktm language",
                   "the ktm language guide, one topic per chapter")
    guide += [".SH DESCRIPTION", ".PP",
              "Everything ktm has, one topic per chapter, each with many small programs you can run. "
              "Read them in order the first time; later, use them as a reference.",
              ".SH CHAPTERS"]
    for n, (stem, title, _, text) in enumerate(chs, 1):
        guide += [".TP", f".BR ktm-guide-{chapters[stem]} (7)", f"{n}. {esc(title)}: {esc(summary(text))}"]
    pages["man7/ktm-guide.7"] = guide + see_also(["ktm(7)", "ktm-std(3)"])

    for m in mods:
        pages[f"man3/ktm-{m['name']}.3"] = module_page(m, version)

    std = header("ktm-std", 3, "", version, "ktm standard library",
                 "the ktm standard library, module by module")
    std += [".SH DESCRIPTION", ".PP",
            "Each module has its own page, ktm-<module>(3), with every public declaration and its "
            "documentation. A program imports a module by its name: \\fBimport str;\\fR."]
    group = None
    for m in mods:
        if m["group"] != group:
            group = m["group"]
            std.append(".SH " + esc(group.upper()))
        std += [".TP", f".BR ktm-{m['name']} (3)", esc(m["summary"])]
    pages["man3/ktm-std.3"] = std + see_also(["ktm(7)", "ktm-guide(7)"])

    pages["man7/ktm.7"] = header("ktm", 7, "", version, "The ktm language",
                                 "a compiled systems language for servers and tools") + [
        ".SH DESCRIPTION", ".PP",
        "ktm's documentation, as on <https://ozzenerol.github.io/ktm-lang/>:",
        ".TP", ".BR ktm-guide (7)", "the language, one topic per chapter, e.g. \\fBman ktm-guide-structs-and-unions\\fR",
        ".TP", ".BR ktm-std (3)", "the standard library, one page per module, e.g. \\fBman ktm-str\\fR",
        ".PP", "\\fBman -k ktm\\fR lists every page."] + see_also(["ktm-guide(7)", "ktm-std(3)"])

    for rel, lines in pages.items():
        text = "\n".join(lines) + "\n"
        if "\n.TS\n" in text:
            text = "'\\\" t\n" + text   # tables: man runs tbl first
        open(os.path.join(out, rel), "w").write(text)
    return len(pages)
