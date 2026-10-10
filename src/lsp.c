/* LSP server for ktm: full document sync, every analysis and query
 * answered from a fresh parse in a fork()ed child (lspRunIsolated). */

#include <ctype.h>
#include <limits.h>
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <setjmp.h>
#include <signal.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include "aostr.h"
#include "arena.h"
#include "ast.h"
#include "cctrl.h"
#include "cli.h"
#include "containers.h"
#include "json.h"
#include "lexer.h"
#include "lsp.h"
#include "parser.h"
#include "util.h"
#include "version.h"

/* Everything the server knows, threaded through every handler. The
 * one instance lives on lspRun's stack. */
typedef struct LspCtx {
    Cctrl *cc;              /* stdlib-only in the parent; see lspRunIsolated */
    Map *docs;              /* uri (char*) -> AoStr* text */
    char *root_dir;         /* builtin include root for <...> */
    enum CliTarget target;
    int shutdown_requested;
    int utf16;              /* wire columns are utf-16 units, not bytes */
    int log_on;
    Vec *pending;           /* uris awaiting a debounced analyze */
    const char *analyzing;  /* uri lspPublishHandler publishes for */
    s64 pending_since_ms;   /* when the oldest pending uri landed */
} LspCtx;

/* Crash guard around parses. Stays global: the signal handler gets no
 * context argument. */
static sigjmp_buf lsp_parse_env;
static volatile sig_atomic_t lsp_in_parse = 0;
static volatile int lsp_crash_sig = 0;

static void lspLog(LspCtx *ctx, const char *fmt, ...) {
    if (!ctx->log_on) return;
    va_list ap;
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
    fputc('\n', stderr);
}

/* One framed message: `Content-Length: N\r\n...\r\n\r\n<N bytes>`.
 * Returns a malloc'd body (caller frees) or NULL on EOF/garbage. */
static char *lspReadMessage(size_t *len_out) {
    char header[512];
    long content_length = -1;
    for (;;) {
        if (fgets(header, sizeof(header), stdin) == NULL) return NULL;
        if (strcmp(header, "\r\n") == 0 || strcmp(header, "\n") == 0) break;
        if (strncasecmp(header, "Content-Length:", 15) == 0) {
            content_length = strtol(header + 15, NULL, 10);
        }
        /* Content-Type etc.: ignored. */
    }
    if (content_length <= 0 || content_length > (64 << 20)) return NULL;
    char *body = (char *)malloc((size_t)content_length + 1);
    size_t got = fread(body, 1, (size_t)content_length, stdin);
    if (got != (size_t)content_length) {
        free(body);
        return NULL;
    }
    body[content_length] = '\0';
    *len_out = (size_t)content_length;
    return body;
}

static void lspWrite(AoStr *body) {
    printf("Content-Length: %llu\r\n\r\n%s",
           (unsigned long long)body->len, body->data);
    fflush(stdout);
}

/* JSON-RPC ids may be numbers or strings; re-emit whichever came in. */
static void lspCatId(AoStr *buf, Json *id) {
    if (id == NULL || id->kind == JSON_NULL) {
        aoStrCatLen(buf, "null", 4);
    } else if (id->kind == JSON_STRING) {
        aoStrPutChar(buf, '"');
        jsonEscapeInto(buf, id->as.str, id->as.str_len);
        aoStrPutChar(buf, '"');
    } else {
        aoStrCatPrintf(buf, "%lld", (long long)id->as.i);
    }
}

/* `result` is spliced in verbatim - build it with jsonEscapeInto for
 * any string payloads. */
static void lspRespond(Json *id, const char *result) {
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body, "{\"jsonrpc\":\"2.0\",\"id\":");
    lspCatId(body, id);
    aoStrCatPrintf(body, ",\"result\":%s}", result);
    lspWrite(body);
    aoStrRelease(body);
}

static void lspRespondError(Json *id, int code, const char *message) {
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body, "{\"jsonrpc\":\"2.0\",\"id\":");
    lspCatId(body, id);
    aoStrCatPrintf(body, ",\"error\":{\"code\":%d,\"message\":\"", code);
    jsonEscapeInto(body, message, strlen(message));
    aoStrCatPrintf(body, "\"}}");
    lspWrite(body);
    aoStrRelease(body);
}

/* file:///a%20b.ktm -> /a b.ktm. Everything else passes through. */
static char *lspUriToPath(const char *uri) {
    const char *p = uri;
    if (strncmp(p, str_lit("file://")) == 0) p += 7;
    size_t n = strlen(p);
    char *out = (char *)malloc(n + 1);
    size_t o = 0;
    for (size_t i = 0; i < n; ++i) {
        if (p[i] == '%' && i + 2 < n + 1 && i + 2 < n) {
            int hi = p[i + 1], lo = p[i + 2];
            int h = (hi >= '0' && hi <= '9') ? hi - '0'
                  : (hi >= 'a' && hi <= 'f') ? hi - 'a' + 10
                  : (hi >= 'A' && hi <= 'F') ? hi - 'A' + 10 : -1;
            int l = (lo >= '0' && lo <= '9') ? lo - '0'
                  : (lo >= 'a' && lo <= 'f') ? lo - 'a' + 10
                  : (lo >= 'A' && lo <= 'F') ? lo - 'A' + 10 : -1;
            if (h >= 0 && l >= 0) {
                out[o++] = (char)((h << 4) | l);
                i += 2;
                continue;
            }
        }
        out[o++] = p[i];
    }
    out[o] = '\0';
    return out;
}

static void lspCrashHandler(int sig, siginfo_t *si, void *uc) {
    (void)si;
    (void)uc;
    if (lsp_in_parse) {
        lsp_crash_sig = sig;
        lsp_in_parse = 0;
        siglongjmp(lsp_parse_env, 1);
    }
    signal(sig, SIG_DFL);
}

static void lspInstallCrashHandlers(void) {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = lspCrashHandler;
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);
    static const int sigs[] = { SIGSEGV, SIGBUS, SIGILL, SIGFPE, SIGABRT,
                                SIGTRAP };
    for (size_t i = 0; i < sizeof(sigs) / sizeof(sigs[0]); ++i)
        sigaction(sigs[i], &sa, NULL);
}

/* Parse the stdlib header so MAlloc/StrNew/... exist - without this
 * every stdlib call in every document gets a false "not defined"
 * squiggle, and hover/completion have nothing to serve. Mirrors
 * replBootstrap; stdlib diagnostics are discarded. */
static void lspBootstrap(LspCtx *ctx, Cctrl *cc) {
    AoStr *builtin = aoStrPrintf("%sstd.ktmh", ctx->root_dir);
    if (access(builtin->data, R_OK) != 0) {
        lspLog(ctx, "lsp: %s not found; stdlib symbols unavailable",
               builtin->data);
        aoStrRelease(builtin);
        return;
    }
    Lexer *l = (Lexer *)malloc(sizeof(Lexer));
    lexInit(l, NULL, CCF_PRE_PROC);
    l->cc = cc; /* lex-time raises route through diagnostics, not exit */
    lexSetBuiltinRoot(l, ctx->root_dir);
    lexPushFile(l, builtin);

    cctrlDiagClear(cc);
    cctrlResetTokenBuffer(cc);
    jmp_buf lex_recovery;
    jmp_buf *prev_recovery = cc->current_recovery;
    cc->current_recovery = &lex_recovery;
    if (setjmp(lex_recovery) == 0) {
        cctrlInitParse(cc, l);
        parseToAst(cc);
    }
    cc->current_recovery = prev_recovery;
    cctrlDiagClear(cc);
    lexerRelease(l);
}

static Cctrl *lspFreshCctrl(LspCtx *ctx) {
    Cctrl *cc = cctrlNew(ctx->target);
    cc->flags |= CCTRL_REPL; /* redefinition replaces - re-parses work */
    cctrlAddDefine(cc, "__KTM_AOT__");
    lspBootstrap(ctx, cc);
    return cc;
}

/* Parse `text` as `path`, leaving diagnostics on ctx->cc->diagnostics.
 * Returns 0 normally, the killing signal if the parser crashed. Only
 * ever called inside an isolated child (lspRunIsolated). */
static int lspParse(LspCtx *ctx, const char *path, AoStr *text) {
    Lexer *l = (Lexer *)malloc(sizeof(Lexer));
    lexInit(l, NULL, CCF_PRE_PROC);
    l->cc = ctx->cc; /* lex-time raises route through diagnostics */
    lexSetBuiltinRoot(l, ctx->root_dir);
    lexPushString(l, (char *)path, text->data, text->len);

    cctrlDiagClear(ctx->cc);
    cctrlResetTokenBuffer(ctx->cc);

    int crashed = 0;
    lsp_crash_sig = 0;
    if (sigsetjmp(lsp_parse_env, 1) == 0) {
        lsp_in_parse = 1;
        /* Lex-time raises longjmp through cc->current_recovery; give
         * them somewhere to land (mirrors replParse). */
        jmp_buf lex_recovery;
        jmp_buf *prev_recovery = ctx->cc->current_recovery;
        ctx->cc->current_recovery = &lex_recovery;
        if (setjmp(lex_recovery) == 0) {
            cctrlInitParse(ctx->cc, l);
            parseToAst(ctx->cc);
        }
        ctx->cc->current_recovery = prev_recovery;
    } else {
        crashed = lsp_crash_sig;
    }
    lsp_in_parse = 0;
    lexerRelease(l);
    return crashed;
}

/* The parent only ever holds the stdlib (lspBootstrap). Every analysis
 * and query runs in a fork()ed child that parses the current text,
 * answers and exits, so memory, stale symbols and parser crashes all
 * die with the child. */

/* Generous next to ~1ms parses; catches parser infinite loops. */
#define KTM_LSP_CHILD_TIMEOUT_SECS 5
/* Child exit code for "the guarded parse crashed with signal N". */
#define KTM_LSP_CHILD_CRASHED 64

typedef void (*LspHandler)(LspCtx *ctx, Json *id, Json *params);

static AoStr *lspDocGetOrLoad(LspCtx *ctx, const char *uri);
static AoStr *lspTextForPath(LspCtx *ctx, const char *path,
                             const char **uri_out);

/* Parse `uri`'s text into this (child) process's compiler. Returns 0 or
 * the crash signal. A uri with no text (never opened, unreadable) is
 * left to the handler, which answers null. */
static int lspParseUri(LspCtx *ctx, const char *uri) {
    AoStr *text = lspDocGetOrLoad(ctx, uri);
    if (text == NULL) return 0;
    char *path = lspUriToPath(uri);
    int crashed = lspParse(ctx, path, text);
    free(path);
    return crashed;
}

/* Run `handler` in a child against a fresh parse of `uri` (and of every
 * other open document first when `with_open_docs` - rename and
 * references must see callers in files that aren't included by `uri`).
 * Returns 0 when the child completed, else the crash/timeout signal;
 * on failure nothing was written and the caller answers instead. */
static int lspRunIsolated(LspCtx *ctx, const char *uri, int with_open_docs,
                          LspHandler handler, Json *id, Json *params)
{
    fflush(stdout);
    pid_t pid = fork();
    if (pid < 0) {
        lspLog(ctx, "lsp: fork failed");
        return SIGCHLD;
    }
    if (pid == 0) {
        /* SIGALRM isn't in the crash handler's set: a hang kills the
         * child and the parent sees WIFSIGNALED. */
        alarm(KTM_LSP_CHILD_TIMEOUT_SECS);
        if (with_open_docs) {
            MapIter mi;
            mapIterInit(ctx->docs, &mi);
            while (mapIterNext(&mi)) {
                const char *other = (const char *)mi.node->key;
                if (strcmp(other, uri) == 0) continue;
                int sig = lspParseUri(ctx, other);
                if (sig) _exit(KTM_LSP_CHILD_CRASHED + sig);
            }
        }
        /* Parsed last so its own symbols win any redefinition. */
        int sig = lspParseUri(ctx, uri);
        if (sig) _exit(KTM_LSP_CHILD_CRASHED + sig);
        handler(ctx, id, params);
        fflush(stdout);
        _exit(0);
    }
    int status = 0;
    while (waitpid(pid, &status, 0) < 0) {
        if (errno == EINTR) continue;
        lspLog(ctx, "lsp: waitpid failed");
        return SIGCHLD;
    }
    if (WIFSIGNALED(status)) return WTERMSIG(status);
    if (WIFEXITED(status) && WEXITSTATUS(status) >= KTM_LSP_CHILD_CRASHED)
        return WEXITSTATUS(status) - KTM_LSP_CHILD_CRASHED;
    if (WIFEXITED(status) && WEXITSTATUS(status) != 0)
        return SIGCHLD;
    return 0;
}

/* Columns are bytes internally; on the wire they are utf-16 units
 * unless the client accepts utf-8 (see lspInitialize). */

