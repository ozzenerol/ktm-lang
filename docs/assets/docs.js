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
      if (code && (code.classList.contains("language-ktm") || pre.hasAttribute("data-run"))) {
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

  /* ---- sidebar as a menu on narrow screens ---- */
  function setupMenu() {
    var menu = document.querySelector(".menu");
    var side = document.getElementById("sidebar");
    var scrim = document.querySelector(".scrim");
    if (!menu || !side) return;
    function set(open) {
      side.classList.toggle("open", open);
      menu.setAttribute("aria-expanded", open ? "true" : "false");
      if (scrim) scrim.hidden = !open;
      if (open) {
        var first = side.querySelector(".current > a") || side.querySelector("a");
        if (first) first.focus();
      }
    }
    menu.addEventListener("click", function () { set(!side.classList.contains("open")); });
    if (scrim) scrim.addEventListener("click", function () { set(false); });
    side.addEventListener("click", function (e) {
      if (e.target.closest("a")) set(false);
    });
    document.addEventListener("keydown", function (e) {
      if (e.key === "Escape" && side.classList.contains("open")) { set(false); menu.focus(); }
    });
    var cur = side.querySelector(".current");
    if (cur && cur.scrollIntoView) cur.scrollIntoView({ block: "nearest" });
  }

  /* ---- the current section in the sidebar ---- */
  function setupScrollSpy() {
    var links = document.querySelectorAll(".sidebar .toc a");
    if (!links.length || !("IntersectionObserver" in window)) return;
    var byId = {};
    links.forEach(function (a) { byId[a.getAttribute("href").replace(/\?.*?#|^#/, "")] = a; });
    var heads = document.querySelectorAll("main h2[id]");
    var observer = new IntersectionObserver(function (entries) {
      entries.forEach(function (e) {
        if (!e.isIntersecting) return;
        links.forEach(function (l) { l.classList.remove("active"); });
        var a = byId[e.target.id];
        if (a) a.classList.add("active");
      });
    }, { rootMargin: "-60px 0px -70% 0px" });
    heads.forEach(function (h) { observer.observe(h); });
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
    var themeBtn = document.querySelector(".theme");
    if (themeBtn) themeBtn.addEventListener("click", toggleTheme);
    carryTheme();
    setupCode();
    setupMenu();
    setupScrollSpy();
    setupSearch();
  });
})();
