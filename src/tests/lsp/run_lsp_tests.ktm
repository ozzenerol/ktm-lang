/* End-to-end tests for `ktm -lsp` over its real stdio transport,
 * written in ktm and driven by the local build - run via
 * `make lsp-test`, which compiles this with <repo>/ktm (mirroring
 * src/tests/run.ktm: always test what was just built, never an
 * installed ktm).
 *
 * The harness forks the server on a pipe pair, frames JSON-RPC with
 * Content-Length headers, and parses replies with libktm's json.parse/
 * json.select. Every cursor position is COMPUTED from the fixture text
 * with PosOf() - never hardcoded. (Three separate debugging sessions
 * were lost to hand-counted coordinates pointing at whitespace.)
 *
 * Covers, one fresh server per group:
 *   handshake   initialize capabilities, clean shutdown exit code
 *   sync        didOpen publishes once; clean file = 0 diagnostics;
 *               broken file = an error on the right line; didClose
 *               clears
 *   definition  functions, locals (decl line), fields via typed
 *               member chains, #include jump-to-file (plain / ./ /
 *               subdir / missing->null / <builtin> when installed)
 *   hover       function prototype; null off the end of a line
 *   completion  prefix filter; symbols added by a didChange are
 *               visible to an IMMEDIATE completion (flush-before-
 *               query); member completion after `.`/`->` offers
 *               exactly the receiver class's fields (prefix-filtered,
 *               nothing on an untypable receiver, no global noise)
 *   debounce    a keystroke storm publishes ONCE after quiet;
 *               continuous typing still publishes via the max-age cap
 *   survival    a parser-crashing input (function-like macro) yields
 *               diagnostics, not a dead server; queries keep
 *               answering through a crash storm
 *   rename      locals stay in their function/file; globals span the
 *               definition file + callers; fields rename their decl +
 *               typed uses while same-named params survive; collisions
 *               and invalid names error; prepareRename ranges; stdlib
 *               symbols are refused
 *   references  same filtering as rename, read-only: cross-file
 *               Locations, includeDeclaration honored both ways,
 *               stdlib symbols ALLOWED (decl synthesized from
 *               provenance); documentHighlight = one-buffer ranges
 *
 * Allocation discipline: this is a short-lived test process, so
 * allocations are deliberately never freed - keeps the harness free
 * of ownership bookkeeping that could itself be buggy.
 *
 * Stack discipline: buffers bigger than ~1KB live on the heap; the
 * aarch64 backend cannot encode add/sub immediates over 4095, so a
 * huge frame miscompiles at the assembler stage. */

import cstr, fs, json, libc, mem, os, proc;

extern "c" i32 pipe(i32 *fds);
extern "c" i32 fork();
extern "c" i32 dup2(i32 from, i32 to);
extern "c" i32 execv(u8 *path, u8 **argv);
extern "c" i32 close(i32 fd);
extern "c" i32 waitpid(i32 pid, i32 *status, i32 options);
extern "c" i32 usleep(u32 usec);
extern "c" i32 getpid();
extern "c" u8 *strstr(u8 *big, u8 *little);
extern "c" i32 poll(void *fds, u64 nfds, i32 timeout);
extern "c" u8 *realpath(u8 *path, u8 *resolved);
extern "c" i32 access(u8 *path, i32 mode);
extern "c" i64 snprintf(u8 *buf, u64 len, u8 *fmt, ...);
extern "c" i32 creat(u8 *path, u16 mode);
extern "c" i32 fflush(void *stream);
extern "c" void _exit(i32 status);
extern "c" i64 strtol(u8 *str, u8 **end, i32 base);

struct LspPollFd
{ /* struct pollfd */
    i32 fd;
    i16 events;
    i16 revents;
};

const i64 LSP_POLLIN = 0x0001;
const i64 REQ_TIMEOUT_MS = 10000;

i64 gPass;
i64 gFail;
u8 *gDir;  /* fixture dir, realpath'd */
u8 *gKtm;  /* absolute path of the ktm under test */

/* ---------------- fixtures ---------------- */

u8 *LIB_HC;
u8 *DEEP_HC;
u8 *MAIN_HC;
u8 *BAD_INCLUDE_HC;
u8 *BROKEN_HC;
u8 *CRASHER_HC;
u8 *RENAME_HC;
u8 *STD_HC;
u8 *REG_HC;
u8 *UTF_HC;
u8 *MOD_GEO_HC;
u8 *MOD_MAIN_HC;
u8 *MOD_LIST_HC;
u8 *ENUM_HC;
u8 *DOC_HC;
u8 *SIG_HC;
u8 *SEM_HC;
u8 *COMP_HC;
u8 *FOLD_HC;
u8 *MOD_GEO2_HC;
u8 *METH_HC;
u8 *OVL_HC;
u8 *GEN_HC;
u8 *ALIAS_HC;

void initFixtures() {
    LIB_HC =
        "i64 LibAdd(i64 a, i64 b) {\n"
        "  return a + b;\n"
        "}\n"
        "struct LibPoint {\n"
        "  i64 x;\n"
        "  i64 y;\n"
        "};\n";
    DEEP_HC = "i64 deepFn() { return 1; }\n";
    /* A module: geo/geo.ktm, three pub names and a private one */
    MOD_GEO_HC =
        "module geo;\n"
        "pub struct Point { i64 x; i64 y; private i64 tag; };\n"
        "pub const i64 ORIGIN = 0;\n"
        "pub enum Kind { square, circle };\n"
        "i64 square(i64 n) { return n * n; }\n"
        "pub i64 area(i64 side) { return square(side); }\n";
    /* geo's second file: uses geo.ktm's square bare */
    MOD_GEO2_HC =
        "module geo;\n"
        "i64 cube(i64 n) { return square(n) * n; }\n";
    MOD_MAIN_HC =
        "import geo;\n"
        "i32 main() {\n"
        "  i64 a = geo.area(3);\n"
        "  geo.Point p;\n"
        "  i64 b = p.x;\n"
        "  geo.\n"
        "  return 0;\n"
        "}\n";
    /* geo after another module in one import list */
    MOD_LIST_HC =
        "import mem,\n"
        "  g = geo; // the alias\n"
        "i32 main() {\n"
        "  g.Kind k = g.Kind.circle;\n"
        "  return (i32)g.area(2);\n"
        "}\n";
    /* Enums: hover, definition and completion of types and members */
    ENUM_HC =
        "enum Color { red, green = 5 };\n"
        "i32 main() {\n"
        "  Color c = Color.green;\n"
        "  Color d = Color.\n"
        "  return 0;\n"
        "}\n";
    /* Doc comments: a block above, `//` lines above, none, one after */
    DOC_HC =
        "/* Adds two\n"
        " * numbers. */\n"
        "i64 add(i64 a, i64 b) { return a + b; }\n"
        "// Scales n.\n"
        "// Twice.\n"
        "i64 scale(i64 n) { return n * 2; }\n"
        "\n"
        "i64 bare(i64 n) { return n; }\n"
        "struct P {\n"
        "  i64 x; // the x\n"
        "};\n"
        "i32 main() { P p; p.x = add(1, 2) + scale(3) + bare(4); return (i32)p.x; }\n";
    /* Signature help: plain, nested, overloaded, method and variadic calls */
    SIG_HC =
        "/* Adds. */\n"
        "i64 add(i64 a, i64 b) { return a + b; }\n"
        "void show(i64 x) { }\n"
        "void show(u8[] s) { }\n"
        "i64 total(i64 ...nums) { return nums.len; }\n"
        "struct User { i64 age; };\n"
        "i64 User.older(i64 years, bool loud) { return self.age + years; }\n"
        "i64 note(@caller(\"line\") i64 line, i64 code, bool loud) { return line + code; }\n"
        "i32 main() {\n"
        "  User u;\n"
        "  i64 r = add(1, add(2, 3));\n"
        "  r = note(4, false);\n"
        "  show(\"hi\");\n"
        "  u.older(3, true);\n"
        "  r = total(1, 2, 3);\n"
        "  return (i32)r;\n"
        "}\n";
    /* Semantic tokens: one of each kind */
    SEM_HC =
        "import geo;\n"
        "#define LIMIT 8\n"
        "enum Color { red, green };\n"
        "struct User { i64 age; };\n"
        "const i64 MAX = 4;\n"
        "i64 User.older(i64 years) { return self.age + years; }\n"
        "i32 main() {\n"
        "  User u;\n"
        "  Color c = Color.green;\n"
        "  i64 n = u.older(geo.area(LIMIT)) + MAX;\n"
        "  return (i32)n;\n"
        "}\n";
    /* Completion kinds: modules, enums, keywords; module names in imports */
    COMP_HC =
        "import mem, geo;\n"
        "enum Mode { fast };\n"
        "i32 main() {\n"
        "  ge\n"
        "  Mo\n"
        "  re\n"
        "  return 0;\n"
        "}\n";
    /* Folding ranges and inlay hints */
    FOLD_HC =
        "import str;\n"
        "/* a\n"
        "   b */\n"
        "struct P {\n"
        "  i64 x;\n"
        "};\n"
        "#ifdef __KTM_JIT__\n"
        "#define M 1\n"
        "#else\n"
        "#define M 2\n"
        "#endif\n"
        "i32 main() {\n"
        "  auto n = 3 * 7;\n"
        "  auto s = str.from(\"x\");\n"
        "  i64 arr[] = {1, 2};\n"
        "  for (auto k : arr) {\n"
        "    n += k;\n"
        "  }\n"
        "  s.free();\n"
        "  return (i32)n;\n"
        "}\n";
    /* Type aliases: hover, definition and completion of the alias */
    ALIAS_HC =
        "type Text = u8[];\n"
        "type Score = i64;\n"
        "i32 main() {\n"
        "  Text t = \"hi\";\n"
        "  Score s = t.len;\n"
        "  return s;\n"
        "}\n";
    /* Generics: a struct and a function used with type arguments */
    GEN_HC =
        "struct Box<T> { T v; };\n"
        "T Box<T>.get() { return self.v; }\n"
        "T maxOf<T>(T a, T b) { return a > b ? a : b; }\n"
        "i32 main() {\n"
        "  Box<i64> b = {41};\n"
        "  i64 m = maxOf(b.get(), 2);\n"
        "  return (i32)m;\n"
        "}\n";
    /* Overloads: a call shows and jumps to the one it picks */
    OVL_HC =
        "void show(i64 x) { }\n"
        "void show(u8[] s) { }\n"
        "struct Box { i64 n; };\n"
        "void Box.put(i64 v) { self.n = v; }\n"
        "void Box.put(u8[] s) { self.n = s.len; }\n"
        "i32 main() {\n"
        "  show(1);\n"
        "  show(\"hi\");\n"
        "  Box b = {0};\n"
        "  b.put(\"abc\");\n"
        "  return 0;\n"
        "}\n";
    /* Methods: Admin inherits older, hides describe */
    METH_HC =
        "struct User { u8 *name; i64 age; };\n"
        "struct Admin : User { i64 level; };\n"
        "i64 User.older(i64 years) {\n"
        "  self.\n"
        "  return self.age + years;\n"
        "}\n"
        "void User.describe() { }\n"
        "void Admin.describe() { }\n"
        "i32 main() {\n"
        "  Admin a;\n"
        "  a.\n"
        "  i64 n = a.older(1);\n"
        "  return 0;\n"
        "}\n";
    MAIN_HC =
        "#include \"lib.ktm\"\n"
        "#include \"./lib.ktm\"\n"
        "#include \"sub/deep.ktm\"\n"
        "i32 main() {\n"
        "  i64 total = LibAdd(1, 2);\n"
        "  LibPoint p;\n"
        "  p.x = total;\n"
        "  return p.x;\n"
        "}\n";
    BAD_INCLUDE_HC = "#include \"missing.ktm\"\n";
    BROKEN_HC =
        "i64 main() {\n"
        "  return not_a_symbol;\n"
        "}\n";
    /* Function-like macros used to crash the parser; they are now a
     * compile error. The point is NOT the crash - it is that a hostile
     * input produces diagnostics and a live server. */
    CRASHER_HC =
        "#define F(x) ((x)*2)\n"
        "i64 main() {\n"
        "  return F(2);\n"
        "}\n";
    /* Rename traps: Helper's param is named `x` like LibPoint's field,
     * and `total` must not leak out of Run. */
    RENAME_HC =
        "#include \"lib.ktm\"\n"
        "i64 Helper(i64 x) {\n"
        "  return x + 1;\n"
        "}\n"
        "i64 Run() {\n"
        "  LibPoint p;\n"
        "  i64 total = LibAdd(3, 4);\n"
        "  p.x = total;\n"
        "  return p.x + Helper(total);\n"
        "}\n";
    /* Regressions: hover/completion of locals, fields, macros and
     * default params; stale symbols across edits and files. */
    REG_HC =
        "import json, yaml;\n"
        "#include \"lib.ktm\"\n"
        "#define REG_LIMIT 8\n"
        "i64 Square(i64 n, i64 scale=2)\n"
        "{\n"
        "  return n * n * scale;\n"
        "}\n"
        "i64 Run()\n"
        "{\n"
        "  i64 counter = REG_LIMIT;\n"
        "  LibPoint p;\n"
        "  p.x = Square(counter);\n"
        "  return p.x;\n"
        "}\n"
        "void *Keep(void *arg = null)\n"
        "{\n"
        "  return arg;\n"
        "}\n"
        "void *Use()\n"
        "{\n"
        "  return Keep();\n"
        "}\n"
        "@json @yaml\n"
        "struct RegUser\n"
        "{\n"
        "  @key(\"e-mail\") String email;\n"
        "  @skip i64 hits;\n"
        "  @omitEmpty i64 age;\n"
        "};\n"
        "i64 RegHits(RegUser *u)\n"
        "{\n"
        "  return u.hits + u.age;\n"
        "}\n";
    /* A non-ASCII line: utf-16 columns differ from byte columns. */
    UTF_HC =
        "void main()\n"
        "{\n"
        "  u8 *s = \"héllo\"; i64 target = 1;\n"
        "  target++;\n"
        "}\n";
    STD_HC =
        "import mem;\n\nvoid go() {\n"
        "  u8 *m = mem.alloc(16);\n"
        "  mem.free(m);\n"
        "}\n";
}

