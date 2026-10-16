#!/usr/bin/env python3
"""Build the documentation site: docs-src/ -> docs/ (served by GitHub Pages).

  docs/index.html              docs-src/pages/index.html (the home page)
  docs/language/index.html     the chapters, listed
  docs/language/NN-name.html   docs-src/language/NN-name.md
  docs/guides/index.html       the quick guides, listed
  docs/guides/NN-name.html     docs-src/guides/NN-name.md
  docs/std/index.html          the library browser (docs-src/assets/std.js)
  docs/std/data.js             the library's API, from src/std (gen_std.py)
  docs/releases.html           docs-src/pages/releases.html
  docs/known-issues.html       docs-src/pages/known-issues.html
  docs/assets/                 docs-src/assets/, and search.js (what the search box finds)
  man/                         the chapters and the library as man pages (man.py)

Usage: python3 docs-src/tools/build.py
"""
import glob
import html
import json
import os
import re
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_std  # noqa: E402
import man  # noqa: E402
import md  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs-src")
OUT = os.path.join(ROOT, "docs")


def version():
    text = open(os.path.join(ROOT, "src", "version.h")).read()
    return re.search(r'KTM_VERSION \("(v[^"]+)"\)', text).group(1)


VERSION = version()


def shell(title, body, root, section="", wide=False, desc=""):
    """A whole page: the top bar, body, footer. root: the way back to docs/ ("" or "../")."""
    def nav(href, label, key):
        on = ' class="on"' if key == section else ""
        return f'<a href="{root}{href}"{on}>{label}</a>'
    full = "ktm" if title == "ktm" else f"{title} - ktm"
    meta = f'<meta name="description" content="{html.escape(desc)}">' if desc else ""
    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(full)}</title>
{meta}
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Public+Sans:ital,wght@0,400;0,600;0,700;1,400&family=JetBrains+Mono:wght@400;600&display=swap">
<link rel="stylesheet" href="{root}assets/style.css">
<script>try {{
  var t = localStorage.getItem("ktm-theme"); if (t) document.documentElement.dataset.theme = t;
  ["chapters", "onpage", "std-left", "std-right"].forEach(function (s) {{
    if (localStorage.getItem("ktm-side-" + s) === "closed") document.documentElement.classList.add("closed-" + s);
  }});
}} catch (e) {{}}</script>
</head>
<body{' class="wide"' if wide else ''}>
<header class="top">
  <div class="bar">
    <a class="brand" href="{root}index.html">ktm</a>
    <nav class="main">
      {nav("language/index.html", "Language", "language")}
      {nav("guides/index.html", "Quick Guides", "guides")}
      {nav("std/index.html", "Library", "std")}
      {nav("releases.html", "Releases", "releases")}
    </nav>
    <div class="search" data-root="{root}">
      <input id="site-search" type="search" placeholder="Search" aria-label="Search the documentation" autocomplete="off">
      <kbd>/</kbd>
      <ul id="site-results" class="results" hidden></ul>
    </div>
    <span class="ver">{VERSION}</span>
    <button id="theme" class="theme" aria-label="Switch between light and dark">&#9680;</button>
  </div>
</header>
{body}
<footer>
  <div class="bar">
    <span>ktm {VERSION}</span>
    <a href="{root}releases.html">Releases</a>
    <a href="{root}known-issues.html">Known issues</a>
    <a href="{root}roadmap.html">Roadmap</a>
    <a href="https://github.com/ozzenerol/ktm-lang">GitHub</a>
  </div>
