/* LSP server for ktm: full document sync, every analysis and query
 * answered from a fresh parse in a fork()ed child (lspRunIsolated). */

#include <ctype.h>
#include <dirent.h>
#include <sys/stat.h>
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
    char *std_dir;          /* <install>/modules/: the standard library */
    enum CliTarget target;
    int shutdown_requested;
    int utf16;              /* wire columns are utf-16 units, not bytes */
    int log_on;
    Vec *pending;           /* uris awaiting a debounced analyze */
    const char *analyzing;  /* uri lspPublishHandler publishes for */
    s64 pending_since_ms;   /* when the oldest pending uri landed */
    int module_doc;         /* the last parse was a module's file */
} LspCtx;

/* A module's file is analyzed as the compiler reads it: through a
 * program, named this, that imports the module */
#define KTM_LSP_MODULE_FILE ".ktm-lsp-module.ktm"

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
    /* the prelude and the runtime (the stdlib is modules, read when
     * imported) */
    Lexer *l = (Lexer *)malloc(sizeof(Lexer));
    lexInit(l, NULL, CCF_PRE_PROC);
    l->cc = cc; /* lex-time raises route through diagnostics, not exit */
    lexSetBuiltinRoot(l, ctx->root_dir);
    lexPushRuntime(l);
    lexPushPrelude(l);

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
    cc->flags |= CCTRL_REPL | CCTRL_LSP; /* redefinition replaces - re-parses work */
    cctrlAddDefine(cc, "__KTM_AOT__");
    lspBootstrap(ctx, cc);
    return cc;
}

/* Parse `text` as `path`, leaving diagnostics on ctx->cc->diagnostics.
 * Returns 0 normally, the killing signal if the parser crashed. Only
 * ever called inside an isolated child (lspRunIsolated). */
/* The lexer reads a file open in the editor from its text (a copy: the
 * lexer owns what it reads), NULL for any other */
static char *lspSourceFor(void *arg, const char *path, s64 *_len) {
    LspCtx *ctx = arg;
    MapIter mi;
    mapIterInit(ctx->docs, &mi);
    while (mapIterNext(&mi)) {
        char *doc_path = lspUriToPath((const char *)mi.node->key);
        int hit = strcmp(doc_path, path) == 0;
        free(doc_path);
        if (!hit) continue;
        AoStr *t = mi.node->value;
        char *copy = malloc(t->len + 1);
        memcpy(copy, t->data, t->len);
        copy[t->len] = '\0';
        *_len = (s64)t->len;
        return copy;
    }
    return NULL;
}

static char *lspDocModule(AoStr *text);