static size_t lspLineStart(AoStr *text, int line) {
    size_t i = 0;
    for (int l = 0; l < line && i < text->len; ++i)
        if (text->data[i] == '\n') l++;
    return i;
}

static int lspUtf8Len(unsigned char c) {
    if (c < 0x80)         return 1;
    if ((c >> 5) == 0x6)  return 2;
    if ((c >> 4) == 0xE)  return 3;
    if ((c >> 3) == 0x1E) return 4;
    return 1; /* stray continuation byte: count it alone */
}

/* Byte column -> wire column on `line` of `text`. */
static int lspColToWire(LspCtx *ctx, AoStr *text, int line, int bytecol) {
    if (!ctx->utf16 || text == NULL || bytecol <= 0) return bytecol;
    const unsigned char *s = (const unsigned char *)text->data;
    size_t i = lspLineStart(text, line);
    size_t end = i + (size_t)bytecol;
    int units = 0;
    while (i < end && i < text->len && s[i] != '\n') {
        int len = lspUtf8Len(s[i]);
        units += len == 4 ? 2 : 1; /* astral chars are surrogate pairs */
        i += (size_t)len;
    }
    /* Past the end of the line: keep the overhang as-is. */
    if (i < end) units += (int)(end - i);
    return units;
}

/* Wire column -> byte column on `line` of `text`. */
static int lspColFromWire(LspCtx *ctx, AoStr *text, int line, int col) {
    if (!ctx->utf16 || text == NULL || col <= 0) return col;
    const unsigned char *s = (const unsigned char *)text->data;
    size_t start = lspLineStart(text, line);
    size_t i = start;
    int units = 0;
    while (units < col && i < text->len && s[i] != '\n') {
        int len = lspUtf8Len(s[i]);
        units += len == 4 ? 2 : 1;
        i += (size_t)len;
    }
    return (int)(i - start) + (col - units);
}

/* `"range":{...}` for byte columns [c0, c1) on 0-based `line` of
 * `text` (NULL text: columns go out unconverted). */
static void lspCatRange(LspCtx *ctx, AoStr *buf, AoStr *text, int line,
                        int c0, int c1)
{
    aoStrCatPrintf(buf,
        "\"range\":{\"start\":{\"line\":%d,\"character\":%d},"
        "\"end\":{\"line\":%d,\"character\":%d}}",
        line, lspColToWire(ctx, text, line, c0),
        line, lspColToWire(ctx, text, line, c1));
}

static int lspSeverity(int cctrl_severity) {
    switch (cctrl_severity) {
        case CCTRL_WARN: return 2; /* Warning */
        case CCTRL_INFO: return 3; /* Information */
        default:         return 1; /* Error (incl. ICE) */
    }
}

/* 1-based (or 0 = unknown) -> 0-based, clamped. */
static int lspZero(int v) {
    return v > 0 ? v - 1 : 0;
}

static void lspCatDiagnostic(LspCtx *ctx, AoStr *body, AoStr *text,
                             CctrlDiagnostic *d, int remote_file,
                             const char *remote_name) {
    int sl = lspZero(d->line);
    int sc = lspZero(d->col);
    int el = lspZero(d->end_line);
    int ec = lspZero(d->end_col);
    if (remote_file) {
        /* Fault is in an #include - pin it to the top of the open
         * document and name the real location in the message. */
        sl = sc = el = 0;
        ec = 1;
    } else if (el < sl || (el == sl && ec <= sc)) {
        el = sl;
        ec = sc + 1;
    }
    if (!remote_file) {
        sc = lspColToWire(ctx, text, sl, sc);
        ec = lspColToWire(ctx, text, el, ec);
    }
    aoStrCatPrintf(body,
        "{\"range\":{\"start\":{\"line\":%d,\"character\":%d},"
        "\"end\":{\"line\":%d,\"character\":%d}},"
        "\"severity\":%d,\"source\":\"ktm\",\"message\":\"",
        sl, sc, el, ec, lspSeverity(d->severity));
    if (remote_file) {
        AoStr *loc = aoStrPrintf("in included file %s:%d: ",
                                 remote_name, d->line);
        jsonEscapeInto(body, loc->data, loc->len);
        aoStrRelease(loc);
    }
    /* The stored message is the terminal rendering: severity prefix,
     * then a `--> file:line` caret diagram. In an editor the range
     * and severity already say all that - keep the first line only,
     * minus the prefix. */
    const char *m = d->message->data;
    size_t mlen = d->message->len;
    if      (strncmp(m, str_lit("error: ")) == 0)   { m += 7; mlen -= 7; }
    else if (strncmp(m, str_lit("warning: ")) == 0) { m += 9; mlen -= 9; }
    else if (strncmp(m, str_lit("info: ")) == 0)    { m += 6; mlen -= 6; }

    const char *nl = memchr(m, '\n', mlen);
    if (nl) mlen = (size_t)(nl - m);
    jsonEscapeInto(body, m, mlen);
    if (d->suggestion) {
        aoStrCatLen(body, "\\n", 2);
        jsonEscapeInto(body, d->suggestion->data, d->suggestion->len);
    }
    aoStrCatPrintf(body, "\"}");
}

static void lspPublishDiagnostics(LspCtx *ctx, const char *uri,
                                  const char *path) {
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body,
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
        "\"params\":{\"uri\":\"");
    jsonEscapeInto(body, uri, strlen(uri));
    aoStrCatPrintf(body, "\",\"diagnostics\":[");

    int emitted = 0;
    AoStr *text = (AoStr *)mapGet(ctx->docs, (void *)uri);
    Vec *diags = ctx->cc->diagnostics;
    for (u64 i = 0; diags && i < diags->size; ++i) {
        CctrlDiagnostic *d = vecGet(CctrlDiagnostic *, diags, i);
        const char *dfile = (d->file && d->file->filename)
                          ? d->file->filename->data : NULL;
        int remote = dfile != NULL && strcmp(dfile, path) != 0;
        if (emitted++) aoStrPutChar(body, ',');
        lspCatDiagnostic(ctx, body, text, d, remote, dfile ? dfile : "?");
    }
    aoStrCatPrintf(body, "]}}");
    lspWrite(body);
    aoStrRelease(body);
    lspLog(ctx, "lsp: published %d diagnostic(s) for %s", emitted, path);
}

/* Child side of an analysis: the parse already ran, publish its
 * diagnostics. */
static void lspPublishHandler(LspCtx *ctx, Json *id, Json *params) {
    (void)id;
    (void)params;
    char *path = lspUriToPath(ctx->analyzing);
    lspPublishDiagnostics(ctx, ctx->analyzing, path);
    free(path);
}

static void lspAnalyze(LspCtx *ctx, const char *uri) {
    if (mapGet(ctx->docs, (void *)uri) == NULL) return;
    ctx->analyzing = uri;
    int crash_sig = lspRunIsolated(ctx, uri, 0, lspPublishHandler, NULL, NULL);
    ctx->analyzing = NULL;
    if (crash_sig == 0) return;

    /* One synthetic diagnostic instead of silence. */
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body,
        "{\"jsonrpc\":\"2.0\","
        "\"method\":\"textDocument/publishDiagnostics\","
        "\"params\":{\"uri\":\"");
    jsonEscapeInto(body, uri, strlen(uri));
    aoStrCatPrintf(body,
        "\",\"diagnostics\":[{\"range\":{\"start\":{\"line\":0,"
        "\"character\":0},\"end\":{\"line\":0,\"character\":1}},"
        "\"severity\":1,\"source\":\"ktm\",\"message\":\"internal: ");
    if (crash_sig == SIGALRM) {
        aoStrCatPrintf(body,
            "the ktm parser hung on this input (killed after %ds)",
            KTM_LSP_CHILD_TIMEOUT_SECS);
    } else {
        aoStrCatPrintf(body,
            "the ktm parser crashed on this input (signal %d)",
            crash_sig);
    }
    aoStrCatPrintf(body, " - please report this file\"}]}}");
    lspWrite(body);
    aoStrRelease(body);
    lspLog(ctx, "lsp: parser %s (signal %d) on %s",
           crash_sig == SIGALRM ? "hung" : "crashed", crash_sig, uri);
}

/* A keystroke storm costs one parse, not one per keystroke: didChange
 * only stores the text and schedules the uri here; the main loop runs
 * the analyses once stdin has been quiet for KTM_LSP_DEBOUNCE_MS, capped
 * at KTM_LSP_DEBOUNCE_MAX_MS so a chatty client can't starve diagnostics
 * forever. Position requests flush first, so a query never sees parse
 * state older than the text it queries against. */
#define KTM_LSP_DEBOUNCE_MS     150
#define KTM_LSP_DEBOUNCE_MAX_MS 500

static s64 lspNowMs(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (s64)ts.tv_sec * 1000 + (s64)(ts.tv_nsec / 1000000);
}

/* 1 = stdin has input (or EOF - a read will not block), 0 = quiet for
 * timeout_ms. */
static int lspInputWait(int timeout_ms) {
    struct pollfd pfd = { .fd = STDIN_FILENO, .events = POLLIN };
    for (;;) {
        int rc = poll(&pfd, 1, timeout_ms);
        if (rc < 0 && errno == EINTR) continue;
        return rc > 0;
    }
}

static void lspDeferAnalysis(LspCtx *ctx, const char *uri) {
    if (vecEmpty(ctx->pending)) ctx->pending_since_ms = lspNowMs();
    if (!vecHas(ctx->pending, (void *)uri))
        vecPush(ctx->pending, strdup(uri));
}

static void lspFlushPending(LspCtx *ctx) {
    for (u64 i = 0; i < ctx->pending->size; ++i)
        lspAnalyze(ctx, vecGet(char *, ctx->pending, i));
    vecClear(ctx->pending);
}

/* Position queries against a document the client never opened (e.g. a
 * jump target buffer the editor didn't attach to): read it from disk
 * and cache it, so hover/definition keep working. No diagnostics are
 * published - the doc's symbols are already in the Cctrl if anything
 * ever included it (std.ktmh always is, via the bootstrap). */
static AoStr *lspDocGetOrLoad(LspCtx *ctx, const char *uri) {
    AoStr *text = (AoStr *)mapGet(ctx->docs, (void *)uri);
    if (text) return text;
    char *path = lspUriToPath(uri);
    FILE *f = fopen(path, "rb");
    free(path);
    if (f == NULL) return NULL;
    AoStr *buf = aoStrNew();
    char chunk[4096];
    size_t n;
    while ((n = fread(chunk, 1, sizeof(chunk), f)) > 0)
        aoStrCatLen(buf, chunk, n);
    fclose(f);
    mapAdd(ctx->docs, strdup(uri), buf);
    return buf;
}

static void lspDocSet(LspCtx *ctx, const char *uri, const char *text,
                      size_t len)
{
    AoStr *old = (AoStr *)mapGet(ctx->docs, (void *)uri);
    if (old) {
        mapRemove(ctx->docs, (void *)uri);
        aoStrRelease(old);
    }
    mapAdd(ctx->docs, strdup(uri), aoStrDupRaw((char *)text, len));
}

static void lspDidOpen(LspCtx *ctx, Json *params) {
    Json *td = jsonObjGet(params, "textDocument");
    const char *uri = jsonStrOr(td, "uri", NULL);
    const char *text = jsonStrOr(td, "text", NULL);
    if (uri == NULL || text == NULL) return;
    Json *tv = jsonObjGet(td, "text");
    lspDocSet(ctx, uri, text, tv->as.str_len);
    lspAnalyze(ctx, uri);
}

static void lspDidChange(LspCtx *ctx, Json *params) {
    Json *td = jsonObjGet(params, "textDocument");
    const char *uri = jsonStrOr(td, "uri", NULL);
    Json *changes = jsonObjGet(params, "contentChanges");
    if (uri == NULL || changes == NULL || changes->as.size == 0) return;
    /* Full sync: the last change wins and carries the whole text. */
    Json *last = jsonArrayAt(changes, changes->as.size - 1);
    Json *tv = jsonObjGet(last, "text");
    if (tv == NULL || tv->kind != JSON_STRING) return;
    lspDocSet(ctx, uri, tv->as.str, tv->as.str_len);
    lspDeferAnalysis(ctx, uri);
}

static void lspDidClose(LspCtx *ctx, Json *params) {
    Json *td = jsonObjGet(params, "textDocument");
    const char *uri = jsonStrOr(td, "uri", NULL);
    if (uri == NULL) return;
    vecRemove(ctx->pending, (void *)uri);
    AoStr *old = (AoStr *)mapGet(ctx->docs, (void *)uri);
    if (old) {
        mapRemove(ctx->docs, (void *)uri);
        aoStrRelease(old);
    }
    /* Clear the squiggles. */
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body,
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\","
        "\"params\":{\"uri\":\"");
    jsonEscapeInto(body, uri, strlen(uri));
    aoStrCatPrintf(body, "\",\"diagnostics\":[]}}");
    lspWrite(body);
    aoStrRelease(body);
}