</footer>
<script src="{root}assets/search.js"></script>
<script src="{root}assets/site.js"></script>
</body>
</html>
"""


def write(rel, text):
    path = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(text)


def chapters(section="language"):
    """[(file stem, title, lede, html, toc)] in order"""
    out = []
    for path in sorted(glob.glob(os.path.join(SRC, section, "[0-9][0-9]-*.md"))):
        title, lede, body, toc, _ = md.convert(open(path).read())
        out.append((os.path.basename(path)[:-3], title, lede, body, toc))
    return out


def chapter_pages(chs, index, section="language", label="Language"):
    side = "".join(
        f'<li><a href="{stem}.html"{{on{stem}}}><span class="num">{stem[:2].lstrip("0")}</span>{html.escape(title)}</a></li>'
        for stem, title, _, _, _ in chs)
    for n, (stem, title, lede, body, toc) in enumerate(chs):
        nav = side
        for s, *_ in chs:
            nav = nav.replace("{on" + s + "}", ' class="on"' if s == stem else "")
        on_page = "".join(f'<li><a href="#{sid}">{html.escape(text)}</a></li>' for sid, text in toc)
        prev_link = next_link = ""
        if n > 0:
            p = chs[n - 1]
            prev_link = f'<a class="prev" href="{p[0]}.html"><span>Previous</span>{html.escape(p[1])}</a>'
        if n + 1 < len(chs):
            q = chs[n + 1]
            next_link = f'<a class="next" href="{q[0]}.html"><span>Next</span>{html.escape(q[1])}</a>'
        page = f"""<div class="doc">
  <button class="side-toggle" type="button" data-side="chapters" data-text="{html.escape(label)}" data-label="the chapter list" aria-expanded="true">&#8249; {html.escape(label)}</button>
  <aside class="chapters" aria-label="Chapters"><h4>{label}</h4><ol>{nav}</ol></aside>
  <article class="prose">
{body}
    <nav class="pager">{prev_link}{next_link}</nav>
  </article>
  <button class="side-toggle side-toggle-r" type="button" data-side="onpage" data-text="On this page" data-label="on this page" aria-expanded="true">On this page &#8250;</button>
  <aside class="onpage" aria-label="On this page"><h4>On this page</h4><ul>{on_page}</ul></aside>
</div>"""
        write(f"{section}/{stem}.html", shell(title, page, "../", section, wide=True, desc=lede))
        for sid, text in toc:
            index.append({"t": text, "p": title, "u": f"{section}/{stem}.html#{sid}", "k": "section"})
        index.append({"t": title, "p": label, "u": f"{section}/{stem}.html",
                      "k": "chapter" if section == "language" else "guide"})


def language_index(chs):
    items = "".join(
        f'<li><a href="{stem}.html"><span class="num">{stem[:2].lstrip("0")}</span>'
        f'<b>{html.escape(title)}</b><span class="desc">{html.escape(lede)}</span></a></li>'
        for stem, title, lede, _, _ in chs)
    body = f"""<main class="narrow">
  <h1>The language</h1>
  <p class="lede">Everything ktm has, one topic per chapter, each with many small programs you can run.
  Read them in order the first time; later, use them as a reference.</p>
  <ol class="toc">{items}</ol>
</main>"""
    write("language/index.html", shell("Language", body, "../", "language",
                                       desc="The ktm language, one topic per chapter."))


def guides_index(gs):
    items = "".join(
        f'<li><a href="{stem}.html"><span class="num">{stem[:2].lstrip("0")}</span>'
        f'<b>{html.escape(title)}</b><span class="desc">{html.escape(lede)}</span></a></li>'
        for stem, title, lede, _, _ in gs)
    body = f"""<main class="narrow">
  <h1>Quick Guides</h1>
  <p class="lede">One task each, from an empty directory to a program that works, with every file
  shown whole. Each guide stands on its own.</p>
  <ol class="toc">{items}</ol>
</main>"""
    write("guides/index.html", shell("Quick Guides", body, "../", "guides",
                                     desc="Quick guides: one task each, from start to a working program."))


def std_pages(index):
    mods = gen_std.modules()
    # a module's intro, docs-src/std/<module>.md, when there is one
    for m in mods:
        path = os.path.join(SRC, "std", m["name"] + ".md")
        m["intro"] = md.convert(open(path).read())[2] if os.path.exists(path) else ""
        for d in m["decls"]:
            short = d["name"].split(".")[-1]
            index.append({"t": d["name"] if m["name"] != "prelude" else short,
                          "p": m["name"], "u": f"std/index.html#{m['name']}/{d['name']}", "k": d["kind"]})
        index.append({"t": m["name"], "p": m["title"], "u": f"std/index.html#{m['name']}", "k": "module"})
    data = {"version": VERSION, "modules": mods,
            "source": "https://github.com/ozzenerol/ktm-lang/blob/main/src/std/"}
    write("std/data.js", "window.KTM_STD = " + json.dumps(data, separators=(",", ":")) + ";\n")
    body = """<div class="std" id="std">
  <button class="side-toggle" type="button" data-side="std-left" data-text="Modules" data-label="modules" aria-expanded="true">&#8249; Modules</button>
  <aside class="std-left" aria-label="Modules">
    <input id="std-filter" type="search" placeholder="Find a function, type or module" aria-label="Find in the library" autocomplete="off">
    <nav id="std-modules"></nav>
    <ul id="std-found" class="std-found" hidden></ul>
  </aside>
  <main id="std-main" class="std-main"></main>
  <button class="side-toggle side-toggle-r" type="button" data-side="std-right" data-text="Names" data-label="this module's names" aria-expanded="true">Names &#8250;</button>
  <aside id="std-right" class="std-right" aria-label="In this module"></aside>
