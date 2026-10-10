/* End-to-end tests for `ktm -lsp` over its real stdio transport,
 * written in ktm and driven by the local build - run via
 * `make lsp-test`, which compiles this with <repo>/ktm (mirroring
 * src/tests/run.ktm: always test what was just built, never an
 * installed ktm).
 *
 * The harness forks the server on a pipe pair, frames JSON-RPC with
 * Content-Length headers, and parses replies with libktm's JsonParse/
 * JsonSelect. Every cursor position is COMPUTED from the fixture text
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

extern "c" I32 pipe(I32 *fds);
extern "c" I32 fork();
extern "c" I32 dup2(I32 from, I32 to);
extern "c" I32 execv(U8 *path, U8 **argv);
extern "c" I32 close(I32 fd);
extern "c" I32 waitpid(I32 pid, I32 *status, I32 options);
extern "c" I32 usleep(U32 usec);
extern "c" I32 getpid();
extern "c" U8 *strstr(U8 *big, U8 *little);
extern "c" I32 poll(U0 *fds, U64 nfds, I32 timeout);
extern "c" U8 *realpath(U8 *path, U8 *resolved);
extern "c" I32 access(U8 *path, I32 mode);
extern "c" I64 snprintf(U8 *buf, U64 len, U8 *fmt, ...);
extern "c" I32 creat(U8 *path, U16 mode);
extern "c" I32 fflush(U0 *stream);
extern "c" U0 _exit(I32 status);
extern "c" I64 strtol(U8 *str, U8 **end, I32 base);

class LspPollFd
{ /* struct pollfd */
  I32 fd;
  I16 events;
  I16 revents;
};

#define LSP_POLLIN     0x0001
#define REQ_TIMEOUT_MS 10000

I64 g_pass;
I64 g_fail;
U8 *g_dir;  /* fixture dir, realpath'd */
U8 *g_ktm;  /* absolute path of the ktm under test */

/* ---------------- fixtures ---------------- */

U8 *LIB_HC;
U8 *DEEP_HC;
U8 *MAIN_HC;
U8 *BAD_INCLUDE_HC;
U8 *BROKEN_HC;
U8 *CRASHER_HC;
U8 *RENAME_HC;
U8 *STD_HC;
U8 *REG_HC;
U8 *UTF_HC;

U0 InitFixtures()
{
  LIB_HC =
    "I64 LibAdd(I64 a, I64 b) {\n"
    "  return a + b;\n"
    "}\n"
    "class LibPoint {\n"
    "  I64 x;\n"
    "  I64 y;\n"
    "};\n";
  DEEP_HC = "I64 DeepFn() { return 1; }\n";
  MAIN_HC =
    "#include \"lib.ktm\"\n"
    "#include \"./lib.ktm\"\n"
    "#include \"sub/deep.ktm\"\n"
    "I64 Main() {\n"
    "  I64 total = LibAdd(1, 2);\n"
    "  LibPoint p;\n"
    "  p.x = total;\n"
    "  return p.x;\n"
    "}\n";
  BAD_INCLUDE_HC = "#include \"missing.ktm\"\n";
  BROKEN_HC =
    "I64 Main() {\n"
    "  return not_a_symbol;\n"
    "}\n";
  /* Function-like macros used to crash the parser; they are now a
   * compile error. The point is NOT the crash - it is that a hostile
   * input produces diagnostics and a live server. */
  CRASHER_HC =
    "#define F(x) ((x)*2)\n"
    "I64 Main() {\n"
    "  return F(2);\n"
    "}\n";
  /* Rename traps: Helper's param is named `x` like LibPoint's field,
   * and `total` must not leak out of Run. */
  RENAME_HC =
    "#include \"lib.ktm\"\n"
    "I64 Helper(I64 x) {\n"
    "  return x + 1;\n"
    "}\n"
    "I64 Run() {\n"
    "  LibPoint p;\n"
    "  I64 total = LibAdd(3, 4);\n"
    "  p.x = total;\n"
    "  return p.x + Helper(total);\n"
    "}\n";
  /* Regressions: hover/completion of locals, fields, macros and
   * default params; stale symbols across edits and files. */
  REG_HC =
    "#include \"lib.ktm\"\n"
    "#define REG_LIMIT 8\n"
    "I64 Square(I64 n, I64 scale=2)\n"
    "{\n"
    "  return n * n * scale;\n"
    "}\n"
    "I64 Run()\n"
    "{\n"
    "  I64 counter = REG_LIMIT;\n"
    "  LibPoint p;\n"
    "  p.x = Square(counter);\n"
    "  return p.x;\n"
    "}\n";
  /* A non-ASCII line: utf-16 columns differ from byte columns. */
  UTF_HC =
    "U0 Main()\n"
    "{\n"
    "  U8 *s = \"héllo\"; I64 target = 1;\n"
    "  target++;\n"
    "}\n";
  STD_HC =
    "U0 Go() {\n"
    "  U8 *m = MAlloc(16);\n"
    "  Free(m);\n"
    "}\n";
}

/* ---------------- small helpers ---------------- */

U0 Check(U8 *name, Bool ok)
{
  if (ok) {
    g_pass++;
    "  \033[0;32mok\033[0;0m %s\n", name;
  } else {
    g_fail++;
    "  \033[0;31mFAILED\033[0;0m %s\n", name;
  }
}

I64 NowMs()
{
  timeval tv;
  gettimeofday(&tv, NULL);
  return tv.tv_sec * 1000 + tv.tv_usec / 1000;
}

/* 0-based (line, character) of `needle` in `text`, plus `within`
 * characters. Positions always derive from the text so a fixture
 * edit cannot silently point a query at whitespace. */