static int lspIsIdentChar(char c) {
    return isalnum((unsigned char)c) || c == '_';
}

/* The identifier spanning (line, character) in `text`, copied into
 * `out`. `prefix_only` stops at the cursor (completion wants the
 * typed prefix; hover wants the whole word). `start_col` (nullable)
 * receives the identifier's 0-based start column - rename needs the
 * exact range. 0 when there isn't one. */
static int lspIdentAt(AoStr *text, int line, int character, int prefix_only,
                      char *out, size_t out_size, int *start_col) {
    const char *s = text->data;
    size_t n = text->len;
    size_t i = 0;
    for (int l = 0; l < line && i < n; ++i)
        if (s[i] == '\n') l++;
    size_t line_start = i;
    size_t line_end = i;
    while (line_end < n && s[line_end] != '\n') line_end++;

    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;

    size_t start = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    size_t end = pos;
    if (!prefix_only)
        while (end < line_end && lspIsIdentChar(s[end])) end++;

    if (end <= start || end - start >= out_size) return 0;
    if (isdigit((unsigned char)s[start])) return 0;
    memcpy(out, s + start, end - start);
    out[end - start] = '\0';
    if (start_col) *start_col = (int)(start - line_start);
    return 1;
}

/* Uri + position out of a request's params, the column converted to
 * bytes; 1 on success. */
static int lspTextDocPosition(LspCtx *ctx, Json *params, const char **uri,
                              int *line, int *character)
{
    *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri", NULL);
    Json *pos = jsonObjGet(params, "position");
    if (*uri == NULL || pos == NULL) return 0;
    *line = (int)jsonIntOr(pos, "line", -1);
    *character = (int)jsonIntOr(pos, "character", -1);
    if (*line < 0 || *character < 0) return 0;
    *character = lspColFromWire(ctx, lspDocGetOrLoad(ctx, *uri), *line,
                                *character);
    return 1;
}

/* Forward: the definition machinery below resolves names in scope. */
static Ast *lspResolveValue(LspCtx *ctx, u32 doc_id, int cursor_line,
                            const char *ident, s64 ilen, int *in_doc);
static AstType *lspMemberClassAt(LspCtx *ctx, AoStr *text, u32 doc_id,
                                 int line, int character, int *is_member);

static int lspIsFn(Ast *entry) {
    return astIsFnLike(entry) || (entry && entry->kind == AST_ASM_FUNC_BIND);
}

/* `#define NAME value` with the macro's evaluated value. */
static void lspCatMacro(AoStr *out, const char *ident, Lexeme *le) {
    aoStrCatPrintf(out, "#define %s", ident);
    if (le == NULL || le->tk_type == -1) return; /* bare flag define */
    switch (le->tk_type) {
        case TK_I64:
        case TK_CHAR_CONST:
            aoStrCatPrintf(out, " %lld", (long long)le->i64);
            break;
        case TK_F64:
            aoStrCatPrintf(out, " %g", (double)le->f64);
            break;
        case TK_STR:
            aoStrCatPrintf(out, " \"%.*s\"", le->len, le->start);
            break;
    }
}

static u32 lspDocId(LspCtx *ctx, const char *uri) {
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(doc_path);
    return doc_id;
}

/* ---- modules ----
 * `prefix.name` where `prefix` is one of the document's imports means the
 * module's `a.b.name`. The imports are read from the document's text: the
 * lexer that loaded the modules is gone by the time a request runs. */

/* The module path `import a.b;` (prefix b) or `import x = a.b;` (prefix
 * x) in `text` gives `recv`; NULL if no import does */
static char *lspImportPath(AoStr *text, const char *recv, size_t rlen) {
    const char *s = text->data, *end = s + text->len;
    while (s < end) {
        const char *eol = memchr(s, '\n', end - s);
        if (!eol) eol = end;
        const char *p = s;
        while (p < eol && isspace((unsigned char)*p)) p++;
        if (eol - p > 7 && !memcmp(p, "import", 6) && isspace((unsigned char)p[6])) {
            const char *w[2];
            size_t wl[2];
            int nw = 0, alias = 0;
            p += 6;
            while (p < eol && nw < 2) {
                while (p < eol && isspace((unsigned char)*p)) p++;
                if (p < eol && *p == '=') { alias = 1; p++; continue; }
                const char *q = p;
                while (q < eol && (lspIsIdentChar(*q) || *q == '.')) q++;
                if (q == p) break;
                w[nw] = p;
                wl[nw++] = q - p;
                p = q;
            }
            const char *path = NULL, *prefix = NULL;
            size_t path_len = 0, prefix_len = 0;
            if (alias && nw == 2) {
                prefix = w[0]; prefix_len = wl[0];
                path = w[1]; path_len = wl[1];
            } else if (!alias && nw >= 1) {
                path = w[0]; path_len = wl[0];
                prefix = path;
                for (size_t i = 0; i < path_len; ++i) {
                    if (path[i] == '.') prefix = path + i + 1;
                }
                prefix_len = path + path_len - prefix;
            }
            if (path && prefix_len == rlen && !memcmp(prefix, recv, rlen)) {
                return mprintf("%.*s", (int)path_len, path);
            }
        }
        s = eol + 1;
    }
    return NULL;
}

/* `recv.name` at the cursor with `recv` an import prefix: "a.b.name",
 * `name` running to the cursor with prefix_only (completion) or to its
 * end; the module path in *_path. NULL otherwise. */
static char *lspModuleRefAt(AoStr *text, int line, int character,
                            int prefix_only, char **_path)
{
    const char *s = text->data;
    size_t n = text->len, i = 0;
    for (int l = 0; l < line && i < n; ++i) {
        if (s[i] == '\n') l++;
    }
    size_t line_start = i, line_end = i;
    while (line_end < n && s[line_end] != '\n') line_end++;
    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;
    size_t start = pos, end = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    if (!prefix_only) {
        while (end < line_end && lspIsIdentChar(s[end])) end++;
    }
    if (start <= line_start || s[start - 1] != '.') return NULL;
    size_t r_end = start - 1, r_start = r_end;
    while (r_start > line_start && lspIsIdentChar(s[r_start - 1])) r_start--;
    if (r_start == r_end) return NULL;
    char *path = lspImportPath(text, s + r_start, r_end - r_start);
    if (!path) return NULL;
    if (_path) *_path = path;
    return mprintf("%s.%.*s", path, (int)(end - start), s + start);
}

/* A module's function is named by its symbol: show `a.b.name` */
static char *lspModuleFnString(Ast *fn, const char *qual) {
    char *str = astFunctionToString(fn);
    char *at = fn->fname ? strstr(str, fn->fname->data) : NULL;
    if (!at) return str;
    return mprintf("%.*s%s%s", (int)(at - str), str, qual,
                   at + fn->fname->len);
}

/* A method: the function `Class.name` whose first parameter is its
 * const `self` */
static int lspIsMethod(Ast *fn) {
    if (!lspIsFn(fn) || !fn->params || fn->params->size == 0) return 0;
    Ast *self = fn->params->entries[0];
    return self && self->kind == AST_LVAR && self->lname &&
           !strcmp(self->lname->data, "self") && (self->flags & AST_FLAG_CONST);
}

/* The method `name` of `cls` or of the nearest class it inherits from */
static Ast *lspFindMethod(LspCtx *ctx, AstType *cls, const char *name,
                          s64 len, AstType **owner) {
    for (AstType *t = cls; t; t = t->base_cls) {
        if (!t->clsname) continue;
        Ast *fn = mapGet(ctx->cc->global_env,
                         mprintf("%s.%.*s", t->clsname->data, (int)len, name));
        if (fn && lspIsMethod(fn)) {
            if (owner) *owner = t;
            return fn;
        }
    }
    return NULL;
}

/* `i64 User.older(i64 years)`: the method's signature without `self` */
static char *lspMethodString(Ast *fn, AstType *owner, const char *name) {
    char *str = lspModuleFnString(fn, mprintf("%s.%s", owner->clsname->data, name));
    char *open = strchr(str, '(');
    char *self = open ? strstr(open, "self") : NULL;
    if (!self) return str;
    char *rest = self + 4;
    if (*rest == ',') rest++;
    while (*rest == ' ') rest++;
    return mprintf("%.*s%s", (int)(open + 1 - str), str, rest);
}

static void lspHover(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL ||
        !lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL))
    {
        lspRespond(id, "null");
        return;
    }
    s64 ilen = (s64)strlen(ident);
    u32 doc_id = lspDocId(ctx, uri);

    /* Same priority as definition: a field of a typed member access,
     * then a value in scope (locals and params first), then a type,
     * then a macro. */
    AoStr *rendered = aoStrNew();
    int is_member = 0;
    char *qual = lspModuleRefAt(text, line, character, 0, NULL);
    AstType *mcls = qual ? NULL
                  : lspMemberClassAt(ctx, text, doc_id, line, character,
                                     &is_member);
    if (qual) {
        Ast *entry = mapGet(ctx->cc->global_env, qual);
        AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, qual);
        if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, qual);
        if (entry && lspIsFn(entry)) {
            aoStrCatPrintf(rendered, "%s", lspModuleFnString(entry, qual));
        } else if (entry && entry->type) {
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(entry->type), qual);
        } else if (ty) {
            AoStr *body = astClassToAoStr(ty);
            aoStrCatLen(rendered, body->data, body->len);
            aoStrRelease(body);
        }
    } else if (is_member) {
        AstType *f = (mcls && mcls->fields) ? mapGetLen(mcls->fields, ident, ilen) : NULL;
        AstType *owner = NULL;
        Ast *method = (mcls && !f) ? lspFindMethod(ctx, mcls, ident, ilen, &owner)
                                   : NULL;
        if (f && mcls->clsname)
            aoStrCatPrintf(rendered, "%s %s.%s", astTypeToString(f),
                           mcls->clsname->data, ident);
        else if (f)
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(f), ident);
        else if (method)
            aoStrCatPrintf(rendered, "%s", lspMethodString(method, owner, ident));
    } else {
        int in_doc = 0;
        Ast *entry = lspResolveValue(ctx, doc_id, line + 1, ident, ilen,
                                     &in_doc);
        AstType *cls = mapGetLen(ctx->cc->clsdefs, ident, ilen);
        AstType *uni = cls ? NULL : mapGetLen(ctx->cc->uniondefs, ident, ilen);
        Lexeme *macro = mapGetLen(ctx->cc->macro_defs, ident, ilen);
        if (entry && lspIsFn(entry)) {
            aoStrCatPrintf(rendered, "%s", astFunctionToString(entry));
        } else if (entry && entry->type) {
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(entry->type),
                           ident);
        } else if (cls || uni) {
            AoStr *body = astClassToAoStr(cls ? cls : uni);
            aoStrCatLen(rendered, body->data, body->len);
            aoStrRelease(body);
        } else if (macro) {
            lspCatMacro(rendered, ident, macro);
        }
    }

    if (rendered->len == 0) {
        lspRespond(id, "null");
        aoStrRelease(rendered);
        return;
    }
    AoStr *result = aoStrNew();
    aoStrCatPrintf(result,
                   "{\"contents\":{\"kind\":\"markdown\",\"value\":\"");
    AoStr *md = aoStrPrintf("```ktm\n%s\n```", rendered->data);
    jsonEscapeInto(result, md->data, md->len);
    aoStrCatPrintf(result, "\"}}");
    lspRespond(id, result->data);
    aoStrRelease(md);
    aoStrRelease(rendered);
    aoStrRelease(result);
}

static const char *lsp_keywords[] = {
    "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "f32", "f64",
    "void", "bool",
    "I8", "I16", "I32", "I64", "U8", "U16", "U32", "U64", "F64", "U0",
    "Bool",  "break", "case", "class", "default", "do", "else", "extern",
    "_extern", "for", "goto", "if", "public", "return", "sizeof", "static",
    "switch", "union", "while",
    /* Added */
    "auto", "continue", "typeof", "F32", "alignof",
    "true", "false", "null",
    "struct", "pub", "private", "const", "enum", "defer",
};

#define KTM_LSP_FUNCTION     3
#define KTM_LSP_FIELD        5
#define KTM_LSP_VARIABLE     6
#define KTM_LSP_CLASS        7
/* tbh we could double up with out usage of class here */
#define KTM_LSP_UNION_STRUCT 22
#define KTM_LSP_CONSTANT     21
#define KTM_LSP_KEYWORD      24

/* One CompletionItem; label escaped, detail optional. */
static void lspCatCompletion(AoStr *body, int *emitted, const char *label,
                             int kind, const char *detail)
{
    if ((*emitted)++) aoStrPutChar(body, ',');
    aoStrCatPrintf(body, "{\"label\":\"");
    jsonEscapeInto(body, label, strlen(label));
    aoStrCatPrintf(body, "\",\"kind\":%d", kind);
    if (detail) {
        aoStrCatPrintf(body, ",\"detail\":\"");
        jsonEscapeInto(body, detail, strlen(detail));
        aoStrPutChar(body, '"');
    }
    aoStrPutChar(body, '}');
}

