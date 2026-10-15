(function () {
  var root = document.documentElement;

  /* ---- theme ----
   * Without a choice the system's preference applies (CSS). A choice is
   * saved and carried in ?theme= on links between pages, since pages
   * opened from file:// don't share storage in every browser. */
  function currentTheme() {
    var t = root.getAttribute("data-theme");
    if (t) return t;
    return matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  function carryTheme() {
    var t = root.getAttribute("data-theme");
    document.querySelectorAll("a[href]").forEach(function (a) {
      var href = a.getAttribute("href");
      if (/^[a-z]+:/i.test(href) || href.charAt(0) === "#") return;
      if (!/\.html(\?|#|$)/.test(href)) return;
      var hash = "", i = href.indexOf("#");
      if (i >= 0) { hash = href.slice(i); href = href.slice(0, i); }
      href = href.replace(/\?.*$/, "");
      a.setAttribute("href", href + (t ? "?theme=" + t : "") + hash);
    });
  }

  function toggleTheme() {
    var next = currentTheme() === "dark" ? "light" : "dark";
    root.setAttribute("data-theme", next);
    try { localStorage.setItem("ktm-theme", next); } catch (e) {}
    carryTheme();
  }

  /* ---- ktm syntax highlighting ---- */
  /* The words of src/syntax-highlighting/vim/syntax/ktm.vim; `ad` only
   * where it starts a declaration (see highlight). The HolyC type names
   * are still read by the compiler, so they stay types here. */
  var KEYWORDS = /^(if|else|for|while|do|switch|case|default|break|continue|return|goto|fallthrough|try|catch|throw|defer|struct|union|enum|type|fn|module|import|pub|private|static|const|extern|_extern|inline|volatile|reg|noreg|asm|sizeof|alignof|typeof|self|class)$/;
  var TYPES = /^(void|bool|auto|i8|u8|i16|u16|i32|u32|i64|u64|f32|f64|String|Error|U0|I8|U8|I16|U16|I32|U32|I64|U64|F32|F64|Bool)$/;
  var LITERALS = /^(true|false|null)$/;

  function esc(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }

  function highlight(src) {
    var out = "", i = 0, n = src.length;
    function span(cls, text) { out += '<span class="tok-' + cls + '">' + esc(text) + "</span>"; }
    while (i < n) {
      var c = src[i], rest = src.slice(i), m;
      if (rest.startsWith("//")) {
        var e = src.indexOf("\n", i); if (e < 0) e = n;
        span("com", src.slice(i, e)); i = e;
      } else if (rest.startsWith("/*")) {
        var e2 = src.indexOf("*/", i + 2); e2 = e2 < 0 ? n : e2 + 2;
        span("com", src.slice(i, e2)); i = e2;
      } else if (c === '"' || c === "'") {
        var j = i + 1;
        while (j < n && src[j] !== c && src[j] !== "\n") j += src[j] === "\\" ? 2 : 1;
        span("str", src.slice(i, j + 1)); i = j + 1;
      } else if (c === "#" && /^\s*$/.test(src.slice(src.lastIndexOf("\n", i - 1) + 1, i)) &&
                 (m = /^#\s*[a-z]+/.exec(rest))) {
        span("pp", m[0]); i += m[0].length;
      } else if ((m = /^(0[xX][0-9a-fA-F_]+|0[bB][01_]+|\d[\d_]*\.\d*([eE][+-]?\d+)?|\d[\d_]*([eE][+-]?\d+)?)/.exec(rest)) &&
                 !/[A-Za-z_0-9]/.test(src[i - 1] || "")) {
        span("num", m[0]); i += m[0].length;
      } else if ((m = /^[A-Za-z_]\w*/.exec(rest))) {
        var w = m[0];
        if (src[i - 1] === ".") out += esc(w);  // a field or method: s.type
        else if (KEYWORDS.test(w)) span("kw", w);
        else if (TYPES.test(w)) span("type", w);
        else if (LITERALS.test(w)) span("num", w);
        else if (w === "ad" && /^ad\s+[A-Za-z_][\w.<>, *]*\s[A-Za-z_*]/.test(rest)) span("kw", w);
        else out += esc(w);
        i += w.length;
      } else {
        out += esc(c); i++;
      }
    }
    return out;
  }

  function copyText(text, btn) {
    function done() {
      btn.textContent = "Copied";
      setTimeout(function () { btn.textContent = "Copy"; }, 1200);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(done);
      return;
    }
    var ta = document.createElement("textarea");
    ta.value = text;
    ta.setAttribute("readonly", "");
    ta.style.position = "fixed";
    ta.style.opacity = "0";
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand("copy"); done(); } catch (e) {}
    document.body.removeChild(ta);
  }

  function setupCode() {
    document.querySelectorAll("pre").forEach(function (pre) {
      var code = pre.querySelector("code");
      /* ktm unless the block says otherwise: output, a shell session, or
       * a language-* class naming another language */
      var other = pre.classList.contains("output") || pre.classList.contains("shell") ||
                  (code && /\blanguage-(?!ktm\b)/.test(code.className));
      if (code && !other) {
        code.innerHTML = highlight(code.textContent);
      }
      if (pre.classList.contains("output")) return;
      var btn = document.createElement("button");
      btn.className = "copy";
      btn.type = "button";
      btn.textContent = "Copy";
      btn.setAttribute("aria-label", "Copy the code");
      btn.addEventListener("click", function () { copyText((code || pre).textContent, btn); });
      pre.appendChild(btn);
    });
  }

  /* ---- sidebar: folds away on wide screens, a menu on narrow ones ---- */
  function setupMenu() {
    var menu = document.querySelector(".menu");
    var side = document.getElementById("sidebar");
    var scrim = document.querySelector(".scrim");
    if (!menu || !side) return;
    var narrow = window.matchMedia("(max-width: 52rem)");
    var root = document.documentElement;
    function fold(closed) {
      root.classList.toggle("side-closed", closed);
      menu.setAttribute("aria-expanded", closed ? "false" : "true");
      try { localStorage.setItem("ktm-side", closed ? "closed" : "open"); } catch (e) {}
    }
    if (!narrow.matches) menu.setAttribute("aria-expanded", root.classList.contains("side-closed") ? "false" : "true");
    function set(open) {
      side.classList.toggle("open", open);
      menu.setAttribute("aria-expanded", open ? "true" : "false");
      if (scrim) scrim.hidden = !open;
      if (open) {
        var first = side.querySelector(".current > a") || side.querySelector("a");
        if (first) first.focus();
      }
    }
    menu.addEventListener("click", function () {
      if (narrow.matches) set(!side.classList.contains("open"));
      else fold(!root.classList.contains("side-closed"));
    });
    if (scrim) scrim.addEventListener("click", function () { set(false); });
    side.addEventListener("click", function (e) {
      if (narrow.matches && e.target.closest("a")) set(false);
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && side.classList.contains("open")) { set(false); menu.focus(); }
    });
    var cur = side.querySelector(".current");
    if (cur && cur.scrollIntoView) cur.scrollIntoView({ block: "nearest" });
  }

  /* ---- the section being read, in "On this page" ---- */
  function setupScrollSpy() {
    var links = document.querySelectorAll(".onpage a");
    if (!links.length) return;
    var byId = {};
    links.forEach(function (a) { byId[a.getAttribute("href").replace(/^.*#/, "")] = a; });
    function mark(id) {
      var a = byId[id];
      if (!a) return;
      links.forEach(function (l) { l.classList.remove("active"); });
      a.classList.add("active");
    }
    if (location.hash) mark(decodeURIComponent(location.hash.slice(1)));
    if (!("IntersectionObserver" in window)) return;
    var heads = document.querySelectorAll("main h2[id]");
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) { if (e.isIntersecting) mark(e.target.id); });
    }, { rootMargin: "-60px 0px -70% 0px" });
    heads.forEach(function (h) { observer.observe(h); });
  }

  /* ---- links to the old one-page sections ----
   * language.html, library.html and tools.html used to hold every topic
   * on one long page; a link to one of their old sections goes to the
   * topic's own page now. */
  var MOVED = {
    "language.html": {
      "program-structure": "lang-basics.html", "printing": "lang-basics.html#printing",
      "types": "lang-types.html", "literals": "lang-types.html#literals",
      "variables": "lang-variables.html", "auto": "lang-variables.html#auto",
      "static": "lang-variables.html#static", "const": "lang-variables.html#const",
      "operators": "lang-operators.html", "sizeof-alignof-typeof": "lang-operators.html#sizeof-alignof-typeof",
      "type-casts": "lang-operators.html#casts", "control-flow": "lang-control-flow.html",
      "if-and-else": "lang-control-flow.html#if-and-else", "switch": "lang-switch.html",
      "loops": "lang-control-flow.html#loops", "range-based-for": "lang-control-flow.html#range-based-for",
      "goto": "lang-control-flow.html#goto", "functions": "lang-functions.html",
      "default-arguments": "lang-functions.html#default-arguments", "variable-arguments": "lang-varargs.html",
      "overloading": "lang-overloading.html", "function-pointers": "lang-function-pointers.html",
      "fn-types": "lang-function-pointers.html", "pointers-and-arrays": "lang-pointers.html",
      "heap-memory": "lang-pointers.html#heap-memory", "slices": "lang-slices.html",
      "strings": "lang-strings.html", "c-strings": "lang-strings.html#c-strings",
      "structs": "lang-structs.html", "inheritance": "lang-methods.html#inheritance",
      "methods": "lang-methods.html", "unions": "lang-structs.html#unions", "enums": "lang-enums.html",
      "type-aliases": "lang-type-aliases.html", "attributes": "lang-attributes.html",
      "generics": "lang-generics.html", "modules": "lang-modules.html",
      "preprocessor": "lang-preprocessor.html", "define": "lang-preprocessor.html#define",
      "conditional-compilation": "lang-preprocessor.html#conditional-compilation",
      "include-and-link": "lang-preprocessor.html#include-and-link", "exceptions": "lang-exceptions.html",
      "reflection": "lang-reflection.html", "defer": "lang-defer.html", "autodefer": "lang-defer.html#ad",
      "calling-c": "lang-calling-c.html", "inline-assembly": "lang-calling-c.html#inline-assembly",
      "differences-from-its-origins": "from-c.html"
    },
    "library.html": {
      "memory": "lib-memory.html", "strings": "lib-strings.html", "string": "lib-strings.html",
      "reading-text": "lib-strings.html#reading-text", "case-folding": "lib-text.html#ignoring-case",
      "url-html": "lib-text.html#urls-and-html", "format-conversions": "lib-formatting.html",
      "c-strings": "lib-cstrings.html", "building-c-strings": "lib-cstrings.html",
      "inspecting-c-strings": "lib-cstrings.html", "copying-c-strings": "lib-cstrings.html",
      "splitting-paths-and-names": "lib-text.html#paths-and-file-names", "files": "lib-files.html",
      "directories": "lib-directories.html", "file-status": "lib-directories.html#file-status",
      "csv": "lib-csv.html", "system": "lib-shell.html", "processes": "lib-processes.html",
      "pipelines": "lib-processes.html#pipelines", "environment": "lib-environment.html",
      "signals": "lib-signals.html", "lists": "lib-lists.html", "vectors": "lib-vectors.html",
      "sets": "lib-sets.html", "tree": "lib-trees.html", "hash-tables": "lib-maps.html",
      "bit-vectors": "lib-vectors.html#bit-vectors", "bytes": "lib-bytes.html",
      "hashing": "lib-hashing.html", "json": "lib-json.html", "parsing-and-selecting": "lib-json.html",
      "wildcards-and-getters": "lib-json.html#wildcards", "writing-json-and-freeing": "lib-json-writer.html",
      "building-json-with-a-writer": "lib-json-writer.html", "errors-and-strictness": "lib-json.html#errors",
      "numbers-and-strings": "lib-json.html#numbers", "json-structs": "lib-json-structs.html",
      "json-generated": "lib-json-structs.html#json-and-yaml-attributes", "yaml": "lib-yaml.html",
      "dates": "lib-dates.html", "clocks": "lib-time.html", "maths": "lib-maths.html",
      "net": "lib-tcp.html", "coroutines": "lib-coroutines.html", "coroutine-loop": "lib-event-loop.html",
      "serve": "lib-serve.html", "fzf": "lib-fzf.html", "sqlite": "lib-sqlite.html",
      "atomics": "lib-threads.html#atomics", "more-modules": "lib-threads.html"
    },
    "tools.html": {
      "the-jit": "tools-jit-repl.html", "the-repl": "tools-jit-repl.html#the-repl",
      "editor-support": "tools-editors.html", "neovim": "tools-editors.html#neovim",
      "vim": "tools-editors.html#vim", "other-editors": "tools-editors.html#other-editors",
      "libraries": "tools-libraries.html", "looking-under-the-hood": "tools-debugging.html"
    }
  };

  function followMoved() {
    var id = decodeURIComponent(location.hash.slice(1));
    if (!id) return;
    var page = location.pathname.replace(/^.*\//, "") || "index.html";
    var to = MOVED[page] && MOVED[page][id];
    if (!to || document.getElementById(id)) return;
    var t = root.getAttribute("data-theme");
    if (t) {
      var i = to.indexOf("#");
      to = i < 0 ? to + "?theme=" + t : to.slice(0, i) + "?theme=" + t + to.slice(i);
    }
    location.replace(to);
  }

  /* ---- search ----
   * window.KTM_SEARCH (assets/search-index.js, written by build.py):
   * [text, href, where] for every page, heading and library name. */
  function setupSearch() {
    var input = document.getElementById("search");
    var list = document.getElementById("search-results");
    if (!input || !list) return;
    var index = window.KTM_SEARCH || [];
    var results = [], sel = -1;
    var theme = function () { return root.getAttribute("data-theme"); };

    function score(text, q) {
      var t = text.toLowerCase();
      if (t === q) return 0;
      if (t.indexOf(q) === 0) return 1;
      var at = t.indexOf(q);
      if (at < 0) return -1;
      return /\W/.test(t.charAt(at - 1)) ? 2 : 3;
    }

    function markup(text, q) {
      var at = text.toLowerCase().indexOf(q);
      if (at < 0) return esc(text);
      return esc(text.slice(0, at)) + "<mark>" + esc(text.slice(at, at + q.length)) +
             "</mark>" + esc(text.slice(at + q.length));
    }

    function hrefOf(href) {
      var t = theme();
      if (!t) return href;
      var i = href.indexOf("#");
      return i < 0 ? href + "?theme=" + t : href.slice(0, i) + "?theme=" + t + href.slice(i);
    }

    function close() {
      list.hidden = true;
      input.setAttribute("aria-expanded", "false");
      input.removeAttribute("aria-activedescendant");
      sel = -1;
    }

    function select(i) {
      var items = list.querySelectorAll("li[role=option]");
      if (!items.length) return;
      sel = (i + items.length) % items.length;
      items.forEach(function (li, k) { li.setAttribute("aria-selected", k === sel ? "true" : "false"); });
      input.setAttribute("aria-activedescendant", items[sel].id);
      items[sel].scrollIntoView({ block: "nearest" });
    }

    function render() {
      var q = input.value.trim().toLowerCase();
      if (!q) { close(); return; }
      results = [];
      for (var i = 0; i < index.length; i++) {
        var s = score(index[i][0], q);
        if (s >= 0) results.push([s, index[i][2] === "Page" ? 0 : 1, index[i][0].length, i]);
      }
      results.sort(function (a, b) { return a[0] - b[0] || a[1] - b[1] || a[2] - b[2] || a[3] - b[3]; });
      results = results.slice(0, 12);
      if (!results.length) {
        list.innerHTML = '<li class="none">No results for "' + esc(input.value.trim()) + '"</li>';
      } else {
        list.innerHTML = results.map(function (r, k) {
          var e = index[r[3]];
          return '<li role="option" id="sr-' + k + '" aria-selected="false"><a href="' +
                 esc(hrefOf(e[1])) + '" tabindex="-1"><span class="what">' + markup(e[0], q) +
                 '</span><span class="where">' + esc(e[2]) + "</span></a></li>";
        }).join("");
      }
      list.hidden = false;
      input.setAttribute("aria-expanded", "true");
      sel = -1;
      if (results.length) select(0);
    }

    input.addEventListener("input", render);
    input.addEventListener("focus", function () { if (input.value.trim()) render(); });
    input.addEventListener("keydown", function (e) {
      if (e.key === "ArrowDown") { e.preventDefault(); select(sel + 1); }
      else if (e.key === "ArrowUp") { e.preventDefault(); select(sel - 1); }
      else if (e.key === "Enter") {
        var a = sel >= 0 && list.querySelectorAll("li[role=option] a")[sel];
        if (a) { e.preventDefault(); location.href = a.getAttribute("href"); close(); }
      } else if (e.key === "Escape") {
        if (!list.hidden) close(); else input.blur();
      }
    });
    list.addEventListener("mousedown", function (e) { e.preventDefault(); });
    list.addEventListener("click", function (e) { if (e.target.closest("a")) close(); });
    input.addEventListener("blur", function () { setTimeout(close, 100); });
    document.addEventListener("keydown", function (e) {
      if (e.key !== "/" || e.ctrlKey || e.metaKey || e.altKey) return;
      var t = e.target;
      if (t && (t.tagName === "INPUT" || t.tagName === "TEXTAREA" || t.isContentEditable)) return;
      e.preventDefault();
      input.focus();
      input.select();
    });
  }

  document.addEventListener("DOMContentLoaded", function () {
    followMoved();
    var themeBtn = document.querySelector(".theme");
    if (themeBtn) themeBtn.addEventListener("click", toggleTheme);
    carryTheme();
    setupCode();
    setupMenu();
    setupScrollSpy();
    setupSearch();
  });
})();