Bool PosOf(U8 *text, U8 *needle, I64 within, I64 *line, I64 *chr)
{
  U8 *hit = strstr(text, needle);
  if (hit == NULL) return FALSE;
  I64 ln = 0;
  U8 *line_start = text;
  U8 *p = text;
  while (p < hit) {
    if (*p == '\n') {
      ln++;
      line_start = p + 1;
    }
    p++;
  }
  *line = ln;
  *chr = hit - line_start + within;
  return TRUE;
}

/* JSON string-escape `s` (quotes, backslashes, \n \r \t). */
U8 *JsonEscapeText(U8 *s)
{
  I64 len = StrLen(s);
  U8 *out = MAlloc(len * 2 + 1);
  I64 o = 0;
  for (I64 i = 0; i < len; i++) {
    U8 c = s[i];
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

U8 *UriOf(U8 *rel)
{
  U8 *uri = MAlloc(StrLen(g_dir) + StrLen(rel) + 16);
  snprintf(uri, StrLen(g_dir) + StrLen(rel) + 16, "file://%s/%s",
           g_dir, rel);
  return uri;
}

/* ---------------- the client ---------------- */

class LspClient
{
  I32 pid;
  I32 wfd;            /* requests go here (server stdin) */
  I32 rfd;            /* replies come from here (server stdout) */
  U8 *buf;            /* unconsumed transport bytes, NUL-terminated */
  I64 len;
  I64 cap;
  I64 next_id;
  I64 publishes;      /* every publishDiagnostics seen, ever */
  Json *last_publish; /* the most recent one */
};

U0 LspSendBody(LspClient *l, U8 *body)
{
  U8 hdr[64];
  I64 blen = StrLen(body);
  I64 hlen = snprintf(hdr, 64, "Content-Length: %lld\r\n\r\n", blen);
  Write(l->wfd, hdr, hlen);
  Write(l->wfd, body, blen);
}

U0 LspNotify(LspClient *l, U8 *method, U8 *params)
{
  I64 cap = StrLen(method) + StrLen(params) + 64;
  U8 *body = MAlloc(cap);
  snprintf(body, cap, "{\"jsonrpc\":\"2.0\",\"method\":\"%s\",\"params\":%s}",
           method, params);
  LspSendBody(l, body);
}

/* One poll+read; TRUE if bytes arrived within timeout_ms. */
Bool LspPump(LspClient *l, I64 timeout_ms)
{
  LspPollFd pfd;
  pfd.fd = l->rfd;
  pfd.events = LSP_POLLIN;
  pfd.revents = 0;
  if (poll(&pfd, 1, timeout_ms) <= 0) return FALSE;
  if (l->cap - l->len < 8193) {
    l->cap = l->cap * 2;
    U8 *bigger = MAlloc(l->cap);
    MemCpy(bigger, l->buf, l->len);
    l->buf = bigger;
  }
  I64 got = read(l->rfd, l->buf + l->len, 8192);
  if (got <= 0) return FALSE;
  l->len += got;
  l->buf[l->len] = 0;
  return TRUE;
}

/* The next complete framed message, parsed - or NULL. Consumes it
 * from the transport buffer. */
Json *LspNextMsg(LspClient *l)
{
  U8 *sep = strstr(l->buf, "\r\n\r\n");
  if (sep == NULL) return NULL;
  U8 *cl = strstr(l->buf, "Content-Length:");
  if (cl == NULL || cl > sep) return NULL;
  I64 blen = Atoi(cl + 15);
  U8 *body = sep + 4;
  I64 have = l->len - (body - l->buf);
  if (have < blen) return NULL;
  Json *msg = JsonParse(body, blen);
  I64 consumed = body - l->buf + blen;
  I64 rest = l->len - consumed;
  for (I64 i = 0; i < rest; i++) l->buf[i] = l->buf[consumed + i];
  l->len = rest;
  l->buf[l->len] = 0;
  return msg;
}

/* Next buffered non-publish message; publishes are counted and
 * stashed on the way past. */
Json *LspTake(LspClient *l)
{
  while (TRUE) {
    Json *msg = LspNextMsg(l);
    if (msg == NULL) return NULL;
    Json *method = JsonSelect(msg, ".method:s");
    if (method != NULL &&
        StrCmp(method->str, "textDocument/publishDiagnostics") == 0)
    {
      l->publishes++;
      l->last_publish = msg;
      continue;
    }
    return msg;
  }
}

/* The response to request `id`; publishes arriving first are
 * absorbed. NULL on timeout. */
Json *LspCollectId(LspClient *l, I64 id, I64 timeout_ms)
{
  I64 deadline = NowMs() + timeout_ms;
  while (TRUE) {
    Json *msg = LspTake(l);
    if (msg != NULL) {
      Json *mid = JsonSelect(msg, ".id:i");
      if (mid != NULL && mid->i64 == id) return msg;
      continue; /* stray response; drop */
    }
    I64 remain = deadline - NowMs();
    if (remain <= 0) return NULL;
    LspPump(l, remain);
  }
}

Json *LspRequest(LspClient *l, U8 *method, U8 *params,
                 I64 timeout_ms=REQ_TIMEOUT_MS)
{
  l->next_id++;
  I64 cap = StrLen(method) + StrLen(params) + 96;
  U8 *body = MAlloc(cap);
  snprintf(body, cap,
           "{\"jsonrpc\":\"2.0\",\"id\":%lld,\"method\":\"%s\",\"params\":%s}",
           l->next_id, method, params);
  LspSendBody(l, body);
  return LspCollectId(l, l->next_id, timeout_ms);
}

/* Absorb everything the server says for a fixed quiet window - for
 * counting debounced publishes, where returning early would lie. */
U0 LspSettle(LspClient *l, I64 window_ms)
{
  I64 deadline = NowMs() + window_ms;
  while (TRUE) {
    Json *stray = LspTake(l);
    if (stray != NULL) continue;
    I64 remain = deadline - NowMs();
    if (remain <= 0) return;
    LspPump(l, remain);
  }
}

/* Wait until the running publish count reaches `target`. */
Bool LspWaitPublishes(LspClient *l, I64 target, I64 timeout_ms)
{
  I64 deadline = NowMs() + timeout_ms;
  while (l->publishes < target) {
    Json *stray = LspTake(l);
    if (stray != NULL) continue;
    /* LspTake may just have counted the publish we're waiting for. */
    if (l->publishes >= target) break;
    I64 remain = deadline - NowMs();
    if (remain <= 0) return FALSE;
    LspPump(l, remain);
  }
  return TRUE;
}

LspClient *LspStart()
{
  I32 to_child[2];
  I32 from_child[2];
  if (pipe(to_child) != 0 || pipe(from_child) != 0) {
    "\033[0;31mFAILED\033[0;0m: pipe()\n";
    Exit(1);
  }
  I32 pid = fork();
  if (pid == 0) {
    dup2(to_child[0], 0);
    dup2(from_child[1], 1);
    /* The server logs intentional-fixture errors on stderr; keep the
     * test output clean. O_WRONLY == 1. */
    I32 devnull = open("/dev/null", 1, 0);
    if (devnull >= 0) dup2(devnull, 2);
    close(to_child[1]);
    close(from_child[0]);
    U8 *args[3];
    args[0] = g_ktm;
    args[1] = "-lsp";
    args[2] = NULL;
    execv(g_ktm, args);
    Exit(127);
  }
  close(to_child[0]);
  close(from_child[1]);
  LspClient *l = MAlloc(sizeof(LspClient));
  l->pid = pid;
  l->wfd = to_child[1];
  l->rfd = from_child[0];
  l->cap = 1 << 16;
  l->buf = MAlloc(l->cap);
  l->len = 0;
  l->buf[0] = 0;
  l->next_id = 0;
  l->publishes = 0;
  l->last_publish = NULL;
  Json *resp = LspRequest(l, "initialize", "{}");
  if (resp == NULL) {
    "\033[0;31mFAILED\033[0;0m: no initialize response\n";
    Exit(1);
  }
  return l;
}

/* shutdown + exit; the server's exit code, or -1 if it died on a
 * signal. */
I64 LspClose(LspClient *l)
{
  LspRequest(l, "shutdown", "{}", 5000);
  LspNotify(l, "exit", "{}");
  close(l->wfd);
  close(l->rfd);
  I32 status = 0;
  waitpid(l->pid, &status, 0);
  if (status & 0x7f) return -1;
  return (status >> 8) & 0xFF;
}

U0 LspDidOpen(LspClient *l, U8 *uri, U8 *text)
{
  U8 *esc = JsonEscapeText(text);
  I64 cap = StrLen(esc) + StrLen(uri) + 128;
  U8 *params = MAlloc(cap);
  snprintf(params, cap,
           "{\"textDocument\":{\"uri\":\"%s\",\"languageId\":\"ktm\","
           "\"version\":1,\"text\":\"%s\"}}",
           uri, esc);
  LspNotify(l, "textDocument/didOpen", params);
  /* didOpen analyzes immediately - wait for its publish. */
  LspWaitPublishes(l, l->publishes + 1, 5000);
}

U0 LspDidChange(LspClient *l, U8 *uri, U8 *text)
{
  U8 *esc = JsonEscapeText(text);
  I64 cap = StrLen(esc) + StrLen(uri) + 128;
  U8 *params = MAlloc(cap);
  snprintf(params, cap,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"contentChanges\":[{\"text\":\"%s\"}]}",
           uri, esc);
  LspNotify(l, "textDocument/didChange", params);
}

Json *LspPositionRequest(LspClient *l, U8 *method, U8 *uri,
                         I64 line, I64 chr)
{
  U8 params[512];
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld}}",
           uri, line, chr);
  return LspRequest(l, method, params);
}