/* Handles global variables and non-asm functions */
static char *lspGlobalSuggestionResolver(Ast *entry, int *_type) {
    if (lspIsFn(entry)) {
        *_type = KTM_LSP_FUNCTION;
        return astFunctionToString(entry);
    } else {
        *_type = KTM_LSP_VARIABLE;
        return entry->type ? astTypeToString(entry->type) : NULL;
    }
}

static char *lspAsmFunctionSuggestionResolver(Ast *entry, int *_type) {
    *_type = KTM_LSP_FUNCTION;
    return lspIsFn(entry) ? astFunctionToString(entry) : NULL;
}

static char *lspClassSuggestionResolver(Ast *entry, int *_type) {
    (void)entry;
    *_type = KTM_LSP_CLASS;
    return "class";
}

static char *lspUnionSuggestionResolver(Ast *entry, int *_type) {
    (void)entry;
    *_type = KTM_LSP_UNION_STRUCT;
    return "union";
}

static char *lspConstantSuggestionResolver(Ast *entry, int *_type) {
    (void)entry;
    *_type = KTM_LSP_CONSTANT;
    return "#define";
}

/* Find matching prefix in a symbol table */
void lspScanMapForSuggestion(Map *suggestions,
                             char *prefix,
                             int plen,
                             AoStr *body,
                             int *_emitted,
                             char *(lsp_suggestion_resolver)(Ast *entry, int *_type))
{
    MapIter mi;
    int emitted = *_emitted;
    mapIterInit(suggestions, &mi);
    while (mapIterNext(&mi) && emitted < 1000) {
        const char *name = (const char *)mi.node->key;
        if (strncmp(name, prefix, plen) != 0) continue;
        Ast *entry = (Ast *)mi.node->value;
        /* a method is completed after `u.`, not on its own */
        if (lsp_suggestion_resolver == lspGlobalSuggestionResolver &&
            lspIsMethod(entry)) {
            continue;
        }
        int type = 0;
        char *str_suggestion = lsp_suggestion_resolver(entry, &type);
        lspCatCompletion(body, &emitted, name, type, str_suggestion);
    }
    *_emitted = emitted;
}

/* The function in `doc_id` whose body the 1-based `cursor_line` is in:
 * the nearest one starting at or above it. */
static Ast *lspEnclosingFn(LspCtx *ctx, u32 doc_id, int cursor_line) {
    Ast *encl = NULL;
    MapIter mi;
    mapIterInit(ctx->cc->global_env, &mi);
    while (mapIterNext(&mi)) {
        Ast *fn = (Ast *)mi.node->value;
        if (!astIsFnLike(fn) || fn->file_id != doc_id) continue;
        if (fn->line > 0 && fn->line <= cursor_line &&
            (encl == NULL || fn->line > encl->line))
            encl = fn;
    }
    return encl;
}

static int lspNameSeen(Vec *seen, const char *name, size_t len) {
    for (u64 i = 0; i < seen->size; ++i) {
        AoStr *n = vecGet(AoStr *, seen, i);
        if (n->len == len && memcmp(n->data, name, len) == 0) return 1;
    }
    return 0;
}

/* Offer one local/param if it matches and isn't shadowed by a closer one. */
static void lspCatLocal(AoStr *body, int *emitted, Vec *seen, Ast *v,
                        const char *prefix, size_t plen)
{
    if (v && v->kind == AST_DECL && v->declvar) v = v->declvar;
    if (v == NULL || v->kind != AST_LVAR || v->lname == NULL) return;
    /* hidden locals (`nums#len`, a call's `#va0`) can't be named */
    if (memchr(v->lname->data, '#', v->lname->len)) return;
    if (v->lname->len < plen || strncmp(v->lname->data, prefix, plen) != 0)
        return;
    if (lspNameSeen(seen, v->lname->data, v->lname->len)) return;
    vecPush(seen, v->lname);
    char *name = strndup(v->lname->data, v->lname->len);
    lspCatCompletion(body, emitted, name, KTM_LSP_VARIABLE,
                     v->type ? astTypeToString(v->type) : NULL);
    free(name);
}

static void lspCompleteLocals(LspCtx *ctx, u32 doc_id, int cursor_line,
                              const char *prefix, size_t plen,
                              AoStr *body, int *emitted)
{
    Ast *encl = lspEnclosingFn(ctx, doc_id, cursor_line);
    if (encl == NULL) return;
    Vec *seen = vecNew(&vec_ast_type);   /* borrowed AoStr names */
    /* Latest declaration first, so the innermost of two same-named
     * locals is the one offered. */
    Vec *locals = vecNew(&vec_ast_type);
    if (encl->locals) {
        listForEach(encl->locals) {
            Ast *v = (Ast *)it->value;
            if (v && v->kind == AST_DECL && v->declvar) v = v->declvar;
            if (v && v->line > 0 && v->line <= cursor_line)
                vecPush(locals, v);
        }
    }
    for (u64 i = locals->size; i > 0; --i)
        lspCatLocal(body, emitted, seen, vecGet(Ast *, locals, i - 1),
                    prefix, plen);
    if (encl->params)
        for (u64 i = 0; i < encl->params->size; ++i)
            lspCatLocal(body, emitted, seen, vecGet(Ast *, encl->params, i),
                        prefix, plen);
    vecRelease(locals);
    vecRelease(seen);
}

/* A `private` field is seen only from its module: the directory of the
 * file it is declared in */
static int lspFieldVisible(LspCtx *ctx, AstType *f, u32 doc_id) {
    if (!f || !f->private_module) return 1;
    AoStr *fpath = cctrlLookUpFile(ctx->cc, f->file_id);
    AoStr *dpath = cctrlLookUpFile(ctx->cc, doc_id);
    if (!fpath || !dpath) return 0;
    char fabs[PATH_MAX], dabs[PATH_MAX];
    const char *fp = realpath(fpath->data, fabs) ? fabs : fpath->data;
    const char *dp = realpath(dpath->data, dabs) ? dabs : dpath->data;
    const char *fslash = strrchr(fp, '/');
    const char *dslash = strrchr(dp, '/');
    int flen = fslash ? (int)(fslash - fp) : 0;
    int dlen = dslash ? (int)(dslash - dp) : 0;
    return flen == dlen && !strncmp(fp, dp, flen);
}

static void lspCompletion(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char prefix[256];
    AoStr *text;
    prefix[0] = '\0';
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL)
    {
        lspRespond(id, "null");
        return;
    }
    /* No identifier under the cursor = empty prefix = offer everything
     * (the client filters as the user types). */
    lspIdentAt(text, line, character, 1, prefix, sizeof(prefix), NULL);
    size_t plen = strlen(prefix);

    /* Member completion: after `recv.` / `recv->` (plus an optional
     * partial field prefix) the receiver class's FIELDS are the whole
     * namespace - global suggestions would be pure noise. An
     * untypable receiver offers nothing rather than everything. */
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(doc_path);
    /* `prefix.` of an import: the module's pub names */
    char *mod_path = NULL;
    if (lspModuleRefAt(text, line, character, 1, &mod_path)) {
        AoStr *mbody = aoStrNew();
        aoStrCatPrintf(mbody, "{\"isIncomplete\":false,\"items\":[");
        int memitted = 0;
        size_t mlen = strlen(mod_path);
        SetIter it;
        setIterInit(ctx->cc->pub_names, &it);
        char *qn;
        while ((qn = setNext(&it)) != NULL) {
            if (strncmp(qn, mod_path, mlen) || qn[mlen] != '.') continue;
            const char *name = qn + mlen + 1;
            if (strchr(name, '.') || strncmp(name, prefix, plen)) continue;
            Ast *entry = mapGet(ctx->cc->global_env, qn);
            int kind = KTM_LSP_CLASS;
            const char *detail = NULL;
            if (entry && lspIsFn(entry)) {
                kind = KTM_LSP_FUNCTION;
                detail = lspModuleFnString(entry, qn);
            } else if (entry) {
                kind = (entry->flags & AST_FLAG_CONST) ? KTM_LSP_CONSTANT
                                                      : KTM_LSP_VARIABLE;
                detail = entry->type ? astTypeToString(entry->type) : NULL;
            }
            lspCatCompletion(mbody, &memitted, name, kind, detail);
        }
        aoStrCatPrintf(mbody, "]}");
        lspRespond(id, mbody->data);
        aoStrRelease(mbody);
        lspLog(ctx, "lsp: module completion '%s.%s' -> %d item(s)",
               mod_path, prefix, memitted);
        return;
    }

    int is_member = 0;
    AstType *mcls = lspMemberClassAt(ctx, text, doc_id, line, character,
                                     &is_member);
    if (is_member) {
        AoStr *mbody = aoStrNew();
        aoStrCatPrintf(mbody, "{\"isIncomplete\":false,\"items\":[");
        int memitted = 0;
        if (mcls && mcls->fields) {
            MapIter mi;
            mapIterInit(mcls->fields, &mi);
            while (mapIterNext(&mi)) {
                const char *fname = (const char *)mi.node->key;
                if (strncmp(fname, prefix, plen) != 0) continue;
                AstType *ft = (AstType *)mi.node->value;
                if (!lspFieldVisible(ctx, ft, doc_id)) continue;
                int kind = (ft && ft->kind == AST_TYPE_FUNC)
                         ? KTM_LSP_FUNCTION : KTM_LSP_FIELD;
                lspCatCompletion(mbody, &memitted, fname, kind,
                                 ft ? astTypeToString(ft) : NULL);
            }
        }
        /* then its methods and the ones it inherits (a hidden base
         * method is listed once, as the derived one) */
        for (AstType *t = mcls; t; t = t->base_cls) {
            if (!t->clsname) continue;
            size_t clen = t->clsname->len;
            MapIter gi;
            mapIterInit(ctx->cc->global_env, &gi);
            while (mapIterNext(&gi)) {
                const char *key = (const char *)gi.node->key;
                if (strncmp(key, t->clsname->data, clen) || key[clen] != '.') continue;
                const char *mname = key + clen + 1;
                if (strchr(mname, '.') || strncmp(mname, prefix, plen)) continue;
                Ast *fn = (Ast *)gi.node->value;
                AstType *owner = NULL;
                if (!lspIsMethod(fn) ||
                    lspFindMethod(ctx, mcls, mname, strlen(mname), &owner) != fn) {
                    continue;
                }
                lspCatCompletion(mbody, &memitted, mname, KTM_LSP_FUNCTION,
                                 lspMethodString(fn, owner, mname));
            }
        }
        aoStrCatPrintf(mbody, "]}");
        lspRespond(id, mbody->data);
        aoStrRelease(mbody);
        lspLog(ctx, "lsp: member completion '%s' -> %d item(s)",
               prefix, memitted);
        return;
    }

    AoStr *body = aoStrNew();
    aoStrCatPrintf(body, "{\"isIncomplete\":false,\"items\":[");
    int emitted = 0;

    lspCompleteLocals(ctx, doc_id, line + 1, prefix, plen, body, &emitted);

    lspScanMapForSuggestion(ctx->cc->global_env, prefix, plen, body, &emitted,
                            &lspGlobalSuggestionResolver);

    lspScanMapForSuggestion(ctx->cc->asm_funcs, prefix, plen, body, &emitted,
                            &lspAsmFunctionSuggestionResolver);

    lspScanMapForSuggestion(ctx->cc->clsdefs, prefix, plen, body, &emitted,
                            &lspClassSuggestionResolver);

    lspScanMapForSuggestion(ctx->cc->uniondefs, prefix, plen, body, &emitted,
                            &lspUnionSuggestionResolver);

    lspScanMapForSuggestion(ctx->cc->macro_defs, prefix, plen, body, &emitted,
                            &lspConstantSuggestionResolver);

    for (size_t i = 0; i < sizeof(lsp_keywords) / sizeof(lsp_keywords[0]); ++i) {
        if (strncmp(lsp_keywords[i], prefix, plen) != 0) continue;
        lspCatCompletion(body, &emitted, lsp_keywords[i], KTM_LSP_KEYWORD, NULL);
    }

    aoStrCatPrintf(body, "]}");
    lspRespond(id, body->data);
    aoStrRelease(body);
    lspLog(ctx, "lsp: completion '%s' -> %d item(s)", prefix, emitted);
}

/* Jump-to-definition for functions and globals: their definition Asts
 * carry file_id/line stamped at parse. (Classes/unions are AstTypes,
 * which don't carry provenance yet - they respond null for now.) */
/* Resolve `ident` as a VALUE at (doc, cursor): locals/params of the
 * enclosing function (nearest preceding declaration wins), then
 * globals, then asm-bound prototypes. *in_doc is set when the hit
 * came from the enclosing function - its file is the document by
 * construction. */
