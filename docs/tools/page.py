"""Shared page layout for the generated docs.

page() wraps a page body in the layout: the header (wordmark, search,
theme toggle), the sidebar (every page, grouped by section), the page's
own table of contents and the footer. It gives each h2/h3 an id and an
anchor link, wraps tables so they scroll on narrow screens, and records
the page, its headings and (for the library reference) its declarations
in SEARCH for build.py to write out as the search index."""
import html
import re

# The sidebar, in reading order: (section, [(file, title), ...]). Previous
# and next links follow this order too.
NAV = [
    ("Start", [
        ("index.html", "Home"),
        ("getting-started.html", "Getting started"),
        ("tour.html", "A quick tour"),
        ("from-c.html", "Coming from C"),
    ]),
    ("Language", [
        ("language.html", "Overview"),
        ("lang-basics.html", "Program basics"),
        ("lang-types.html", "Types and literals"),
        ("lang-variables.html", "Variables and constants"),
        ("lang-operators.html", "Operators and casts"),
        ("lang-control-flow.html", "Control flow"),
        ("lang-switch.html", "switch"),
        ("lang-functions.html", "Functions"),
        ("lang-varargs.html", "Variable arguments"),
        ("lang-overloading.html", "Overloading"),
        ("lang-function-pointers.html", "Function pointers"),
        ("lang-pointers.html", "Pointers and arrays"),
        ("lang-slices.html", "Slices"),
        ("lang-strings.html", "Strings"),
        ("lang-structs.html", "Structs and unions"),
        ("lang-methods.html", "Methods and inheritance"),
        ("lang-enums.html", "Enums"),
        ("lang-type-aliases.html", "Type aliases"),
        ("lang-generics.html", "Generics"),
        ("lang-modules.html", "Modules"),
        ("lang-exceptions.html", "Exceptions"),
        ("lang-defer.html", "defer and ad"),
        ("lang-reflection.html", "Reflection"),
        ("lang-attributes.html", "Attributes"),
        ("lang-preprocessor.html", "The preprocessor"),
        ("lang-calling-c.html", "Calling C and assembly"),
    ]),
    ("Library", [
        ("library.html", "Overview"),
        ("lib-memory.html", "Memory"),
        ("lib-strings.html", "Strings"),
        ("lib-text.html", "Text helpers"),
        ("lib-formatting.html", "Format strings"),
        ("lib-cstrings.html", "C strings"),
        ("lib-files.html", "Files"),
        ("lib-directories.html", "Directories and file status"),
        ("lib-csv.html", "CSV"),
        ("lib-shell.html", "Shell commands"),
        ("lib-processes.html", "Processes"),
        ("lib-environment.html", "Environment and exit"),
        ("lib-signals.html", "Signals"),
        ("lib-logging.html", "Logging"),
        ("lib-vectors.html", "Vectors and sorting"),
        ("lib-lists.html", "Lists"),
        ("lib-maps.html", "Hash maps"),
        ("lib-trees.html", "Ordered maps"),
        ("lib-sets.html", "Sets"),
        ("lib-bytes.html", "Byte buffers"),
        ("lib-hashing.html", "Hashing"),
        ("lib-encoding.html", "Base64 and hex"),
        ("lib-crypto.html", "Cryptography"),
        ("lib-uuid.html", "UUIDs"),
        ("lib-json.html", "JSON: reading"),
        ("lib-json-writer.html", "JSON: writing"),
        ("lib-json-structs.html", "JSON: structs"),
        ("lib-yaml.html", "YAML"),
        ("lib-dates.html", "Dates"),
        ("lib-time.html", "Clocks and sleeping"),
        ("lib-maths.html", "Maths"),
        ("lib-tcp.html", "TCP sockets"),
        ("lib-udp.html", "UDP sockets"),
        ("lib-coroutines.html", "Coroutines"),
        ("lib-event-loop.html", "The coroutine loop"),
        ("lib-serve.html", "Serving on every core"),
        ("lib-http.html", "HTTP servers"),
        ("lib-tls.html", "TLS"),
        ("lib-url.html", "URLs"),
        ("lib-threads.html", "Threads and atomics"),
        ("lib-fzf.html", "Fuzzy matching"),
        ("lib-sqlite.html", "SQLite"),
    ]),
    ("Tools", [
        ("tools.html", "The compiler"),
        ("tools-jit-repl.html", "JIT and REPL"),
        ("tools-editors.html", "Editors"),
        ("tools-packages.html", "Packages"),
        ("tools-libraries.html", "Building libraries"),
        ("tools-debugging.html", "Debugging"),
    ]),
    ("Reference", [
        ("stdlib.html", "Library reference"),
        ("examples.html", "Example projects"),
        ("known-issues.html", "Known issues"),
        ("releases.html", "Releases"),
    ]),
]