/* MAIN_HC plus one appended declaration - for keystroke storms. */
U8 *MainPlusFn(U8 *name, I64 i)
{
  I64 cap = StrLen(MAIN_HC) + 64;
  U8 *text = MAlloc(cap);
  snprintf(text, cap, "%s\nI64 %s%lld() { return %lld; }\n",
           MAIN_HC, name, i, i);
  return text;
}

/* ---------------- response predicates ---------------- */

Bool IsNullResult(Json *resp)
{
  if (resp == NULL) return FALSE;
  return JsonSelect(resp, ".result:!") != NULL;
}

Bool LocEq(Json *resp, U8 *want_uri, I64 want_line, I64 want_chr,
           Bool check_chr)
{
  if (resp == NULL) return FALSE;
  Json *u = JsonSelect(resp, ".result.uri:s");
  Json *ln = JsonSelect(resp, ".result.range.start.line:i");
  Json *ch = JsonSelect(resp, ".result.range.start.character:i");
  if (u == NULL || ln == NULL || ch == NULL) return FALSE;
  if (StrCmp(u->str, want_uri) != 0) return FALSE;
  if (ln->i64 != want_line) return FALSE;
  if (check_chr && ch->i64 != want_chr) return FALSE;
  return TRUE;
}

Bool HoverContains(Json *resp, U8 *needle)
{
  if (resp == NULL) return FALSE;
  Json *val = JsonSelect(resp, ".result.contents.value:s");
  if (val == NULL) return FALSE;
  return strstr(val->str, needle) != NULL;
}

/* All labels start with `prefix`; `must1`/`must2` are present
 * (must2 may be NULL). */