static Ast *lspResolveValue(LspCtx *ctx, u32 doc_id, int cursor_line,
                            const char *ident, s64 ilen, int *in_doc)
{
    Ast *entry = NULL;
    *in_doc = 0;

    Ast *encl = lspEnclosingFn(ctx, doc_id, cursor_line);
    if (encl) {
        if (encl->params) {
            for (u64 i = 0; i < encl->params->size; ++i) {
                Ast *v = vecGet(Ast *, encl->params, i);
                if (v && v->kind == AST_LVAR && v->lname &&
                    (s64)v->lname->len == ilen &&
                    memcmp(v->lname->data, ident, ilen) == 0)
                {
                    entry = v;
                }
            }
        }
        if (encl->locals) {
            listForEach(encl->locals) {
                Ast *v = (Ast *)it->value;
                /* Declarations may arrive wrapped. */
                if (v && v->kind == AST_DECL && v->declvar) v = v->declvar;
                if (v && v->kind == AST_LVAR && v->lname &&
                    (s64)v->lname->len == ilen &&
                    memcmp(v->lname->data, ident, ilen) == 0 &&
                    v->line > 0 && v->line <= cursor_line &&
                    (entry == NULL || v->line >= entry->line))
                {
                    entry = v;
                }
            }
        }
    }
    if (entry) {
        *in_doc = 1;
        return entry;
    }
    entry = (Ast *)mapGetLen(ctx->cc->global_env, (char *)ident, ilen);
    if (entry == NULL)
        entry = (Ast *)mapGetLen(ctx->cc->asm_funcs, (char *)ident, ilen);
    return entry;
}

/* Walk back over one balanced `(...)` / `[...]` group ending at
 * end-1; returns the opener's index or (size_t)-1. Single-line. */
static size_t lspSkipGroupBack(const char *s, size_t line_start, size_t end) {
    char close = s[end - 1];
    char open = close == ')' ? '(' : '[';
    int depth = 0;
    size_t i = end;
    while (i > line_start) {
        char c = s[--i];
        if (c == close) depth++;
        else if (c == open) {
            depth--;
            if (depth == 0) return i;
        }
    }
    return (size_t)-1;
}

#define KTM_LSP_SEG_PLAIN 0
#define KTM_LSP_SEG_CALL  1 /* name(...)  - resolve via the return type */
#define KTM_LSP_SEG_INDEX 2 /* name[...]  - canonical chase handles it */

/* If the identifier at (line, character) is written as
 * `a->b(x).c[i]->ident`, extract the receiver chain (leftmost first)
 * with each segment's shape. Returns segment count, 0 when not a
 * member access or a segment is something we can't type textually. */
#define KTM_LSP_MAX_CHAIN 8
static int lspMemberChain(AoStr *text, int line, int character,
                          char out[KTM_LSP_MAX_CHAIN][256],
                          u8 kinds[KTM_LSP_MAX_CHAIN]) {
    const char *s = text->data;
    size_t n = text->len;
    size_t i = 0;
    for (int l = 0; l < line && i < n; ++i)
        if (s[i] == '\n') l++;
    size_t line_start = i;
    size_t line_end = i;
    while (line_end < n && s[line_end] != '\n') line_end++;
    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;
    size_t start = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;

    int nseg = 0;
    size_t cur = start;
    while (nseg < KTM_LSP_MAX_CHAIN) {
        size_t r_end;
        if (cur >= line_start + 2 && s[cur - 2] == '-' && s[cur - 1] == '>')
            r_end = cur - 2;
        else if (cur >= line_start + 1 && s[cur - 1] == '.')
            r_end = cur - 1;
        else
            break;
        /* Trailing (...) / [...] groups on this segment. */
        u8 kind = KTM_LSP_SEG_PLAIN;
        while (r_end > line_start &&
               (s[r_end - 1] == ')' || s[r_end - 1] == ']'))
        {
            if (s[r_end - 1] == ')') kind = KTM_LSP_SEG_CALL;
            else if (kind == KTM_LSP_SEG_PLAIN) kind = KTM_LSP_SEG_INDEX;
            size_t opener = lspSkipGroupBack(s, line_start, r_end);
            if (opener == (size_t)-1) return 0;
            r_end = opener;
        }
        size_t r_start = r_end;
        while (r_start > line_start && lspIsIdentChar(s[r_start - 1]))
            r_start--;
        if (r_end <= r_start || r_end - r_start >= 256) return 0;
        if (isdigit((unsigned char)s[r_start])) return 0;
        for (int k = nseg; k > 0; --k) {
            memcpy(out[k], out[k - 1], 256);
            kinds[k] = kinds[k - 1];
        }
        memcpy(out[0], s + r_start, r_end - r_start);
        out[0][r_end - r_start] = '\0';
        kinds[0] = kind;
        nseg++;
        cur = r_start;
    }
    return nseg;
}

/* Pointer-chase to the canonical class/union carrying ->fields. An
 * incomplete reference (fields NULL, clsname set) resolves through
 * clsdefs/uniondefs. */
static AstType *lspCanonicalClass(LspCtx *ctx, AstType *t) {
    while (t && (t->kind == AST_TYPE_POINTER || t->kind == AST_TYPE_ARRAY))
        t = t->ptr;
    if (t && t->fields == NULL && t->clsname) {
        AstType *c = (AstType *)mapGetLen(ctx->cc->clsdefs,
                t->clsname->data, t->clsname->len);
        if (c == NULL)
            c = (AstType *)mapGetLen(ctx->cc->uniondefs,
                    t->clsname->data, t->clsname->len);
        if (c) t = c;
    }
    return t;
}

/* Type the receiver chain of the member access whose final identifier
 * sits at (line, character): `a->b(x).c[i]->IDENT` yields the
 * canonical class carrying IDENT as a field. *is_member distinguishes
 * "not a member access at all" (0) from "member access we couldn't
 * type" (1, NULL return) - callers must not fall back to value lookups
 * for the latter, a field name is not a variable reference. */
static AstType *lspMemberClassAt(LspCtx *ctx, AoStr *text, u32 doc_id,
                                 int line, int character, int *is_member)
{
    char chain[KTM_LSP_MAX_CHAIN][256];
    u8 kinds[KTM_LSP_MAX_CHAIN];
    int nseg = lspMemberChain(text, line, character, chain, kinds);
    *is_member = nseg > 0;
    if (nseg == 0) return NULL;

    int in_doc = 0;
    Ast *r = lspResolveValue(ctx, doc_id, line + 1, chain[0],
                             (s64)strlen(chain[0]), &in_doc);
    AstType *ty = NULL;
    if (r) {
        /* `Foo()->x`: the head is a call - hop via the return type.
         * Indexing needs nothing special: the canonical chase strips
         * pointer/array levels to reach the class. */
        if (kinds[0] == KTM_LSP_SEG_CALL)
            ty = (r->type && r->type->rettype)
              ? lspCanonicalClass(ctx, r->type->rettype) : NULL;
        else
            ty = lspCanonicalClass(ctx, r->type);
    } else if (kinds[0] == KTM_LSP_SEG_PLAIN) {
        /* `User.describe`: a method named through its type */
        ty = mapGet(ctx->cc->clsdefs, chain[0]);
        if (!ty) ty = mapGet(ctx->cc->uniondefs, chain[0]);
    }
    for (int seg = 1; seg < nseg && ty && ty->fields; ++seg) {
        AstType *f = (AstType *)mapGetLen(ty->fields, chain[seg],
                (s64)strlen(chain[seg]));
        if (f && kinds[seg] == KTM_LSP_SEG_CALL)
            f = f->rettype; /* fn-pointer field call */
        if (!f && kinds[seg] == KTM_LSP_SEG_CALL) {
            /* `u.me().x`: a method call, typed by its return type */
            Ast *m = lspFindMethod(ctx, ty, chain[seg], strlen(chain[seg]), NULL);
            if (m && m->type) f = m->type->rettype;
        }
        ty = f ? lspCanonicalClass(ctx, f) : NULL;
    }
    return (ty && ty->fields) ? ty : NULL;
}

/* If `line` in `text` is an `#include "..."` / `#include <...>`
 * directive, respond with the top of the included file - resolved the
 * way lexInclude does: <> against the builtin include root, "" against
 * the INCLUDING file's directory (leading ./ stripped), absolute paths
 * untouched. Purely textual, so it works even while ctx->cc is dirty.
 * Returns 1 when the line was an include (response sent - null when
 * the target file doesn't exist; the path fragments under the cursor
 * must not fall through to identifier lookup), 0 otherwise. */
static int lspIncludeDefinition(LspCtx *ctx, Json *id, const char *uri,
                                AoStr *text, int line)
{
    const char *s = text->data;
    size_t n = text->len;
    size_t i = 0;
    for (int l = 0; l < line && i < n; ++i)
        if (s[i] == '\n') l++;
    size_t end = i;
    while (end < n && s[end] != '\n') end++;

    while (i < end && (s[i] == ' ' || s[i] == '\t')) i++;
    if (end - i < 8 || strncmp(s + i, str_lit("#include")) != 0) return 0;
    i += 8;
    while (i < end && (s[i] == ' ' || s[i] == '\t')) i++;
    if (i >= end || (s[i] != '"' && s[i] != '<')) return 0;
    char close = s[i] == '<' ? '>' : '"';
    char open = s[i];
    i++;
    size_t p0 = i;
    while (i < end && s[i] != close) i++;
    if (i >= end || i == p0) return 0;
    int plen = (int)(i - p0);

    AoStr *target;
    if (open == '<') {
        /* ctx->root_dir carries its trailing slash. */
        target = aoStrPrintf("%s%.*s", ctx->root_dir, plen, s + p0);
    } else if (s[p0] == '/') {
        target = aoStrDupRaw((char *)s + p0, (size_t)plen);
    } else {
        const char *rel = s + p0;
        if (plen > 2 && rel[0] == '.' && rel[1] == '/') {
            rel += 2;
            plen -= 2;
        }
        char *doc_path = lspUriToPath(uri);
        char *slash = strrchr(doc_path, '/');
        if (slash) {
            target = aoStrPrintf("%.*s/%.*s", (int)(slash - doc_path),
                                 doc_path, plen, rel);
        } else {
            target = aoStrDupRaw((char *)rel, (size_t)plen);
        }
        free(doc_path);
    }

    char abs_path[PATH_MAX];
    const char *out_path = target->data;
    if (realpath(target->data, abs_path) != NULL) out_path = abs_path;
    if (access(out_path, R_OK) != 0) {
        lspLog(ctx, "lsp: include target %s unreadable", target->data);
        aoStrRelease(target);
        lspRespond(id, "null");
        return 1;
    }
    AoStr *result = aoStrNew();
    aoStrCatPrintf(result, "{\"uri\":\"file://");
    jsonEscapeInto(result, out_path, strlen(out_path));
    aoStrCatPrintf(result,
        "\",\"range\":{\"start\":{\"line\":0,\"character\":0},"
        "\"end\":{\"line\":0,\"character\":0}}}");
    lspRespond(id, result->data);
    lspLog(ctx, "lsp: definition -> include %s", out_path);
    aoStrRelease(result);
    aoStrRelease(target);
    return 1;
}

/* The `#define NAME` line in `text`: 1-based line and column of NAME. */
static int lspFindDefine(AoStr *text, const char *name, s64 nlen,
                         int *line_out, int *col_out)
{
    const char *s = text->data;
    size_t n = text->len;
    int line = 1;
    for (size_t i = 0; i < n; ++line) {
        size_t start = i;
        while (i < n && s[i] != '\n') i++;
        size_t end = i++;
        size_t j = start;
        while (j < end && (s[j] == ' ' || s[j] == '\t')) j++;
        if (j >= end || s[j] != '#') continue;
        j++;
        while (j < end && (s[j] == ' ' || s[j] == '\t')) j++;
        if (end - j < 6 || strncmp(s + j, str_lit("define")) != 0) continue;
        j += 6;
        if (j >= end || (s[j] != ' ' && s[j] != '\t')) continue;
        while (j < end && (s[j] == ' ' || s[j] == '\t')) j++;
        if (end - j < (size_t)nlen || memcmp(s + j, name, nlen) != 0) continue;
        if (j + nlen < end && lspIsIdentChar(s[j + nlen])) continue;
        *line_out = line;
        *col_out = (int)(j - start) + 1;
        return 1;
    }
    return 0;
}

/* Macros carry no provenance: find the #define textually, in the
 * document first, then in every file this parse included. */