ORDER = [item for _, items in NAV for item in items]
SECTION = {f: s for s, items in NAV for f, _ in items}

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


def sidebar(filename):
    out = []
    for section, items in NAV:
        here = SECTION.get(filename) == section
        links = []
        for href, label in items:
            if href == filename:
                links.append(f'<li class="current"><a href="{href}" aria-current="page">'
                             f'{html.escape(label)}</a></li>')
            else:
                links.append(f'<li><a href="{href}">{html.escape(label)}</a></li>')
        out.append(f'<details class="group"{" open" if here else ""}>'
                   f'<summary>{html.escape(section)}</summary>'
                   f'<ul>{"".join(links)}</ul></details>')
    return "".join(out)


def toc(heads):
    """The page's h2s, for the "On this page" column."""
    items = [f'<li><a href="#{hid}">{html.escape(text)}</a></li>'
             for tag, hid, text in heads if tag == "h2"]
    if len(items) < 2:
        return ""
    return ('<nav class="onpage" aria-label="On this page"><p>On this page</p><ul>'
            + "".join(items) + "</ul></nav>")


def prev_next(filename):
    """Links to the pages before and after this one in the sidebar's order."""
    names = [href for href, _ in ORDER]
    if filename not in names:
        return ""
    i = names.index(filename)
    links = []
    if i > 0:
        links.append(f'<a class="prev" href="{ORDER[i - 1][0]}"><span>Previous</span>'
                     f'{html.escape(ORDER[i - 1][1])}</a>')
    if i + 1 < len(ORDER):
        links.append(f'<a class="next" href="{ORDER[i + 1][0]}"><span>Next</span>'
                     f'{html.escape(ORDER[i + 1][1])}</a>')
    return '<nav class="prev-next" aria-label="Previous and next page">' + "".join(links) + "</nav>"


def crumbs(filename, refs):
    """The section the page is in, and the reference modules it covers."""
    section = SECTION.get(filename)
    if not section or filename == "index.html":
        return ""
    first = next(f for s, items in NAV if s == section for f, _ in items[:1])
    trail = (f'<a href="{first}">{html.escape(section)}</a>'
             if first != filename else html.escape(section))
    ref = ""
    if refs:
        ref = ('<span class="refs">Reference: ' + " ".join(
            f'<a href="stdlib.html#{m}"><code>{m}</code></a>' for m in refs) + "</span>")
    return f'<div class="crumbs"><span>{trail}</span>{ref}</div>'


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


def page(title, filename, content, refs=None):
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
    onpage = "" if filename in ("index.html", "stdlib.html") else toc(heads)
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
  if (t !== "light") t = "dark";   // dark unless light was chosen
  document.documentElement.setAttribute("data-theme", t);
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
  <a class="wordmark" href="index.html" aria-label="ktm home"><svg class="logo" viewBox="0 0 64 64" aria-hidden="true"><path d="M14 21C8 40 22 56 56 61C39 50 30 36 35 14Z" fill="#d7262e" stroke="#d7262e" stroke-width="2" stroke-linejoin="round"/><path d="M17.5 26C15.5 38 24 49 39 55" fill="none" stroke="#fff" stroke-opacity=".35" stroke-width="2.6" stroke-linecap="round"/><path d="M12 22L37 15L35 8L10 15Z" fill="#e2a72e" stroke="#e2a72e" stroke-width="2.5" stroke-linejoin="round"/><circle cx="22.5" cy="6.8" r="3.3" fill="none" stroke="#e2a72e" stroke-width="2.4"/></svg><span class="logo-name">ktm</span></a>
  <nav class="toplinks" aria-label="Sections">
    <a href="getting-started.html">Start</a>
    <a href="language.html">Language</a>
    <a href="library.html">Library</a>
    <a href="tools.html">Tools</a>
    <a href="stdlib.html">Reference</a>
  </nav>
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
<div class="layout{' wide' if not onpage else ''}">
  <nav id="sidebar" class="sidebar" aria-label="Docs pages">
{sidebar(filename)}
  </nav>
  <div class="scrim" hidden></div>
  <main id="content">
{crumbs(filename, refs)}
{content}
    {prev_next(filename)}
    <footer class="page-foot">
      <p>{edit}. Every example on these pages is compiled and run, ahead of time and in the JIT,
      by <code>docs/tools/check-examples.py</code>.</p>
    </footer>
  </main>
  {onpage}
</div>
</body>
</html>
'''