</div>
<script src="data.js"></script>
<script src="../assets/std.js"></script>"""
    write("std/index.html", shell("Library", body, "../", "std", wide=True,
                                  desc="The ktm standard library: every module, type and function."))
    return sum(len(m["decls"]) for m in mods)


# Links of the old site's pages (releases, known issues and the roadmap
# came from it) to where the same thing is now; a page that has no new
# place (the guides, the tools pages) loses its link and keeps its text.
OLD_LINKS = {
    "lang-variables.html": "language/03-variables.html",
    "lang-control-flow.html": "language/05-control-flow.html",
    "lang-functions.html": "language/07-functions.html",
    "language.html#autodefer": "language/16-defer-and-scoped.html",
    "language.html#generics": "language/17-generics.html",
    "language.html#overloading": "language/18-overloading.html",
    "lib-crypto.html": "std/index.html#crypto",
    "lib-http.html": "std/index.html#net.http",
    "lib-logging.html": "std/index.html#log",
    "lib-tls.html": "std/index.html#net.tls",
    "library.html#atomics": "std/index.html#atomic",
    "library.html#directories": "std/index.html#fs",
}
KEEP = {"releases.html", "known-issues.html", "roadmap.html", "index.html"}


def old_links(frag):
    def fix(m):
        href, text = m.group(1), m.group(2)
        page = href.split("#")[0]
        if page in KEEP:
            return m.group(0)
        new = OLD_LINKS.get(href) or OLD_LINKS.get(page)
        if new and "#" in href and "#" not in new and href.split("#")[0] == page and page.startswith("lang-"):
            new += "#" + href.split("#", 1)[1]
        return f'<a href="{new}">{text}</a>' if new else text
    return re.sub(r'<a href="([a-z][a-z0-9-]*\.html(?:#[^"]*)?)">(.*?)</a>', fix, frag, flags=re.S)


def page_from(name, title, section, desc):
    frag = old_links(open(os.path.join(SRC, "pages", name)).read())
    write(name, shell(title, f'<main class="narrow prose">\n{frag}\n</main>', "", section, desc=desc))


def main():
    if os.path.isdir(OUT):
        shutil.rmtree(OUT)
    os.makedirs(OUT)
    shutil.copytree(os.path.join(SRC, "assets"), os.path.join(OUT, "assets"))
    open(os.path.join(OUT, ".nojekyll"), "w").close()
    index = []
    chs = chapters()
    chapter_pages(chs, index)
    language_index(chs)
    gs = chapters("guides")
    chapter_pages(gs, index, "guides", "Quick Guides")
    guides_index(gs)
    n = std_pages(index)
    home = open(os.path.join(SRC, "pages", "index.html")).read().replace("{VERSION}", VERSION)
    write("index.html", shell("ktm", home, "", "", desc="ktm, a compiled systems language for servers and tools."))
    page_from("releases.html", "Releases", "releases", "What changed in each version of ktm.")
    page_from("known-issues.html", "Known issues", "", "The bugs ktm has today, and how to work around them.")
    page_from("roadmap.html", "Roadmap", "", "What ktm's standard library and packages have, and what comes next.")
    write("assets/search.js", "window.KTM_SEARCH = " + json.dumps(index, separators=(",", ":")) + ";\n")
    print(f"wrote {OUT}: {len(chs)} chapters, {len(gs)} guides, {n} library declarations, {len(index)} search entries")
    texts = [(stem, title, lede, open(os.path.join(SRC, "language", stem + ".md")).read())
             for stem, title, lede, _, _ in chs]
    pages = man.write(ROOT, texts, gen_std.modules(), VERSION)
    print(f"wrote {os.path.join(ROOT, 'man')}: {pages} man pages")
    broken = broken_links()
    for page, href in broken:
        print(f"broken link: {page} -> {href}")
    if broken:
        sys.exit(1)


def broken_links():
    """Links between the site's pages to a file that isn't there"""
    out = []
    for root, _, files in os.walk(OUT):
        for f in files:
            if not f.endswith(".html"):
                continue
            path = os.path.join(root, f)
            for href in re.findall(r'href="([^"#:]+)(?:#[^"]*)?"', open(path).read()):
                if not os.path.exists(os.path.normpath(os.path.join(root, href))):
                    out.append((os.path.relpath(path, OUT), href))
    return sorted(set(out))


if __name__ == "__main__":
    main()