static AoStr *lspMacroDefinition(LspCtx *ctx, const char *doc_path,
                                 AoStr *text, const char *ident, s64 ilen,
                                 int *line_out, int *col_out)
{
    if (lspFindDefine(text, ident, ilen, line_out, col_out))
        return aoStrDupRaw((char *)doc_path, strlen(doc_path));
    MapIter mi;
    mapIterInit(ctx->cc->file_map, &mi);
    while (mapIterNext(&mi)) {
        AoStr *p = (AoStr *)mi.node->value;
        if (p == NULL || strcmp(p->data, doc_path) == 0) continue;
        const char *furi = NULL;
        AoStr *ftext = lspTextForPath(ctx, p->data, &furi);
        if (ftext && lspFindDefine(ftext, ident, ilen, line_out, col_out))
            return p;
    }
    return NULL;
}

static void lspDefinition(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL)
    {
        lspRespond(id, "null");
        return;
    }
    /* Anywhere on an #include line jumps to the file itself. */
    if (lspIncludeDefinition(ctx, id, uri, text, line)) return;
    if (!lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL))
    {
        lspRespond(id, "null");
        return;
    }
    s64 ilen = (s64)strlen(ident);

    AoStr path_s = {
        .data = (char *)lspUriToPath(uri),
        .len = 0,
        .capacity = 0
    };
    path_s.len = strlen(path_s.data);
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(path_s.data);
    int cursor_line = line + 1; /* ast lines are 1-based */

    int def_line = 0, def_col = 0;
    u32 def_file = 0;
    u32 fallback_id = 0;
    int in_doc = 0;

    /* Member access `a->b.ident`: type the chain left-to-right from
     * the head variable, hopping class fields, and jump to the FIELD.
     * The canonical class AstTypes are shared with clsdefs, so fields
     * carry the provenance stamped at parse. */
    int is_member = 0;
    char *qual = lspModuleRefAt(text, line, character, 0, NULL);
    AstType *mcls = NULL;
    if (qual) {
        /* `prefix.name`: the module's declaration, in its own file */
        Ast *entry = mapGet(ctx->cc->global_env, qual);
        AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, qual);
        if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, qual);
        is_member = 1;
        if (entry) {
            def_line = entry->line;
            def_col = entry->col;
            def_file = entry->file_id;
        } else if (ty) {
            def_line = ty->line;
            def_col = ty->col;
            def_file = ty->file_id;
        }
    } else {
        mcls = lspMemberClassAt(ctx, text, doc_id, line, character,
                                &is_member);
    }
    if (mcls && mcls->fields) {
        AstType *field = (AstType *)mapGetLen(mcls->fields, ident, ilen);
        Ast *method = field ? NULL : lspFindMethod(ctx, mcls, ident, ilen, NULL);
        if (field && field->line > 0) {
            def_line = field->line;
            def_col = field->col;
            def_file = field->file_id;
        } else if (method && method->line > 0) {
            def_line = method->line;
            def_col = method->col;
            def_file = method->file_id;
        }
    }

    /* Plain identifier: values first, then types. A member access
     * that failed to resolve stays null - a field name is not a
     * variable reference, and guessing jumps to wrong classes. */
    if (!is_member && def_line == 0) {
        in_doc = 0;
        Ast *entry = lspResolveValue(ctx, doc_id, cursor_line, ident, ilen,
                                     &in_doc);
        if (entry) {
            def_line = entry->line;
            def_col = entry->col;
            def_file = entry->file_id;
        } else {
            AstType *ty = (AstType *)mapGetLen(ctx->cc->clsdefs, ident, ilen);
            if (ty == NULL)
                ty = (AstType *)mapGetLen(ctx->cc->uniondefs, ident, ilen);
            if (ty) {
                def_line = ty->line;
                def_col = ty->col;
                def_file = ty->file_id;
            }
        }
    }
    fallback_id = in_doc ? doc_id : 0;

    AoStr *fname = NULL;
    if (def_line > 0) {
        fname = cctrlLookUpFile(ctx->cc, def_file);
        if (fname == NULL && fallback_id)
            fname = cctrlLookUpFile(ctx->cc, fallback_id);
    } else if (!is_member && mapGetLen(ctx->cc->macro_defs, ident, ilen)) {
        char *doc_path = lspUriToPath(uri);
        fname = lspMacroDefinition(ctx, doc_path, text, ident, ilen,
                                   &def_line, &def_col);
        free(doc_path);
    }
    if (fname == NULL) {
        lspRespond(id, "null");
        return;
    }
    /* With a column the range spans exactly the defining name; without
     * one (synthetic nodes) fall back to line start. */
    int c0 = def_col > 0 ? def_col - 1 : 0;
    int c1 = def_col > 0 ? c0 + (int)ilen : 0;
    /* file_map can hold a cwd-relative path (AOT-style invocations);
     * a file:// uri must be absolute or the editor guesses. */
    char abs_path[PATH_MAX];
    const char *out_path = fname->data;
    if (out_path[0] != '/' && realpath(out_path, abs_path) != NULL)
        out_path = abs_path;
    AoStr *result = aoStrNew();
    const char *def_uri = NULL;
    AoStr *def_text = lspTextForPath(ctx, out_path, &def_uri);
    aoStrCatPrintf(result, "{\"uri\":\"file://");
    jsonEscapeInto(result, out_path, strlen(out_path));
    aoStrCatLen(result, "\",", 2);
    lspCatRange(ctx, result, def_text, def_line - 1, c0, c1);
    aoStrPutChar(result, '}');
    lspRespond(id, result->data);
    aoStrRelease(result);
}

/* Rename: Occurrences are found TEXTUALLY (a scan that skips strings, char
 * literals, comments and #include paths) and then filtered
 * SEMANTICALLY with the same machinery hover/definition use: a value
 * occurrence must resolve to the SAME Ast as the cursor's symbol, a
 * field occurrence's receiver chain must type to the SAME canonical
 * class. That is what keeps `total` in one function from renaming
 * `total` in another, and `p.x` from renaming an unrelated local `x`.
 *
 * Scope: a local never escapes its document; everything else scans
 * every file the compiler has seen (cc->file_map), with open-editor
 * buffers taking precedence over disk. The stdlib is excluded - and a
 * symbol DEFINED there is refused outright, renaming it would edit
 * the installed header. */

static int lspIsIdentStart(char c) {
    return isalpha((unsigned char)c) || c == '_';
}

typedef struct LspOccurrence {
    int line;   /* 0-based */
    int col;    /* 0-based byte column of the identifier's first char */
    u8 member;  /* preceded by `.` / `->`: a field position */
    u8 hash;    /* directly preceded by `#`: a directive keyword */
    u8 define;  /* the name slot of a `#define` */
} LspOccurrence;


int lspOccurrenceRelease(void *_occ) {
    if (_occ) free(_occ);
    return 1;
}

VecType vec_lsp_occurrence_type = {
    .stringify = NULL,
    .match     = NULL,
    .release   = &lspOccurrenceRelease,
    .type_str  = "long",
};

LspOccurrence *lspOccurrenceNew(int line, int col, u8 member,
                                u8 is_hash, u8 is_define)
{
   LspOccurrence *occ = malloc(sizeof(LspOccurrence));
   occ->line = line;
   occ->col = col;
   occ->member = member;
   occ->hash = is_hash;
   occ->define = is_define;
   return occ;
}

/* Every identifier-boundary occurrence of `name` in `text`, skipping
 * string/char literals, comments and #include paths. Returns a
 * malloc'd array (caller frees), count in *n_out. */