/* ---------------- small helpers ---------------- */

void check(u8 *name, bool ok) {
    if (ok) {
        gPass++;
        printf("  \033[0;32mok\033[0;0m %s\n", name);
    } else {
        gFail++;
        printf("  \033[0;31mFAILED\033[0;0m %s\n", name);
    }
}

i64 nowMs() {
    libc.timeval tv;
    libc.gettimeofday(&tv, null);
    return tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

/* 0-based (line, character) of `needle` in `text`, plus `within`
 * characters. Positions always derive from the text so a fixture
 * edit cannot silently point a query at whitespace. */
bool posOf(u8 *text, u8 *needle, i64 within, i64 *line, i64 *chr) {
    u8 *hit = libc.strstr(text, needle);
    if (hit == null) { return false; }
    i64 ln = 0;
    u8 *lineStart = text;
    u8 *p = text;
    while (p < hit) {
        if (*p == '\n') {
            ln++;
            lineStart = p + 1;
        }
        p++;
    }
    *line = ln;
    *chr = hit - lineStart + within;
    return true;
}

/* JSON string-escape `s` (quotes, backslashes, \n \r \t). */
u8 *jsonEscapeText(u8 *s) {
    i64 len = cstr.len(s);
    u8 *out = mem.alloc(len * 2 + 1);
    i64 o = 0;
    for (i64 i = 0; i < len; i++) {
        u8 c = s[i];
        if (c == 0x22 || c == 0x5C) { /* " or \ */
            out[o] = 0x5C;
            o++;
            out[o] = c;
            o++;
        } else if (c == 10) {
            out[o] = 0x5C;
            o++;
            out[o] = 'n';
            o++;
        } else if (c == 13) {
            out[o] = 0x5C;
            o++;
            out[o] = 'r';
            o++;
        } else if (c == 9) {
            out[o] = 0x5C;
            o++;
            out[o] = 't';
            o++;
        } else {
            out[o] = c;
            o++;
        }
    }
    out[o] = 0;
    return out;
}

u8 *uriOf(u8 *rel) {
    u8 *uri = mem.alloc(cstr.len(gDir) + cstr.len(rel) + 16);
    libc.snprintf(uri, cstr.len(gDir) + cstr.len(rel) + 16, "file://%s/%s",
                     gDir, rel);
    return uri;
}

/* ---------------- the client ---------------- */

struct LspClient {
    i32 pid;
    i32 wfd;            /* requests go here (server stdin) */
    i32 rfd;            /* replies come from here (server stdout) */
    u8 *buf;            /* unconsumed transport bytes, NUL-terminated */
    i64 len;
    i64 cap;
    i64 nextId;
    i64 publishes;      /* every publishDiagnostics seen, ever */
    json.Value *lastPublish; /* the most recent one */
};

void lspSendBody(LspClient *l, u8 *body) {
    u8 hdr[64];
    i64 blen = cstr.len(body);
    i64 hlen = libc.snprintf(hdr, 64, "Content-Length: %lld\r\n\r\n", blen);
    libc.write(l.wfd, hdr, hlen);
    libc.write(l.wfd, body, blen);
}

void lspNotify(LspClient *l, u8 *method, u8 *params) {
    i64 cap = cstr.len(method) + cstr.len(params) + 64;
    u8 *body = mem.alloc(cap);
    libc.snprintf(body, cap, "{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":%s}",
                     method, params);
    lspSendBody(l, body);
}

/* One poll+read; true if bytes arrived within timeout_ms. */
bool lspPump(LspClient *l, i64 timeout_ms) {
    LspPollFd pfd;
    pfd.fd = l.rfd;
    pfd.events = LSP_POLLIN;
    pfd.revents = 0;
    if (poll(&pfd, 1, timeout_ms) <= 0) { return false; }
    if (l.cap - l.len < 8193) {
        l.cap = l.cap * 2;
        u8 *bigger = mem.alloc(l.cap);
        mem.copy(bigger, l.buf, l.len);
        l.buf = bigger;
    }
    i64 got = libc.read(l.rfd, l.buf + l.len, 8192);
    if (got <= 0) { return false; }
    l.len += got;
    l.buf[l.len] = 0;
    return true;
}

/* The next complete framed message, parsed - or null. Consumes it
 * from the transport buffer. */
json.Value *lspNextMsg(LspClient *l) {
    u8 *sep = libc.strstr(l.buf, "\r\n\r\n");
    if (sep == null) { return null; }
    u8 *cl = libc.strstr(l.buf, "Content-Length:");
    if (cl == null || cl > sep) { return null; }
    i64 blen = cstr.toInt(cl + 15);
    u8 *body = sep + 4;
    i64 have = l.len - (body - l.buf);
    if (have < blen) { return null; }
    json.Value *msg = json.parse(body[0:blen]);
    i64 consumed = body - l.buf + blen;
    i64 rest = l.len - consumed;
    for (i64 i = 0; i < rest; i++) { l.buf[i] = l.buf[consumed + i]; }
    l.len = rest;
    l.buf[l.len] = 0;
    return msg;
}

/* Next buffered non-publish message; publishes are counted and
 * stashed on the way past. */
json.Value *lspTake(LspClient *l) {
    while (true) {
        json.Value *msg = lspNextMsg(l);
        if (msg == null) { return null; }
        json.Value *method = json.select(msg, ".method:s");
        if (method != null &&
                cstr.cmp(method.str.ptr, "textDocument/publishDiagnostics") == 0) {
            l.publishes++;
            l.lastPublish = msg;
            continue;
        }
        return msg;
    }
}

/* The response to request `id`; publishes arriving first are
 * absorbed. null on timeout. */
