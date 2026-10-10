"""Shared page layout for the generated docs.

page() wraps a page body in the layout: the header (wordmark, search,
theme toggle), the sidebar (every page, and the current page's headings)
and the footer. It gives each h2/h3 an id and an anchor link, wraps tables
so they scroll on narrow screens, and records the page, its headings and
(for the library reference) its declarations in SEARCH for build.py to
write out as the search index."""
import html
import re

NAV = [
    ("index.html", "Home"),
    ("getting-started.html", "Getting started"),
    ("language.html", "Language"),
    ("library.html", "Library guide"),
    ("stdlib.html", "Library reference"),
    ("tools.html", "Tools"),
    ("examples.html", "Examples"),
    ("known-issues.html", "Known issues"),
    ("releases.html", "Releases"),
]

REPO = "https://github.com/ozzenerol/ktm-lang"

# Filled by page(): [title, href, kind] rows for the search index.
SEARCH = []

HEADING = re.compile(r"<(h[23])(\s[^>]*)?>(.*?)</\1>", re.S)
TAG = re.compile(r"<[^>]+>")


def slug(text):
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def text_of(fragment):
    return html.unescape(TAG.sub("", fragment)).strip()


def headings(content):
    """Give every h2/h3 an id and an anchor; return the new content and
    the (tag, id, text) list."""
    found = []
    # ids already in the page (the reference's <section id>s) aren't reused
    seen = set(re.findall(r'\sid="([^"]+)"', content))

    def one(m):
        tag, attrs, inner = m.group(1), m.group(2) or "", m.group(3)
        text = text_of(inner)
        mid = re.search(r'\sid="([^"]*)"', attrs)
        if mid:
            hid = mid.group(1)
        else:
            hid = base = slug(text) or "section"
            n = 2
            while hid in seen:
                hid = f"{base}-{n}"
                n += 1
            seen.add(hid)
            attrs += f' id="{hid}"'
        found.append((tag, hid, text))
        return (f'<{tag}{attrs}>{inner}<a class="anchor" href="#{hid}" '
                f'aria-label="Link to this section">#</a></{tag}>')

    return HEADING.sub(one, content), found


def wrap_tables(content):
    return re.sub(r"(<table\b.*?</table>)", r'<div class="table-wrap">\1</div>',
                  content, flags=re.S)


def sidebar(filename, heads):
    items = []
    for href, label in NAV:
        if href != filename:
            items.append(f'<li><a href="{href}">{html.escape(label)}</a></li>')
            continue
        sub = ""
        h2s = [h for h in heads if h[0] == "h2"]
        if h2s:
            sub = '<ul class="toc">' + "".join(
                f'<li><a href="#{hid}">{html.escape(text)}</a></li>'
                for _, hid, text in h2s) + "</ul>"
        items.append(f'<li class="current"><a href="{href}" aria-current="page">'
                     f'{html.escape(label)}</a>{sub}</li>')
    return '<ul class="pages">' + "".join(items) + "</ul>"


def record(title, filename, heads, content):
    SEARCH.append([title, filename, "Page"])
    for _, hid, text in heads:
        SEARCH.append([text, f"{filename}#{hid}", title])
    if filename == "stdlib.html":
        for m in re.finditer(r'<div class="decl" id="([^"]+)">.*?<span class="kind">([^<]*)</span>',
                             content, re.S):
            kind = m.group(2)
            SEARCH.append([m.group(1), f"stdlib.html#{m.group(1)}",
                           kind[:1].upper() + kind[1:]])
        for m in re.finditer(r'<tr id="([^"]+)">', content):
            SEARCH.append([m.group(1), f"stdlib.html#{m.group(1)}", "Constant"])


def page(title, filename, content):
    content, heads = headings(content)
    content = wrap_tables(content)
    record(title, filename, heads, content)
    full_title = "ktm" if filename == "index.html" else f"{title} - ktm"
    if filename == "stdlib.html":
        edit = (f'<a href="{REPO}/blob/main/src/std/std.ktmh">View the source of this page '
                f'(std.ktmh)</a>')
    else:
        edit = f'<a href="{REPO}/edit/main/docs/pages/{filename}">Edit this page</a>'
    body_class = ' class="home"' if filename == "index.html" else ""
    return f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{html.escape(full_title)}</title>
<meta name="color-scheme" content="light dark">
<link rel="icon" type="image/svg+xml" href="assets/favicon.svg">
<link rel="stylesheet" href="assets/style.css">
<script>
(function () {{
  var t = new URLSearchParams(location.search).get("theme");
  try {{ if (!t) t = localStorage.getItem("ktm-theme"); }} catch (e) {{}}
  if (t === "dark" || t === "light") document.documentElement.setAttribute("data-theme", t);
}})();
</script>
<script src="assets/search-index.js" defer></script>
<script src="assets/docs.js" defer></script>
</head>
<body{body_class}>
<a class="skip" href="#content">Skip to content</a>
<header class="topbar">
  <button class="menu" type="button" aria-label="Show the page list" aria-expanded="false" aria-controls="sidebar">
    <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path d="M3 6h18M3 12h18M3 18h18" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
  </button>
  <a class="wordmark" href="index.html">ktm</a>
  <div class="search" role="search">
    <input id="search" type="search" placeholder="Search docs" autocomplete="off" spellcheck="false"
           aria-label="Search the docs" aria-controls="search-results" aria-expanded="false"
           aria-autocomplete="list" role="combobox">
    <kbd class="search-key" aria-hidden="true">/</kbd>
    <ul id="search-results" class="search-results" role="listbox" aria-label="Search results" hidden></ul>
  </div>
  <a class="gh" href="{REPO}">GitHub</a>
  <button class="theme" type="button" aria-label="Switch between light and dark">
    <svg class="sun" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><circle cx="12" cy="12" r="4.5" fill="none" stroke="currentColor" stroke-width="2"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
    <svg class="moon" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>
  </button>
</header>
<div class="layout">
  <nav id="sidebar" class="sidebar" aria-label="Docs pages">
{sidebar(filename, heads)}
  </nav>
  <div class="scrim" hidden></div>
  <main id="content">
{content}
    <footer class="page-foot">
      <p>{edit}. Every example on these pages is compiled and run, ahead of time and in the JIT,
      by <code>docs/tools/check-examples.py</code>.</p>
    </footer>
  </main>
</div>
</body>
</html>
'''