static Vec *lspFindOccurrences(AoStr *text, const char *name, size_t nlen) {
    Vec *occs = vecNew(&vec_lsp_occurrence_type);
    const char *s = text->data;
    size_t n = text->len;
    int line = 0;
    int after_define = 0;
    size_t line_start = 0;
    size_t i = 0;

    while (i < n) {
        char c = s[i];
        if (c == '\n') {
            line++;
            i++;
            line_start = i;
            after_define = 0;
            continue;
        }
        if (c == '/' && i + 1 < n && s[i + 1] == '/') {
            while (i < n && s[i] != '\n') i++;
            continue;
        }
        if (c == '/' && i + 1 < n && s[i + 1] == '*') {
            i += 2;
            while (i + 1 < n && !(s[i] == '*' && s[i + 1] == '/')) {
                if (s[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
            i = (i + 2 <= n) ? i + 2 : n;
            continue;
        }
        if (c == '"' || c == '\'') {
            char q = c;
            i++;
            while (i < n && s[i] != q) {
                if (s[i] == '\\' && i + 1 < n) i++;
                else if (s[i] == '\n') {
                    line++;
                    line_start = i + 1;
                }
                i++;
            }
            if (i < n) i++;
            continue;
        }
        if (lspIsIdentStart(c)) {
            size_t start = i;
            while (i < n && lspIsIdentChar(s[i])) i++;
            size_t len = i - start;
            int is_hash = start > line_start && s[start - 1] == '#';
            if (is_hash && len == 6 && memcmp(s + start, str_lit("define")) == 0)
                after_define = 1;
            if (is_hash && len == 7 && memcmp(s + start, str_lit("include")) == 0) {
                /* The `<...>` path form isn't a string literal; skip
                 * the rest of the line so path fragments never match. */
                while (i < n && s[i] != '\n') i++;
                continue;
            }
            if (len == nlen && memcmp(s + start, name, nlen) == 0) {
                size_t j = start - (is_hash ? 1 : 0);
                while (j > line_start && (s[j - 1] == ' ' || s[j - 1] == '\t'))
                    j--;
                int member = 0;
                if (j > line_start && s[j - 1] == '.') {
                    /* Guard `1.x`-style float fragments. */
                    member = !(j - 1 > line_start &&
                               isdigit((unsigned char)s[j - 2]));
                } else if (j - 1 > line_start && s[j - 1] == '>' &&
                           s[j - 2] == '-') {
                    member = 1;
                }
                int col = (int)(start - line_start);
                u8 is_define = (u8)(!is_hash && after_define);
                LspOccurrence *occ = lspOccurrenceNew(line,col, member,
                                                      is_hash, is_define);
                vecPush(occs, occ);
            }
            if (!is_hash) after_define = 0; /* only the name slot counts */
            continue;
        }
        i++;
    }
    return occs;
}

#define KTM_LSP_RENAME_NONE  0
#define KTM_LSP_RENAME_VALUE 1
#define KTM_LSP_RENAME_FIELD 2
#define KTM_LSP_RENAME_TYPE  3
#define KTM_LSP_RENAME_MACRO 4

typedef struct LspRenameTarget {
    int kind;
    Ast *value;      /* VALUE: local/param/global/asm prototype */
    int value_local; /* VALUE came from the enclosing function */
    AstType *cls;    /* FIELD: the canonical class carrying it */
    AstType *field;  /* FIELD */
    AstType *type;   /* TYPE */
} LspRenameTarget;

/* Is the cursor exactly on a field DECLARATION inside a class/union
 * body? A rename invoked there has no receiver chain to type, so
 * match by the provenance stamped on the field at parse. */
static int lspFieldDeclAt(LspCtx *ctx, u32 doc_id, int line1, int character,
                          const char *ident, s64 ilen,
                          AstType **cls_out, AstType **field_out)
{
    Map *tables[2] = { ctx->cc->clsdefs, ctx->cc->uniondefs };
    for (int ti = 0; ti < 2; ++ti) {
        MapIter mi;
        mapIterInit(tables[ti], &mi);
        while (mapIterNext(&mi)) {
            AstType *cls = (AstType *)mi.node->value;
            if (cls == NULL || cls->fields == NULL) continue;
            AstType *f = (AstType *)mapGetLen(cls->fields, (char *)ident,
                                              ilen);
            if (f == NULL || f->file_id != doc_id || f->line != line1)
                continue;
            if (f->col > 0) {
                int c0 = f->col - 1;
                if (character < c0 || character > c0 + (int)ilen) continue;
            }
            *cls_out = cls;
            *field_out = f;
            return 1;
        }
    }
    return 0;
}

/* What does the identifier at the cursor NAME? Priority mirrors
 * lspDefinition: a field declaration under the cursor, a typed member
 * access, a value, a class/union, a macro. */
static LspRenameTarget lspRenameTargetAt(LspCtx *ctx,
                                         AoStr *text,
                                         u32 doc_id,
                                         int line,
                                         int character,
                                         const char *ident,
                                         s64 ilen)
{
    LspRenameTarget tg = {0};

    if (lspFieldDeclAt(ctx, doc_id, line + 1, character, ident, ilen,
                       &tg.cls, &tg.field)) {
        tg.kind = KTM_LSP_RENAME_FIELD;
        return tg;
    }
    int is_member = 0;
    AstType *cls = lspMemberClassAt(ctx, text, doc_id, line, character,
                                    &is_member);
    if (is_member) {
        /* An untypable member access must NOT fall through: a field
         * name is not a variable reference. */
        AstType *f = cls ? (AstType *)mapGetLen(cls->fields, (char *)ident,
                                                ilen) : NULL;
        if (f) {
            tg.kind = KTM_LSP_RENAME_FIELD;
            tg.cls = cls;
            tg.field = f;
        }
        return tg;
    }
    int in_doc = 0;
    Ast *entry = lspResolveValue(ctx, doc_id, line + 1, ident, ilen, &in_doc);
    if (entry) {
        tg.kind = KTM_LSP_RENAME_VALUE;
        tg.value = entry;
        tg.value_local = in_doc;
        return tg;
    }
    AstType *ty = (AstType *)mapGetLen(ctx->cc->clsdefs, (char *)ident, ilen);
    if (ty == NULL)
        ty = (AstType *)mapGetLen(ctx->cc->uniondefs, (char *)ident, ilen);
    if (ty) {
        tg.kind = KTM_LSP_RENAME_TYPE;
        tg.type = ty;
        return tg;
    }
    if (mapGetLen(ctx->cc->macro_defs, (char *)ident, ilen))
        tg.kind = KTM_LSP_RENAME_MACRO;
    return tg;
}

static int lspPathInStdlib(LspCtx *ctx, const char *path) {
    return path && strncmp(path, ctx->root_dir, strlen(ctx->root_dir)) == 0;
}

static int lspValidNewName(const char *s) {
    if (s == NULL || !lspIsIdentStart(s[0])) return 0;
    for (const char *p = s + 1; *p; ++p)
        if (!lspIsIdentChar(*p)) return 0;
    for (size_t i = 0; i < sizeof(lsp_keywords) / sizeof(lsp_keywords[0]); ++i)
        if (strcmp(lsp_keywords[i], s) == 0) return 0;
    return 1;
}

/* NULL when `newname` is free to use, else what it collides with.
 * Scoped per kind: a local only cares about names visible in its own
 * function, a field about its own class. */
static const char *lspRenameCollision(LspCtx *ctx, LspRenameTarget *tg,
                                      u32 doc_id, int line,
                                      const char *newname, s64 nlen)
{
    switch (tg->kind) {
    case KTM_LSP_RENAME_VALUE: {
        if (tg->value_local) {
            int in_doc = 0;
            Ast *hit = lspResolveValue(ctx, doc_id, line + 1, newname, nlen,
                                       &in_doc);
            if (hit && in_doc)
                return "a local with that name already exists here";
            return NULL;
        }
        if (mapGetLen(ctx->cc->global_env, (char *)newname, nlen) ||
            mapGetLen(ctx->cc->asm_funcs, (char *)newname, nlen))
            return "a global with that name already exists";
        return NULL;
    }
    case KTM_LSP_RENAME_FIELD:
        if (tg->cls->fields &&
            mapGetLen(tg->cls->fields, (char *)newname, nlen))
            return "the class already has a field with that name";
        return NULL;
    case KTM_LSP_RENAME_TYPE:
        if (mapGetLen(ctx->cc->clsdefs, (char *)newname, nlen) ||
            mapGetLen(ctx->cc->uniondefs, (char *)newname, nlen))
            return "a class/union with that name already exists";
        return NULL;
    case KTM_LSP_RENAME_MACRO:
        if (mapGetLen(ctx->cc->macro_defs, (char *)newname, nlen))
            return "a macro with that name already exists";
        return NULL;
    }
    return NULL;
}

/* The freshest text for `path`: an open document's buffer when the
 * client has one (matched by DECODED path - uri encodings differ),
 * else disk via the doc cache. *uri_out is the uri to key the
 * WorkspaceEdit with - for open documents it must be the client's own
 * uri string or the editor patches a different buffer. NOT owned by
 * the caller: either borrowed from the docs map or pool-allocated
 * (mprintf) - free()ing it would hand the pool allocator's memory to
 * libc. */
static AoStr *lspTextForPath(LspCtx *ctx, const char *path,
                             const char **uri_out)
{
    MapIter mi;
    mapIterInit(ctx->docs, &mi);
    while (mapIterNext(&mi)) {
        const char *doc_uri = (const char *)mi.node->key;
        char *doc_path = lspUriToPath(doc_uri);
        int hit = strcmp(doc_path, path) == 0;
        free(doc_path);
        if (hit) {
            *uri_out = doc_uri;
            return (AoStr *)mi.node->value;
        }
    }
    char *uri = mprintf("file://%s", (char *)path);
    AoStr *text = lspDocGetOrLoad(ctx, uri);
    if (text == NULL) return NULL;
    *uri_out = uri;
    return text;
}

/* Does occurrence `o` (in `text`, registered as `doc_id`) actually
 * refer to the target? The semantic filter shared by rename,
 * references and documentHighlight. */
static int lspOccurrenceMatches(LspCtx *ctx, LspRenameTarget *tg,
                                u32 doc_id, AoStr *text, LspOccurrence *o,
                                const char *name, s64 nlen)
{
    int in_doc = 0, is_member = 0;
    switch (tg->kind) {
    case KTM_LSP_RENAME_VALUE:
        if (o->member || o->hash) return 0;
        return lspResolveValue(ctx, doc_id, o->line + 1, name, nlen,
                               &in_doc) == tg->value;
    case KTM_LSP_RENAME_FIELD:
        if (o->hash) return 0;
        if (doc_id == tg->field->file_id &&
            o->line + 1 == tg->field->line &&
            tg->field->col > 0 && o->col == tg->field->col - 1)
            return 1; /* the declaration itself */
        if (!o->member) return 0;
        return lspMemberClassAt(ctx, text, doc_id, o->line, o->col,
                                &is_member) == tg->cls;
    case KTM_LSP_RENAME_TYPE:
        /* A same-named VALUE in scope shadows the type reading -
         * `Foo p;` never resolves as a value, a local `Foo` does. */
        return !o->member && !o->hash &&
               lspResolveValue(ctx, doc_id, o->line + 1, name, nlen,
                               &in_doc) == NULL;
    case KTM_LSP_RENAME_MACRO:
        return !o->member && !o->hash;
    }
    return 0;
}

/* Is this occurrence the target's DECLARATION? references uses it to
 * honor includeDeclaration=false; the provenance stamped at parse is
 * the ground truth (macros have none - their #define slot is). */
static int lspOccurrenceIsDecl(LspRenameTarget *tg, u32 doc_id,
                               LspOccurrence *o)
{
    u32 fid = 0;
    int line1 = 0, col1 = 0;
    switch (tg->kind) {
    case KTM_LSP_RENAME_VALUE:
        fid = tg->value->file_id;
        line1 = tg->value->line;
        col1 = tg->value->col;
        break;
    case KTM_LSP_RENAME_FIELD:
        fid = tg->field->file_id;
        line1 = tg->field->line;
        col1 = tg->field->col;
        break;
    case KTM_LSP_RENAME_TYPE:
        fid = tg->type->file_id;
        line1 = tg->type->line;
        col1 = tg->type->col;
        break;
    case KTM_LSP_RENAME_MACRO:
        return o->define;
    }
    return fid == doc_id &&
           o->line + 1 == line1 &&
           col1 > 0 &&
           o->col == col1 - 1;
}

/* Scan one file, append its `"uri":[TextEdit...]` entry to `body`
 * when anything matched. Returns the number of edits. */
static int lspRenameScanFile(LspCtx *ctx, LspRenameTarget *tg,
                             const char *uri, const char *path, AoStr *text,
                             const char *oldname, s64 olen,
                             const char *newname,
                             AoStr *body, int *files_emitted, int *def_seen)
{
    Vec *occs = lspFindOccurrences(text, oldname, (size_t)olen);
    if (occs->size == 0) {
        vecRelease(occs);
        return 0;
    }
    AoStr path_s = { .data = (char *)path, .len = strlen(path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);

    AoStr *edits = aoStrNew();
    int emitted = 0;
    for (u64 k = 0; k < occs->size; ++k) {
        LspOccurrence *o = occs->entries[k];
        if (!lspOccurrenceMatches(ctx, tg, doc_id, text, o, oldname, olen))
            continue;
        if (o->define) *def_seen = 1;
        if (emitted++) aoStrPutChar(edits, ',');
        aoStrPutChar(edits, '{');
        lspCatRange(ctx, edits, text, o->line, o->col, o->col + (int)olen);
        aoStrCatPrintf(edits, ",\"newText\":\"");
        jsonEscapeInto(edits, newname, strlen(newname));
        aoStrCatLen(edits, "\"}", 2);
    }
    vecRelease(occs);
    if (emitted) {
        if ((*files_emitted)++) aoStrPutChar(body, ',');
        aoStrPutChar(body, '"');
        jsonEscapeInto(body, uri, strlen(uri));
        aoStrCatLen(body, "\":[", 3);
        aoStrCatLen(body, edits->data, edits->len);
        aoStrPutChar(body, ']');
    }
    aoStrRelease(edits);
    return emitted;
}

/* Shared by rename and prepareRename: is this target renameable at
 * all, or does its definition live in the installed stdlib? */
static int lspRenameTargetInStdlib(LspCtx *ctx, LspRenameTarget *tg) {
    u32 def_fid = 0;
    if (tg->kind == KTM_LSP_RENAME_VALUE)      def_fid = tg->value->file_id;
    else if (tg->kind == KTM_LSP_RENAME_FIELD) def_fid = tg->field->file_id;
    else if (tg->kind == KTM_LSP_RENAME_TYPE)  def_fid = tg->type->file_id;
    if (def_fid == 0) return 0; /* macros: caught via def_seen instead */
    AoStr *def_file = cctrlLookUpFile(ctx->cc, def_fid);
    return def_file && lspPathInStdlib(ctx, def_file->data);
}

static void lspRename(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL ||
        !lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL))
    {
        lspRespond(id, "null");
        return;
    }
    const char *newname = jsonStrOr(params, "newName", NULL);
    if (newname == NULL || !lspValidNewName(newname)) {
        lspRespondError(id, -32602,
                        "the new name is not a valid ktm identifier");
        return;
    }
    s64 ilen = (s64)strlen(ident);
    s64 nlen = (s64)strlen(newname);
    if (ilen == nlen && memcmp(ident, newname, ilen) == 0) {
        lspRespond(id, "null");
        return;
    }

    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);

    LspRenameTarget tg = lspRenameTargetAt(ctx, text, doc_id, line, character,
                                           ident, ilen);
    if (tg.kind == KTM_LSP_RENAME_NONE) {
        free(doc_path);
        lspRespond(id, "null");
        return;
    }
    if (lspRenameTargetInStdlib(ctx, &tg)) {
        free(doc_path);
        lspRespondError(id, -32803, "cannot rename a stdlib symbol");
        return;
    }
    const char *clash = lspRenameCollision(ctx, &tg, doc_id, line,
                                           newname, nlen);
    if (clash) {
        free(doc_path);
        lspRespondError(id, -32803, clash);
        return;
    }

    AoStr *body = aoStrNew();
    aoStrCatPrintf(body, "{\"changes\":{");
    int files_emitted = 0, def_seen = 0;
    int total = lspRenameScanFile(ctx, &tg, uri, doc_path, text, ident, ilen,
                                  newname, body, &files_emitted, &def_seen);

    if (!(tg.kind == KTM_LSP_RENAME_VALUE && tg.value_local)) {
        MapIter mi;
        mapIterInit(ctx->cc->file_map, &mi);
        while (mapIterNext(&mi)) {
            AoStr *p = (AoStr *)mi.node->value;
            if (p == NULL || strcmp(p->data, doc_path) == 0) continue;
            if (lspPathInStdlib(ctx, p->data)) continue;
            const char *furi = NULL;
            AoStr *ftext = lspTextForPath(ctx, p->data, &furi);
            if (ftext == NULL) continue;
            total += lspRenameScanFile(ctx, &tg, furi, p->data, ftext, ident,
                                       ilen, newname, body, &files_emitted,
                                       &def_seen);
        }
    }
    free(doc_path);
    aoStrCatLen(body, "}}", 2);

    /* A macro carries no provenance, so the stdlib guard can't see it;
     * instead require its `#define` to be one of the edits - renaming
     * only the use sites would orphan them. */
    if (tg.kind == KTM_LSP_RENAME_MACRO && !def_seen) {
        aoStrRelease(body);
        lspRespondError(id, -32803,
                        "the macro's #define is outside the project");
        return;
    }
    if (total == 0) {
        aoStrRelease(body);
        lspRespond(id, "null");
        return;
    }
    lspRespond(id, body->data);
    lspLog(ctx, "lsp: rename %s -> %s: %d edit(s) in %d file(s)",
           ident, newname, total, files_emitted);
    aoStrRelease(body);
}

/* prepareRename: the range to highlight, or null when the position
 * isn't renameable - the editor learns "no" before prompting. */
static void lspPrepareRename(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character, col0 = 0;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL ||
        !lspIdentAt(text, line, character, 0, ident, sizeof(ident), &col0))
    {
        lspRespond(id, "null");
        return;
    }
    s64 ilen = (s64)strlen(ident);
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(doc_path);

    LspRenameTarget tg = lspRenameTargetAt(ctx, text, doc_id, line, character,
                                           ident, ilen);
    if (tg.kind == KTM_LSP_RENAME_NONE || lspRenameTargetInStdlib(ctx, &tg)) {
        lspRespond(id, "null");
        return;
    }
    AoStr *result = aoStrNew();
    aoStrPutChar(result, '{');
    lspCatRange(ctx, result, text, line, col0, col0 + (int)ilen);
    aoStrCatPrintf(result, ",\"placeholder\":\"");
    jsonEscapeInto(result, ident, (size_t)ilen);
    aoStrCatLen(result, "\"}", 2);
    lspRespond(id, result->data);
    aoStrRelease(result);
}