Bool LabelsOk(Json *resp, U8 *prefix, U8 *must1, U8 *must2)
{
  Json *items = JsonSelect(resp, ".result.items:a");
  if (items == NULL) return FALSE;
  Bool has1 = FALSE;
  Bool has2 = FALSE;
  if (must2 == NULL) has2 = TRUE;
  Json *it = items->array;
  while (it != NULL) {
    Json *lab = JsonSelect(it, ".label:s");
    if (lab == NULL) return FALSE;
    if (StrNCmp(lab->str, prefix, StrLen(prefix)) != 0) return FALSE;
    if (StrCmp(lab->str, must1) == 0) has1 = TRUE;
    if (must2 != NULL && StrCmp(lab->str, must2) == 0) has2 = TRUE;
    it = it->next;
  }
  return has1 && has2;
}

/* The `"uri":[TextEdit...]` entry of a rename's WorkspaceEdit, or
 * NULL when the file has no edits. */
Json *RenameChangesFor(Json *resp, U8 *uri)
{
  if (resp == NULL) return NULL;
  Json *changes = JsonSelect(resp, ".result.changes:o");
  if (changes == NULL) return NULL;
  Json *it = changes->object;
  while (it != NULL) {
    if (it->key != NULL && StrCmp(it->key, uri) == 0) return it;
    it = it->next;
  }
  return NULL;
}

I64 RenameFileCount(Json *resp)
{
  if (resp == NULL) return -1;
  Json *changes = JsonSelect(resp, ".result.changes:o");
  if (changes == NULL) return -1;
  I64 n = 0;
  Json *it = changes->object;
  while (it != NULL) {
    n++;
    it = it->next;
  }
  return n;
}

I64 JsonArrayCount(Json *arr)
{
  if (arr == NULL) return -1;
  I64 n = 0;
  Json *it = arr->array;
  while (it != NULL) {
    n++;
    it = it->next;
  }
  return n;
}

Bool HasEditAt(Json *file_edits, I64 line, I64 chr)
{
  if (file_edits == NULL) return FALSE;
  Json *it = file_edits->array;
  while (it != NULL) {
    Json *l = JsonSelect(it, ".range.start.line:i");
    Json *c = JsonSelect(it, ".range.start.character:i");
    if (l != NULL && c != NULL && l->i64 == line && c->i64 == chr) {
      return TRUE;
    }
    it = it->next;
  }
  return FALSE;
}

Bool IsErrorResult(Json *resp)
{
  if (resp == NULL) return FALSE;
  return JsonSelect(resp, ".error:o") != NULL;
}

/* A Location array (references result) contains (uri, line, chr). */
Bool HasLocation(Json *arr, U8 *uri, I64 line, I64 chr)
{
  if (arr == NULL) return FALSE;
  Json *it = arr->array;
  while (it != NULL) {
    Json *u = JsonSelect(it, ".uri:s");
    Json *l = JsonSelect(it, ".range.start.line:i");
    Json *c = JsonSelect(it, ".range.start.character:i");
    if (u != NULL && l != NULL && c != NULL &&
        StrCmp(u->str, uri) == 0 && l->i64 == line && c->i64 == chr)
    {
      return TRUE;
    }
    it = it->next;
  }
  return FALSE;
}

/* Diagnostics array of the LAST publish is empty. */
Bool LastPublishClean(LspClient *l)
{
  if (l->last_publish == NULL) return FALSE;
  Json *diags = JsonSelect(l->last_publish, ".params.diagnostics:a");
  if (diags == NULL) return FALSE;
  return diags->array == NULL;
}

/* ---------------- test groups ---------------- */

U0 TestHandshake()
{
  "handshake\n";
  LspClient *l = LspStart();
  Json *resp = LspRequest(l, "initialize", "{}");
  Json *enc = JsonSelect(resp, ".result.capabilities.positionEncoding:s");
  Check("utf-16 positions unless the client offers utf-8",
        enc != NULL && StrCmp(enc->str, "utf-16") == 0);
  Bool ok = FALSE;
  if (resp != NULL) {
    Json *df = JsonSelect(resp, ".result.capabilities.definitionProvider:b");
    Json *hv = JsonSelect(resp, ".result.capabilities.hoverProvider:b");
    Json *cp = JsonSelect(resp, ".result.capabilities.completionProvider:o");
    if (df != NULL && df->boolean && hv != NULL && hv->boolean &&
        cp != NULL)
    {
      ok = TRUE;
    }
  }
  Check("advertises definition/hover/completion", ok);
  Check("clean shutdown exit code", LspClose(l) == 0);
}