json.Value *lspCollectId(LspClient *l, i64 id, i64 timeout_ms) {
    i64 deadline = nowMs() + timeout_ms;
    while (true) {
        json.Value *msg = lspTake(l);
        if (msg != null) {
            json.Value *mid = json.select(msg, ".id:i");
            if (mid != null && mid.intVal == id) { return msg; }
            continue; /* stray response; drop */
        }
        i64 remain = deadline - nowMs();
        if (remain <= 0) { return null; }
        lspPump(l, remain);
    }
}

json.Value *lspRequest(LspClient *l, u8 *method, u8 *params,
                                 i64 timeout_ms=REQ_TIMEOUT_MS) {
    l.nextId++;
    i64 cap = cstr.len(method) + cstr.len(params) + 96;
    u8 *body = mem.alloc(cap);
    libc.snprintf(body, cap,
                     "{\"jsonrpc\":\"2.0\",\"id\":%lld,\"method\":\"%s\",\"params\":%s}",
                     l.nextId, method, params);
    lspSendBody(l, body);
    return lspCollectId(l, l.nextId, timeout_ms);
}

/* Absorb everything the server says for a fixed quiet window - for
 * counting debounced publishes, where returning early would lie. */
void lspSettle(LspClient *l, i64 windowMs) {
    i64 deadline = nowMs() + windowMs;
    while (true) {
        json.Value *stray = lspTake(l);
        if (stray != null) { continue; }
        i64 remain = deadline - nowMs();
        if (remain <= 0) { return; }
        lspPump(l, remain);
    }
}

/* Wait until the running publish count reaches `target`. */
bool lspWaitPublishes(LspClient *l, i64 target, i64 timeout_ms) {
    i64 deadline = nowMs() + timeout_ms;
    while (l.publishes < target) {
        json.Value *stray = lspTake(l);
        if (stray != null) { continue; }
        /* LspTake may just have counted the publish we're waiting for. */
        if (l.publishes >= target) { break; }
        i64 remain = deadline - nowMs();
        if (remain <= 0) { return false; }
        lspPump(l, remain);
    }
    return true;
}

LspClient *lspStart() {
    i32 toChild[2];
    i32 fromChild[2];
    if (libc.pipe(toChild) != 0 || libc.pipe(fromChild) != 0) {
        printf("\033[0;31mFAILED\033[0;0m: pipe()\n");
        os.exit(1);
    }
    i32 pid = libc.fork();
    if (pid == 0) {
        dup2(toChild[0], 0);
        dup2(fromChild[1], 1);
        /* The server logs intentional-fixture errors on stderr; keep the
         * test output clean. O_WRONLY == 1. */
        i32 devnull = libc.open("/dev/null", 1, 0);
        if (devnull >= 0) { dup2(devnull, 2); }
        libc.close(toChild[1]);
        libc.close(fromChild[0]);
        /* the test's prefix (build.sh --test exports it), so the
         * stdlib checks see its modules, not an installed ktm's */
        u8 *args[4];
        args[0] = gKtm;
        args[1] = "-lsp";
        args[2] = null;
        args[3] = null;
        u8 *prefix = libc.getenv("KTM_INSTALL_DIR");
        if (prefix && prefix[0]) { args[2] = cstr.format("--install-dir=%s", prefix); }
        execv(gKtm, args);
        os.exit(127);
    }
    libc.close(toChild[0]);
    libc.close(fromChild[1]);
    LspClient *l = mem.alloc(sizeof(LspClient));
    l.pid = pid;
    l.wfd = toChild[1];
    l.rfd = fromChild[0];
    l.cap = 1 << 16;
    l.buf = mem.alloc(l.cap);
    l.len = 0;
    l.buf[0] = 0;
    l.nextId = 0;
    l.publishes = 0;
    l.lastPublish = null;
    json.Value *resp = lspRequest(l, "initialize", "{}");
    if (resp == null) {
        printf("\033[0;31mFAILED\033[0;0m: no initialize response\n");
        os.exit(1);
    }
    return l;
}

/* shutdown + exit; the server's exit code, or -1 if it died on a
 * signal. */
i64 lspClose(LspClient *l) {
    lspRequest(l, "shutdown", "{}", 5000);
    lspNotify(l, "exit", "{}");
    libc.close(l.wfd);
    libc.close(l.rfd);
    i32 status = 0;
    libc.waitpid(l.pid, &status, 0);
    if (status & 0x7f) { return -1; }
    return (status >> 8) & 0xFF;
}

void lspDidOpen(LspClient *l, u8 *uri, u8 *text) {
    u8 *esc = jsonEscapeText(text);
    i64 cap = cstr.len(esc) + cstr.len(uri) + 128;
    u8 *params = mem.alloc(cap);
    libc.snprintf(params, cap,
                     "{\"textDocument\":{\"uri\":\"%s\",\"languageId\":\"ktm\","
                     "\"version\":1,\"text\":\"%s\"}}",
                     uri, esc);
    lspNotify(l, "textDocument/didOpen", params);
    /* didOpen analyzes immediately - wait for its publish. */
    lspWaitPublishes(l, l.publishes + 1, 5000);
}

void lspDidChange(LspClient *l, u8 *uri, u8 *text) {
    u8 *esc = jsonEscapeText(text);
    i64 cap = cstr.len(esc) + cstr.len(uri) + 128;
    u8 *params = mem.alloc(cap);
    libc.snprintf(params, cap,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"contentChanges\":[{\"text\":\"%s\"}]}",
                     uri, esc);
    lspNotify(l, "textDocument/didChange", params);
}

json.Value *lspPositionRequest(LspClient *l, u8 *method, u8 *uri,
                                                 i64 line, i64 chr) {
    u8 params[512];
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld}}",
                     uri, line, chr);
    return lspRequest(l, method, params);
}

/* MAIN_HC plus one appended declaration - for keystroke storms. */
u8 *mainPlusFn(u8 *name, i64 i) {
    i64 cap = cstr.len(MAIN_HC) + 64;
    u8 *text = mem.alloc(cap);
    libc.snprintf(text, cap, "%s\ni64 %s%lld() { return %lld; }\n",
                     MAIN_HC, name, i, i);
    return text;
}

/* ---------------- response predicates ---------------- */

bool isNullResult(json.Value *resp) {
    if (resp == null) { return false; }
    return json.select(resp, ".result:!") != null;
}

bool locEq(json.Value *resp, u8 *wantUri, i64 wantLine, i64 wantChr,
                     bool checkChr) {
    if (resp == null) { return false; }
    json.Value *u = json.select(resp, ".result.uri:s");
    json.Value *ln = json.select(resp, ".result.range.start.line:i");
    json.Value *ch = json.select(resp, ".result.range.start.character:i");
    if (u == null || ln == null || ch == null) { return false; }
    if (cstr.cmp(u.str.ptr, wantUri) != 0) { return false; }
    if (ln.intVal != wantLine) { return false; }
    if (checkChr && ch.intVal != wantChr) { return false; }
    return true;
}

bool hoverContains(json.Value *resp, u8 *needle) {
    if (resp == null) { return false; }
    json.Value *val = json.select(resp, ".result.contents.value:s");
    if (val == null) { return false; }
    return libc.strstr(val.str.ptr, needle) != null;
}

/* All labels start with `prefix`; `must1`/`must2` are present
 * (must2 may be null). */
bool labelsOk(json.Value *resp, u8 *prefix, u8 *must1, u8 *must2) {
    json.Value *items = json.select(resp, ".result.items:a");
    if (items == null) { return false; }
    bool has1 = false;
    bool has2 = false;
    if (must2 == null) { has2 = true; }
    json.Value *it = items.array;
    while (it != null) {
        json.Value *lab = json.select(it, ".label:s");
        if (lab == null) { return false; }
        if (cstr.cmpLen(lab.str.ptr, prefix, cstr.len(prefix)) != 0) { return false; }
        if (cstr.cmp(lab.str.ptr, must1) == 0) { has1 = true; }
        if (must2 != null && cstr.cmp(lab.str.ptr, must2) == 0) { has2 = true; }
        it = it.next;
    }
    return has1 && has2;
}

/* The `"uri":[TextEdit...]` entry of a rename's WorkspaceEdit, or
 * null when the file has no edits. */
json.Value *renameChangesFor(json.Value *resp, u8 *uri) {
    if (resp == null) { return null; }
    json.Value *changes = json.select(resp, ".result.changes:o");
    if (changes == null) { return null; }
    json.Value *it = changes.object;
    while (it != null) {
        if (it.key.ptr != null && cstr.cmp(it.key.ptr, uri) == 0) { return it; }
        it = it.next;
    }
    return null;
}

i64 renameFileCount(json.Value *resp) {
    if (resp == null) { return -1; }
    json.Value *changes = json.select(resp, ".result.changes:o");
    if (changes == null) { return -1; }
    i64 n = 0;
    json.Value *it = changes.object;
    while (it != null) {
        n++;
        it = it.next;
    }
    return n;
}

i64 jsonArrayCount(json.Value *arr) {
    if (arr == null) { return -1; }
    i64 n = 0;
    json.Value *it = arr.array;
    while (it != null) {
        n++;
        it = it.next;
    }
    return n;
}

bool hasEditAt(json.Value *fileEdits, i64 line, i64 chr) {
    if (fileEdits == null) { return false; }
    json.Value *it = fileEdits.array;
    while (it != null) {
        json.Value *l = json.select(it, ".range.start.line:i");
        json.Value *c = json.select(it, ".range.start.character:i");
        if (l != null && c != null && l.intVal == line && c.intVal == chr) {
            return true;
        }
        it = it.next;
    }
    return false;
}

bool isErrorResult(json.Value *resp) {
    if (resp == null) { return false; }
    return json.select(resp, ".error:o") != null;
}

/* A Location array (references result) contains (uri, line, chr). */
bool hasLocation(json.Value *arr, u8 *uri, i64 line, i64 chr) {
    if (arr == null) { return false; }
    json.Value *it = arr.array;
    while (it != null) {
        json.Value *u = json.select(it, ".uri:s");
        json.Value *l = json.select(it, ".range.start.line:i");
        json.Value *c = json.select(it, ".range.start.character:i");
        if (u != null && l != null && c != null &&
                cstr.cmp(u.str.ptr, uri) == 0 && l.intVal == line && c.intVal == chr) {
            return true;
        }
        it = it.next;
    }
    return false;
}