/* References and highlight: The read-only siblings of rename: same target resolution, same
 * occurrence scan, same semantic filter - the last step emits
 * Locations instead of TextEdits. One policy flips: a STDLIB symbol
 * is fair game ("where do I call MAlloc" is the whole point); the
 * stdlib's own files stay out of the scan, but the declaration is
 * synthesized from provenance when includeDeclaration asks for it. */

/* Scan one file for references; append `{"uri","range"}` Locations
 * to `body`. Returns the number appended. */
static int lspReferencesScanFile(LspCtx *ctx, LspRenameTarget *tg,
                                 const char *uri, const char *path,
                                 AoStr *text, const char *name, s64 nlen,
                                 int include_decl,
                                 AoStr *body, int *emitted)
{
    Vec *occs = lspFindOccurrences(text, name, (size_t)nlen);
    if (occs->size == 0) {
        free(occs);
        return 0;
    }
    AoStr path_s = { .data = (char *)path, .len = strlen(path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);

    int found = 0;
    for (u64 k = 0; k < occs->size; ++k) {
        LspOccurrence *o = occs->entries[k];
        if (!lspOccurrenceMatches(ctx, tg, doc_id, text, o, name, nlen))
            continue;
        if (!include_decl && lspOccurrenceIsDecl(tg, doc_id, o)) continue;
        if ((*emitted)++) aoStrPutChar(body, ',');
        aoStrCatPrintf(body, "{\"uri\":\"");
        jsonEscapeInto(body, uri, strlen(uri));
        aoStrCatLen(body, "\",", 2);
        lspCatRange(ctx, body, text, o->line, o->col, o->col + (int)nlen);
        aoStrPutChar(body, '}');
        found++;
    }
    free(occs);
    return found;
}

static void lspReferences(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL ||
        !lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL))
    {
        lspRespond(id, "null");
        return;
    }
    int include_decl = jsonBoolOr(jsonObjGet(params, "context"),
                                  "includeDeclaration", 0);
    s64 ilen = (s64)strlen(ident);
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);

    LspRenameTarget tg = lspRenameTargetAt(ctx, text, doc_id, line, character,
                                           ident, ilen);
    if (tg.kind == KTM_LSP_RENAME_NONE) {
        free(doc_path);
        lspRespond(id, "null");
        return;
    }

    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    int emitted = 0;
    lspReferencesScanFile(ctx, &tg, uri, doc_path, text, ident, ilen,
                          include_decl, body, &emitted);

    if (!(tg.kind == KTM_LSP_RENAME_VALUE && tg.value_local)) {
        MapIter mi;
        mapIterInit(ctx->cc->file_map, &mi);
        while (mapIterNext(&mi)) {
            AoStr *p = (AoStr *)mi.node->value;
            if (p == NULL || strcmp(p->data, doc_path) == 0) continue;
            if (lspPathInStdlib(ctx, p->data)) continue;
            const char *furi = NULL;
            AoStr *ftext = lspTextForPath(ctx, p->data, &furi);
            if (ftext == NULL) continue;
            lspReferencesScanFile(ctx, &tg, furi, p->data, ftext, ident,
                                  ilen, include_decl, body, &emitted);
        }
        /* A stdlib symbol's declaration lives in a file the loop just
         * skipped - synthesize its Location from provenance. */
        if (include_decl && lspRenameTargetInStdlib(ctx, &tg)) {
            u32 fid = 0;
            int line1 = 0, col1 = 0;
            if (tg.kind == KTM_LSP_RENAME_VALUE) {
                fid = tg.value->file_id;
                line1 = tg.value->line;
                col1 = tg.value->col;
            } else if (tg.kind == KTM_LSP_RENAME_FIELD) {
                fid = tg.field->file_id;
                line1 = tg.field->line;
                col1 = tg.field->col;
            } else if (tg.kind == KTM_LSP_RENAME_TYPE) {
                fid = tg.type->file_id;
                line1 = tg.type->line;
                col1 = tg.type->col;
            }
            AoStr *def_file = cctrlLookUpFile(ctx->cc, fid);
            if (def_file && line1 > 0) {
                int c0 = col1 > 0 ? col1 - 1 : 0;
                int c1 = col1 > 0 ? c0 + (int)ilen : 0;
                if (emitted++) aoStrPutChar(body, ',');
                const char *def_uri = NULL;
                AoStr *def_text = lspTextForPath(ctx, def_file->data,
                                                 &def_uri);
                aoStrCatPrintf(body, "{\"uri\":\"file://");
                jsonEscapeInto(body, def_file->data, def_file->len);
                aoStrCatLen(body, "\",", 2);
                lspCatRange(ctx, body, def_text, line1 - 1, c0, c1);
                aoStrPutChar(body, '}');
            }
        }
    }
    free(doc_path);
    aoStrPutChar(body, ']');
    lspRespond(id, body->data);
    lspLog(ctx, "lsp: references %s -> %d location(s)", ident, emitted);
    aoStrRelease(body);
}

/* documentHighlight: every occurrence in THIS document, declaration
 * included - the same query as references, scoped to one buffer. */
static void lspDocumentHighlight(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    char ident[256];
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL ||
        !lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL))
    {
        lspRespond(id, "null");
        return;
    }
    s64 ilen = (s64)strlen(ident);
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(doc_path);

    LspRenameTarget tg = lspRenameTargetAt(ctx, text, doc_id, line, character,
                                           ident, ilen);
    if (tg.kind == KTM_LSP_RENAME_NONE) {
        lspRespond(id, "null");
        return;
    }
    Vec *occs = lspFindOccurrences(text, ident, (size_t)ilen);
    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    int emitted = 0;
    for (u64 k = 0; k < occs->size; ++k) {
        LspOccurrence *o = occs->entries[k];
        if (!lspOccurrenceMatches(ctx, &tg, doc_id, text, o, ident, ilen))
            continue;
        if (emitted++) aoStrPutChar(body, ',');
        aoStrPutChar(body, '{');
        lspCatRange(ctx, body, text, o->line, o->col, o->col + (int)ilen);
        aoStrPutChar(body, '}');
    }
    vecRelease(occs);
    aoStrPutChar(body, ']');
    lspRespond(id, body->data);
    aoStrRelease(body);
}

/* Answer a textDocument request from an isolated child; if the child
 * crashed or hung on the document, answer null ourselves. */
static void lspServe(LspCtx *ctx, Json *id, Json *params,
                     LspHandler handler, int with_open_docs)
{
    const char *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri",
                                NULL);
    if (uri == NULL) {
        lspRespond(id, "null");
        return;
    }
    int sig = lspRunIsolated(ctx, uri, with_open_docs, handler, id, params);
    if (sig) {
        lspLog(ctx, "lsp: request failed on %s (signal %d)", uri, sig);
        lspRespond(id, "null");
    }
}

/* Does the client list utf-8 among general.positionEncodings? Without
 * it LSP mandates utf-16 (VS Code, older clients). */
static int lspClientSpeaksUtf8(Json *params) {
    Json *general = jsonObjGet(jsonObjGet(params, "capabilities"), "general");
    Json *encs = jsonObjGet(general, "positionEncodings");
    if (encs == NULL || encs->kind != JSON_ARRAY) return 0;
    for (u64 i = 0; i < (u64)encs->as.size; ++i) {
        Json *e = jsonArrayAt(encs, i);
        if (e && e->kind == JSON_STRING && strcmp(e->as.str, "utf-8") == 0)
            return 1;
    }
    return 0;
}

static void lspInitialize(LspCtx *ctx, Json *id, Json *params) {
    ctx->utf16 = !lspClientSpeaksUtf8(params);
    AoStr *result = aoStrNew();
    aoStrCatPrintf(result,
        "{\"capabilities\":{"
        "\"positionEncoding\":\"%s\","
        "\"textDocumentSync\":{\"openClose\":true,\"change\":1},"
        "\"hoverProvider\":true,"
        "\"definitionProvider\":true,"
        "\"completionProvider\":{\"triggerCharacters\":[\".\",\">\"]},"
        "\"renameProvider\":{\"prepareProvider\":true},"
        "\"referencesProvider\":true,"
        "\"documentHighlightProvider\":true"
        "},\"serverInfo\":{\"name\":\"ktm\",\"version\":\"%s\"}}",
        ctx->utf16 ? "utf-16" : "utf-8", cctrlGetVersion());
    lspRespond(id, result->data);
    aoStrRelease(result);
}

int lspRun(CliArgs *args) {
    LspCtx lsp_ctx = {0};
    LspCtx *ctx = &lsp_ctx;
    ctx->log_on = getenv("KTM_LSP_LOG") != NULL;
    ctx->target = args->target;
    ctx->root_dir = mprintf("%s/include/", args->install_dir);
    ctx->docs = mapNew(16, &map_cstring_opaque_type);
    ctx->pending = vecNew(&vec_cstring_owned_type);
    ctx->cc = lspFreshCctrl(ctx);
    lspInstallCrashHandlers();
    /* The debounce poll()s fd 0; an stdio read-ahead buffer would make
     * poll block on data the process already holds. Unbuffered stdin
     * keeps the two views identical (headers are tens of bytes, and
     * fread still pulls the body in bulk). */
    setvbuf(stdin, NULL, _IONBF, 0);
    lspLog(ctx, "lsp: ktm %s ready", cctrlGetVersion());

    Arena *arena = arenaNew(1 << 16);
    for (;;) {
        if (!vecEmpty(ctx->pending)) {
            if (lspNowMs() - ctx->pending_since_ms >= KTM_LSP_DEBOUNCE_MAX_MS ||
                !lspInputWait(KTM_LSP_DEBOUNCE_MS))
            {
                lspFlushPending(ctx);
                continue;
            }
        }
        size_t len = 0;
        char *raw = lspReadMessage(&len);
        if (raw == NULL) break; /* EOF or framing error */

        arenaReset(arena);
        const char *jerr = NULL;
        Json *msg = jsonParse(arena, raw, len, &jerr);
        if (msg == NULL) {
            lspLog(ctx, "lsp: bad JSON (%s)", jerr ? jerr : "?");
            free(raw);
            continue;
        }

        const char *method = jsonStrOr(msg, "method", "");
        Json *id = jsonObjGet(msg, "id");
        Json *params = jsonObjGet(msg, "params");
        lspLog(ctx, "lsp: <- %s", method);

        if (strcmp(method, "initialize") == 0) {
            lspInitialize(ctx, id, params);
        } else if (strcmp(method, "exit") == 0) {
            free(raw);
            break;
        } else if (ctx->shutdown_requested && id != NULL) {
            /* After shutdown only exit is valid (LSP 3.17). */
            lspRespondError(id, -32600, "server is shutting down");
        } else if (strcmp(method, "shutdown") == 0) {
            ctx->shutdown_requested = 1;
            lspRespond(id, "null");
        } else if (strcmp(method, "textDocument/didOpen") == 0) {
            lspDidOpen(ctx, params);
        } else if (strcmp(method, "textDocument/didChange") == 0) {
            lspDidChange(ctx, params);
        } else if (strcmp(method, "textDocument/didClose") == 0) {
            lspDidClose(ctx, params);
        } else if (strcmp(method, "textDocument/hover") == 0) {
            lspServe(ctx, id, params, lspHover, 0);
        } else if (strcmp(method, "textDocument/completion") == 0) {
            lspServe(ctx, id, params, lspCompletion, 0);
        } else if (strcmp(method, "textDocument/definition") == 0) {
            lspServe(ctx, id, params, lspDefinition, 0);
        } else if (strcmp(method, "textDocument/rename") == 0) {
            lspServe(ctx, id, params, lspRename, 1);
        } else if (strcmp(method, "textDocument/prepareRename") == 0) {
            lspServe(ctx, id, params, lspPrepareRename, 0);
        } else if (strcmp(method, "textDocument/references") == 0) {
            lspServe(ctx, id, params, lspReferences, 1);
        } else if (strcmp(method, "textDocument/documentHighlight") == 0) {
            lspServe(ctx, id, params, lspDocumentHighlight, 0);
        } else if (id != NULL) {
            /* Requests we don't implement yet; notifications
             * (initialized, didSave, $/...) are silently fine. */
            lspRespondError(id, -32601, "method not implemented");
        }
        free(raw);
    }
    arenaRelease(arena);
    return ctx->shutdown_requested ? 0 : 1;
}