static int lspIsDirPath(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

/* A file starting `module a.b;` in a directory .../a/b: the directory
 * the module is found from (*_mod "a.b"), else NULL */
static char *lspModuleRoot(const char *path, AoStr *text, char **_mod) {
    char *mod = lspDocModule(text);
    const char *slash = strrchr(path, '/');
    if (!mod || !slash) return NULL;
    char *rel = mprintf("%s", mod);
    for (char *p = rel; *p; ++p) if (*p == '.') *p = '/';
    size_t dlen = slash - path, rlen = strlen(rel);
    if (dlen <= rlen || memcmp(path + dlen - rlen, rel, rlen) ||
        path[dlen - rlen - 1] != '/') {
        return NULL;
    }
    *_mod = mod;
    return mprintf("%.*s", (int)(dlen - rlen - 1), path);
}

static int lspParse(LspCtx *ctx, const char *path, AoStr *text) {
    Lexer *l = (Lexer *)malloc(sizeof(Lexer));
    lexInit(l, NULL, CCF_PRE_PROC);
    l->cc = ctx->cc; /* lex-time raises route through diagnostics */
    lexSetBuiltinRoot(l, ctx->root_dir);
    l->source_for = lspSourceFor;
    l->source_arg = ctx;
    char *mod = NULL;
    char *root = lspModuleRoot(path, text, &mod);
    ctx->module_doc = root != NULL;
    if (root) {
        char *src = mprintf("import %s;\n", mod);
        lexPushString(l, mprintf("%s/%s", root, KTM_LSP_MODULE_FILE), src,
                      strlen(src));
        /* A tree with rt and prelude is a standard library's source: it
         * is the standard library while one of its files is edited, read
         * into a compiler without the installed one (this child's own) */
        if (lspIsDirPath(mprintf("%s/rt", root)) &&
            lspIsDirPath(mprintf("%s/prelude", root))) {
            l->std_modules = root;
            ctx->cc = cctrlNew(ctx->target);
            ctx->cc->flags |= CCTRL_REPL | CCTRL_LSP;
            cctrlAddDefine(ctx->cc, "__KTM_AOT__");
            l->cc = ctx->cc;
            lexPushRuntime(l);
            lexPushPrelude(l);
        }
    } else {
        lexPushString(l, (char *)path, text->data, text->len);
    }

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

static char *lspDocModule(AoStr *text);

/* A module's files see each other's names bare, and the compiler reads
 * them all together. When `uri` is one of them (`module a.b;`), parse
 * the others in its directory first (their open text, else the file),
 * so its uses of their names resolve. Open documents are skipped when
 * `skip_open`: they were parsed already. */
static int lspParseModuleSiblings(LspCtx *ctx, const char *uri, int skip_open) {
    AoStr *text = lspDocGetOrLoad(ctx, uri);
    if (text == NULL || lspDocModule(text) == NULL) return 0;
    char *path = lspUriToPath(uri);
    char *mod = NULL;
    if (lspModuleRoot(path, text, &mod)) {
        /* its import reads them all */
        free(path);
        return 0;
    }
    char *slash = strrchr(path, '/');
    if (!slash) {
        free(path);
        return 0;
    }
    char *dir_path = mprintf("%.*s", (int)(slash - path), path);
    DIR *dir = opendir(dir_path);
    int crashed = 0;
    struct dirent *ent;
    while (dir && !crashed && (ent = readdir(dir)) != NULL) {
        size_t n = strlen(ent->d_name);
        if (n < 5 || strcmp(ent->d_name + n - 4, ".ktm") != 0) continue;
        char *other = mprintf("%s/%s", dir_path, ent->d_name);
        if (strcmp(other, path) == 0) continue;
        const char *other_uri = NULL;
        int was_open = skip_open && mapHas(ctx->docs, mprintf("file://%s", other));
        AoStr *other_text = was_open ? NULL : lspTextForPath(ctx, other, &other_uri);
        if (other_text) crashed = lspParse(ctx, other, other_text);
    }
    if (dir) closedir(dir);
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
        int sig = lspParseModuleSiblings(ctx, uri, with_open_docs);
        if (sig) _exit(KTM_LSP_CHILD_CRASHED + sig);
        /* Parsed last so its own symbols win any redefinition. */
        sig = lspParseUri(ctx, uri);
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
        /* the module's program says nothing of its own; the module's
         * other files only matter for their errors */
        if (dfile && strstr(dfile, KTM_LSP_MODULE_FILE)) continue;
        if (remote && ctx->module_doc && d->severity != CCTRL_ERROR) continue;
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

/* Skips spaces and comments from p up to end */
static const char *lspSkipSpace(const char *p, const char *end) {
    for (;;) {
        while (p < end && isspace((unsigned char)*p)) p++;
        if (end - p >= 2 && p[0] == '/' && p[1] == '/') {
            while (p < end && *p != '\n') p++;
        } else if (end - p >= 2 && p[0] == '/' && p[1] == '*') {
            const char *c = p + 2;
            while (end - c >= 2 && !(c[0] == '*' && c[1] == '/')) c++;
            p = end - c >= 2 ? c + 2 : end;
        } else {
            return p;
        }
    }
}

/* Calls fn(path, path_len, prefix, prefix_len, arg) for each module of
 * every `import [x =] a.b {, [y =] c};` in `text` (a list may span
 * lines), the prefix being the alias or the path's last part. Stops at
 * the first non-zero return of fn and returns it. */
typedef int LspImportFn(const char *path, size_t path_len, const char *prefix,
                        size_t prefix_len, void *arg);
static int lspEachImport(AoStr *text, LspImportFn *fn, void *arg) {
    const char *s = text->data, *end = s + text->len;
    while (s < end) {
        const char *eol = memchr(s, '\n', end - s);
        if (!eol) eol = end;
        const char *p = s;
        while (p < eol && isspace((unsigned char)*p)) p++;
        if (eol - p > 7 && !memcmp(p, "import", 6) && isspace((unsigned char)p[6])) {
            p += 6;
            for (;;) {
                const char *w[2];
                size_t wl[2];
                int nw = 0, alias = 0;
                while (nw < 2) {
                    p = lspSkipSpace(p, end);
                    if (p < end && *p == '=') { alias = 1; p++; continue; }
                    const char *q = p;
                    while (q < end && (lspIsIdentChar(*q) || *q == '.')) q++;
                    if (q == p) break;
                    w[nw] = p;
                    wl[nw++] = q - p;
                    p = q;
                    /* a path alone: `import a.b, ...` */
                    if (!alias && nw == 1) {
                        const char *n = lspSkipSpace(p, end);
                        if (n >= end || *n != '=') break;
                    }
                }
                if (nw >= 1) {
                    int aliased = alias && nw == 2;
                    const char *path = aliased ? w[1] : w[0];
                    size_t path_len = aliased ? wl[1] : wl[0];
                    const char *prefix = aliased ? w[0] : path;
                    size_t prefix_len = aliased ? wl[0] : 0;
                    if (!aliased) {
                        for (size_t i = 0; i < path_len; ++i) {
                            if (path[i] == '.') prefix = path + i + 1;
                        }
                        prefix_len = path + path_len - prefix;
                    }
                    int r = fn(path, path_len, prefix, prefix_len, arg);
                    if (r) return r;
                }
                p = lspSkipSpace(p, end);
                if (p < end && *p == ',') { p++; continue; }
                break;
            }
            if (p > eol) {
                eol = memchr(p, '\n', end - p);
                if (!eol) eol = end;
            }
        }
        s = eol + 1;
    }
    return 0;
}

typedef struct LspImportFind {
    const char *recv;
    size_t rlen;
    char *path;
} LspImportFind;

static int lspImportMatch(const char *path, size_t path_len,
                          const char *prefix, size_t prefix_len, void *arg) {
    LspImportFind *f = arg;
    if (prefix_len != f->rlen || memcmp(prefix, f->recv, prefix_len)) return 0;
    f->path = mprintf("%.*s", (int)path_len, path);
    return 1;
}

/* The module path `import a.b;` (prefix b) or `import x = a.b;` (prefix
 * x), alone or in a list, in `text` gives `recv`; NULL if no import does */
static char *lspImportPath(AoStr *text, const char *recv, size_t rlen) {
    LspImportFind f = { .recv = recv, .rlen = rlen, .path = NULL };
    lspEachImport(text, lspImportMatch, &f);
    return f.path;
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

/* A module's value `a.b.name`: a function or global, or an `_extern`
 * binding to an asm label (mem.alloc), which lives in asm_funcs */
static Ast *lspModuleValue(LspCtx *ctx, const char *qual) {
    Ast *entry = mapGet(ctx->cc->global_env, (char *)qual);
    return entry ? entry : mapGet(ctx->cc->asm_funcs, (char *)qual);
}

static char *lspDocModule(AoStr *text);

/* A bare name in one of a module's own files (`square` in geo's files is
 * `geo.square`): "a.b.name" when the module has such a value or type and
 * no local or parameter by that name hides it, else NULL */
static char *lspOwnModuleQual(LspCtx *ctx, AoStr *text, u32 doc_id,
                              int cursor_line, const char *ident, s64 ilen)
{
    char *mod = lspDocModule(text);
    if (!mod) return NULL;
    int in_doc = 0;
    lspResolveValue(ctx, doc_id, cursor_line, ident, ilen, &in_doc);
    if (in_doc) return NULL;
    char *qual = mprintf("%s.%.*s", mod, (int)ilen, ident);
    if (lspModuleValue(ctx, qual) || mapGet(ctx->cc->clsdefs, qual) ||
        mapGet(ctx->cc->uniondefs, qual))
    {
        return qual;
    }
    return NULL;
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

/* An overloaded function at (line1 1-based, character 0-based): the
 * overload a call there picked, or the one declared on that line; NULL
 * when neither (the name alone: every overload applies) */
static Ast *lspPickOverload(LspCtx *ctx, u32 doc_id, int line1, int character,
                            Ast *entry) {
    if (!astOverloads(entry)) return entry;
    Vec *calls = ctx->cc->overload_calls;
    for (u64 i = 0; calls && i < calls->size; ++i) {
        CctrlOverloadCall *c = calls->entries[i];
        if (c->file_id == doc_id && c->line == line1 &&
            character + 1 >= c->col && character + 1 < c->col + c->len) {
            return c->fn;
        }
    }
    for (u64 i = 0; i < entry->overloads->size; ++i) {
        Ast *fn = entry->overloads->entries[i];
        if (fn->file_id == doc_id && fn->line == line1) return fn;
    }
    return NULL;
}

/* Every overload of fn as `render` shows one, a line each */
static void lspCatOverloads(AoStr *out, Ast *fn, const char *qual) {
    for (u64 i = 0; i < fn->overloads->size; ++i) {
        if (i) aoStrPutChar(out, '\n');
        aoStrCat(out, lspModuleFnString(fn->overloads->entries[i], qual));
    }
}

/* A generic function or struct named `ident` (as written in the
 * document, or `mod.name`): its definition, NULL if none */
static GenericDef *lspGeneric(LspCtx *ctx, const char *ident, s64 ilen) {
    Cctrl *cc = ctx->cc;
    GenericDef *d = cc->generic_funcs ? mapGetLen(cc->generic_funcs, (char *)ident, ilen) : NULL;
    if (!d && cc->generic_structs) d = mapGetLen(cc->generic_structs, (char *)ident, ilen);
    return d;
}

/* `T maxOf<T>(T a, T b)`: a generic's declaration up to its body */
static void lspCatGenericHead(AoStr *out, GenericDef *d) {
    for (u64 i = 0; i < d->toks->size; ++i) {
        Lexeme *t = d->toks->entries[i];
        if (tokenPunctIs(t, '{') || tokenPunctIs(t, ';')) break;
        if (tokenPunctIs(t, ',')) { aoStrCat(out, ", "); continue; }
        int glue = out->len == 0 || tokenPunctIs(t, '(') || tokenPunctIs(t, ')') ||
                   tokenPunctIs(t, '<') || tokenPunctIs(t, '>') || tokenPunctIs(t, '.') ||
                   (out->len && (out->data[out->len - 1] == '(' || out->data[out->len - 1] == '<' ||
                                 out->data[out->len - 1] == '.' || out->data[out->len - 1] == ' '));
        if (!glue) aoStrPutChar(out, ' ');
        aoStrCat(out, lexemeAsWritten(t));
    }
}

/* A struct's or field's attributes as written, each followed by a space:
 * `@json @yaml `, `@key("e-mail") ` */
static void lspCatAttrs(AoStr *out, Vec *attrs) {
    if (!attrs) return;
    for (u64 i = 0; i < attrs->size; ++i) {
        AstAttr *a = attrs->entries[i];
        aoStrCatPrintf(out, "@%s", a->name->data);
        if (a->arg) aoStrCatPrintf(out, "(\"%s\")", a->arg->data);
        aoStrPutChar(out, ' ');
    }
}

/* A struct, union or enum as hover shows it */
static void lspCatType(AoStr *out, AstType *ty) {
    if (ty->is_enum) {
        aoStrCatPrintf(out, "enum %s\n{\n", ty->clsname ? ty->clsname->data : "");
        if (ty->enum_members) {
            MapIter it;
            mapIterInit(ty->enum_members, &it);
            while (mapIterNext(&it)) {
                Ast *m = it.node->value;
                aoStrCatPrintf(out, "  %s = %lld,\n", (char *)it.node->key,
                               (long long)m->i64);
            }
        }
        aoStrCat(out, "};");
        return;
    }
    if (ty->attrs && ty->attrs->size) {   /* on their own line, as written */
        lspCatAttrs(out, ty->attrs);
        out->data[out->len - 1] = '\n';
    }
    if (!ty->fields) {
        aoStrCatPrintf(out, "%s %s;", ty->kind == AST_TYPE_UNION ? "union" : "struct",
                       ty->clsname ? ty->clsname->data : "");
        return;
    }
    AoStr *body = astClassToAoStr(ty);
    /* the compiler's dump says `class`: ktm writes `struct` */
    if (body->len > 6 && !memcmp(body->data, "class ", 6)) {
        aoStrCat(out, "struct");
        aoStrCatLen(out, body->data + 5, body->len - 5);
    } else {
        aoStrCatLen(out, body->data, body->len);
    }
    aoStrRelease(body);
}

/* A type named as written in `text` (`Color`, `geo.Kind`, or a bare name
 * of the document's own module): its struct, union or enum, NULL if none */
static AstType *lspTypeByName(LspCtx *ctx, AoStr *text, const char *name,
                              size_t len) {
    Cctrl *cc = ctx->cc;
    const char *dot = memchr(name, '.', len);
    if (dot) {
        char *path = lspImportPath(text, name, dot - name);
        if (path) {
            char *q = mprintf("%s%.*s", path, (int)(name + len - dot), dot);
            AstType *t = mapGet(cc->clsdefs, q);
            return t ? t : mapGet(cc->uniondefs, q);
        }
    } else {
        char *mod = lspDocModule(text);
        if (mod) {
            char *q = mprintf("%s.%.*s", mod, (int)len, name);
            AstType *t = mapGet(cc->clsdefs, q);
            if (t) return t;
        }
    }
    AstType *t = mapGetLen(cc->clsdefs, (char *)name, len);
    return t ? t : mapGetLen(cc->uniondefs, (char *)name, len);
}

/* The field of `ty` declared at (line1, col1) and named name, or NULL */
static AstType *lspFieldDeclaredAt(AstType *ty, u32 doc_id, int line1,
                                   int col1, const char *name, size_t len) {
    if (!ty || !ty->fields || ty->is_enum) return NULL;
    AstType *f = mapGetLen(ty->fields, (char *)name, len);
    if (f && f->file_id == doc_id && f->line == line1 && f->col == col1) {
        return f;
    }
    return NULL;
}

/* `T Box<T>.v`: a generic struct's field as its declaration writes it
 * (the type from its tokens, an instance's field has the same place) */
static void lspCatGenericField(AoStr *out, GenericDef *d, AstType *f,
                               const char *name) {
    s64 at = -1, from = 0;
    for (u64 i = 0; i < d->toks->size; ++i) {
        Lexeme *t = d->toks->entries[i];
        if (tokenPunctIs(t, '{') || tokenPunctIs(t, ';')) from = (s64)i + 1;
        if (t->line == f->line && t->col == f->col) {
            at = (s64)i;
            break;
        }
    }
    if (at > from) {
        for (s64 i = from; i < at; ++i) {
            Lexeme *t = d->toks->entries[i];
            if (tokenPunctIs(t, ',')) break;
            int glue = out->len == 0 || tokenPunctIs(t, '<') || tokenPunctIs(t, '>') ||
                       tokenPunctIs(t, '[') || tokenPunctIs(t, ']') ||
                       out->data[out->len - 1] == '<' || out->data[out->len - 1] == '[';
            if (!glue) aoStrPutChar(out, ' ');
            aoStrCat(out, lexemeAsWritten(t));
        }
        aoStrPutChar(out, ' ');
    }
    aoStrCatPrintf(out, "%s<", d->name->data);
    for (u64 i = 0; i < d->params->size; ++i) {
        AoStr *pn = d->params->entries[i];
        aoStrCatPrintf(out, "%s%s", i ? ", " : "", pn->data);
    }
    aoStrCatPrintf(out, ">.%s", name);
}

/* A field's name where its struct or union declares it: the field
 * (*_owner its type), NULL otherwise */
static AstType *lspFieldDeclHere(LspCtx *ctx, AoStr *text, u32 doc_id, int line,
                                 int character, AstType **_owner) {
    const char *s = text->data;
    size_t line_start = lspLineStart(text, line), line_end = line_start;
    while (line_end < text->len && s[line_end] != '\n') line_end++;
    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;
    size_t start = pos, end = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    while (end < line_end && lspIsIdentChar(s[end])) end++;
    if (end == start || (start > line_start && s[start - 1] == '.')) return NULL;
    int col1 = (int)(start - line_start) + 1;
    /* a generic struct's own field, not one of its instances' copies */
    if (ctx->cc->generic_structs) {
        MapIter it;
        mapIterInit(ctx->cc->generic_structs, &it);
        while (mapIterNext(&it)) {
            GenericDef *d = it.node->value;
            AstType *f = d ? lspFieldDeclaredAt(d->tmpl, doc_id, line + 1, col1,
                                                s + start, end - start) : NULL;
            if (f) {
                if (_owner) *_owner = d->tmpl;
                return f;
            }
        }
    }
    Map *maps[2] = { ctx->cc->clsdefs, ctx->cc->uniondefs };
    for (int k = 0; k < 2; ++k) {
        MapIter it;
        mapIterInit(maps[k], &it);
        while (mapIterNext(&it)) {
            AstType *ty = it.node->value;
            AstType *f = lspFieldDeclaredAt(ty, doc_id, line + 1, col1,
                                            s + start, end - start);
            if (f) {
                if (_owner) *_owner = ty;
                return f;
            }
        }
    }
    return NULL;
}

static char *lspModuleDir(LspCtx *ctx, const char *path);

/* Is byte offset `at` inside an `import ...;` statement? */
static int lspInImport(AoStr *text, size_t at) {
    const char *s = text->data;
    size_t i = at;
    while (i > 0 && s[i - 1] != ';' && s[i - 1] != '{' && s[i - 1] != '}') i--;
    const char *p = lspSkipSpace(s + i, s + at);
    /* a statement after a #directive or a comment line: its own line */
    while (p < s + at && *p == '#') {
        const char *eol = memchr(p, '\n', (s + at) - p);
        if (!eol) return 0;
        p = lspSkipSpace(eol + 1, s + at);
    }
    return (s + at) - p > 6 && !memcmp(p, "import", 6) && isspace((unsigned char)p[6]);
}

/* A module's name at the cursor: in an import list, or as the prefix of
 * `geo.area`. Its path (`a.b`), NULL when the word isn't one or a local
 * or parameter of that name hides it. */
static char *lspModuleNameAt(LspCtx *ctx, AoStr *text, u32 doc_id, int line,
                             int character) {
    const char *s = text->data;
    size_t line_start = lspLineStart(text, line), line_end = line_start;
    while (line_end < text->len && s[line_end] != '\n') line_end++;
    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;
    size_t start = pos, end = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    while (end < line_end && lspIsIdentChar(s[end])) end++;
    if (end == start) return NULL;
    if (lspInImport(text, start)) {
        /* `import x = a.b, c;`: an alias or any part of a path */
        const char *eq = s + end;
        while (eq < s + text->len && isspace((unsigned char)*eq)) eq++;
        if (eq < s + text->len && *eq == '=') {
            return lspImportPath(text, s + start, end - start);
        }
        size_t p0 = start, p1 = end;
        while (p0 > 0 && (lspIsIdentChar(s[p0 - 1]) || s[p0 - 1] == '.')) p0--;
        while (p1 < text->len && (lspIsIdentChar(s[p1]) || s[p1] == '.')) p1++;
        char *path = mprintf("%.*s", (int)(p1 - p0), s + p0);
        return lspModuleDir(ctx, path) ? path : NULL;
    }
    if (start > line_start && s[start - 1] == '.') return NULL;
    if (end >= line_end || s[end] != '.') return NULL;
    char word[256];
    size_t wlen = 0;
    for (size_t i = start; i < end; ++i) {
        if (wlen < sizeof(word) - 1) word[wlen++] = s[i];
    }
    word[wlen] = '\0';
    int in_doc = 0;
    lspResolveValue(ctx, doc_id, line + 1, word, (s64)wlen, &in_doc);
    if (in_doc) return NULL;
    return lspImportPath(text, word, wlen);
}

/* The directory module `path` was read from: any of its declarations'
 * file's directory. NULL when nothing of it was loaded. */
static char *lspModuleDir(LspCtx *ctx, const char *path) {
    size_t plen = strlen(path);
    Map *maps[3] = { ctx->cc->global_env, ctx->cc->clsdefs, ctx->cc->asm_funcs };
    for (int k = 0; k < 3; ++k) {
        MapIter it;
        mapIterInit(maps[k], &it);
        while (mapIterNext(&it)) {
            const char *key = it.node->key;
            if (strncmp(key, path, plen) || key[plen] != '.' ||
                strchr(key + plen + 1, '.')) {
                continue;
            }
            u32 file_id = k == 1 ? ((AstType *)it.node->value)->file_id
                                 : ((Ast *)it.node->value)->file_id;
            AoStr *f = file_id ? cctrlLookUpFile(ctx->cc, file_id) : NULL;
            char *slash = f ? strrchr(f->data, '/') : NULL;
            if (!slash) continue;
            char abs_dir[PATH_MAX];
            char *dir = mprintf("%.*s", (int)(slash - f->data), f->data);
            if (dir[0] != '/' && realpath(dir, abs_dir)) dir = mprintf("%s", abs_dir);
            return dir;
        }
    }
    return NULL;
}

/* The enum named right before a `.` that ends at the cursor's word
 * (`Color.` or `geo.Kind.gr`), NULL when that isn't an enum */
static AstType *lspEnumBeforeDot(LspCtx *ctx, AoStr *text, int line,
                                 int character) {
    const char *s = text->data;
    size_t line_start = lspLineStart(text, line), line_end = line_start;
    while (line_end < text->len && s[line_end] != '\n') line_end++;
    size_t start = line_start + (size_t)character;
    if (start > line_end) start = line_end;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    if (start <= line_start || s[start - 1] != '.') return NULL;
    size_t t_end = start - 1, t_start = t_end;
    while (t_start > line_start &&
           (lspIsIdentChar(s[t_start - 1]) || s[t_start - 1] == '.')) {
        t_start--;
    }
    if (t_start == t_end) return NULL;
    AstType *ty = lspTypeByName(ctx, text, s + t_start, t_end - t_start);
    return ty && ty->is_enum && ty->enum_members ? ty : NULL;
}

/* `Enum.member` or `mod.Enum.member` with the cursor on `member`, or a
 * member's name in its enum's declaration: the member (*_enum its enum),
 * NULL otherwise */
static Ast *lspEnumMemberAt(LspCtx *ctx, AoStr *text, u32 doc_id, int line,
                            int character, AstType **_enum) {
    const char *s = text->data;
    size_t i = lspLineStart(text, line);
    size_t line_start = i, line_end = i;
    while (line_end < text->len && s[line_end] != '\n') line_end++;
    size_t pos = line_start + (size_t)character;
    if (pos > line_end) pos = line_end;
    size_t start = pos, end = pos;
    while (start > line_start && lspIsIdentChar(s[start - 1])) start--;
    while (end < line_end && lspIsIdentChar(s[end])) end++;
    if (end == start) return NULL;

    if (start > line_start && s[start - 1] == '.') {
        /* the dotted type name before the member */
        size_t t_end = start - 1, t_start = t_end;
        while (t_start > line_start &&
               (lspIsIdentChar(s[t_start - 1]) || s[t_start - 1] == '.')) {
            t_start--;
        }
        if (t_start == t_end) return NULL;
        AstType *ty = lspTypeByName(ctx, text, s + t_start, t_end - t_start);
        if (!ty || !ty->is_enum || !ty->enum_members) return NULL;
        Ast *m = mapGetLen(ty->enum_members, (char *)s + start, end - start);
        if (m && _enum) *_enum = ty;
        return m;
    }

    /* a member where its enum declares it */
    MapIter it;
    mapIterInit(ctx->cc->clsdefs, &it);
    while (mapIterNext(&it)) {
        AstType *ty = it.node->value;
        if (!ty || !ty->is_enum || !ty->enum_members || ty->file_id != doc_id) {
            continue;
        }
        Ast *m = mapGetLen(ty->enum_members, (char *)s + start, end - start);
        if (m && m->line == line + 1 &&
            m->col == (int)(start - line_start) + 1) {
            if (_enum) *_enum = ty;
            return m;
        }
    }
    return NULL;
}

/* ---- doc comments ----
 * The comment right above a declaration (a block, or `//` lines, with no
 * blank line between), else one after it on its line, is its doc: hover
 * shows it under the signature. */

/* `line` (0-based) of text: its start and end, 0 past the last */
static int lspLineSpan(AoStr *text, int line, const char **b, const char **e) {
    const char *s = text->data, *end = s + text->len;
    for (int l = 0; l < line; ++l) {
        const char *nl = memchr(s, '\n', end - s);
        if (!nl) return 0;
        s = nl + 1;
    }
    const char *nl = memchr(s, '\n', end - s);
    *b = s;
    *e = nl ? nl : end;
    return 1;
}

static void lspTrim(const char **b, const char **e) {
    while (*b < *e && isspace((unsigned char)**b)) (*b)++;
    while (*e > *b && isspace((unsigned char)(*e)[-1])) (*e)--;
}

/* One comment line's text, its markers gone, onto out */
static void lspCatCommentLine(AoStr *out, const char *b, const char *e) {
    lspTrim(&b, &e);
    if (e - b >= 2 && b[0] == '/' && (b[1] == '/' || b[1] == '*')) {
        b += 2;
        while (b < e && (*b == '*' || *b == '/' || *b == '!')) b++;
    } else if (b < e && *b == '*' && !(e - b >= 2 && b[1] == '/')) {
        b++;
    }
    if (e - b >= 2 && e[-2] == '*' && e[-1] == '/') {
        e -= 2;
        while (e > b && e[-1] == '*') e--;
    }
    lspTrim(&b, &e);
    if (out->len && b == e && out->data[out->len - 1] == '\n') return;
    if (out->len) aoStrPutChar(out, '\n');
    aoStrCatLen(out, b, e - b);
}

/* The doc comment of the declaration on line1 (1-based) of text: its
 * lines, NULL when it has none */
static AoStr *lspDocComment(AoStr *text, int line1) {
    const char *b, *e;
    int decl = line1 - 1;
    AoStr *out = aoStrNew();
    if (decl > 0 && lspLineSpan(text, decl - 1, &b, &e)) {
        const char *tb = b, *te = e;
        lspTrim(&tb, &te);
        int first = decl - 1;
        if (te - tb >= 2 && te[-2] == '*' && te[-1] == '/') {
            /* a block: up to the line with its opening */
            for (int l = decl - 1; l >= 0 && lspLineSpan(text, l, &b, &e); --l) {
                first = l;
                const char *o = b;
                int opens = 0;
                for (; o + 1 < e; ++o) {
                    if (o[0] == '/' && o[1] == '*') { opens = 1; break; }
                }
                if (opens) {
                    /* code before it on that line: not a doc block */
                    const char *cb = b;
                    while (cb < o && isspace((unsigned char)*cb)) cb++;
                    if (cb != o) first = -1;
                    break;
                }
                if (decl - l > 60) { first = -1; break; }
            }
        } else if (te - tb >= 2 && tb[0] == '/' && tb[1] == '/') {
            while (first > 0 && lspLineSpan(text, first - 1, &b, &e)) {
                lspTrim(&b, &e);
                if (e - b < 2 || b[0] != '/' || b[1] != '/') break;
                first--;
            }
        } else {
            first = -1;
        }
        if (first >= 0 && !(te - tb >= 2 && tb[0] == '/' && tb[1] == '/' && 0)) {
            int is_block = te - tb >= 2 && te[-2] == '*' && te[-1] == '/';
            int is_line = te - tb >= 2 && tb[0] == '/' && tb[1] == '/';
            if (is_block || is_line) {
                for (int l = first; l < decl && lspLineSpan(text, l, &b, &e); ++l) {
                    lspCatCommentLine(out, b, e);
                }
            }
        }
    }
    if (out->len == 0 && lspLineSpan(text, decl, &b, &e)) {
        /* `i64 n; // what n is` */
        int in_str = 0;
        for (const char *p = b; p + 1 < e; ++p) {
            if (*p == '"' && (p == b || p[-1] != '\\')) in_str = !in_str;
            if (in_str || p[0] != '/' || (p[1] != '/' && p[1] != '*')) continue;
            const char *ce = e;
            if (p[1] == '*') {
                const char *c = p + 2;
                while (c + 1 < e && !(c[0] == '*' && c[1] == '/')) c++;
                if (c + 1 >= e) break;
                ce = c + 2;
            }
            lspCatCommentLine(out, p, ce);
            break;
        }
    }
    /* drop blank lines at the ends */
    while (out->len && (out->data[out->len - 1] == '\n' || out->data[out->len - 1] == ' ')) {
        out->data[--out->len] = '\0';
    }
    if (out->len == 0) {
        aoStrRelease(out);
        return NULL;
    }
    return out;
}

/* Where a name is declared: see lspLocate */
typedef struct LspLoc {
    char *path;
    int line;
    int col;
    int len;
} LspLoc;
static int lspLocate(LspCtx *ctx, const char *uri, AoStr *text, int line,
                     int character, LspLoc *loc);

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
    AstType *menum = NULL, *fowner = NULL;
    Ast *member = lspEnumMemberAt(ctx, text, doc_id, line, character, &menum);
    AstType *fdecl = member ? NULL
                   : lspFieldDeclHere(ctx, text, doc_id, line, character, &fowner);
    char *modname = (member || fdecl) ? NULL
                  : lspModuleNameAt(ctx, text, doc_id, line, character);
    if (fdecl || modname) member = NULL;
    char *qual = (member || fdecl || modname) ? NULL
               : lspModuleRefAt(text, line, character, 0, NULL);
    AstType *mcls = (qual || member || fdecl || modname) ? NULL
                  : lspMemberClassAt(ctx, text, doc_id, line, character,
                                     &is_member);
    if (!qual && !is_member && !member && !fdecl && !modname)
        qual = lspOwnModuleQual(ctx, text, doc_id, line + 1, ident, ilen);
    if (modname) {
        char *dir = lspModuleDir(ctx, modname);
        aoStrCatPrintf(rendered, "module %s", modname);
        if (dir) aoStrCatPrintf(rendered, "  // %s", dir);
    } else if (fdecl && fowner->generic) {
        lspCatAttrs(rendered, fdecl->field_attrs);
        lspCatGenericField(rendered, fowner->generic, fdecl, ident);
    } else if (fdecl) {
        lspCatAttrs(rendered, fdecl->field_attrs);
        aoStrCatPrintf(rendered, "%s %s.%s", astTypeToString(fdecl),
                       fowner->clsname ? fowner->clsname->data : "", ident);
    } else if (member) {
        aoStrCatPrintf(rendered, "%s.%s = %lld", menum->clsname->data, ident,
                       (long long)member->i64);
    } else if (qual) {
        Ast *entry = lspModuleValue(ctx, qual);
        AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, qual);
        if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, qual);
        Ast *pick = entry && lspIsFn(entry)
                  ? lspPickOverload(ctx, doc_id, line + 1, character, entry) : entry;
        if (entry && lspIsFn(entry) && !pick) {
            lspCatOverloads(rendered, entry, qual);
        } else if (entry && lspIsFn(entry)) {
            aoStrCatPrintf(rendered, "%s", lspModuleFnString(pick, qual));
        } else if (entry && entry->type) {
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(entry->type), qual);
        } else if (ty) {
            lspCatType(rendered, ty);
        }
    } else if (is_member) {
        AstType *f = (mcls && mcls->fields) ? mapGetLen(mcls->fields, ident, ilen) : NULL;
        AstType *owner = NULL;
        Ast *method = (mcls && !f) ? lspFindMethod(ctx, mcls, ident, ilen, &owner)
                                   : NULL;
        if (f) lspCatAttrs(rendered, f->field_attrs);
        if (f && mcls->clsname)
            aoStrCatPrintf(rendered, "%s %s.%s", astTypeToString(f),
                           mcls->clsname->data, ident);
        else if (f)
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(f), ident);
        else if (method && astOverloads(method)) {
            Ast *pick = lspPickOverload(ctx, doc_id, line + 1, character, method);
            for (u64 i = 0; i < method->overloads->size; ++i) {
                Ast *o = method->overloads->entries[i];
                if (pick && o != pick) continue;
                if (rendered->len) aoStrPutChar(rendered, '\n');
                aoStrCatPrintf(rendered, "%s", lspMethodString(o, owner, ident));
            }
        }
        else if (method)
            aoStrCatPrintf(rendered, "%s", lspMethodString(method, owner, ident));
    } else {
        int in_doc = 0;
        Ast *entry = lspResolveValue(ctx, doc_id, line + 1, ident, ilen,
                                     &in_doc);
        AstType *cls = mapGetLen(ctx->cc->clsdefs, ident, ilen);
        AstType *uni = cls ? NULL : mapGetLen(ctx->cc->uniondefs, ident, ilen);
        TypeAlias *alias = mapGetLen(ctx->cc->type_aliases, ident, ilen);
        Lexeme *macro = mapGetLen(ctx->cc->macro_defs, ident, ilen);
        if (entry && lspIsFn(entry) && entry->overloads) {
            Ast *pick = lspPickOverload(ctx, doc_id, line + 1, character, entry);
            if (pick) aoStrCat(rendered, lspModuleFnString(pick, ident));
            else lspCatOverloads(rendered, entry, ident);
        } else if (entry && lspIsFn(entry)) {
            aoStrCatPrintf(rendered, "%s", astFunctionToString(entry));
        } else if (entry && entry->type) {
            aoStrCatPrintf(rendered, "%s %s", astTypeToString(entry->type),
                           ident);
        } else if (lspGeneric(ctx, ident, ilen)) {
            lspCatGenericHead(rendered, lspGeneric(ctx, ident, ilen));
        } else if (cls || uni) {
            lspCatType(rendered, cls ? cls : uni);
        } else if (alias) {
            aoStrCatPrintf(rendered, "type %.*s = %s", ilen, ident,
                           astTypeToString(alias->type));
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
    LspLoc loc;
    if (lspLocate(ctx, uri, text, line, character, &loc) && loc.line > 0) {
        const char *def_uri = NULL;
        AoStr *def_text = lspTextForPath(ctx, loc.path, &def_uri);
        AoStr *doc = def_text ? lspDocComment(def_text, loc.line) : NULL;
        if (doc) {
            aoStrCatPrintf(md, "\n\n%s", doc->data);
            aoStrRelease(doc);
        }
    }
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
    "break", "case", "default", "do", "else", "extern",
    "_extern", "for", "goto", "if", "return", "sizeof", "static",
    "switch", "union", "while",
    "auto", "continue", "typeof", "alignof",
    "true", "false", "null",
    "struct", "pub", "private", "const", "enum", "defer",
    "fn", "self", "module", "import", "fallthrough", "try", "catch", "throw",
    "type", "ad", "asm", "volatile", "inline",
};

#define KTM_LSP_FUNCTION     3
#define KTM_LSP_FIELD        5
#define KTM_LSP_VARIABLE     6
#define KTM_LSP_CLASS        7
/* tbh we could double up with out usage of class here */
#define KTM_LSP_UNION_STRUCT 22
#define KTM_LSP_CONSTANT     21
#define KTM_LSP_KEYWORD      14
#define KTM_LSP_MODULE       9
#define KTM_LSP_ENUM         13
#define KTM_LSP_ENUM_MEMBER  20

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
    AstType *t = (AstType *)entry;   /* clsdefs holds types */
    *_type = t && t->is_enum ? KTM_LSP_ENUM : KTM_LSP_UNION_STRUCT;
    return t && t->is_enum ? "enum" : "struct";
}

static char *lspUnionSuggestionResolver(Ast *entry, int *_type) {
    (void)entry;
    *_type = KTM_LSP_UNION_STRUCT;
    return "union";
}

static char *lspAliasSuggestionResolver(Ast *entry, int *_type) {
    (void)entry;   /* a TypeAlias, not an Ast */
    *_type = KTM_LSP_CLASS;
    return "type";
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
        /* names written another way: a module's (`geo.area`, after
         * `geo.`), an instance's (`Vec<i64>`), a symbol (`_K3geo4area`) */
        if (strchr(name, '.') || strchr(name, '<') ||
            (name[0] == '_' && name[1] == 'K' && isdigit((unsigned char)name[2]))) {
            continue;
        }
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

typedef struct LspPrefixScan {
    const char *prefix;
    size_t plen;
    AoStr *body;
    int *emitted;
} LspPrefixScan;

static int lspCatImportPrefix(const char *path, size_t path_len,
                              const char *prefix, size_t prefix_len, void *arg) {
    LspPrefixScan *ps = arg;
    if (prefix_len < ps->plen || strncmp(prefix, ps->prefix, ps->plen)) return 0;
    lspCatCompletion(ps->body, ps->emitted, mprintf("%.*s", (int)prefix_len, prefix),
                     KTM_LSP_MODULE, mprintf("module %.*s", (int)path_len, path));
    return 0;
}

/* The modules in `dir` (directories holding .ktm files), as completions
 * of `prefix`; `rel` is their path so far ("" or "a.") */
static void lspCatModuleDirs(AoStr *body, int *emitted, const char *dir,
                             const char *rel, const char *prefix, size_t plen,
                             Set *seen) {
    DIR *d = opendir(dir);
    struct dirent *ent;
    while (d && (ent = readdir(d)) != NULL) {
        if (ent->d_name[0] == '.') continue;
        char *name = mprintf("%s%s", rel, ent->d_name);
        if (strncmp(name, prefix, plen) || setHas(seen, name)) continue;
        char *sub = mprintf("%s/%s", dir, ent->d_name);
        DIR *sd = opendir(sub);
        int has_ktm = 0;
        struct dirent *se;
        while (sd && !has_ktm && (se = readdir(sd)) != NULL) {
            size_t n = strlen(se->d_name);
            has_ktm = n > 4 && !strcmp(se->d_name + n - 4, ".ktm");
        }
        if (sd) closedir(sd);
        /* rt is the runtime, prelude is always there: not imported */
        if (!has_ktm || (!*rel && (!strcmp(name, "rt") || !strcmp(name, "prelude")))) {
            continue;
        }
        setAdd(seen, name);
        lspCatCompletion(body, emitted, name, KTM_LSP_MODULE, sub);
    }
    if (d) closedir(d);
}

/* After `import ` (or a `,` of its list): the modules it can name, the
 * standard library's and the ones beside the document */
static int lspImportCompletion(LspCtx *ctx, Json *id, const char *uri,
                               AoStr *text, int line, int character) {
    size_t at = lspLineStart(text, line) + (size_t)character;
    if (at > text->len) at = text->len;
    size_t b = at;
    while (b > 0 && (lspIsIdentChar(text->data[b - 1]) || text->data[b - 1] == '.')) b--;
    if (!lspInImport(text, b)) return 0;
    char *prefix = mprintf("%.*s", (int)(at - b), text->data + b);
    char *dot = strrchr(prefix, '.');
    AoStr *body = aoStrNew();
    aoStrCat(body, "{\"isIncomplete\":false,\"items\":[");
    int emitted = 0;
    Set *seen = setNew(16, &set_cstring_type);
    char *path = lspUriToPath(uri);
    char *slash = strrchr(path, '/');
    char *docdir = slash ? mprintf("%.*s", (int)(slash - path), path) : mprintf(".");
    /* `a.` : a's submodules */
    char *rel = dot ? mprintf("%.*s", (int)(dot - prefix + 1), prefix) : "";
    char *reldir = mprintf("%s", rel);
    for (char *p = reldir; *p; ++p) if (*p == '.') *p = '/';
    lspCatModuleDirs(body, &emitted, mprintf("%s/%s", docdir, reldir), rel, prefix,
                     strlen(prefix), seen);
    lspCatModuleDirs(body, &emitted, mprintf("%s%s", ctx->std_dir, reldir), rel, prefix,
                     strlen(prefix), seen);
    free(path);
    aoStrCat(body, "]}");
    lspRespond(id, body->data);
    aoStrRelease(body);
    return 1;
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
    if (lspImportCompletion(ctx, id, uri, text, line, character)) return;

    /* Member completion: after `recv.` / `recv->` (plus an optional
     * partial field prefix) the receiver class's FIELDS are the whole
     * namespace - global suggestions would be pure noise. An
     * untypable receiver offers nothing rather than everything. */
    char *doc_path = lspUriToPath(uri);
    AoStr path_s = { .data = doc_path, .len = strlen(doc_path),
                     .capacity = 0 };
    u32 doc_id = cctrlRegisterFile(ctx->cc, &path_s);
    free(doc_path);
    /* `Enum.` or `mod.Enum.`: the enum's members */
    AstType *ety = lspEnumBeforeDot(ctx, text, line, character);
    if (ety) {
        AoStr *ebody = aoStrNew();
        aoStrCatPrintf(ebody, "{\"isIncomplete\":false,\"items\":[");
        int eemitted = 0;
        MapIter ei;
        mapIterInit(ety->enum_members, &ei);
        while (mapIterNext(&ei)) {
            const char *mname = (const char *)ei.node->key;
            if (strncmp(mname, prefix, plen)) continue;
            Ast *m = ei.node->value;
            char *detail = mprintf("%s.%s = %lld", ety->clsname->data, mname,
                                   (long long)m->i64);
            lspCatCompletion(ebody, &eemitted, mname, KTM_LSP_ENUM_MEMBER, detail);
        }
        aoStrCatPrintf(ebody, "]}");
        lspRespond(id, ebody->data);
        aoStrRelease(ebody);
        return;
    }

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
            Ast *entry = lspModuleValue(ctx, qn);
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

    lspScanMapForSuggestion(ctx->cc->type_aliases, prefix, plen, body, &emitted,
                            &lspAliasSuggestionResolver);

    lspScanMapForSuggestion(ctx->cc->macro_defs, prefix, plen, body, &emitted,
                            &lspConstantSuggestionResolver);

    /* the document's import prefixes */
    LspPrefixScan ps = { .prefix = prefix, .plen = plen, .body = body,
                         .emitted = &emitted };
    lspEachImport(text, lspCatImportPrefix, &ps);

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

static int lspCmpStr(const void *a, const void *b) {
    return strcmp(*(char *const *)a, *(char *const *)b);
}

/* A module's name: its files, each at its `module` line */
static void lspModuleDefinition(LspCtx *ctx, Json *id, const char *modname) {
    char *dir = lspModuleDir(ctx, modname);
    DIR *d = dir ? opendir(dir) : NULL;
    if (!d) {
        lspRespond(id, "null");
        return;
    }
    Vec *names = vecNew(&vec_cstring_type);
    struct dirent *ent;
    while ((ent = readdir(d)) != NULL) {
        size_t n = strlen(ent->d_name);
        if (n >= 5 && !strcmp(ent->d_name + n - 4, ".ktm")) {
            vecPush(names, mprintf("%s", ent->d_name));
        }
    }
    closedir(d);
    qsort(names->entries, names->size, sizeof(char *), lspCmpStr);
    AoStr *result = aoStrNew();
    aoStrPutChar(result, '[');
    for (u64 i = 0; i < names->size; ++i) {
        char *path = mprintf("%s/%s", dir, (char *)names->entries[i]);
        const char *furi = NULL;
        AoStr *ftext = lspTextForPath(ctx, path, &furi);
        int mline = 0;
        if (ftext) {
            const char *p = ftext->data, *end = p + ftext->len;
            for (int l = 0; p < end; ++l) {
                const char *q = p;
                while (q < end && (*q == ' ' || *q == '\t')) q++;
                if (end - q > 7 && !memcmp(q, "module", 6) && isspace((unsigned char)q[6])) {
                    mline = l;
                    break;
                }
                const char *eol = memchr(p, '\n', end - p);
                if (!eol) break;
                p = eol + 1;
            }
        }
        if (i) aoStrPutChar(result, ',');
        aoStrCatPrintf(result, "{\"uri\":\"file://");
        jsonEscapeInto(result, path, strlen(path));
        aoStrCatLen(result, "\",", 2);
        lspCatRange(ctx, result, ftext, mline, 0, 0);
        aoStrPutChar(result, '}');
    }
    aoStrPutChar(result, ']');
    lspRespond(id, names->size ? result->data : "null");
    aoStrRelease(result);
}

/* Where the name at (line, character) of `uri` is declared: its file
 * (an absolute path), 1-based line and column (0: none) and the name's
 * length. 0 when it isn't known. Module names are left to the caller. */

static int lspLocate(LspCtx *ctx, const char *uri, AoStr *text, int line,
                     int character, LspLoc *loc) {
    char ident[256];
    if (!lspIdentAt(text, line, character, 0, ident, sizeof(ident), NULL)) {
        return 0;
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
    Ast *emember = lspEnumMemberAt(ctx, text, doc_id, line, character, NULL);
    AstType *fdecl = emember ? NULL
                   : lspFieldDeclHere(ctx, text, doc_id, line, character, NULL);
    char *modname = (emember || fdecl) ? NULL
                  : lspModuleNameAt(ctx, text, doc_id, line, character);
    if (modname) return 0;
    char *qual = (emember || fdecl) ? NULL
               : lspModuleRefAt(text, line, character, 0, NULL);
    AstType *mcls = NULL;
    if (fdecl) {
        is_member = 1;
        def_line = fdecl->line;
        def_col = fdecl->col;
        def_file = fdecl->file_id;
    } else if (emember) {
        is_member = 1;
        def_line = emember->line;
        def_col = emember->col;
        def_file = emember->file_id;
    } else if (qual) {
        /* `prefix.name`: the module's declaration, in its own file */
        Ast *entry = lspModuleValue(ctx, qual);
        AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, qual);
        if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, qual);
        is_member = 1;
        if (entry && astOverloads(entry)) {
            Ast *pick = lspPickOverload(ctx, doc_id, cursor_line, character, entry);
            if (pick) entry = pick;
        }
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
        /* a bare name in the module's own files: its declaration */
        char *own = is_member ? NULL
                  : lspOwnModuleQual(ctx, text, doc_id, cursor_line, ident, ilen);
        if (own) {
            Ast *entry = lspModuleValue(ctx, own);
            AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, own);
            if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, own);
            is_member = 1;
            if (entry && astOverloads(entry)) {
                Ast *pick = lspPickOverload(ctx, doc_id, cursor_line, character, entry);
                if (pick) entry = pick;
            }
            if (entry) {
                def_line = entry->line;
                def_col = entry->col;
                def_file = entry->file_id;
            } else {
                def_line = ty->line;
                def_col = ty->col;
                def_file = ty->file_id;
            }
        }
    }
    if (mcls && mcls->fields) {
        AstType *field = (AstType *)mapGetLen(mcls->fields, ident, ilen);
        Ast *method = field ? NULL : lspFindMethod(ctx, mcls, ident, ilen, NULL);
        if (method && astOverloads(method)) {
            Ast *pick = lspPickOverload(ctx, doc_id, cursor_line, character, method);
            if (pick) method = pick;
        }
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
        if (entry && astOverloads(entry) && lspIsFn(entry)) {
            Ast *pick = lspPickOverload(ctx, doc_id, cursor_line, character, entry);
            if (pick) entry = pick;
        }
        if (entry) {
            def_line = entry->line;
            def_col = entry->col;
            def_file = entry->file_id;
        } else if (lspGeneric(ctx, ident, ilen)) {
            GenericDef *g = lspGeneric(ctx, ident, ilen);
            def_line = g->at->line;
            def_col = g->at->col;
            def_file = g->file_id;
        } else {
            AstType *ty = (AstType *)mapGetLen(ctx->cc->clsdefs, ident, ilen);
            if (ty == NULL)
                ty = (AstType *)mapGetLen(ctx->cc->uniondefs, ident, ilen);
            TypeAlias *alias = ty ? NULL : mapGetLen(ctx->cc->type_aliases, ident, ilen);
            if (ty) {
                def_line = ty->line;
                def_col = ty->col;
                def_file = ty->file_id;
            } else if (alias) {
                def_line = alias->line;
                def_col = alias->col;
                def_file = alias->file_id;
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
    if (fname == NULL) return 0;
    /* file_map can hold a cwd-relative path (AOT-style invocations);
     * a file:// uri must be absolute or the editor guesses. */
    char abs_path[PATH_MAX];
    const char *out_path = fname->data;
    if (out_path[0] != '/' && realpath(out_path, abs_path) != NULL)
        out_path = abs_path;
    loc->path = mprintf("%s", out_path);
    loc->line = def_line;
    loc->col = def_col;
    loc->len = (int)ilen;
    return 1;
}

static void lspDefinition(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL)
    {
        lspRespond(id, "null");
        return;
    }
    /* Anywhere on an #include line jumps to the file itself. */
    if (lspIncludeDefinition(ctx, id, uri, text, line)) return;
    u32 doc_id = lspDocId(ctx, uri);
    char *modname = lspEnumMemberAt(ctx, text, doc_id, line, character, NULL) ||
                    lspFieldDeclHere(ctx, text, doc_id, line, character, NULL)
                  ? NULL : lspModuleNameAt(ctx, text, doc_id, line, character);
    if (modname) {
        lspModuleDefinition(ctx, id, modname);
        return;
    }
    LspLoc loc;
    if (!lspLocate(ctx, uri, text, line, character, &loc)) {
        lspRespond(id, "null");
        return;
    }
    /* With a column the range spans exactly the defining name; without
     * one (synthetic nodes) fall back to line start. */
    int c0 = loc.col > 0 ? loc.col - 1 : 0;
    int c1 = loc.col > 0 ? c0 + loc.len : 0;
    AoStr *result = aoStrNew();
    const char *def_uri = NULL;
    AoStr *def_text = lspTextForPath(ctx, loc.path, &def_uri);
    aoStrCatPrintf(result, "{\"uri\":\"file://");
    jsonEscapeInto(result, loc.path, strlen(loc.path));
    aoStrCatLen(result, "\",", 2);
    lspCatRange(ctx, result, def_text, loc.line - 1, c0, c1);
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

    /* `prefix.name` of an import: the module's `a.b.name` */
    char *qual = lspModuleRefAt(text, line, character, 0, NULL);
    if (qual) {
        Ast *entry = lspModuleValue(ctx, qual);
        AstType *ty = entry ? NULL : mapGet(ctx->cc->clsdefs, qual);
        if (!entry && !ty) ty = mapGet(ctx->cc->uniondefs, qual);
        if (entry) {
            tg.kind = KTM_LSP_RENAME_VALUE;
            tg.value = entry;
        } else if (ty) {
            tg.kind = KTM_LSP_RENAME_TYPE;
            tg.type = ty;
        }
        return tg;
    }

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

/* A file of the installed standard library: its modules (prelude and rt
 * among them), or an old header in the include root */
static int lspPathInStdlib(LspCtx *ctx, const char *path) {
    if (!path) return 0;
    if (!strncmp(path, ctx->root_dir, strlen(ctx->root_dir))) return 1;
    if (!ctx->std_dir) return 0;
    if (!strncmp(path, ctx->std_dir, strlen(ctx->std_dir))) return 1;
    /* the same directory spelled another way (a relative --install-dir) */
    char *real = realpath(path, NULL), *std = realpath(ctx->std_dir, NULL);
    size_t sl = std ? strlen(std) : 0;
    int in = real && std && !strncmp(real, std, sl) && real[sl] == '/';
    free(real);
    free(std);
    return in;
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

/* `module a.b;` at the top of `text`: "a.b", else NULL */
static char *lspDocModule(AoStr *text) {
    const char *s = text->data, *end = s + text->len;
    while (s < end) {
        const char *eol = memchr(s, '\n', end - s);
        if (!eol) eol = end;
        const char *p = s;
        while (p < eol && isspace((unsigned char)*p)) p++;
        if (eol - p > 7 && !memcmp(p, "module", 6) && isspace((unsigned char)p[6])) {
            p += 6;
            while (p < eol && isspace((unsigned char)*p)) p++;
            const char *q = p;
            while (q < eol && (lspIsIdentChar(*q) || *q == '.')) q++;
            return q > p ? mprintf("%.*s", (int)(q - p), p) : NULL;
        }
        s = eol + 1;
    }
    return NULL;
}

/* What a module's name at `o` is, when it is one: `prefix.name` of an
 * import, or a bare name in one of the module's own files */
static void *lspOccurrenceModuleName(LspCtx *ctx, AoStr *text,
                                     LspOccurrence *o, const char *name,
                                     s64 nlen, int want_type)
{
    char *qual = NULL;
    if (o->member) {
        qual = lspModuleRefAt(text, o->line, o->col, 0, NULL);
    } else {
        char *mod = lspDocModule(text);
        if (mod) qual = mprintf("%s.%.*s", mod, (int)nlen, name);
    }
    if (!qual) return NULL;
    if (!want_type) return lspModuleValue(ctx, qual);
    AstType *ty = mapGet(ctx->cc->clsdefs, qual);
    return ty ? ty : mapGet(ctx->cc->uniondefs, qual);
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
    case KTM_LSP_RENAME_VALUE: {
        if (o->hash) return 0;
        if (!o->member) {
            Ast *v = lspResolveValue(ctx, doc_id, o->line + 1, name, nlen,
                                     &in_doc);
            if (v) return v == tg->value;
        }
        return lspOccurrenceModuleName(ctx, text, o, name, nlen, 0) == tg->value;
    }
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
        if (o->hash) return 0;
        /* a module's type, `geo.Point` or `Point` in geo's files */
        if (lspOccurrenceModuleName(ctx, text, o, name, nlen, 1) == tg->type)
            return 1;
        /* any other spelling of a module's type is another type */
        if (tg->type->clsname && strchr(tg->type->clsname->data, '.'))
            return 0;
        /* A same-named VALUE in scope shadows the type reading -
         * `Foo p;` never resolves as a value, a local `Foo` does. */
        return !o->member &&
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

/* ---- symbols ----
 * The outline of a document (textDocument/documentSymbol): its functions,
 * structs (their fields and methods inside), unions, enums (their
 * members), constants and globals, type aliases, generics and macros,
 * each with its whole declaration as its range. workspace/symbol searches
 * the same over every open document. */

#define LSP_SK_FUNCTION  12
#define LSP_SK_METHOD    6
#define LSP_SK_FIELD     8
#define LSP_SK_ENUM      10
#define LSP_SK_VARIABLE  13
#define LSP_SK_CONSTANT  14
#define LSP_SK_ENUM_MEMBER 22
#define LSP_SK_STRUCT    23
#define LSP_SK_TYPE_PARAM 26
#define LSP_SK_MODULE    2

typedef struct LspSym {
    char *name;
    char *detail;
    int kind;
    u32 file_id;
    int line, col, len;  /* the name: 1-based line and column */
    int end_line, end_col; /* the declaration's end, 0-based, exclusive */
    char *owner;         /* a method's or field's type, nesting it */
    Vec *children;       /* LspSym * */
} LspSym;

static int lspSymCmp(const void *a, const void *b) {
    const LspSym *x = *(LspSym *const *)a, *y = *(LspSym *const *)b;
    if (x->line != y->line) return x->line - y->line;
    return x->col - y->col;
}

/* The byte offset of (line1, col1) in text */
static size_t lspOffsetOf(AoStr *text, int line1, int col1) {
    size_t off = lspLineStart(text, line1 - 1) + (size_t)(col1 > 0 ? col1 - 1 : 0);
    return off > text->len ? text->len : off;
}

/* Past the end of the declaration whose name is at `off`: its `;`, or
 * its body's closing `}` (and a `;` after it) */
static size_t lspDeclEnd(AoStr *text, size_t off) {
    const char *s = text->data;
    size_t n = text->len, i = off;
    int paren = 0;
    while (i < n) {
        char c = s[i];
        if (c == '/' && i + 1 < n && s[i + 1] == '/') {
            while (i < n && s[i] != '\n') i++;
            continue;
        }
        if (c == '/' && i + 1 < n && s[i + 1] == '*') {
            i += 2;
            while (i + 1 < n && !(s[i] == '*' && s[i + 1] == '/')) i++;
            i += 2;
            continue;
        }
        if (c == '"' || c == '\'') {
            for (i++; i < n && s[i] != c && s[i] != '\n'; i++) {
                if (s[i] == '\\') i++;
            }
            i++;
            continue;
        }
        if (c == '(') paren++;
        else if (c == ')') paren--;
        else if (c == ';' && paren <= 0) return i + 1;
        else if (c == '{' && paren <= 0) {
            int depth = 0;
            while (i < n) {
                char d = s[i];
                if (d == '/' && i + 1 < n && s[i + 1] == '/') {
                    while (i < n && s[i] != '\n') i++;
                    continue;
                }
                if (d == '/' && i + 1 < n && s[i + 1] == '*') {
                    i += 2;
                    while (i + 1 < n && !(s[i] == '*' && s[i + 1] == '/')) i++;
                    i += 2;
                    continue;
                }
                if (d == '"' || d == '\'') {
                    for (i++; i < n && s[i] != d && s[i] != '\n'; i++) {
                        if (s[i] == '\\') i++;
                    }
                    i++;
                    continue;
                }
                if (d == '{') depth++;
                else if (d == '}' && --depth == 0) {
                    size_t j = i + 1;
                    while (j < n && (s[j] == ' ' || s[j] == '\t')) j++;
                    return j < n && s[j] == ';' ? j + 1 : i + 1;
                }
                i++;
            }
            return n;
        }
        i++;
    }
    return n;
}

/* The 0-based line and byte column of offset off */
static void lspLineColOf(AoStr *text, size_t off, int *line, int *col) {
    int l = 0;
    size_t start = 0;
    for (size_t i = 0; i < off && i < text->len; ++i) {
        if (text->data[i] == '\n') {
            l++;
            start = i + 1;
        }
    }
    *line = l;
    *col = (int)(off - start);
}

/* `name` without the document's module prefix (`geo.area` in geo: area) */
static const char *lspLocalName(const char *name, const char *mod) {
    size_t ml = mod ? strlen(mod) : 0;
    if (ml && !strncmp(name, mod, ml) && name[ml] == '.') return name + ml + 1;
    return name;
}

static LspSym *lspSymNew(Vec *out, const char *name, int kind, char *detail,
                         u32 file_id, int line, int col, int len) {
    LspSym *sym = calloc(1, sizeof(LspSym));
    sym->name = mprintf("%s", name);
    sym->kind = kind;
    sym->detail = detail;
    sym->file_id = file_id;
    sym->line = line;
    sym->col = col;
    sym->len = len;
    vecPush(out, sym);
    return sym;
}

/* Every symbol declared in file `file_id` (whose module is `mod`, NULL
 * when none), flat: methods and fields carry their owner */
static void lspCollectSymbols(LspCtx *ctx, u32 file_id, const char *mod,
                              Vec *out) {
    Cctrl *cc = ctx->cc;
    Set *seen = setNew(32, &set_cstring_type);
    MapIter it;
    mapIterInit(cc->global_env, &it);
    while (mapIterNext(&it)) {
        const char *key = it.node->key;
        Ast *entry = it.node->value;
        /* instances' and symbols' names (`_K4User5older`): the source's
         * own name is there too */
        if (!entry || strchr(key, '<') ||
            (key[0] == '_' && key[1] == 'K' && isdigit((unsigned char)key[2]))) {
            continue;
        }
        u64 n = entry->overloads ? entry->overloads->size : 1;
        for (u64 k = 0; k < n; ++k) {
            Ast *e = entry->overloads ? entry->overloads->entries[k] : entry;
            if (!e || e->file_id != file_id || e->line <= 0) continue;
            char *id = mprintf("%p", (void *)e);
            if (setHas(seen, id)) continue;
            setAdd(seen, id);
            const char *name = lspLocalName(key, mod);
            if (lspIsFn(e)) {
                if (e->kind == AST_FUN_PROTO) continue;
                if (lspIsMethod(e)) {
                    const char *dot = strrchr(name, '.');
                    LspSym *m = lspSymNew(out, dot ? dot + 1 : name, LSP_SK_METHOD,
                                          NULL, file_id, e->line, e->col,
                                          (int)strlen(dot ? dot + 1 : name));
                    /* `i64 User.older(i64 years)`: without self */
                    char *sig = lspModuleFnString(e, key);
                    char *open = strchr(sig, '(');
                    char *self = open ? strstr(open, "self") : NULL;
                    if (self) {
                        char *rest = self + 4;
                        if (*rest == ',') rest++;
                        while (*rest == ' ') rest++;
                        sig = mprintf("%.*s%s", (int)(open + 1 - sig), sig, rest);
                    }
                    m->detail = sig;
                    m->owner = dot ? mprintf("%.*s", (int)(dot - name), name) : NULL;
                } else {
                    const char *bare = strrchr(name, '.');
                    bare = bare ? bare + 1 : name;
                    lspSymNew(out, bare, LSP_SK_FUNCTION, lspModuleFnString(e, key),
                              file_id, e->line, e->col, (int)strlen(bare));
                }
            } else if (e->kind == AST_GVAR || e->kind == AST_DECL) {
                const char *bare = strrchr(name, '.');
                bare = bare ? bare + 1 : name;
                lspSymNew(out, bare,
                          (e->flags & AST_FLAG_CONST) ? LSP_SK_CONSTANT : LSP_SK_VARIABLE,
                          e->type ? astTypeToString(e->type) : NULL, file_id,
                          e->line, e->col, (int)strlen(bare));
            }
        }
    }
    Map *types[2] = { cc->clsdefs, cc->uniondefs };
    for (int t = 0; t < 2; ++t) {
        mapIterInit(types[t], &it);
        while (mapIterNext(&it)) {
            const char *key = it.node->key;
            AstType *ty = it.node->value;
            if (!ty || ty->file_id != file_id || ty->line <= 0 || strchr(key, '<') ||
                ty->generic) {
                continue;
            }
            const char *name = lspLocalName(key, mod);
            int kind = ty->is_enum ? LSP_SK_ENUM : LSP_SK_STRUCT;
            LspSym *sym = lspSymNew(out, name, kind,
                                    mprintf("%s", ty->is_enum ? "enum"
                                            : ty->kind == AST_TYPE_UNION ? "union"
                                                                         : "struct"),
                                    file_id, ty->line, ty->col, (int)strlen(name));
            (void)sym;
            if (ty->is_enum && ty->enum_members) {
                MapIter mi;
                mapIterInit(ty->enum_members, &mi);
                while (mapIterNext(&mi)) {
                    Ast *m = mi.node->value;
                    if (m->line <= 0) continue;
                    LspSym *ms = lspSymNew(out, mi.node->key, LSP_SK_ENUM_MEMBER,
                                           mprintf("= %lld", (long long)m->i64), file_id,
                                           m->line, m->col, (int)strlen(mi.node->key));
                    ms->owner = mprintf("%s", name);
                }
            } else if (ty->fields) {
                MapIter fi;
                mapIterInit(ty->fields, &fi);
                while (mapIterNext(&fi)) {
                    AstType *f = fi.node->value;
                    if (!f || f->file_id != file_id || f->line <= 0) continue;
                    LspSym *fs = lspSymNew(out, fi.node->key, LSP_SK_FIELD,
                                           astTypeToString(f), file_id, f->line,
                                           f->col, (int)strlen(fi.node->key));
                    fs->owner = mprintf("%s", name);
                }
            }
        }
    }
    if (cc->type_aliases) {
        mapIterInit(cc->type_aliases, &it);
        while (mapIterNext(&it)) {
            TypeAlias *a = it.node->value;
            if (!a || a->file_id != file_id || a->line <= 0) continue;
            const char *name = lspLocalName(it.node->key, mod);
            lspSymNew(out, name, LSP_SK_TYPE_PARAM,
                      mprintf("type = %s", astTypeToString(a->type)), file_id,
                      a->line, a->col, (int)strlen(name));
        }
    }
    Map *gens[2] = { cc->generic_structs, cc->generic_funcs };
    for (int g = 0; g < 2; ++g) {
        if (!gens[g]) continue;
        mapIterInit(gens[g], &it);
        while (mapIterNext(&it)) {
            GenericDef *d = it.node->value;
            if (!d || d->file_id != file_id || !d->at) continue;
            const char *name = lspLocalName(d->name->data, mod);
            AoStr *head = aoStrNew();
            lspCatGenericHead(head, d);
            lspSymNew(out, mprintf("%s<%s>", name, ""),
                      g == 0 ? LSP_SK_STRUCT : LSP_SK_FUNCTION, head->data, file_id,
                      d->at->line, d->at->col, d->at->len);
            LspSym *last = out->entries[out->size - 1];
            AoStr *nm = aoStrNew();
            aoStrCatPrintf(nm, "%s<", name);
            for (u64 i = 0; i < d->params->size; ++i) {
                aoStrCatPrintf(nm, "%s%s", i ? ", " : "",
                               ((AoStr *)d->params->entries[i])->data);
            }
            aoStrPutChar(nm, '>');
            last->name = nm->data;
        }
    }
    if (cc->generic_methods) {
        for (u64 i = 0; i < cc->generic_methods->size; ++i) {
            GenericDef *d = cc->generic_methods->entries[i];
            if (!d || d->file_id != file_id || !d->method) continue;
            /* the method's name token: the first one spelled so after
             * the struct's name */
            Lexeme *at = NULL;
            for (u64 k = 0; k < d->toks->size; ++k) {
                Lexeme *t = d->toks->entries[k];
                if (t->tk_type == TK_IDENT && (size_t)t->len == d->method->len &&
                    !memcmp(t->start, d->method->data, t->len) &&
                    (d->at == NULL || t->line > d->at->line ||
                     (t->line == d->at->line && t->col > d->at->col))) {
                    at = t;
                    break;
                }
            }
            if (!at) continue;
            AoStr *head = aoStrNew();
            lspCatGenericHead(head, d);
            LspSym *m = lspSymNew(out, d->method->data, LSP_SK_METHOD, head->data,
                                  file_id, at->line, at->col, at->len);
            AoStr *owner = aoStrNew();
            aoStrCatPrintf(owner, "%s<", lspLocalName(d->of->name->data, mod));
            for (u64 k = 0; k < d->of->params->size; ++k) {
                aoStrCatPrintf(owner, "%s%s", k ? ", " : "",
                               ((AoStr *)d->of->params->entries[k])->data);
            }
            aoStrPutChar(owner, '>');
            m->owner = owner->data;
        }
    }
}

/* `#define NAME` lines of text, as symbols */
static void lspCollectMacros(AoStr *text, u32 file_id, Vec *out) {
    const char *s = text->data, *end = s + text->len;
    for (int line = 1; s < end; ++line) {
        const char *eol = memchr(s, '\n', end - s);
        if (!eol) eol = end;
        const char *p = s;
        while (p < eol && isspace((unsigned char)*p)) p++;
        if (eol - p > 8 && !memcmp(p, "#define", 7) && isspace((unsigned char)p[7])) {
            const char *q = p + 7;
            while (q < eol && isspace((unsigned char)*q)) q++;
            const char *ne = q;
            while (ne < eol && lspIsIdentChar(*ne)) ne++;
            if (ne > q) {
                LspSym *m = lspSymNew(out, mprintf("%.*s", (int)(ne - q), q),
                                      LSP_SK_CONSTANT, NULL, file_id, line,
                                      (int)(q - s) + 1, (int)(ne - q));
                const char *v = ne;
                while (v < eol && isspace((unsigned char)*v)) v++;
                m->detail = mprintf("%.*s", (int)(eol - v), v);
                m->end_line = line - 1;
                m->end_col = (int)(eol - s);
            }
        }
        s = eol + 1;
    }
}

/* Each symbol's whole-declaration range (from its text) and the nesting
 * of fields, members and methods into their type: the top level */
static Vec *lspNestSymbols(AoStr *text, Vec *flat) {
    for (u64 i = 0; i < flat->size; ++i) {
        LspSym *sym = flat->entries[i];
        /* the name as the text spells it (`main`, which the compiler
         * calls Main) */
        if (sym->kind == LSP_SK_FUNCTION) {
            size_t off = lspOffsetOf(text, sym->line, sym->col), e = off;
            while (e < text->len && lspIsIdentChar(text->data[e])) e++;
            if (e - off == (size_t)sym->len &&
                !strncasecmp(text->data + off, sym->name, sym->len)) {
                sym->name = mprintf("%.*s", sym->len, text->data + off);
            }
        }
        if (sym->end_line || sym->end_col) continue;
        if (sym->kind == LSP_SK_FIELD || sym->kind == LSP_SK_ENUM_MEMBER) {
            sym->end_line = sym->line - 1;
            sym->end_col = sym->col - 1 + sym->len;
            continue;
        }
        size_t off = lspOffsetOf(text, sym->line, sym->col);
        lspLineColOf(text, lspDeclEnd(text, off), &sym->end_line, &sym->end_col);
    }
    qsort(flat->entries, flat->size, sizeof(LspSym *), lspSymCmp);
    Vec *top = vecNew(&vec_unsigned_long_type);
    for (u64 i = 0; i < flat->size; ++i) {
        LspSym *sym = flat->entries[i];
        LspSym *parent = NULL;
        if (sym->owner) {
            for (u64 k = 0; k < flat->size && !parent; ++k) {
                LspSym *t = flat->entries[k];
                if ((t->kind == LSP_SK_STRUCT || t->kind == LSP_SK_ENUM) &&
                    !strcmp(t->name, sym->owner)) {
                    parent = t;
                }
            }
        }
        if (parent) {
            if (!parent->children) parent->children = vecNew(&vec_unsigned_long_type);
            vecPush(parent->children, sym);
            /* a method after its struct: the struct's range takes it in */
            if (sym->end_line > parent->end_line ||
                (sym->end_line == parent->end_line && sym->end_col > parent->end_col)) {
                if (sym->kind != LSP_SK_METHOD) {
                    parent->end_line = sym->end_line;
                    parent->end_col = sym->end_col;
                }
            }
        } else {
            if (sym->owner && sym->kind == LSP_SK_METHOD) {
                sym->name = mprintf("%s.%s", sym->owner, sym->name);
            }
            vecPush(top, sym);
        }
    }
    return top;
}

static void lspCatSym(LspCtx *ctx, AoStr *body, AoStr *text, LspSym *sym) {
    int l0 = sym->line - 1, c0 = sym->col - 1;
    aoStrCat(body, "{\"name\":\"");
    jsonEscapeInto(body, sym->name, strlen(sym->name));
    aoStrCatPrintf(body, "\",\"kind\":%d,", sym->kind);
    if (sym->detail) {
        aoStrCat(body, "\"detail\":\"");
        jsonEscapeInto(body, sym->detail, strlen(sym->detail));
        aoStrCat(body, "\",");
    }
    /* a method declared outside its struct's range keeps its own */
    int el = sym->end_line, ec = sym->end_col;
    if (el < l0 || (el == l0 && ec < c0 + sym->len)) {
        el = l0;
        ec = c0 + sym->len;
    }
    aoStrCatPrintf(body,
        "\"range\":{\"start\":{\"line\":%d,\"character\":%d},"
        "\"end\":{\"line\":%d,\"character\":%d}},",
        l0, 0, el, lspColToWire(ctx, text, el, ec));
    aoStrCatPrintf(body,
        "\"selectionRange\":{\"start\":{\"line\":%d,\"character\":%d},"
        "\"end\":{\"line\":%d,\"character\":%d}}",
        l0, lspColToWire(ctx, text, l0, c0), l0,
        lspColToWire(ctx, text, l0, c0 + sym->len));
    if (sym->children && sym->children->size) {
        aoStrCat(body, ",\"children\":[");
        for (u64 i = 0; i < sym->children->size; ++i) {
            LspSym *c = sym->children->entries[i];
            /* children inside the parent's range only: methods written
             * after the struct are listed with it but range on their own */
            if (i) aoStrPutChar(body, ',');
            lspCatSym(ctx, body, text, c);
        }
        aoStrPutChar(body, ']');
    }
    aoStrPutChar(body, '}');
}

static void lspDocumentSymbol(LspCtx *ctx, Json *id, Json *params) {
    const char *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri", NULL);
    AoStr *text = uri ? lspDocGetOrLoad(ctx, uri) : NULL;
    if (!text) {
        lspRespond(id, "null");
        return;
    }
    u32 doc_id = lspDocId(ctx, uri);
    Vec *flat = vecNew(&vec_unsigned_long_type);
    lspCollectSymbols(ctx, doc_id, lspDocModule(text), flat);
    lspCollectMacros(text, doc_id, flat);
    Vec *top = lspNestSymbols(text, flat);
    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    for (u64 i = 0; i < top->size; ++i) {
        if (i) aoStrPutChar(body, ',');
        lspCatSym(ctx, body, text, top->entries[i]);
    }
    aoStrPutChar(body, ']');
    lspRespond(id, body->data);
    aoStrRelease(body);
}

/* Does `name` contain every character of `query`, in order, ignoring
 * case (the fuzzy match editors expect)? */
static int lspFuzzy(const char *name, const char *query) {
    for (; *query; ++query) {
        while (*name && tolower((unsigned char)*name) != tolower((unsigned char)*query)) name++;
        if (!*name) return 0;
        name++;
    }
    return 1;
}

/* workspace/symbol: the symbols of every open document (and of the
 * files of their modules) matching the query */
static void lspWorkspaceSymbol(LspCtx *ctx, Json *id, Json *params) {
    const char *query = jsonStrOr(params, "query", "");
    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    int emitted = 0;
    Set *files = setNew(16, &set_cstring_type);
    MapIter di;
    mapIterInit(ctx->docs, &di);
    while (mapIterNext(&di)) {
        char *path = lspUriToPath(di.node->key);
        setAdd(files, path);
        char *slash = strrchr(path, '/');
        AoStr *dtext = di.node->value;
        if (slash && lspDocModule(dtext)) {
            /* its module's other files */
            char *dir = mprintf("%.*s", (int)(slash - path), path);
            DIR *d = opendir(dir);
            struct dirent *ent;
            while (d && (ent = readdir(d)) != NULL) {
                size_t n = strlen(ent->d_name);
                if (n >= 5 && !strcmp(ent->d_name + n - 4, ".ktm")) {
                    setAdd(files, mprintf("%s/%s", dir, ent->d_name));
                }
            }
            if (d) closedir(d);
        }
    }
    SetIter si;
    setIterInit(files, &si);
    char *path;
    while ((path = setNext(&si)) != NULL && emitted < 500) {
        const char *furi = NULL;
        AoStr *ftext = lspTextForPath(ctx, path, &furi);
        if (!ftext) continue;
        AoStr ps = { .data = path, .len = strlen(path), .capacity = 0 };
        u32 fid = cctrlRegisterFile(ctx->cc, &ps);
        Vec *flat = vecNew(&vec_unsigned_long_type);
        lspCollectSymbols(ctx, fid, lspDocModule(ftext), flat);
        lspCollectMacros(ftext, fid, flat);
        for (u64 i = 0; i < flat->size && emitted < 500; ++i) {
            LspSym *sym = flat->entries[i];
            if (!lspFuzzy(sym->name, query)) continue;
            if (emitted++) aoStrPutChar(body, ',');
            aoStrCat(body, "{\"name\":\"");
            jsonEscapeInto(body, sym->name, strlen(sym->name));
            aoStrCatPrintf(body, "\",\"kind\":%d,", sym->kind);
            if (sym->owner) {
                aoStrCat(body, "\"containerName\":\"");
                jsonEscapeInto(body, sym->owner, strlen(sym->owner));
                aoStrCat(body, "\",");
            }
            aoStrCat(body, "\"location\":{\"uri\":\"file://");
            jsonEscapeInto(body, path, strlen(path));
            aoStrCat(body, "\",");
            lspCatRange(ctx, body, ftext, sym->line - 1, sym->col - 1,
                        sym->col - 1 + sym->len);
            aoStrCat(body, "}}");
        }
    }
    aoStrPutChar(body, ']');
    lspRespond(id, body->data);
    aoStrRelease(body);
}

/* ---- signature help ----
 * Inside a call's parentheses: the function's signature (every overload
 * of it), the parameter the cursor is in, and the doc comment. */

/* The call around byte offset `at`: the offset of its `(` and how many
 * top-level commas come before `at`; 0 when not in one */
static int lspCallAround(AoStr *text, size_t at, size_t *open, int *commas) {
    const char *s = text->data;
    int depth = 0, n = 0;
    size_t i = at;
    /* only the statement the cursor is in: stop at ; { } */
    while (i > 0) {
        char c = s[--i];
        if (c == '"' || c == '\'') {
            /* back over the literal */
            while (i > 0 && !(s[i - 1] == c && (i < 2 || s[i - 2] != '\\'))) i--;
            if (i > 0) i--;
            continue;
        }
        if (c == ')' || c == ']') depth++;
        else if (c == '[') depth--;
        else if (c == '(') {
            if (depth == 0) {
                *open = i;
                *commas = n;
                return 1;
            }
            depth--;
        } else if (c == ',' && depth == 0) n++;
        else if (c == ';' || c == '{' || c == '}') return 0;
    }
    return 0;
}

/* The parameter list of a signature: each one's [start, end) in sig */
static int lspSigParams(const char *sig, int starts[32], int ends[32]) {
    const char *open = strchr(sig, '(');
    if (!open) return 0;
    int n = 0, depth = 0;
    const char *p = open + 1, *b = p;
    for (; *p; ++p) {
        if (*p == '(' || *p == '[' || *p == '<') depth++;
        else if ((*p == ')' || *p == ']' || *p == '>') && depth > 0) depth--;
        else if ((*p == ',' || *p == ')') && depth == 0) {
            const char *e = p;
            while (b < e && *b == ' ') b++;
            while (e > b && e[-1] == ' ') e--;
            if (e > b && n < 32 && !(e - b == 4 && !memcmp(b, "void", 4))) {
                starts[n] = (int)(b - sig);
                ends[n] = (int)(e - sig);
                n++;
            }
            if (*p == ')') break;
            b = p + 1;
        }
    }
    return n;
}

static void lspCatSignature(LspCtx *ctx, AoStr *body, char *sig, Ast *fn,
                            int commas) {
    size_t sl = strlen(sig);
    while (sl && (sig[sl - 1] == ';' || sig[sl - 1] == ' ')) sig[--sl] = '\0';
    aoStrCat(body, "{\"label\":\"");
    jsonEscapeInto(body, sig, sl);
    aoStrCat(body, "\",\"parameters\":[");
    int st[32], en[32];
    int n = lspSigParams(sig, st, en);
    for (int i = 0; i < n; ++i) {
        if (i) aoStrPutChar(body, ',');
        aoStrCatPrintf(body, "{\"label\":[%d,%d]}", st[i], en[i]);
    }
    aoStrPutChar(body, ']');
    /* the commas count the arguments written: skip the @caller
     * parameters, which a call never writes */
    int active = commas;
    for (int i = 0, seen = 0; i < n; ++i) {
        if (!strncmp(sig + st[i], "@caller", 7)) continue;
        if (seen++ == commas) { active = i; break; }
        active = i + 1;
    }
    /* past the last parameter of `...args`: still that one */
    if (n > 0 && active >= n && strstr(sig + st[n - 1], "...")) active = n - 1;
    aoStrCatPrintf(body, ",\"activeParameter\":%d", active);
    AoStr *fname = fn && fn->line > 0 ? cctrlLookUpFile(ctx->cc, fn->file_id) : NULL;
    if (fname) {
        const char *furi = NULL;
        AoStr *ftext = lspTextForPath(ctx, fname->data, &furi);
        AoStr *doc = ftext ? lspDocComment(ftext, fn->line) : NULL;
        if (doc) {
            aoStrCat(body, ",\"documentation\":{\"kind\":\"markdown\",\"value\":\"");
            jsonEscapeInto(body, doc->data, doc->len);
            aoStrCat(body, "\"}");
        }
    }
    aoStrPutChar(body, '}');
}

static void lspSignatureHelp(LspCtx *ctx, Json *id, Json *params) {
    const char *uri;
    int line, character;
    AoStr *text;
    if (!lspTextDocPosition(ctx, params, &uri, &line, &character) ||
        (text = lspDocGetOrLoad(ctx, uri)) == NULL) {
        lspRespond(id, "null");
        return;
    }
    size_t at = lspOffsetOf(text, line + 1, character + 1), open;
    int commas;
    if (!lspCallAround(text, at, &open, &commas)) {
        lspRespond(id, "null");
        return;
    }
    /* the callee's name, right before the `(` */
    size_t ne = open;
    while (ne > 0 && (text->data[ne - 1] == ' ' || text->data[ne - 1] == '\t')) ne--;
    if (ne == 0 || !lspIsIdentChar(text->data[ne - 1])) {
        lspRespond(id, "null");
        return;
    }
    int cl, cc;
    lspLineColOf(text, ne - 1, &cl, &cc);
    u32 doc_id = lspDocId(ctx, uri);
    char ident[256];
    if (!lspIdentAt(text, cl, cc, 0, ident, sizeof(ident), NULL)) {
        lspRespond(id, "null");
        return;
    }
    s64 ilen = (s64)strlen(ident);

    Ast *fn = NULL;
    AstType *owner = NULL;
    char *qual = lspModuleRefAt(text, cl, cc, 0, NULL);
    int is_member = 0;
    AstType *mcls = qual ? NULL : lspMemberClassAt(ctx, text, doc_id, cl, cc, &is_member);
    if (!qual && !is_member) qual = lspOwnModuleQual(ctx, text, doc_id, cl + 1, ident, ilen);
    if (qual) {
        fn = lspModuleValue(ctx, qual);
    } else if (is_member) {
        fn = mcls ? lspFindMethod(ctx, mcls, ident, ilen, &owner) : NULL;
    } else {
        int in_doc = 0;
        fn = lspResolveValue(ctx, doc_id, cl + 1, ident, ilen, &in_doc);
    }
    GenericDef *gen = (!fn && !qual && !is_member) ? lspGeneric(ctx, ident, ilen) : NULL;
    if ((!fn || !lspIsFn(fn)) && !gen) {
        lspRespond(id, "null");
        return;
    }

    AoStr *body = aoStrNew();
    aoStrCat(body, "{\"signatures\":[");
    int active = 0;
    if (gen) {
        AoStr *head = aoStrNew();
        lspCatGenericHead(head, gen);
        lspCatSignature(ctx, body, head->data, NULL, commas);
    } else {
        Ast *pick = astOverloads(fn) ? lspPickOverload(ctx, doc_id, cl + 1, cc, fn) : fn;
        u64 n = fn->overloads ? fn->overloads->size : 1;
        int best = -1;
        for (u64 i = 0; i < n; ++i) {
            Ast *o = fn->overloads ? fn->overloads->entries[i] : fn;
            char *sig = owner ? lspMethodString(o, owner, ident)
                              : lspModuleFnString(o, qual ? qual : ident);
            if (i) aoStrPutChar(body, ',');
            lspCatSignature(ctx, body, sig, o, commas);
            if (o == pick) active = (int)i;
            int st[32], en[32];
            if (best < 0 && lspSigParams(sig, st, en) > commas) best = (int)i;
        }
        if ((!pick || pick == fn) && astOverloads(fn) && best >= 0) active = best;
    }
    aoStrCatPrintf(body, "],\"activeSignature\":%d,\"activeParameter\":%d}", active, commas);
    lspRespond(id, body->data);
    aoStrRelease(body);
}

/* ---- semantic tokens ----
 * What each name is, for editors to colour beyond what a grammar can
 * tell: modules, types, enums and their members, functions and methods,
 * fields, parameters, locals, constants, macros, type parameters; with
 * `declaration`, `readonly` (consts) and `defaultLibrary` (the stdlib's).
 * Keywords, literals and comments are left to the grammar. */

enum {
    LSP_ST_NAMESPACE, LSP_ST_TYPE, LSP_ST_STRUCT, LSP_ST_ENUM,
    LSP_ST_ENUM_MEMBER, LSP_ST_FUNCTION, LSP_ST_METHOD, LSP_ST_PROPERTY,
    LSP_ST_PARAMETER, LSP_ST_VARIABLE, LSP_ST_MACRO, LSP_ST_TYPE_PARAMETER,
};
#define LSP_SM_DECLARATION 1
#define LSP_SM_READONLY    2
#define LSP_SM_DEFAULT_LIB 4

#define LSP_SEMANTIC_LEGEND \
    "{\"tokenTypes\":[\"namespace\",\"type\",\"struct\",\"enum\",\"enumMember\"," \
    "\"function\",\"method\",\"property\",\"parameter\",\"variable\",\"macro\"," \
    "\"typeParameter\"],\"tokenModifiers\":[\"declaration\",\"readonly\"," \
    "\"defaultLibrary\"]}"

typedef struct LspSemCtx {
    LspCtx *ctx;
    AoStr *text;
    u32 doc_id;
    char *mod;          /* the document's module, NULL when none */
    Vec *fns;           /* the document's functions, by line */
    Map *std_files;     /* file id (as text) -> "1"/"0": in the stdlib */
    Set *type_params;   /* every generic's parameter names */
    AoStr *out;
    int emitted, prev_line, prev_col;
} LspSemCtx;

static int lspFnLineCmp(const void *a, const void *b) {
    return (*(Ast *const *)a)->line - (*(Ast *const *)b)->line;
}

static int lspSemStd(LspSemCtx *sc, u32 file_id) {
    if (!file_id) return 0;
    char *key = mprintf("%u", file_id);
    char *v = mapGet(sc->std_files, key);
    if (!v) {
        AoStr *f = cctrlLookUpFile(sc->ctx->cc, file_id);
        v = f && lspPathInStdlib(sc->ctx, f->data) ? "1" : "0";
        mapAdd(sc->std_files, key, v);
    }
    return *v == '1';
}

/* The function of the document enclosing line1, NULL at file scope */
static Ast *lspSemEnclosing(LspSemCtx *sc, int line1) {
    Ast *encl = NULL;
    for (u64 i = 0; i < sc->fns->size; ++i) {
        Ast *fn = sc->fns->entries[i];
        if (fn->line > line1) break;
        encl = fn;
    }
    return encl;
}

static void lspSemEmit(LspSemCtx *sc, int line, int bcol, int blen, int type,
                       int mods) {
    int c0 = lspColToWire(sc->ctx, sc->text, line, bcol);
    int c1 = lspColToWire(sc->ctx, sc->text, line, bcol + blen);
    int dl = line - sc->prev_line;
    int dc = dl == 0 ? c0 - sc->prev_col : c0;
    if (sc->emitted++) aoStrPutChar(sc->out, ',');
    aoStrCatPrintf(sc->out, "%d,%d,%d,%d,%d", dl, dc, c1 - c0, type, mods);
    sc->prev_line = line;
    sc->prev_col = c0;
}

static int lspSemIsKeyword(const char *w, size_t n) {
    static const char *extra[] = {
        "ad", "type", "asm", "volatile", "inline", "class", "public",
        "noreg", "reg", "elif", "elifdef", "ifdef", "ifndef", "endif",
        "define", "undef", "defined", "include", "link", "typeof", "alignof",
    };
    for (u64 i = 0; i < sizeof(lsp_keywords) / sizeof(lsp_keywords[0]); ++i) {
        if (strlen(lsp_keywords[i]) == n && !memcmp(lsp_keywords[i], w, n)) return 1;
    }
    for (u64 i = 0; i < sizeof(extra) / sizeof(extra[0]); ++i) {
        if (strlen(extra[i]) == n && !memcmp(extra[i], w, n)) return 1;
    }
    return 0;
}

/* A value's (function's, global's) token type and modifiers */
static int lspSemValue(LspSemCtx *sc, Ast *e, int line1, int col1, int *mods) {
    if (lspIsFn(e)) {
        Ast *d = e->overloads && e->overloads->size ? e->overloads->entries[0] : e;
        if (lspSemStd(sc, d->file_id)) *mods |= LSP_SM_DEFAULT_LIB;
        if (e->overloads) {
            for (u64 i = 0; i < e->overloads->size; ++i) {
                Ast *o = e->overloads->entries[i];
                if (o->file_id == sc->doc_id && o->line == line1 && o->col == col1) {
                    *mods |= LSP_SM_DECLARATION;
                }
            }
        }
        if (e->file_id == sc->doc_id && e->line == line1 && e->col == col1) {
            *mods |= LSP_SM_DECLARATION;
        }
        return lspIsMethod(e) ? LSP_ST_METHOD : LSP_ST_FUNCTION;
    }
    if (e->flags & AST_FLAG_CONST) *mods |= LSP_SM_READONLY;
    if (lspSemStd(sc, e->file_id)) *mods |= LSP_SM_DEFAULT_LIB;
    if (e->file_id == sc->doc_id && e->line == line1 && e->col == col1) {
        *mods |= LSP_SM_DECLARATION;
    }
    return LSP_ST_VARIABLE;
}

static int lspSemType(LspSemCtx *sc, AstType *t, int line1, int col1, int *mods) {
    if (lspSemStd(sc, t->generic ? ((GenericDef *)t->generic)->file_id : t->file_id)) {
        *mods |= LSP_SM_DEFAULT_LIB;
    }
    if (t->file_id == sc->doc_id && t->line == line1 && t->col == col1) {
        *mods |= LSP_SM_DECLARATION;
    }
    return t->is_enum ? LSP_ST_ENUM : LSP_ST_STRUCT;
}

/* A qualified name `a.b.name` (a module's): its token type, -1 if none */
static int lspSemQual(LspSemCtx *sc, const char *qual, int line1, int col1,
                      int *mods) {
    Cctrl *cc = sc->ctx->cc;
    Ast *e = lspModuleValue(sc->ctx, qual);
    if (e) return lspSemValue(sc, e, line1, col1, mods);
    AstType *t = mapGet(cc->clsdefs, (char *)qual);
    if (!t) t = mapGet(cc->uniondefs, (char *)qual);
    if (t) return lspSemType(sc, t, line1, col1, mods);
    TypeAlias *a = cc->type_aliases ? mapGet(cc->type_aliases, (char *)qual) : NULL;
    if (a) return LSP_ST_TYPE;
    GenericDef *g = lspGeneric(sc->ctx, qual, strlen(qual));
    if (g) {
        if (lspSemStd(sc, g->file_id)) *mods |= LSP_SM_DEFAULT_LIB;
        return g->kind == GEN_STRUCT ? LSP_ST_STRUCT : LSP_ST_FUNCTION;
    }
    return -1;
}

/* The identifier [b, e) on 0-based `line` (line_start its first byte) */
static void lspSemIdent(LspSemCtx *sc, size_t b, size_t e, int line,
                        size_t line_start) {
    const char *s = sc->text->data;
    size_t n = sc->text->len, len = e - b;
    if (lspSemIsKeyword(s + b, len)) return;
    int line1 = line + 1, col1 = (int)(b - line_start) + 1;
    LspCtx *ctx = sc->ctx;
    Cctrl *cc = ctx->cc;

    /* what is around it */
    size_t p = b;
    while (p > line_start && (s[p - 1] == ' ' || s[p - 1] == '\t')) p--;
    int after_dot = p > line_start && (s[p - 1] == '.' ||
                    (s[p - 1] == '>' && p - 1 > line_start && s[p - 2] == '-'));
    size_t q = e;
    while (q < n && (s[q] == ' ' || s[q] == '\t')) q++;
    int before_dot = q < n && s[q] == '.' && !(q + 1 < n && s[q + 1] == '.');
    int before_call = q < n && s[q] == '(';
    int mods = 0, type = -1;

    if (after_dot) {
        /* the dotted chain before: a module, an enum or a type */
        size_t ce = p - 1;
        if (s[ce] == '>') ce--;
        size_t cb = ce;
        while (cb > line_start && (lspIsIdentChar(s[cb - 1]) || s[cb - 1] == '.')) cb--;
        if (cb < ce && s[cb] != '.' && s[ce] != '-') {
            const char *dot = memchr(s + cb, '.', ce - cb);
            size_t hl = dot ? (size_t)(dot - (s + cb)) : ce - cb;
            char *path = lspImportPath(sc->text, s + cb, hl);
            int in_doc = 0;
            if (path) {
                char word[256];
                snprintf(word, sizeof(word), "%.*s", (int)hl, s + cb);
                lspResolveValue(ctx, sc->doc_id, line1, word, (s64)hl, &in_doc);
            }
            if (path && !in_doc) {
                char *qual = mprintf("%s%.*s.%.*s", path, (int)(ce - cb - hl), s + cb + hl,
                                     (int)len, s + b);
                type = lspSemQual(sc, qual, line1, col1, &mods);
                if (type < 0) {
                    /* `geo.Kind.rect` */
                    AstType *t = lspTypeByName(ctx, sc->text, s + cb, ce - cb);
                    if (t && t->is_enum) {
                        type = LSP_ST_ENUM_MEMBER;
                        mods |= LSP_SM_READONLY;
                        if (lspSemStd(sc, t->file_id)) mods |= LSP_SM_DEFAULT_LIB;
                    }
                }
            } else if (!in_doc) {
                AstType *t = lspTypeByName(ctx, sc->text, s + cb, ce - cb);
                int v_in_doc = 0;
                Ast *v = dot ? NULL : lspResolveValue(ctx, sc->doc_id, line1, s + cb,
                                                      (s64)(ce - cb), &v_in_doc);
                if (t && t->is_enum && !v_in_doc && !(v && !lspIsFn(v))) {
                    type = LSP_ST_ENUM_MEMBER;
                    mods |= LSP_SM_READONLY;
                    if (lspSemStd(sc, t->file_id)) mods |= LSP_SM_DEFAULT_LIB;
                }
            }
        }
        if (type < 0) type = before_call ? LSP_ST_METHOD : LSP_ST_PROPERTY;
        lspSemEmit(sc, line, (int)(b - line_start), (int)len, type, mods);
        return;
    }

    char word[256];
    if (len >= sizeof(word)) return;
    memcpy(word, s + b, len);
    word[len] = '\0';

    /* a module's prefix */
    if (before_dot) {
        char *path = lspImportPath(sc->text, word, len);
        int in_doc = 0;
        if (path) lspResolveValue(ctx, sc->doc_id, line1, word, (s64)len, &in_doc);
        if (path && !in_doc) {
            char *dir = lspModuleDir(ctx, path);
            lspSemEmit(sc, line, (int)(b - line_start), (int)len, LSP_ST_NAMESPACE,
                       dir && lspPathInStdlib(ctx, mprintf("%s/", dir))
                       ? LSP_SM_DEFAULT_LIB : 0);
            return;
        }
    }

    /* a local or a parameter */
    Ast *encl = lspSemEnclosing(sc, line1);
    if (encl) {
        Ast *hit = NULL;
        int is_param = 0;
        if (encl->params) {
            for (u64 i = 0; i < encl->params->size; ++i) {
                Ast *v = encl->params->entries[i];
                if (v && v->kind == AST_LVAR && v->lname && v->lname->len == len &&
                    !memcmp(v->lname->data, word, len)) {
                    hit = v;
                    is_param = 1;
                }
            }
        }
        if (encl->locals) {
            listForEach(encl->locals) {
                Ast *v = (Ast *)it->value;
                if (v && v->kind == AST_DECL && v->declvar) v = v->declvar;
                if (v && v->kind == AST_LVAR && v->lname && v->lname->len == len &&
                    !memcmp(v->lname->data, word, len) && v->line > 0 &&
                    v->line <= line1 && (hit == NULL || v->line >= hit->line)) {
                    hit = v;
                    is_param = 0;
                }
            }
        }
        if (hit) {
            if (hit->flags & AST_FLAG_CONST) mods |= LSP_SM_READONLY;
            if (hit->line == line1 && hit->col == col1) mods |= LSP_SM_DECLARATION;
            /* `self`: a method's receiver */
            lspSemEmit(sc, line, (int)(b - line_start), (int)len,
                       is_param ? LSP_ST_PARAMETER : LSP_ST_VARIABLE, mods);
            return;
        }
    }

    /* a name of the document's own module, then any global */
    if (sc->mod) {
        char *qual = mprintf("%s.%s", sc->mod, word);
        type = lspSemQual(sc, qual, line1, col1, &mods);
    }
    if (type < 0) {
        Ast *e = mapGetLen(cc->global_env, word, len);
        if (!e) e = mapGetLen(cc->asm_funcs, word, len);
        if (e) type = lspSemValue(sc, e, line1, col1, &mods);
    }
    if (type < 0) type = lspSemQual(sc, word, line1, col1, &mods);
    if (type < 0) {
        /* a member where its enum declares it, a field where its struct does */
        AstType *owner = NULL;
        if (lspEnumMemberAt(ctx, sc->text, sc->doc_id, line, (int)(b - line_start), &owner)) {
            type = LSP_ST_ENUM_MEMBER;
            mods |= LSP_SM_DECLARATION | LSP_SM_READONLY;
        } else if (lspFieldDeclHere(ctx, sc->text, sc->doc_id, line,
                                    (int)(b - line_start), &owner)) {
            type = LSP_ST_PROPERTY;
            mods |= LSP_SM_DECLARATION;
        }
    }
    if (type < 0 && mapGetLen(cc->macro_defs, word, len)) type = LSP_ST_MACRO;
    if (type < 0 && setHas(sc->type_params, word)) type = LSP_ST_TYPE_PARAMETER;
    if (type < 0 && lspImportPath(sc->text, word, len)) type = LSP_ST_NAMESPACE;
    if (type < 0) return;
    lspSemEmit(sc, line, (int)(b - line_start), (int)len, type, mods);
}

static void lspSemanticTokens(LspCtx *ctx, Json *id, Json *params) {
    const char *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri", NULL);
    AoStr *text = uri ? lspDocGetOrLoad(ctx, uri) : NULL;
    if (!text) {
        lspRespond(id, "null");
        return;
    }
    LspSemCtx sc = {0};
    sc.ctx = ctx;
    sc.text = text;
    sc.doc_id = lspDocId(ctx, uri);
    sc.mod = lspDocModule(text);
    sc.fns = vecNew(&vec_unsigned_long_type);
    sc.std_files = mapNew(16, &map_cstring_opaque_type);
    sc.type_params = setNew(16, &set_cstring_type);
    sc.out = aoStrNew();
    MapIter mi;
    mapIterInit(ctx->cc->global_env, &mi);
    Set *seen = setNew(16, &set_cstring_type);
    while (mapIterNext(&mi)) {
        Ast *fn = mi.node->value;
        if (!fn || !astIsFnLike(fn) || fn->file_id != sc.doc_id || fn->line <= 0) continue;
        char *k = mprintf("%p", (void *)fn);
        if (setHas(seen, k)) continue;
        setAdd(seen, k);
        vecPush(sc.fns, fn);
    }
    qsort(sc.fns->entries, sc.fns->size, sizeof(Ast *), lspFnLineCmp);
    Map *gens[2] = { ctx->cc->generic_structs, ctx->cc->generic_funcs };
    for (int g = 0; g < 2; ++g) {
        if (!gens[g]) continue;
        mapIterInit(gens[g], &mi);
        while (mapIterNext(&mi)) {
            GenericDef *d = mi.node->value;
            for (u64 i = 0; d && i < d->params->size; ++i) {
                setAdd(sc.type_params, ((AoStr *)d->params->entries[i])->data);
            }
        }
    }

    const char *s = text->data;
    size_t n = text->len, i = 0, line_start = 0;
    int line = 0, at_line_start = 1;
    while (i < n) {
        char c = s[i];
        if (c == '\n') {
            line++;
            i++;
            line_start = i;
            at_line_start = 1;
            continue;
        }
        if (c == ' ' || c == '\t' || c == '\r') {
            i++;
            continue;
        }
        if (at_line_start && c == '#') {
            /* a directive: `#define NAME` names a macro; the rest is the
             * grammar's */
            size_t eol = i;
            while (eol < n && s[eol] != '\n') eol++;
            if (eol - i > 8 && !memcmp(s + i, "#define", 7) && isspace((unsigned char)s[i + 7])) {
                size_t b = i + 7;
                while (b < eol && isspace((unsigned char)s[b])) b++;
                size_t e = b;
                while (e < eol && lspIsIdentChar(s[e])) e++;
                if (e > b) {
                    lspSemEmit(&sc, line, (int)(b - line_start), (int)(e - b),
                               LSP_ST_MACRO, LSP_SM_DECLARATION);
                }
            }
            i = eol;
            continue;
        }
        at_line_start = 0;
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
            i += 2;
            continue;
        }
        if (c == '"' || c == '\'') {
            for (i++; i < n && s[i] != c && s[i] != '\n'; i++) {
                if (s[i] == '\\') i++;
            }
            i++;
            continue;
        }
        if (isdigit((unsigned char)c)) {
            while (i < n && (lspIsIdentChar(s[i]) || s[i] == '.')) i++;
            continue;
        }
        if (lspIsIdentChar(c)) {
            size_t b = i;
            while (i < n && lspIsIdentChar(s[i])) i++;
            lspSemIdent(&sc, b, i, line, line_start);
            continue;
        }
        i++;
    }
    AoStr *body = aoStrNew();
    aoStrCatPrintf(body, "{\"data\":[%s]}", sc.out->data);
    lspRespond(id, body->data);
    aoStrRelease(body);
    aoStrRelease(sc.out);
}

/* ---- folding ranges ----
 * Blocks (`{` ... `}` over several lines), block comments and runs of
 * `//` lines, runs of imports, and #if ... #endif regions. */

static void lspCatFold(AoStr *body, int *emitted, int l0, int l1, const char *kind) {
    if (l1 <= l0) return;
    if ((*emitted)++) aoStrPutChar(body, ',');
    aoStrCatPrintf(body, "{\"startLine\":%d,\"endLine\":%d", l0, l1);
    if (kind) aoStrCatPrintf(body, ",\"kind\":\"%s\"", kind);
    aoStrPutChar(body, '}');
}

static void lspFoldingRange(LspCtx *ctx, Json *id, Json *params) {
    const char *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri", NULL);
    AoStr *text = uri ? lspDocGetOrLoad(ctx, uri) : NULL;
    if (!text) {
        lspRespond(id, "null");
        return;
    }
    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    int emitted = 0;
    int braces[256], nb = 0, ifs[64], ni = 0;
    int line = 0, at_line_start = 1;
    int imp_first = -1, imp_last = -1, cmt_first = -1, cmt_last = -1;
    const char *s = text->data;
    size_t n = text->len, i = 0;
    while (i < n) {
        char c = s[i];
        if (c == '\n') {
            line++;
            i++;
            at_line_start = 1;
            continue;
        }
        if (c == ' ' || c == '\t' || c == '\r') {
            i++;
            continue;
        }
        if (at_line_start) {
            at_line_start = 0;
            /* a line's first word: runs of imports and of // lines */
            int is_import = n - i > 7 && !memcmp(s + i, "import", 6) &&
                            isspace((unsigned char)s[i + 6]);
            int is_cmt = n - i > 1 && s[i] == '/' && s[i + 1] == '/';
            if (is_import) {
                if (imp_first < 0 || imp_last < line - 1) {
                    if (imp_first >= 0) lspCatFold(body, &emitted, imp_first, imp_last, "imports");
                    imp_first = line;
                }
                imp_last = line;
            }
            if (is_cmt) {
                if (cmt_first < 0 || cmt_last < line - 1) {
                    if (cmt_first >= 0) lspCatFold(body, &emitted, cmt_first, cmt_last, "comment");
                    cmt_first = line;
                }
                cmt_last = line;
            }
            if (c == '#') {
                size_t eol = i;
                while (eol < n && s[eol] != '\n') eol++;
                const char *d = s + i + 1;
                while (d < s + eol && isspace((unsigned char)*d)) d++;
                size_t dl = (s + eol) - d;
                if ((dl >= 2 && !memcmp(d, "if", 2)) && ni < 64) {
                    ifs[ni++] = line;
                } else if (dl >= 5 && !memcmp(d, "endif", 5) && ni > 0) {
                    lspCatFold(body, &emitted, ifs[--ni], line - 1, "region");
                } else if (dl >= 4 && (!memcmp(d, "else", 4) || !memcmp(d, "elif", 4)) && ni > 0) {
                    lspCatFold(body, &emitted, ifs[ni - 1], line - 1, "region");
                    ifs[ni - 1] = line;
                }
                i = eol;
                continue;
            }
        }
        if (c == '/' && i + 1 < n && s[i + 1] == '/') {
            while (i < n && s[i] != '\n') i++;
            continue;
        }
        if (c == '/' && i + 1 < n && s[i + 1] == '*') {
            int first = line;
            i += 2;
            while (i + 1 < n && !(s[i] == '*' && s[i + 1] == '/')) {
                if (s[i] == '\n') line++;
                i++;
            }
            i += 2;
            lspCatFold(body, &emitted, first, line, "comment");
            continue;
        }
        if (c == '"' || c == '\'') {
            for (i++; i < n && s[i] != c && s[i] != '\n'; i++) {
                if (s[i] == '\\') i++;
            }
            i++;
            continue;
        }
        if (c == '{') {
            if (nb < 256) braces[nb] = line;
            nb++;
        } else if (c == '}' && nb > 0) {
            nb--;
            /* the closing line stays visible */
            if (nb < 256) lspCatFold(body, &emitted, braces[nb], line - 1, NULL);
        }
        i++;
    }
    if (imp_first >= 0) lspCatFold(body, &emitted, imp_first, imp_last, "imports");
    if (cmt_first >= 0) lspCatFold(body, &emitted, cmt_first, cmt_last, "comment");
    aoStrPutChar(body, ']');
    lspRespond(id, body->data);
    aoStrRelease(body);
}

/* ---- inlay hints ----
 * The type `auto` gave a variable, after its name (`auto n = 3`: `: i64`). */
static void lspInlayHint(LspCtx *ctx, Json *id, Json *params) {
    const char *uri = jsonStrOr(jsonObjGet(params, "textDocument"), "uri", NULL);
    AoStr *text = uri ? lspDocGetOrLoad(ctx, uri) : NULL;
    if (!text) {
        lspRespond(id, "null");
        return;
    }
    Json *range = jsonObjGet(params, "range");
    int first = (int)jsonIntOr(jsonObjGet(range, "start"), "line", 0);
    int last = (int)jsonIntOr(jsonObjGet(range, "end"), "line", 1 << 30);
    u32 doc_id = lspDocId(ctx, uri);
    AoStr *body = aoStrNew();
    aoStrPutChar(body, '[');
    int emitted = 0;
    const char *s = text->data;
    size_t n = text->len;
    int line = 0;
    size_t line_start = 0;
    for (size_t i = 0; i < n; ++i) {
        if (s[i] == '\n') {
            line++;
            line_start = i + 1;
            continue;
        }
        if (line < first || line > last) continue;
        /* skip comments and literals */
        if (s[i] == '/' && i + 1 < n && s[i + 1] == '/') {
            while (i + 1 < n && s[i + 1] != '\n') i++;
            continue;
        }
        if (s[i] == '"' || s[i] == '\'') {
            char q = s[i];
            for (i++; i < n && s[i] != q && s[i] != '\n'; i++) {
                if (s[i] == '\\') i++;
            }
            continue;
        }
        if (n - i < 5 || memcmp(s + i, "auto", 4) || lspIsIdentChar(s[i + 4]) ||
            (i > 0 && lspIsIdentChar(s[i - 1]))) {
            continue;
        }
        size_t b = i + 4;
        while (b < n && (s[b] == ' ' || s[b] == '\t')) b++;
        size_t e = b;
        while (e < n && lspIsIdentChar(s[e])) e++;
        if (e == b) continue;
        int in_doc = 0;
        Ast *v = lspResolveValue(ctx, doc_id, line + 1, s + b, (s64)(e - b), &in_doc);
        i = e - 1;
        if (!v || !in_doc || !v->type) continue;
        char *ty = astTypeToString(v->type);
        if (!ty || !*ty) continue;
        if (emitted++) aoStrPutChar(body, ',');
        aoStrCatPrintf(body,
            "{\"position\":{\"line\":%d,\"character\":%d},\"label\":\": ",
            line, lspColToWire(ctx, text, line, (int)(e - line_start)));
        jsonEscapeInto(body, ty, strlen(ty));
        aoStrCat(body, "\",\"kind\":1,\"paddingLeft\":false}");
    }
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

/* A request about no one document: answered from a child that parsed
 * every open document (an empty answer when none is open) */
static void lspServeWorkspace(LspCtx *ctx, Json *id, Json *params,
                              LspHandler handler) {
    MapIter mi;
    mapIterInit(ctx->docs, &mi);
    if (!mapIterNext(&mi)) {
        lspRespond(id, "[]");
        return;
    }
    const char *uri = mi.node->key;
    int sig = lspRunIsolated(ctx, uri, 1, handler, id, params);
    if (sig) {
        lspLog(ctx, "lsp: workspace request failed (signal %d)", sig);
        lspRespond(id, "[]");
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
        "\"documentHighlightProvider\":true,"
        "\"documentSymbolProvider\":true,"
        "\"foldingRangeProvider\":true,"
        "\"inlayHintProvider\":true,"
        "\"semanticTokensProvider\":{\"legend\":" LSP_SEMANTIC_LEGEND ",\"full\":true},"
        "\"signatureHelpProvider\":{\"triggerCharacters\":[\"(\",\",\"],"
        "\"retriggerCharacters\":[\")\"]},"
        "\"workspaceSymbolProvider\":true"
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
    ctx->std_dir = mprintf("%s/modules/", args->install_dir);
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
        } else if (strcmp(method, "textDocument/signatureHelp") == 0) {
            lspServe(ctx, id, params, lspSignatureHelp, 0);
        } else if (strcmp(method, "textDocument/semanticTokens/full") == 0) {
            lspServe(ctx, id, params, lspSemanticTokens, 0);
        } else if (strcmp(method, "textDocument/foldingRange") == 0) {
            lspServe(ctx, id, params, lspFoldingRange, 0);
        } else if (strcmp(method, "textDocument/inlayHint") == 0) {
            lspServe(ctx, id, params, lspInlayHint, 0);
        } else if (strcmp(method, "textDocument/documentSymbol") == 0) {
            lspServe(ctx, id, params, lspDocumentSymbol, 0);
        } else if (strcmp(method, "workspace/symbol") == 0) {
            lspServeWorkspace(ctx, id, params, lspWorkspaceSymbol);
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