U0 TestSync()
{
  "document sync\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("main.ktm");
  I64 before = l->publishes;
  LspDidOpen(l, uri, MAIN_HC);
  LspSettle(l, 300);
  Check("didOpen publishes exactly once", l->publishes - before == 1);
  Check("clean file has 0 diagnostics", LastPublishClean(l));

  U8 *buri = UriOf("broken.ktm");
  LspDidOpen(l, buri, BROKEN_HC);
  I64 line;
  I64 chr;
  PosOf(BROKEN_HC, "not_a_symbol", 0, &line, &chr);
  Bool on_line = FALSE;
  if (l->last_publish != NULL) {
    Json *sev = JsonSelect(l->last_publish,
                           ".params.diagnostics[0].severity:i");
    Json *dl = JsonSelect(l->last_publish,
                          ".params.diagnostics[0].range.start.line:i");
    if (sev != NULL && sev->i64 == 1 && dl != NULL && dl->i64 == line) {
      on_line = TRUE;
    }
  }
  Check("broken file gets an error diagnostic on the right line", on_line);

  U8 params[512];
  snprintf(params, 512, "{\"textDocument\":{\"uri\":\"%s\"}}", buri);
  LspNotify(l, "textDocument/didClose", params);
  LspWaitPublishes(l, l->publishes + 1, 5000);
  Check("didClose clears diagnostics", LastPublishClean(l));
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestDefinition()
{
  "definition\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("main.ktm");
  U8 *lib_uri = UriOf("lib.ktm");
  LspDidOpen(l, uri, MAIN_HC);

  I64 line;
  I64 chr;
  I64 want_line;
  I64 want_chr;

  /* Symbol: the LibAdd call site -> its defining name in lib.ktm. */
  PosOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
  Json *resp = LspPositionRequest(l, "textDocument/definition", uri,
                                  line, chr);
  PosOf(LIB_HC, "LibAdd", 0, &want_line, &want_chr);
  Check("function resolves into the included file",
        LocEq(resp, lib_uri, want_line, want_chr, TRUE));

  /* Local: the `total` usage in `p.x = total;` -> its declaration. */
  PosOf(MAIN_HC, "total;", 2, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  PosOf(MAIN_HC, "I64 total", 0, &want_line, &want_chr);
  Check("local resolves to its declaration",
        LocEq(resp, uri, want_line, want_chr, FALSE));

  /* Field: p.x -> the x member of LibPoint in lib.ktm. */
  PosOf(MAIN_HC, "p.x = total", 2, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  PosOf(LIB_HC, "I64 x", 0, &want_line, &want_chr);
  Check("field resolves through the member chain",
        LocEq(resp, lib_uri, want_line, want_chr, FALSE));

  /* Include jumps: anywhere on the line, to the top of the file. */
  PosOf(MAIN_HC, "#include \"lib.ktm\"", 4, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  Check("include jump: plain relative", LocEq(resp, lib_uri, 0, 0, TRUE));

  PosOf(MAIN_HC, "#include \"./lib.ktm\"", 4, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  Check("include jump: dot-slash", LocEq(resp, lib_uri, 0, 0, TRUE));

  PosOf(MAIN_HC, "#include \"sub/deep.ktm\"", 4, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  Check("include jump: subdirectory",
        LocEq(resp, UriOf("sub/deep.ktm"), 0, 0, TRUE));

  /* A missing target answers null - never an identifier fallthrough
   * on the path fragments. */
  U8 *buri = UriOf("bad.ktm");
  LspDidOpen(l, buri, BAD_INCLUDE_HC);
  PosOf(BAD_INCLUDE_HC, "missing", 0, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", buri, line, chr);
  Check("missing include target answers null", IsNullResult(resp));

  /* Builtin <std.ktmh>: only when a stdlib is installed to point at. */
  if (access("/usr/local/include/std.ktmh", 0) == 0) {
    I64 cap = StrLen(MAIN_HC) + 32;
    U8 *with_tos = MAlloc(cap);
    snprintf(with_tos, cap, "%s#include <std.ktmh>\n", MAIN_HC);
    LspDidChange(l, uri, with_tos);
    PosOf(with_tos, "#include <std.ktmh>", 11, &line, &chr);
    resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
    Check("include jump: <std.ktmh> via builtin root",
          LocEq(resp, "file:///usr/local/include/std.ktmh", 0, 0, TRUE));
  } else {
    "  (skipped <std.ktmh> jump: no installed stdlib)\n";
  }
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestHoverCompletion()
{
  "hover & completion\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("main.ktm");
  LspDidOpen(l, uri, MAIN_HC);

  I64 line;
  I64 chr;
  PosOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
  Json *resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover shows the function prototype", HoverContains(resp, "LibAdd"));

  PosOf(MAIN_HC, "I64 Main", 0, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/hover", uri, line, 999);
  Check("hover past end of line is null", IsNullResult(resp));

  /* Prefix "Lib" - cursor after the 3rd char of the call site. */
  PosOf(MAIN_HC, "LibAdd(1, 2)", 3, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  Check("completion is prefix-filtered",
        LabelsOk(resp, "Lib", "LibAdd", "LibPoint"));

  /* Flush-before-query: a symbol added by an edit must be visible to
   * an IMMEDIATE completion, no quiet period. */
  I64 cap = StrLen(MAIN_HC) + 64;
  U8 *with_mul = MAlloc(cap);
  snprintf(with_mul, cap, "%s\nI64 LibMul(I64 a, I64 b) { return a * b; }\n",
           MAIN_HC);
  LspDidChange(l, uri, with_mul);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  Check("completion right after an edit sees the new symbol",
        LabelsOk(resp, "Lib", "LibMul", NULL));

  /* Member completion: after `p.` the receiver's fields ARE the
   * namespace - exactly x and y, no global noise. */
  PosOf(MAIN_HC, "p.x = total", 2, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  Json *items = JsonSelect(resp, ".result.items:a");
  Check("member completion after `.` offers exactly the fields",
        JsonArrayCount(items) == 2 && LabelsOk(resp, "", "x", "y"));

  PosOf(MAIN_HC, "p.x = total", 3, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  items = JsonSelect(resp, ".result.items:a");
  Check("member completion respects the typed prefix",
        JsonArrayCount(items) == 1 && LabelsOk(resp, "x", "x", NULL));

  /* Each edit EXTENDS the previous text: the REPL-model Cctrl keeps
   * symbols an edit deletes (with their stale lines), and a stale
   * function whose line overlaps the new one derails the
   * enclosing-function heuristic. Supersets mirror real typing. */
  I64 pcap = StrLen(with_mul) + 128;
  U8 *with_ptr = MAlloc(pcap);
  snprintf(with_ptr, pcap, "%sU0 UsePtr(LibPoint *lp) {\n  lp->x = 1;\n}\n",
           with_mul);
  LspDidChange(l, uri, with_ptr);
  PosOf(with_ptr, "lp->x", 4, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  items = JsonSelect(resp, ".result.items:a");
  Check("member completion after `->`",
        JsonArrayCount(items) == 2 && LabelsOk(resp, "", "x", "y"));

  /* An untypable receiver offers NOTHING, never the global soup. */
  U8 *with_bad = MAlloc(pcap + 32);
  snprintf(with_bad, pcap + 32, "%sU0 Zz() {\n  zz.\n}\n", with_ptr);
  LspDidChange(l, uri, with_bad);
  PosOf(with_bad, "zz.", 3, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  items = JsonSelect(resp, ".result.items:a");
  Check("member completion on an untypable receiver offers nothing",
        JsonArrayCount(items) == 0);
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestDebounce()
{
  "debounce\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("main.ktm");
  LspDidOpen(l, uri, MAIN_HC);

  /* 15 keystrokes 20ms apart: all inside the idle window, done well
   * before the max-age cap -> exactly one analysis. */
  I64 before = l->publishes;
  for (I64 i = 0; i < 15; i++) {
    LspDidChange(l, uri, MainPlusFn("S", i));
    usleep(20000);
  }
  LspSettle(l, 1000);
  Check("keystroke storm publishes exactly once",
        l->publishes - before == 1);

  /* ~1.5s of continuous typing, never quiet: the max-age cap must
   * force diagnostics through mid-storm, and once more at the end. */
  before = l->publishes;
  for (I64 i = 0; i < 36; i++) {
    LspDidChange(l, uri, MainPlusFn("C", i));
    LspSettle(l, 40);
  }
  I64 mid = l->publishes - before;
  before = l->publishes;
  LspSettle(l, 1000);
  I64 tail = l->publishes - before;
  Check("continuous typing still publishes via the max-age cap",
        mid >= 1 && tail == 1);
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestSurvival()
{
  "crash survival\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("main.ktm");
  U8 *curi = UriOf("crash.ktm");
  LspDidOpen(l, uri, MAIN_HC);

  /* A hostile document: diagnostics of SOME kind, never silence. */
  I64 before = l->publishes;
  LspDidOpen(l, curi, CRASHER_HC);
  Check("hostile input produces diagnostics", l->publishes - before >= 1);

  /* A crash storm; the canary contains it and features keep serving
   * from the last good parse. */
  for (I64 i = 0; i < 10; i++) {
    I64 cap = StrLen(CRASHER_HC) + 32;
    U8 *text = MAlloc(cap);
    snprintf(text, cap, "%s\nI64 K%lld();\n", CRASHER_HC, i);
    LspDidChange(l, curi, text);
  }
  LspSettle(l, 1300);

  I64 line;
  I64 chr;
  PosOf(MAIN_HC, "LibAdd(1, 2)", 2, &line, &chr);
  Json *resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover still answers after a crash storm",
        HoverContains(resp, "LibAdd"));
  Check("server survives to shut down cleanly", LspClose(l) == 0);
}

U0 TestRename()
{
  "rename\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("rename.ktm");
  U8 *lib_uri = UriOf("lib.ktm");
  LspDidOpen(l, uri, RENAME_HC);

  I64 line;
  I64 chr;
  I64 dline;
  I64 dchr;
  U8 params[512];

  /* Local: decl + both uses, ONE file, Helper untouched. */
  PosOf(RENAME_HC, "p.x = total", 6, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"newName\":\"sum\"}", uri, line, chr);
  Json *resp = LspRequest(l, "textDocument/rename", params);
  Json *edits = RenameChangesFor(resp, uri);
  PosOf(RENAME_HC, "I64 total", 4, &dline, &dchr);
  Check("local rename: decl + both uses in one file",
        RenameFileCount(resp) == 1 && JsonArrayCount(edits) == 3 &&
        HasEditAt(edits, dline, dchr));

  /* Global: the definition in lib.ktm + the call here. */
  PosOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"newName\":\"LibSum\"}", uri, line, chr);
  resp = LspRequest(l, "textDocument/rename", params);
  edits = RenameChangesFor(resp, uri);
  Json *lib_edits = RenameChangesFor(resp, lib_uri);
  PosOf(LIB_HC, "LibAdd", 0, &dline, &dchr);
  Check("global rename spans the definition file and the caller",
        RenameFileCount(resp) == 2 && JsonArrayCount(edits) == 1 &&
        JsonArrayCount(lib_edits) == 1 && HasEditAt(lib_edits, dline, dchr));

  /* Field: both p.x uses + the decl in lib.ktm; Helper's param `x`
   * and its use MUST survive. */
  PosOf(RENAME_HC, "p.x = total", 2, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"newName\":\"px\"}", uri, line, chr);
  resp = LspRequest(l, "textDocument/rename", params);
  edits = RenameChangesFor(resp, uri);
  lib_edits = RenameChangesFor(resp, lib_uri);
  PosOf(LIB_HC, "I64 x;", 4, &dline, &dchr);
  Check("field rename: typed uses + declaration, params spared",
        JsonArrayCount(edits) == 2 && JsonArrayCount(lib_edits) == 1 &&
        HasEditAt(lib_edits, dline, dchr));

  /* Renaming ONTO something that exists is refused. */
  PosOf(RENAME_HC, "p.x = total", 6, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"newName\":\"p\"}", uri, line, chr);
  resp = LspRequest(l, "textDocument/rename", params);
  Check("rename onto an existing local errors", IsErrorResult(resp));

  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"newName\":\"9bad\"}", uri, line, chr);
  resp = LspRequest(l, "textDocument/rename", params);
  Check("invalid new name errors", IsErrorResult(resp));

  /* prepareRename: a range + placeholder on a symbol, null off one. */
  PosOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/prepareRename", uri, line, chr);
  Json *ph = JsonSelect(resp, ".result.placeholder:s");
  Check("prepareRename yields the symbol's range",
        ph != NULL && StrCmp(ph->str, "LibAdd") == 0);
  resp = LspPositionRequest(l, "textDocument/prepareRename", uri, 0, 0);
  Check("prepareRename on a non-symbol is null", IsNullResult(resp));

  /* Stdlib guard - only when an installed stdlib was bootstrapped. */
  if (access("/usr/local/include/std.ktmh", 0) == 0) {
    U8 *suri = UriOf("std.ktm");
    LspDidOpen(l, suri, STD_HC);
    PosOf(STD_HC, "MAlloc", 0, &line, &chr);
    snprintf(params, 512,
             "{\"textDocument\":{\"uri\":\"%s\"},"
             "\"position\":{\"line\":%lld,\"character\":%lld},"
             "\"newName\":\"MyAlloc\"}", suri, line, chr);
    resp = LspRequest(l, "textDocument/rename", params);
    Check("stdlib symbol rename is refused", IsErrorResult(resp));
  } else {
    "  (skipped stdlib rename guard: no installed stdlib)\n";
  }
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestRegressions()
{
  "regressions\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("reg.ktm");
  LspDidOpen(l, uri, REG_HC);
  Check("regression fixture is clean", LastPublishClean(l));

  I64 line;
  I64 chr;
  PosOf(REG_HC, "Square(counter)", 1, &line, &chr);
  Json *resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover names every param, defaults included",
        HoverContains(resp, "Square(i64 n, i64 scale=2)"));

  PosOf(REG_HC, "counter);", 1, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover on a local shows its type", HoverContains(resp, "i64 counter"));

  PosOf(REG_HC, "x = Square", 0, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover on a field shows class and type",
        HoverContains(resp, "i64 LibPoint.x"));

  PosOf(REG_HC, "REG_LIMIT;", 1, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/hover", uri, line, chr);
  Check("hover on a macro shows its value",
        HoverContains(resp, "#define REG_LIMIT 8"));

  resp = LspPositionRequest(l, "textDocument/definition", uri, line, chr);
  I64 dline;
  I64 dchr;
  PosOf(REG_HC, "REG_LIMIT 8", 0, &dline, &dchr);
  Check("definition of a macro jumps to its #define",
        LocEq(resp, uri, dline, dchr, TRUE));

  PosOf(REG_HC, "counter);", 2, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/completion", uri, line, chr);
  Check("completion offers locals", LabelsOk(resp, "co", "counter", NULL));

  /* Rename Square to Cube but keep the call: the old name must not
   * linger in the compiler and hide the error. */
  U8 *edited = StrPrint(NULL, "%s", REG_HC);
  U8 *at = strstr(edited, "Square(I64");
  MemCpy(at, "Cube  ", 6);
  LspDidChange(l, uri, edited);
  LspSettle(l, 700);
  Check("a removed function no longer resolves", !LastPublishClean(l));

  /* Run() lives in reg.ktm, which other.ktm doesn't include. */
  U8 *ouri = UriOf("other.ktm");
  LspDidOpen(l, ouri, "U0 Other() { Run(); }\n");
  Check("open files don't leak symbols into each other",
        !LastPublishClean(l));

  U8 *uuri = UriOf("utf.ktm");
  LspDidOpen(l, uuri, UTF_HC);
  PosOf(UTF_HC, "target++", 0, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/definition", uuri, line, chr);
  PosOf(UTF_HC, "target = 1", 0, &dline, &dchr);
  /* The default client (no positionEncodings) gets utf-16, where the
   * two-byte é is one unit. */
  Check("utf-16 columns on a non-ASCII line",
        LocEq(resp, uuri, dline, dchr - 1, TRUE));

  LspRequest(l, "shutdown", "null", 5000);
  resp = LspPositionRequest(l, "textDocument/hover", uri, 0, 0);
  Check("requests after shutdown are refused", IsErrorResult(resp));
  Check("clean shutdown", LspClose(l) == 0);
}

U0 TestReferences()
{
  "references & highlight\n";
  LspClient *l = LspStart();
  U8 *uri = UriOf("rename.ktm");
  U8 *lib_uri = UriOf("lib.ktm");
  LspDidOpen(l, uri, RENAME_HC);

  I64 line;
  I64 chr;
  I64 dline;
  I64 dchr;
  U8 params[512];

  /* Global fn: the definition in lib.ktm + the call here. */
  PosOf(RENAME_HC, "LibAdd(3, 4)", 2, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"context\":{\"includeDeclaration\":true}}", uri, line, chr);
  Json *resp = LspRequest(l, "textDocument/references", params);
  Json *arr = JsonSelect(resp, ".result:a");
  PosOf(LIB_HC, "LibAdd", 0, &dline, &dchr);
  Check("references: definition + call site across files",
        JsonArrayCount(arr) == 2 && HasLocation(arr, lib_uri, dline, dchr));

  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"context\":{\"includeDeclaration\":false}}", uri, line, chr);
  resp = LspRequest(l, "textDocument/references", params);
  arr = JsonSelect(resp, ".result:a");
  Check("references: includeDeclaration=false drops the definition",
        JsonArrayCount(arr) == 1 && !HasLocation(arr, lib_uri, dline, dchr));

  /* Field: decl + both typed uses, the same-named param spared. */
  PosOf(RENAME_HC, "p.x = total", 2, &line, &chr);
  snprintf(params, 512,
           "{\"textDocument\":{\"uri\":\"%s\"},"
           "\"position\":{\"line\":%lld,\"character\":%lld},"
           "\"context\":{\"includeDeclaration\":true}}", uri, line, chr);
  resp = LspRequest(l, "textDocument/references", params);
  arr = JsonSelect(resp, ".result:a");
  PosOf(LIB_HC, "I64 x;", 4, &dline, &dchr);
  Check("references: field decl + typed uses, params spared",
        JsonArrayCount(arr) == 3 && HasLocation(arr, lib_uri, dline, dchr));

  /* documentHighlight: every occurrence of the local in THIS buffer. */
  PosOf(RENAME_HC, "p.x = total", 6, &line, &chr);
  resp = LspPositionRequest(l, "textDocument/documentHighlight", uri,
                            line, chr);
  arr = JsonSelect(resp, ".result:a");
  Check("documentHighlight: all three occurrences of the local",
        JsonArrayCount(arr) == 3);

  /* Stdlib references ALLOWED (rename refuses these): the use here +
   * the declaration synthesized from std.ktmh provenance. */
  if (access("/usr/local/include/std.ktmh", 0) == 0) {
    U8 *suri = UriOf("std.ktm");
    LspDidOpen(l, suri, STD_HC);
    PosOf(STD_HC, "MAlloc", 0, &line, &chr);
    snprintf(params, 512,
             "{\"textDocument\":{\"uri\":\"%s\"},"
             "\"position\":{\"line\":%lld,\"character\":%lld},"
             "\"context\":{\"includeDeclaration\":true}}", suri, line, chr);
    resp = LspRequest(l, "textDocument/references", params);
    arr = JsonSelect(resp, ".result:a");
    Check("references: stdlib symbol allowed, decl from std.ktmh",
          JsonArrayCount(arr) == 2 && HasLocation(arr, suri, line, chr));
  } else {
    "  (skipped stdlib references: no installed stdlib)\n";
  }
  Check("clean shutdown", LspClose(l) == 0);
}

/* ---------------- driver ---------------- */

#define LSP_GROUPS 9

U0 RunGroup(I64 group)
{
  switch (group) {
    case 0: TestHandshake(); break;
    case 1: TestSync(); break;
    case 2: TestDefinition(); break;
    case 3: TestHoverCompletion(); break;
    case 4: TestDebounce(); break;
    case 5: TestSurvival(); break;
    case 6: TestRename(); break;
    case 7: TestReferences(); break;
    case 8: TestRegressions(); break;
  }
}

/* The groups each talk to their own server and mostly wait on it, so
 * they run at once, each in a child writing its report to a file; the
 * reports are printed in group order and the counts added up. */
U0 RunGroups()
{
  I32 pids[LSP_GROUPS];
  I64 group, pass, fail;
  I32 status;
  U8 path[512];

  for (group = 0; group < LSP_GROUPS; group++) {
    fflush(NULL);
    pids[group] = fork();
    if (pids[group] == 0) {
      snprintf(path, 512, "%s/report.%lld", g_dir, group);
      I32 fd = creat(path, 0x1A4);
      if (fd != -1) {
        dup2(fd, 1);
        close(fd);
      }
      RunGroup(group);
      fflush(NULL);
      snprintf(path, 512, "%s/counts.%lld", g_dir, group);
      U8 *counts = MStrPrint("%d %d", g_pass, g_fail);
      FileWrite(path, counts, StrLen(counts));
      _exit(0);
    } else if (pids[group] == -1) {
      RunGroup(group);
    }
  }

  for (group = 0; group < LSP_GROUPS; group++) {
    if (pids[group] == -1) continue;
    waitpid(pids[group], &status, 0);
    snprintf(path, 512, "%s/report.%lld", g_dir, group);
    U8 *report = FileRead(path);
    if (report) "%s", report;
    snprintf(path, 512, "%s/counts.%lld", g_dir, group);
    U8 *counts = FileRead(path);
    U8 *end;
    if (counts) {
      pass = strtol(counts, &end, 10);
      fail = strtol(end, &end, 10);
      g_pass += pass;
      g_fail += fail;
    } else {
      Check("test group finished (it crashed or was killed)", FALSE);
    }
  }
}

I32 Main()
{
  InitFixtures();
  g_pass = 0;
  g_fail = 0;

  /* Test the LOCAL build: this runner lives in src/tests/lsp and is
   * compiled+run from there by `make lsp-test`. */
  g_ktm = MAlloc(1024);
  if (realpath("../../../ktm", g_ktm) == NULL ||
      access(g_ktm, 1) != 0)
  {
    "\033[0;31mFAILED\033[0;0m: ../../../ktm not built or not "
    "executable (run `make` first, and run this via `make lsp-test`)\n";
    return 1;
  }

  U8 tmp[128];
  snprintf(tmp, 128, "/tmp/ktm_lsp_test_%d", getpid());
  U8 cmd[256];
  snprintf(cmd, 256, "mkdir -p %s/sub", tmp);
  System(cmd);
  g_dir = MAlloc(1024);
  realpath(tmp, g_dir);

  U8 path[512];
  snprintf(path, 512, "%s/lib.ktm", g_dir);
  FileWrite(path, LIB_HC, StrLen(LIB_HC));
  snprintf(path, 512, "%s/sub/deep.ktm", g_dir);
  FileWrite(path, DEEP_HC, StrLen(DEEP_HC));
  snprintf(path, 512, "%s/main.ktm", g_dir);
  FileWrite(path, MAIN_HC, StrLen(MAIN_HC));
  snprintf(path, 512, "%s/bad.ktm", g_dir);
  FileWrite(path, BAD_INCLUDE_HC, StrLen(BAD_INCLUDE_HC));
  snprintf(path, 512, "%s/broken.ktm", g_dir);
  FileWrite(path, BROKEN_HC, StrLen(BROKEN_HC));
  snprintf(path, 512, "%s/crash.ktm", g_dir);
  FileWrite(path, CRASHER_HC, StrLen(CRASHER_HC));
  snprintf(path, 512, "%s/rename.ktm", g_dir);
  FileWrite(path, RENAME_HC, StrLen(RENAME_HC));
  snprintf(path, 512, "%s/std.ktm", g_dir);
  FileWrite(path, STD_HC, StrLen(STD_HC));

  "ktm -lsp tests (%s)\n", g_ktm;
  RunGroups();

  snprintf(cmd, 256, "rm -rf %s", tmp);
  System(cmd);

  if (g_fail == 0) {
    "\033[0;32mlsp: %d/%d passed\033[0;0m\n", g_pass, g_pass + g_fail;
    return 0;
  }
  "\033[0;31mlsp: %d/%d passed\033[0;0m\n", g_pass, g_pass + g_fail;
  return 1;
}