/* Diagnostics array of the LAST publish is empty. */
bool lastPublishClean(LspClient *l) {
    if (l.lastPublish == null) { return false; }
    json.Value *diags = json.select(l.lastPublish, ".params.diagnostics:a");
    if (diags == null) { return false; }
    return diags.array == null;
}

/* ---------------- test groups ---------------- */

void testHandshake() {
    printf("handshake\n");
    LspClient *l = lspStart();
    json.Value *resp = lspRequest(l, "initialize", "{}");
    json.Value *enc = json.select(resp, ".result.capabilities.positionEncoding:s");
    check("utf-16 positions unless the client offers utf-8",
                enc != null && cstr.cmp(enc.str.ptr, "utf-16") == 0);
    bool ok = false;
    if (resp != null) {
        json.Value *df = json.select(resp, ".result.capabilities.definitionProvider:b");
        json.Value *hv = json.select(resp, ".result.capabilities.hoverProvider:b");
        json.Value *cp = json.select(resp, ".result.capabilities.completionProvider:o");
        if (df != null && df.boolean && hv != null && hv.boolean &&
                cp != null) {
            ok = true;
        }
    }
    check("advertises definition/hover/completion", ok);
    check("clean shutdown exit code", lspClose(l) == 0);
}

void testSync() {
    printf("document sync\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("main.ktm");
    i64 before = l.publishes;
    lspDidOpen(l, uri, MAIN_HC);
    lspSettle(l, 300);
    check("didOpen publishes exactly once", l.publishes - before == 1);
    check("clean file has 0 diagnostics", lastPublishClean(l));

    u8 *buri = uriOf("broken.ktm");
    lspDidOpen(l, buri, BROKEN_HC);
    i64 line;
    i64 chr;
    posOf(BROKEN_HC, "not_a_symbol", 0, &line, &chr);
    bool onLine = false;
    if (l.lastPublish != null) {
        json.Value *sev = json.select(l.lastPublish,
                                                     ".params.diagnostics[0].severity:i");
        json.Value *dl = json.select(l.lastPublish,
                                                    ".params.diagnostics[0].range.start.line:i");
        if (sev != null && sev.intVal == 1 && dl != null && dl.intVal == line) {
            onLine = true;
        }
    }
    check("broken file gets an error diagnostic on the right line", onLine);

    u8 params[512];
    libc.snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", buri);
    lspNotify(l, "textDocument/didClose", params);
    lspWaitPublishes(l, l.publishes + 1, 5000);
    check("didClose clears diagnostics", lastPublishClean(l));
    check("clean shutdown", lspClose(l) == 0);
}

void testDefinition() {
    printf("definition\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("main.ktm");
    u8 *libUri = uriOf("lib.ktm");
    lspDidOpen(l, uri, MAIN_HC);

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;

    /* Symbol: the LibAdd call site -> its defining name in lib.ktm. */
    posOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/definition", uri,
                                                                    line, chr);
    posOf(LIB_HC, "LibAdd", 0, &wantLine, &wantChr);
    check("function resolves into the included file",
                locEq(resp, libUri, wantLine, wantChr, true));

    /* Local: the `total` usage in `p.x = total;` -> its declaration. */
    posOf(MAIN_HC, "total;", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(MAIN_HC, "i64 total", 0, &wantLine, &wantChr);
    check("local resolves to its declaration",
                locEq(resp, uri, wantLine, wantChr, false));

    /* Field: p.x -> the x member of LibPoint in lib.ktm. */
    posOf(MAIN_HC, "p.x = total", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(LIB_HC, "i64 x", 0, &wantLine, &wantChr);
    check("field resolves through the member chain",
                locEq(resp, libUri, wantLine, wantChr, false));

    /* Include jumps: anywhere on the line, to the top of the file. */
    posOf(MAIN_HC, "#include \"lib.ktm\"", 4, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    check("include jump: plain relative", locEq(resp, libUri, 0, 0, true));

    posOf(MAIN_HC, "#include \"./lib.ktm\"", 4, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    check("include jump: dot-slash", locEq(resp, libUri, 0, 0, true));

    posOf(MAIN_HC, "#include \"sub/deep.ktm\"", 4, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    check("include jump: subdirectory",
                locEq(resp, uriOf("sub/deep.ktm"), 0, 0, true));

    /* A missing target answers null - never an identifier fallthrough
     * on the path fragments. */
    u8 *buri = uriOf("bad.ktm");
    lspDidOpen(l, buri, BAD_INCLUDE_HC);
    posOf(BAD_INCLUDE_HC, "missing", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", buri, line, chr);
    check("missing include target answers null", isNullResult(resp));

    check("clean shutdown", lspClose(l) == 0);
}

void testHoverCompletion() {
    printf("hover & completion\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("main.ktm");
    lspDidOpen(l, uri, MAIN_HC);

    i64 line;
    i64 chr;
    posOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover shows the function prototype", hoverContains(resp, "LibAdd"));

    posOf(MAIN_HC, "i64 Main", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, 999);
    check("hover past end of line is null", isNullResult(resp));

    /* Prefix "Lib" - cursor after the 3rd char of the call site. */
    posOf(MAIN_HC, "LibAdd(1, 2)", 3, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("completion is prefix-filtered",
                labelsOk(resp, "Lib", "LibAdd", "LibPoint"));

    /* Flush-before-query: a symbol added by an edit must be visible to
     * an IMMEDIATE completion, no quiet period. */
    i64 cap = cstr.len(MAIN_HC) + 64;
    u8 *withMul = mem.alloc(cap);
    libc.snprintf(withMul, cap, "%s\ni64 LibMul(i64 a, i64 b) { return a * b; }\n",
                     MAIN_HC);
    lspDidChange(l, uri, withMul);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("completion right after an edit sees the new symbol",
                labelsOk(resp, "Lib", "LibMul", null));

    /* Member completion: after `p.` the receiver's fields ARE the
     * namespace - exactly x and y, no global noise. */
    posOf(MAIN_HC, "p.x = total", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    json.Value *items = json.select(resp, ".result.items:a");
    check("member completion after `.` offers exactly the fields",
                jsonArrayCount(items) == 2 && labelsOk(resp, "", "x", "y"));

    posOf(MAIN_HC, "p.x = total", 3, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    items = json.select(resp, ".result.items:a");
    check("member completion respects the typed prefix",
                jsonArrayCount(items) == 1 && labelsOk(resp, "x", "x", null));

    /* Each edit EXTENDS the previous text: the REPL-model Cctrl keeps
     * symbols an edit deletes (with their stale lines), and a stale
     * function whose line overlaps the new one derails the
     * enclosing-function heuristic. Supersets mirror real typing. */
    i64 pcap = cstr.len(withMul) + 128;
    u8 *withPtr = mem.alloc(pcap);
    libc.snprintf(withPtr, pcap, "%sU0 UsePtr(LibPoint *lp) {\n  lp->x = 1;\n}\n",
                     withMul);
    lspDidChange(l, uri, withPtr);
    posOf(withPtr, "lp->x", 4, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    items = json.select(resp, ".result.items:a");
    check("member completion after `->`",
                jsonArrayCount(items) == 2 && labelsOk(resp, "", "x", "y"));

    /* An untypable receiver offers NOTHING, never the global soup. */
    u8 *withBad = mem.alloc(pcap + 32);
    libc.snprintf(withBad, pcap + 32, "%sU0 Zz() {\n  zz.\n}\n", withPtr);
    lspDidChange(l, uri, withBad);
    posOf(withBad, "zz.", 3, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    items = json.select(resp, ".result.items:a");
    check("member completion on an untypable receiver offers nothing",
                jsonArrayCount(items) == 0);
    check("clean shutdown", lspClose(l) == 0);
}

void testDebounce() {
    printf("debounce\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("main.ktm");
    lspDidOpen(l, uri, MAIN_HC);

    /* 15 keystrokes 20ms apart: all inside the idle window, done well
     * before the max-age cap -> exactly one analysis. */
    i64 before = l.publishes;
    for (i64 i = 0; i < 15; i++) {
        lspDidChange(l, uri, mainPlusFn("S", i));
        usleep(20000);
    }
    lspSettle(l, 1000);
    check("keystroke storm publishes exactly once",
                l.publishes - before == 1);

    /* ~1.5s of continuous typing, never quiet: the max-age cap must
     * force diagnostics through mid-storm, and the last edit must get
     * its own. The last edit adds an undefined name, so the final
     * publish is recognisable: on a slow host a cap-forced analysis can
     * land just before or just after the storm ends, so how many
     * publishes follow it (0 to 2) is timing, not behaviour. */
    before = l.publishes;
    for (i64 i = 0; i < 36; i++) {
        u8 *text = mainPlusFn("C", i);
        if (i == 35) {
            text = cstr.format("%si64 FinalEdit() { return notDefinedInFinalEdit; }\n", text);
        }
        lspDidChange(l, uri, text);
        lspSettle(l, 40);
    }
    i64 mid = l.publishes - before;
    before = l.publishes;
    lspSettle(l, 1000);
    i64 tail = l.publishes - before;
    u8 *last = l.lastPublish ? json.toStr(l.lastPublish).take() : null;
    bool finalSeen = last && libc.strstr(last, "notDefinedInFinalEdit") != null;
    check("continuous typing still publishes via the max-age cap",
                mid >= 1 && tail <= 2 && finalSeen);
    if (!(mid >= 1 && tail <= 2 && finalSeen)) {
        printf("    mid=%d tail=%d final edit published=%d\n", mid, tail, finalSeen);
    }
    check("clean shutdown", lspClose(l) == 0);
}

void testSurvival() {
    printf("crash survival\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("main.ktm");
    u8 *curi = uriOf("crash.ktm");
    lspDidOpen(l, uri, MAIN_HC);

    /* A hostile document: diagnostics of SOME kind, never silence. */
    i64 before = l.publishes;
    lspDidOpen(l, curi, CRASHER_HC);
    check("hostile input produces diagnostics", l.publishes - before >= 1);

    /* A crash storm; the canary contains it and features keep serving
     * from the last good parse. */
    for (i64 i = 0; i < 10; i++) {
        i64 cap = cstr.len(CRASHER_HC) + 32;
        u8 *text = mem.alloc(cap);
        libc.snprintf(text, cap, "%s\ni64 K%lld();\n", CRASHER_HC, i);
        lspDidChange(l, curi, text);
    }
    lspSettle(l, 1300);

    i64 line;
    i64 chr;
    posOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover still answers after a crash storm",
                hoverContains(resp, "LibAdd"));
    check("server survives to shut down cleanly", lspClose(l) == 0);
}

void testRename() {
    printf("rename\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("rename.ktm");
    u8 *libUri = uriOf("lib.ktm");
    lspDidOpen(l, uri, RENAME_HC);

    i64 line;
    i64 chr;
    i64 dline;
    i64 dchr;
    u8 params[512];

    /* Local: decl + both uses, ONE file, Helper untouched. */
    posOf(RENAME_HC, "p.x = total", 6, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"newName\":\"sum\"}", uri, line, chr);
    json.Value *resp = lspRequest(l, "textDocument/rename", params);
    json.Value *edits = renameChangesFor(resp, uri);
    posOf(RENAME_HC, "i64 total", 4, &dline, &dchr);
    check("local rename: decl + both uses in one file",
                renameFileCount(resp) == 1 && jsonArrayCount(edits) == 3 &&
                hasEditAt(edits, dline, dchr));

    /* Global: the definition in lib.ktm + the call here. */
    posOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"newName\":\"LibSum\"}", uri, line, chr);
    resp = lspRequest(l, "textDocument/rename", params);
    edits = renameChangesFor(resp, uri);
    json.Value *libEdits = renameChangesFor(resp, libUri);
    posOf(LIB_HC, "LibAdd", 0, &dline, &dchr);
    check("global rename spans the definition file and the caller",
                renameFileCount(resp) == 2 && jsonArrayCount(edits) == 1 &&
                jsonArrayCount(libEdits) == 1 && hasEditAt(libEdits, dline, dchr));

    /* Field: both p.x uses + the decl in lib.ktm; Helper's param `x`
     * and its use MUST survive. */
    posOf(RENAME_HC, "p.x = total", 2, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"newName\":\"px\"}", uri, line, chr);
    resp = lspRequest(l, "textDocument/rename", params);
    edits = renameChangesFor(resp, uri);
    libEdits = renameChangesFor(resp, libUri);
    posOf(LIB_HC, "i64 x;", 4, &dline, &dchr);
    check("field rename: typed uses + declaration, params spared",
                jsonArrayCount(edits) == 2 && jsonArrayCount(libEdits) == 1 &&
                hasEditAt(libEdits, dline, dchr));

    /* Renaming ONTO something that exists is refused. */
    posOf(RENAME_HC, "p.x = total", 6, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"newName\":\"p\"}", uri, line, chr);
    resp = lspRequest(l, "textDocument/rename", params);
    check("rename onto an existing local errors", isErrorResult(resp));

    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"newName\":\"9bad\"}", uri, line, chr);
    resp = lspRequest(l, "textDocument/rename", params);
    check("invalid new name errors", isErrorResult(resp));

    /* prepareRename: a range + placeholder on a symbol, null off one. */
    posOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/prepareRename", uri, line, chr);
    json.Value *ph = json.select(resp, ".result.placeholder:s");
    check("prepareRename yields the symbol's range",
                ph != null && cstr.cmp(ph.str.ptr, "LibAdd") == 0);
    resp = lspPositionRequest(l, "textDocument/prepareRename", uri, 0, 0);
    check("prepareRename on a non-symbol is null", isNullResult(resp));

    /* Stdlib guard: mem.alloc is defined in the stdlib's module mem */
    {
        u8 *suri = uriOf("std.ktm");
        lspDidOpen(l, suri, STD_HC);
        posOf(STD_HC, "alloc(16)", 0, &line, &chr);
        libc.snprintf(params, 512,
                         "{\"textDocument\":{\"uri\":\"%s\"},"
                         "\"position\":{\"line\":%lld,\"character\":%lld},"
                         "\"newName\":\"myAlloc\"}", suri, line, chr);
        resp = lspRequest(l, "textDocument/rename", params);
        check("stdlib symbol rename is refused", isErrorResult(resp));
    }
    check("clean shutdown", lspClose(l) == 0);
}

void testRegressions() {
    printf("regressions\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("reg.ktm");
    lspDidOpen(l, uri, REG_HC);
    check("regression fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    posOf(REG_HC, "Square(counter)", 1, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover names every param, defaults included",
                hoverContains(resp, "Square(i64 n, i64 scale=2)"));

    posOf(REG_HC, "Keep();", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover shows a null default as null",
                hoverContains(resp, "Keep(void *arg=null)"));

    posOf(REG_HC, "counter);", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a local shows its type", hoverContains(resp, "i64 counter"));

    posOf(REG_HC, "x = Square", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field shows class and type",
                hoverContains(resp, "i64 LibPoint.x"));

    posOf(REG_HC, "RegUser *u", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a struct shows its attributes",
                hoverContains(resp, "@json @yaml\nstruct RegUser"));

    posOf(REG_HC, "email;", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field's declaration shows its attributes",
                hoverContains(resp, "@key(\"e-mail\") String RegUser.email"));

    posOf(REG_HC, "hits +", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field access shows @skip",
                hoverContains(resp, "@skip i64 RegUser.hits"));

    posOf(REG_HC, "age;\n}", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field access shows @omitEmpty",
                hoverContains(resp, "@omitEmpty i64 RegUser.age"));

    posOf(REG_HC, "REG_LIMIT;", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a macro shows its value",
                hoverContains(resp, "#define REG_LIMIT 8"));

    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    i64 dline;
    i64 dchr;
    posOf(REG_HC, "REG_LIMIT 8", 0, &dline, &dchr);
    check("definition of a macro jumps to its #define",
                locEq(resp, uri, dline, dchr, true));

    posOf(REG_HC, "counter);", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("completion offers locals", labelsOk(resp, "co", "counter", null));

    /* Rename Square to Cube but keep the call: the old name must not
     * linger in the compiler and hide the error. */
    u8 *edited = cstr.formatTo(null, "%s", REG_HC);
    u8 *at = libc.strstr(edited, "Square(i64");
    mem.copy(at, "Cube  ", 6);
    lspDidChange(l, uri, edited);
    lspSettle(l, 700);
    check("a removed function no longer resolves", !lastPublishClean(l));

    /* Run() lives in reg.ktm, which other.ktm doesn't include. */
    u8 *ouri = uriOf("other.ktm");
    lspDidOpen(l, ouri, "void Other() { Run(); }\n");
    check("open files don't leak symbols into each other",
                !lastPublishClean(l));

    u8 *uuri = uriOf("utf.ktm");
    lspDidOpen(l, uuri, UTF_HC);
    posOf(UTF_HC, "target++", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uuri, line, chr);
    posOf(UTF_HC, "target = 1", 0, &dline, &dchr);
    /* The default client (no positionEncodings) gets utf-16, where the
     * two-byte é is one unit. */
    check("utf-16 columns on a non-ASCII line",
                locEq(resp, uuri, dline, dchr - 1, true));

    lspRequest(l, "shutdown", "null", 5000);
    resp = lspPositionRequest(l, "textDocument/hover", uri, 0, 0);
    check("requests after shutdown are refused", isErrorResult(resp));
    check("clean shutdown", lspClose(l) == 0);
}

void testReferences() {
    printf("references & highlight\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("rename.ktm");
    u8 *libUri = uriOf("lib.ktm");
    lspDidOpen(l, uri, RENAME_HC);

    i64 line;
    i64 chr;
    i64 dline;
    i64 dchr;
    u8 params[512];

    /* Global fn: the definition in lib.ktm + the call here. */
    posOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"context\":{\"includeDeclaration\":true}}", uri, line, chr);
    json.Value *resp = lspRequest(l, "textDocument/references", params);
    json.Value *arr = json.select(resp, ".result:a");
    posOf(LIB_HC, "LibAdd", 0, &dline, &dchr);
    check("references: definition + call site across files",
                jsonArrayCount(arr) == 2 && hasLocation(arr, libUri, dline, dchr));

    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"context\":{\"includeDeclaration\":false}}", uri, line, chr);
    resp = lspRequest(l, "textDocument/references", params);
    arr = json.select(resp, ".result:a");
    check("references: includeDeclaration=false drops the definition",
                jsonArrayCount(arr) == 1 && !hasLocation(arr, libUri, dline, dchr));

    /* Field: decl + both typed uses, the same-named param spared. */
    posOf(RENAME_HC, "p.x = total", 2, &line, &chr);
    libc.snprintf(params, 512,
                     "{\"textDocument\":{\"uri\":\"%s\"},"
                     "\"position\":{\"line\":%lld,\"character\":%lld},"
                     "\"context\":{\"includeDeclaration\":true}}", uri, line, chr);
    resp = lspRequest(l, "textDocument/references", params);
    arr = json.select(resp, ".result:a");
    posOf(LIB_HC, "i64 x;", 4, &dline, &dchr);
    check("references: field decl + typed uses, params spared",
                jsonArrayCount(arr) == 3 && hasLocation(arr, libUri, dline, dchr));

    /* documentHighlight: every occurrence of the local in THIS buffer. */
    posOf(RENAME_HC, "p.x = total", 6, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/documentHighlight", uri,
                                                        line, chr);
    arr = json.select(resp, ".result:a");
    check("documentHighlight: all three occurrences of the local",
                jsonArrayCount(arr) == 3);

    /* Stdlib references ALLOWED (rename refuses these): the use here +
     * the declaration in the module mem. */
    {
        u8 *suri = uriOf("std.ktm");
        lspDidOpen(l, suri, STD_HC);
        posOf(STD_HC, "alloc(16)", 0, &line, &chr);
        libc.snprintf(params, 512,
                         "{\"textDocument\":{\"uri\":\"%s\"},"
                         "\"position\":{\"line\":%lld,\"character\":%lld},"
                         "\"context\":{\"includeDeclaration\":true}}", suri, line, chr);
        resp = lspRequest(l, "textDocument/references", params);
        arr = json.select(resp, ".result:a");
        check("references: stdlib symbol allowed, decl in its module",
                    jsonArrayCount(arr) == 2 && hasLocation(arr, suri, line, chr));
    }
    check("clean shutdown", lspClose(l) == 0);
}

/* ---------------- driver ---------------- */

const i64 LSP_GROUPS = 21;

/* Modules: completion after `geo.` offers geo's pub names only, after
 * `p.` the public fields of geo.Point only, hover
 * and definition on `geo.area` resolve into geo/geo.ktm */
void testModules() {
    printf("modules\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("modmain.ktm");
    u8 *geoUri = uriOf("geo/geo.ktm");
    u8 *moreUri0 = uriOf("geo/more.ktm");
    lspDidOpen(l, uri, MOD_MAIN_HC);

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(MOD_MAIN_HC, "geo.\n", 4, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    json.Value *items = json.select(resp, ".result.items:a");
    check("completion after `geo.` offers exactly geo's pub names",
                jsonArrayCount(items) == 4 && labelsOk(resp, "", "area", "Point"));

    posOf(MOD_MAIN_HC, "p.x;", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    items = json.select(resp, ".result.items:a");
    check("completion after `p.` outside geo leaves out the private field",
                jsonArrayCount(items) == 2 && labelsOk(resp, "", "x", "y"));

    posOf(MOD_MAIN_HC, "area(3)", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on geo.area shows the module's function",
                hoverContains(resp, "geo.area(i64 side)"));

    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(MOD_GEO_HC, "area(i64", 0, &wantLine, &wantChr);
    check("definition of geo.area is in geo/geo.ktm",
                locEq(resp, geoUri, wantLine, wantChr, false));

    /* `import mem, g = geo;` over two lines: g is geo too */
    u8 *listUri = uriOf("modlist.ktm");
    lspDidOpen(l, listUri, MOD_LIST_HC);
    posOf(MOD_LIST_HC, "area(2)", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", listUri, line, chr);
    check("hover on a module named later in an import list",
                hoverContains(resp, "geo.area(i64 side)"));
    resp = lspPositionRequest(l, "textDocument/definition", listUri, line, chr);
    check("definition of a module named later in an import list",
                locEq(resp, geoUri, wantLine, wantChr, false));

    posOf(MOD_LIST_HC, "circle;", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", listUri, line, chr);
    check("hover on a module's enum member shows its value",
                hoverContains(resp, "geo.Kind.circle = 1"));
    resp = lspPositionRequest(l, "textDocument/definition", listUri, line, chr);
    posOf(MOD_GEO_HC, "circle", 0, &wantLine, &wantChr);
    check("definition of a module's enum member is in its enum",
                locEq(resp, geoUri, wantLine, wantChr, true));
    posOf(MOD_LIST_HC, "geo;", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", listUri, line, chr);
    check("hover on a module's name in an import list", hoverContains(resp, "module geo"));
    resp = lspPositionRequest(l, "textDocument/definition", listUri, line, chr);
    check("definition of a module's name lists its files",
                hasLocation(json.select(resp, ".result:a"), geoUri, 0, 0) &&
                hasLocation(json.select(resp, ".result:a"), moreUri0, 0, 0));
    posOf(MOD_LIST_HC, "g.area", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", listUri, line, chr);
    check("hover on an import alias used as a prefix", hoverContains(resp, "module geo"));

    posOf(MOD_LIST_HC, "Kind k", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", listUri, line, chr);
    check("hover on a module's enum shows its members",
                hoverContains(resp, "enum geo.Kind") && hoverContains(resp, "square = 0"));

    /* Inside geo's files its names are bare: `square(n)` in more.ktm
     * is geo.ktm's square */
    u8 *moreUri = uriOf("geo/more.ktm");
    lspDidOpen(l, moreUri, MOD_GEO2_HC);
    check("a module's file opens without errors (it is read as its module)",
                lastPublishClean(l));
    posOf(MOD_GEO2_HC, "square(n)", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", moreUri, line, chr);
    check("hover on a module's own bare name from its other file",
                hoverContains(resp, "i64 geo.square(i64 n)"));
    resp = lspPositionRequest(l, "textDocument/definition", moreUri, line, chr);
    posOf(MOD_GEO_HC, "square(i64", 0, &wantLine, &wantChr);
    check("definition of a module's own bare name from its other file",
                locEq(resp, geoUri, wantLine, wantChr, false));
    check("clean shutdown", lspClose(l) == 0);
}

/* Methods: completion after `a.` offers the fields and the methods
 * (inherited ones, a hidden one once), `self.` inside a method too;
 * hover shows the method without `self`; definition jumps to it */
void testMethods() {
    printf("methods\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("methods.ktm");
    lspDidOpen(l, uri, METH_HC);

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(METH_HC, "a.\n", 2, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    json.Value *items = json.select(resp, ".result.items:a");
    check("completion after `a.` offers fields and methods, describe once",
                jsonArrayCount(items) == 5 && labelsOk(resp, "", "older", "level") &&
                labelsOk(resp, "", "describe", "age"));

    posOf(METH_HC, "self.\n", 5, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    items = json.select(resp, ".result.items:a");
    check("completion after `self.` offers User's fields and methods",
                jsonArrayCount(items) == 4 && labelsOk(resp, "", "age", "older"));

    posOf(METH_HC, "age; }", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field where its struct declares it", hoverContains(resp, "i64 User.age"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    check("definition on a field's declaration is itself", locEq(resp, uri, line, chr, true));

    posOf(METH_HC, "older(1)", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a.older shows the method without self",
                hoverContains(resp, "i64 User.older(i64 years)"));

    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(METH_HC, "older(i64", 0, &wantLine, &wantChr);
    check("definition of a.older is User.older",
                locEq(resp, uri, wantLine, wantChr, true));
    check("clean shutdown", lspClose(l) == 0);
}

void testOverloads() {
    printf("overloads\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("overloads.ktm");
    lspDidOpen(l, uri, OVL_HC);
    check("overload fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(OVL_HC, "show(1)", 1, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on show(1) shows show(i64 x) only",
                hoverContains(resp, "show(i64 x)") && !hoverContains(resp, "u8[]"));

    posOf(OVL_HC, "show(\"hi\")", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on show(\"hi\") shows show(u8[] s)", hoverContains(resp, "show(u8[] s)"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(OVL_HC, "show(u8[]", 0, &wantLine, &wantChr);
    check("definition of show(\"hi\") is show(u8[] s)",
                locEq(resp, uri, wantLine, wantChr, true));

    posOf(OVL_HC, "put(\"abc\")", 1, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on b.put(\"abc\") shows Box.put(u8[] s)",
                hoverContains(resp, "Box.put(u8[] s)") && !hoverContains(resp, "i64 v"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(OVL_HC, "put(u8[]", 0, &wantLine, &wantChr);
    check("definition of b.put(\"abc\") is Box.put(u8[] s)",
                locEq(resp, uri, wantLine, wantChr, true));
    check("clean shutdown", lspClose(l) == 0);
}

void testTypeAlias() {
    printf("type aliases\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("alias.ktm");
    lspDidOpen(l, uri, ALIAS_HC);
    check("alias fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(ALIAS_HC, "Text t", 1, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on an alias shows `type Text = u8[]`", hoverContains(resp, "type Text = u8[]"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(ALIAS_HC, "Text =", 0, &wantLine, &wantChr);
    check("definition of an alias is its `type` declaration",
                locEq(resp, uri, wantLine, wantChr, true));
    posOf(ALIAS_HC, "Score s", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("completion offers aliases", labelsOk(resp, "Sc", "Score", null));
    check("clean shutdown", lspClose(l) == 0);
}

void testGenerics() {
    printf("generics\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("generics.ktm");
    lspDidOpen(l, uri, GEN_HC);
    check("generics fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(GEN_HC, "v; }", 0, &line, &chr);
    json.Value *fresp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a generic struct's field shows it with T", hoverContains(fresp, "T Box<T>.v"));

    posOf(GEN_HC, "get(), 2", 0, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on b.get() shows the instance's method",
                hoverContains(resp, "Box<i64>.get()"));
    posOf(GEN_HC, "maxOf(b", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(GEN_HC, "maxOf<T>", 0, &wantLine, &wantChr);
    check("definition of maxOf(...) is the generic", locEq(resp, uri, wantLine, wantChr, true));
    check("clean shutdown", lspClose(l) == 0);
}

void testEnums() {
    printf("enums\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("enums.ktm");
    lspDidOpen(l, uri, ENUM_HC);

    i64 line;
    i64 chr;
    i64 wantLine;
    i64 wantChr;
    posOf(ENUM_HC, "Color c", 1, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on an enum shows its members and values",
                hoverContains(resp, "enum Color") && hoverContains(resp, "green = 5"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(ENUM_HC, "Color {", 0, &wantLine, &wantChr);
    check("definition of an enum is its declaration",
                locEq(resp, uri, wantLine, wantChr, true));

    posOf(ENUM_HC, "green;", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on Color.green shows its value", hoverContains(resp, "Color.green = 5"));
    resp = lspPositionRequest(l, "textDocument/definition", uri, line, chr);
    posOf(ENUM_HC, "green =", 0, &wantLine, &wantChr);
    check("definition of Color.green is the member", locEq(resp, uri, wantLine, wantChr, true));

    posOf(ENUM_HC, "red,", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a member where it is declared", hoverContains(resp, "Color.red = 0"));

    posOf(ENUM_HC, "Color.\n", 6, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    json.Value *items = json.select(resp, ".result.items:a");
    check("completion after `Color.` offers exactly its members",
                jsonArrayCount(items) == 2 && labelsOk(resp, "", "red", "green"));
    check("clean shutdown", lspClose(l) == 0);
}

void testDocs() {
    printf("doc comments\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("docs.ktm");
    lspDidOpen(l, uri, DOC_HC);
    check("doc comment fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    posOf(DOC_HC, "add(1", 0, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover shows the block comment above a function",
                hoverContains(resp, "add(i64 a, i64 b)") && hoverContains(resp, "Adds two\nnumbers.") &&
                !hoverContains(resp, "/*"));
    posOf(DOC_HC, "scale(3)", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover shows the // lines above a function",
                hoverContains(resp, "Scales n.\nTwice.") && !hoverContains(resp, "//"));
    posOf(DOC_HC, "bare(4)", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover after a blank line shows no comment",
                hoverContains(resp, "bare(i64 n)") && !hoverContains(resp, "Twice"));
    posOf(DOC_HC, "x = add", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/hover", uri, line, chr);
    check("hover on a field shows the comment after it", hoverContains(resp, "the x"));
    check("clean shutdown", lspClose(l) == 0);
}

/* The symbol named `name` in a DocumentSymbol/SymbolInformation array */
json.Value *symNamed(json.Value *arr, u8 *name) {
    if (arr == null) { return null; }
    json.Value *it = arr.array;
    while (it != null) {
        json.Value *n = json.select(it, ".name:s");
        if (n != null && cstr.cmp(n.str.ptr, name) == 0) { return it; }
        it = it.next;
    }
    return null;
}

void testSymbols() {
    printf("symbols\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("symbols.ktm");
    lspDidOpen(l, uri, METH_HC);

    u8 params[512];
    libc.snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", uri);
    json.Value *resp = lspRequest(l, "textDocument/documentSymbol", params);
    json.Value *top = json.select(resp, ".result:a");
    json.Value *user = symNamed(top, "User");
    json.Value *admin = symNamed(top, "Admin");
    json.Value *mainSym = symNamed(top, "main");
    check("outline lists the structs and main at the top",
                user != null && admin != null && mainSym != null &&
                json.select(mainSym, ".kind:i").intVal == 12 &&
                json.select(user, ".kind:i").intVal == 23);
    json.Value *uk = user ? json.select(user, ".children:a") : null;
    check("a struct's fields and methods are its children",
                symNamed(uk, "name") != null && symNamed(uk, "age") != null &&
                symNamed(uk, "older") != null && symNamed(uk, "describe") != null &&
                symNamed(top, "older") == null);
    json.Value *older = symNamed(uk, "older");
    check("a method's range spans its body, its detail has no self",
                older != null && json.select(older, ".range.start.line:i").intVal == 2 &&
                json.select(older, ".range.end.line:i").intVal == 5 &&
                cstr.cmp(json.select(older, ".detail:s").str.ptr, "i64 User.older(i64 years);") == 0);

    resp = lspRequest(l, "workspace/symbol", "{\"query\":\"oldr\"}");
    json.Value *ws = json.select(resp, ".result:a");
    json.Value *wo = symNamed(ws, "older");
    check("workspace symbols match fuzzily and name the container",
                wo != null && cstr.cmp(json.select(wo, ".containerName:s").str.ptr, "User") == 0 &&
                symNamed(ws, "main") == null);
    check("clean shutdown", lspClose(l) == 0);
}

/* The active signature's label and its active parameter's text */
bool sigIs(json.Value *resp, u8 *label, u8 *param) {
    json.Value *as = json.select(resp, ".result.activeSignature:i");
    json.Value *sigs = json.select(resp, ".result.signatures:a");
    if (as == null || sigs == null) { return false; }
    i64 k = as.intVal;
    json.Value *sig = sigs.array;
    while (k > 0 && sig != null) { sig = sig.next; k--; }
    if (sig == null) { return false; }
    json.Value *lab = json.select(sig, ".label:s");
    json.Value *apv = json.select(sig, ".activeParameter:i");
    json.Value *params = json.select(sig, ".parameters:a");
    if (lab == null || apv == null || params == null) { return false; }
    if (cstr.cmp(lab.str.ptr, label) != 0) { return false; }
    i64 ap = apv.intVal;
    json.Value *ps = params.array;
    while (ap > 0 && ps != null) { ps = ps.next; ap--; }
    if (ps == null) { return false; }
    json.Value *range = json.select(ps, ".label:a");
    if (range == null || range.array == null || range.array.next == null) { return false; }
    i64 s0 = range.array.intVal;
    i64 s1 = range.array.next.intVal;
    return s1 - s0 == cstr.len(param) && libc.strncmp(lab.str.ptr + s0, param, s1 - s0) == 0;
}

void testSignatureHelp() {
    printf("signature help\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("sighelp.ktm");
    lspDidOpen(l, uri, SIG_HC);
    check("signature help fixture is clean", lastPublishClean(l));

    i64 line;
    i64 chr;
    posOf(SIG_HC, "add(1, ", 7, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("in add(1, |: the second parameter of add",
                sigIs(resp, "i64 add(i64 a, i64 b)", "i64 b") &&
                json.select(resp, ".result.signatures:a").array != null &&
                json.select(json.select(resp, ".result.signatures:a").array,
                            ".documentation.value:s") != null);
    posOf(SIG_HC, "add(2", 4, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("in a nested call: the inner call's first parameter",
                sigIs(resp, "i64 add(i64 a, i64 b)", "i64 a"));
    posOf(SIG_HC, "show(\"", 5, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("an overloaded call: both overloads, the one it calls active",
                sigIs(resp, "void show(u8[] s)", "u8[] s") &&
                jsonArrayCount(json.select(resp, ".result.signatures:a")) == 2);
    posOf(SIG_HC, "older(3, ", 9, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("a method call: its signature without self",
                sigIs(resp, "i64 User.older(i64 years, bool loud)", "bool loud"));
    posOf(SIG_HC, "note(4, ", 8, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("a @caller parameter: skipped by the argument written",
                sigIs(resp, "i64 note(@caller(\"line\") i64 line, i64 code, bool loud)", "bool loud"));
    posOf(SIG_HC, "total(1, 2, ", 12, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("past a variadic's last parameter: still the variadic one",
                sigIs(resp, "i64 total(i64 ...nums)", "i64 ...nums"));
    posOf(SIG_HC, "User u", 0, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/signatureHelp", uri, line, chr);
    check("outside a call: no signature", isNullResult(resp));
    check("clean shutdown", lspClose(l) == 0);
}

/* The semantic token starting at (line, chr): type * 8 + modifiers, -1
 * when none starts there */
i64 semAt(json.Value *resp, i64 line, i64 chr) {
    json.Value *data = json.select(resp, ".result.data:a");
    if (data == null) { return -1; }
    json.Value *it = data.array;
    i64 l = 0;
    i64 c = 0;
    while (it != null) {
        i64 dl = it.intVal;
        i64 dc = it.next.intVal;
        json.Value *ty = it.next.next.next;
        l += dl;
        if (dl != 0) { c = dc; } else { c += dc; }
        if (l == line && c == chr) { return ty.intVal * 8 + ty.next.intVal; }
        it = ty.next.next;
    }
    return -1;
}

bool semIs(json.Value *resp, u8 *text, u8 *needle, i64 within, i64 type, i64 mods) {
    i64 line;
    i64 chr;
    posOf(text, needle, within, &line, &chr);
    return semAt(resp, line, chr) == type * 8 + mods;
}

void testSemanticTokens() {
    printf("semantic tokens\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("semantic.ktm");
    lspDidOpen(l, uri, SEM_HC);
    check("semantic tokens fixture is clean", lastPublishClean(l));
    u8 params[512];
    libc.snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", uri);
    json.Value *r = lspRequest(l, "textDocument/semanticTokens/full", params);
    /* types: 0 namespace 2 struct 3 enum 4 enumMember 5 function
     * 6 method 7 property 8 parameter 9 variable 10 macro; mods:
     * 1 declaration 2 readonly 4 defaultLibrary */
    check("a module prefix is a namespace", semIs(r, SEM_HC, "geo.area", 0, 0, 0));
    check("a module's function", semIs(r, SEM_HC, "area(LIMIT", 0, 5, 0));
    check("a macro, declared and used", semIs(r, SEM_HC, "LIMIT 8", 0, 10, 1) &&
                semIs(r, SEM_HC, "LIMIT))", 0, 10, 0));
    check("an enum and its members", semIs(r, SEM_HC, "Color {", 0, 3, 1) &&
                semIs(r, SEM_HC, "green;", 0, 4, 2) && semIs(r, SEM_HC, "red,", 0, 4, 3));
    check("a struct and its field", semIs(r, SEM_HC, "User {", 0, 2, 1) &&
                semIs(r, SEM_HC, "age; }", 0, 7, 1) && semIs(r, SEM_HC, "age + years", 0, 7, 0));
    check("a method, declared and called", semIs(r, SEM_HC, "older(i64", 0, 6, 0) &&
                semIs(r, SEM_HC, "older(geo", 0, 6, 0));
    check("a parameter and a local", semIs(r, SEM_HC, "years)", 0, 8, 1) &&
                semIs(r, SEM_HC, "years; }", 0, 8, 0) && semIs(r, SEM_HC, "n = u", 0, 9, 1) &&
                semIs(r, SEM_HC, "(i32)n", 5, 9, 0));
    check("a constant is a readonly variable", semIs(r, SEM_HC, "MAX;", 0, 9, 2));
    check("keywords and builtin types are left to the grammar",
                semIs(r, SEM_HC, "i32 main", 0, 0, 0) == false &&
                semAt(r, 6, 0) == -1);
    /* no token to emit: the reply is still valid JSON (it used to carry a
     * reused buffer's old bytes) */
    u8 *bare = uriOf("bare.ktm");
    lspDidOpen(l, bare, "module bare;\n");
    libc.snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", bare);
    json.Value *e = lspRequest(l, "textDocument/semanticTokens/full", params);
    json.Value *data = e != null ? json.select(e, ".result.data") : null;
    check("a file with no tokens answers an empty data array",
                data != null && jsonArrayCount(data) == 0);
    check("clean shutdown", lspClose(l) == 0);
}

/* The kind of the completion item labelled `label`, -1 when absent */
i64 kindOf(json.Value *resp, u8 *label) {
    json.Value *items = json.select(resp, ".result.items:a");
    if (items == null) { return -1; }
    json.Value *it = items.array;
    while (it != null) {
        json.Value *lab = json.select(it, ".label:s");
        if (lab != null && cstr.cmp(lab.str.ptr, label) == 0) {
            return json.select(it, ".kind:i").intVal;
        }
        it = it.next;
    }
    return -1;
}

void testCompletionKinds() {
    printf("completion kinds\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("compkinds.ktm");
    lspDidOpen(l, uri, COMP_HC);

    i64 line;
    i64 chr;
    posOf(COMP_HC, "ge\n", 2, &line, &chr);
    json.Value *resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("an import prefix completes as a module", kindOf(resp, "geo") == 9);
    posOf(COMP_HC, "Mo\n", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("an enum completes as an enum", kindOf(resp, "Mode") == 13);
    posOf(COMP_HC, "re\n", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("a keyword completes as a keyword, no internal names",
                kindOf(resp, "return") == 14 && labelsOk(resp, "re", "return", null));
    posOf(COMP_HC, "mem,", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("in an import: the standard library's modules", kindOf(resp, "mem") == 9 &&
                labelsOk(resp, "me", "mem", null));
    posOf(COMP_HC, "geo;", 2, &line, &chr);
    resp = lspPositionRequest(l, "textDocument/completion", uri, line, chr);
    check("in an import list: the modules beside the document", kindOf(resp, "geo") == 9);
    check("clean shutdown", lspClose(l) == 0);
}

/* A folding range l0..l1 (of kind, "" for none) is in the answer */
bool hasFold(json.Value *resp, i64 l0, i64 l1, u8 *kind) {
    json.Value *arr = json.select(resp, ".result:a");
    if (arr == null) { return false; }
    json.Value *it = arr.array;
    while (it != null) {
        json.Value *k = json.select(it, ".kind:s");
        if (json.select(it, ".startLine:i").intVal == l0 &&
                json.select(it, ".endLine:i").intVal == l1 &&
                ((k == null && cstr.len(kind) == 0) || (k != null && cstr.cmp(k.str.ptr, kind) == 0))) {
            return true;
        }
        it = it.next;
    }
    return false;
}

/* An inlay hint labelled `label` at the end of `needle` in FOLD_HC */
bool hasHint(json.Value *resp, u8 *needle, u8 *label) {
    i64 line;
    i64 chr;
    posOf(FOLD_HC, needle, cstr.len(needle), &line, &chr);
    json.Value *arr = json.select(resp, ".result:a");
    if (arr == null) { return false; }
    json.Value *it = arr.array;
    while (it != null) {
        if (json.select(it, ".position.line:i").intVal == line &&
                json.select(it, ".position.character:i").intVal == chr &&
                cstr.cmp(json.select(it, ".label:s").str.ptr, label) == 0) {
            return true;
        }
        it = it.next;
    }
    return false;
}

void testFoldingAndHints() {
    printf("folding and inlay hints\n");
    LspClient *l = lspStart();
    u8 *uri = uriOf("folding.ktm");
    lspDidOpen(l, uri, FOLD_HC);
    check("folding fixture is clean", lastPublishClean(l));
    u8 params[512];
    libc.snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", uri);
    json.Value *resp = lspRequest(l, "textDocument/foldingRange", params);
    check("a block comment folds", hasFold(resp, 1, 2, "comment"));
    check("a struct body and a function body fold, the closing line visible",
                hasFold(resp, 3, 4, "") && hasFold(resp, 11, 19, "") && hasFold(resp, 15, 16, ""));
    check("#if ... #else ... #endif fold as regions",
                hasFold(resp, 6, 7, "region") && hasFold(resp, 8, 9, "region"));
    libc.snprintf(params, 512,
                  "{\"textDocument\":{\"uri\":\"%s\"},\"range\":{\"start\":{\"line\":0,"
                  "\"character\":0},\"end\":{\"line\":40,\"character\":0}}}", uri);
    resp = lspRequest(l, "textDocument/inlayHint", params);
    check("auto variables show the type they got",
                hasHint(resp, "auto n", ": i64") && hasHint(resp, "auto s", ": String") &&
                hasHint(resp, "auto k", ": i64") &&
                jsonArrayCount(json.select(resp, ".result:a")) == 3);
    check("clean shutdown", lspClose(l) == 0);
}

void runGroup(i64 group) {
    switch (group) {
        case 0: testHandshake();
        case 1: testSync();
        case 2: testDefinition();
        case 3: testHoverCompletion();
        case 4: testDebounce();
        case 5: testSurvival();
        case 6: testRename();
        case 7: testReferences();
        case 8: testRegressions();
        case 9: testModules();
        case 10: testMethods();
        case 11: testOverloads();
        case 12: testTypeAlias();
        case 13: testGenerics();
        case 14: testEnums();
        case 15: testDocs();
        case 16: testSymbols();
        case 17: testSignatureHelp();
        case 18: testSemanticTokens();
        case 19: testCompletionKinds();
        case 20: testFoldingAndHints();
    }
}

/* The groups each talk to their own server and mostly wait on it, so
 * they run at once, each in a child writing its report to a file; the
 * reports are printed in group order and the counts added up. */
void runGroups() {
    i32 pids[LSP_GROUPS];
    i64 group, pass, fail;
    i32 status;
    u8 path[512];

    for (group = 0; group < LSP_GROUPS; group++) {
        libc.fflush(null);
        pids[group] = libc.fork();
        if (pids[group] == 0) {
            libc.snprintf(path, 512, "%s/report.%lld", gDir, group);
            i32 fd = libc.creat(path, 0x1A4);
            if (fd != -1) {
                dup2(fd, 1);
                libc.close(fd);
            }
            runGroup(group);
            libc.fflush(null);
            libc.snprintf(path, 512, "%s/counts.%lld", gDir, group);
            u8 *counts = cstr.format("%d %d", gPass, gFail);
            fs.writeAll(path, cstr.view(counts));
            _exit(0);
        } else if (pids[group] == -1) {
            runGroup(group);
        }
    }

    for (group = 0; group < LSP_GROUPS; group++) {
        if (pids[group] == -1) { continue; }
        libc.waitpid(pids[group], &status, 0);
        libc.snprintf(path, 512, "%s/report.%lld", gDir, group);
        u8 *report = fs.readAll(path).take();
        if (report) { printf("%s", report); }
        libc.snprintf(path, 512, "%s/counts.%lld", gDir, group);
        u8 *counts = fs.readAll(path).take();
        u8 *end;
        if (counts) {
            pass = strtol(counts, &end, 10);
            fail = strtol(end, &end, 10);
            gPass += pass;
            gFail += fail;
        } else {
            check("test group finished (it crashed or was killed)", false);
        }
    }
}

i32 main() {
    initFixtures();
    gPass = 0;
    gFail = 0;

    /* Test the LOCAL build: this runner lives in src/tests/lsp and is
     * compiled+run from there by `make lsp-test`. */
    gKtm = mem.alloc(1024);
    if (realpath("../../../ktm", gKtm) == null ||
            access(gKtm, 1) != 0) {
        printf("\033[0;31mFAILED\033[0;0m: ../../../ktm not built or not "
        "executable (run `make` first, and run this via `make lsp-test`)\n");
        return 1;
    }

    u8 tmp[128];
    libc.snprintf(tmp, 128, "/tmp/ktm_lsp_test_%d", getpid());
    u8 cmd[256];
    libc.snprintf(cmd, 256, "mkdir -p %s/sub %s/geo", tmp, tmp);
    proc.shell(cmd);
    gDir = mem.alloc(1024);
    realpath(tmp, gDir);

    u8 path[512];
    libc.snprintf(path, 512, "%s/lib.ktm", gDir);
    fs.writeAll(path, cstr.view(LIB_HC));
    libc.snprintf(path, 512, "%s/sub/deep.ktm", gDir);
    fs.writeAll(path, cstr.view(DEEP_HC));
    libc.snprintf(path, 512, "%s/main.ktm", gDir);
    fs.writeAll(path, cstr.view(MAIN_HC));
    libc.snprintf(path, 512, "%s/bad.ktm", gDir);
    fs.writeAll(path, cstr.view(BAD_INCLUDE_HC));
    libc.snprintf(path, 512, "%s/broken.ktm", gDir);
    fs.writeAll(path, cstr.view(BROKEN_HC));
    libc.snprintf(path, 512, "%s/crash.ktm", gDir);
    fs.writeAll(path, cstr.view(CRASHER_HC));
    libc.snprintf(path, 512, "%s/rename.ktm", gDir);
    fs.writeAll(path, cstr.view(RENAME_HC));
    libc.snprintf(path, 512, "%s/std.ktm", gDir);
    fs.writeAll(path, cstr.view(STD_HC));
    libc.snprintf(path, 512, "%s/geo/geo.ktm", gDir);
    fs.writeAll(path, cstr.view(MOD_GEO_HC));
    libc.snprintf(path, 512, "%s/geo/more.ktm", gDir);
    fs.writeAll(path, cstr.view(MOD_GEO2_HC));
    libc.snprintf(path, 512, "%s/modmain.ktm", gDir);
    fs.writeAll(path, cstr.view(MOD_MAIN_HC));
    libc.snprintf(path, 512, "%s/modlist.ktm", gDir);
    fs.writeAll(path, cstr.view(MOD_LIST_HC));

    printf("ktm -lsp tests (%s)\n", gKtm);
    runGroups();

    libc.snprintf(cmd, 256, "rm -rf %s", tmp);
    proc.shell(cmd);

    if (gFail == 0) {
        printf("\033[0;32mlsp: %d/%d passed\033[0;0m\n", gPass, gPass + gFail);
        return 0;
    }
    printf("\033[0;31mlsp: %d/%d passed\033[0;0m\n", gPass, gPass + gFail);
    return 1;
}
