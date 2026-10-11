/* ktm-convert: a one-time tool that rewrites ktm's own code from the old
 * (HolyC-style) syntax to the new one. Dev-only: built from the compiler
 * sources with -DKTM_CONVERT (cmake -DKTM_BUILD_CONVERT=on), never part
 * of ktm. Deleted with the old syntax.
 *
 * Each file is parsed by the real parser in legacy mode. The parser calls
 * the migrateNote* hooks for what only it can see (postfix casts, print
 * statements, calls without parentheses, Main, code at file scope); a
 * scan of the text finds the words to rename (type names, TRUE/FALSE/NULL,
 * class, public). Every change is an edit on a range of the original
 * text, so everything else (comments, layout) is kept byte for byte. */
#if defined(__linux__)
#ifndef _GNU_SOURCE
#define _GNU_SOURCE /* realpath, strdup, PATH_MAX */
#endif
#endif
#include <ctype.h>
#include <stdarg.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <fcntl.h>
#include <unistd.h>

#include "aostr.h"
#include "ast.h"
#include "ir-types.h"
#include "memory.h"
#include "util.h"
#include "cctrl.h"
#include "cli.h"
#include "containers.h"
#include "lexer.h"
#include "convert.h"
#include "parser.h"
#include "prslib.h"

#ifndef INSTALL_PREFIX
#define INSTALL_PREFIX "/usr/local"
#endif

/* main.c's globals and memory set-up, which this tool replaces */
int is_terminal = 0;

static void convertMemoryInit(void) {
    astMemoryInit();
    lexemeMemoryInit();
    globalArenaInit(4096 * 10);
    irMemoryInit();
    aoStrTmpBufInit();
}

typedef struct MigEdit {
    s64 start;      /* replace [start, end) with text; start == end inserts */
    s64 end;
    char *text;
    s64 seq;        /* order of recording: inserts at one offset go newest first */
    const char *kind;
    /* A rewrite that moves code: its text is built at apply time from
     * these pieces (MigSeg *), each literal text or a range of the
     * original rendered with the edits inside it */
    Vec *segs;
} MigEdit;

typedef struct MigSeg {
    const char *text;   /* NULL: the range [s, e) */
    s64 s, e;
} MigSeg;

/* Code at file scope that runs: moved into main */
typedef struct MigItem {
    s64 start;
    s64 end;
    int is_decl;
} MigItem;

typedef struct MigFile {
    char *path;
    char *real;          /* realpath, to recognise it when it's #included */
    char *base;          /* the lexer's buffer for this file, while parsing */
    /* a module's file is read once per pass (names, declarations, full):
     * the other passes' buffers, the same text */
    char *alt_base[4];
    int n_alt;
    char *text;          /* our own copy of the original text */
    s64 len;
    Vec *edits;          /* MigEdit * */
    Vec *items;          /* MigItem * */
    Set *seen;           /* edits already recorded (macro bodies expand often) */
    s64 seq;
    s64 item_start;
    int has_main;
    s64 main_name;
    s64 main_lbrace;
    s64 main_item;
    int main_void;
    int top_calls_main;
    Vec *main_calls;     /* offsets of `Main` called without parentheses */
    int failed;
    AoStr *notes;        /* "path:line: message" lines for the report */
    Vec *skipped;        /* [start, end) pairs of text an #if skipped */
    Vec *catches;        /* MigCatch *: catches without `(Error e)` */
    Vec *defines;        /* MigDefine *: #defines that fold to a number */
    Vec *closers;        /* MigEdit *: `}`s of bodies, innermost first */
    Set *imports;        /* modules the file uses (--map) */
    Set *code_words;     /* identifiers in its code (--names clashes) */
    const char *module;  /* the module it says it is in (migFileModule) */
} MigFile;

typedef struct MigDefine {
    s64 line;            /* offset of the #define line */
    const char *type;    /* i64 u64 f64 */
} MigDefine;

typedef struct MigCatch {
    s64 kw_end;          /* just past `catch` */
    s64 end;             /* just past its handler */
    const char *name;    /* the Error's name, once one is needed */
} MigCatch;

struct Migrate {
    MigFile **files;
    int n_files;
    Lexeme *item_first;
};

static Migrate *mig_active = NULL;

/* ------------------------------------------------------------------ */

static MigFile *migFileOf(Cctrl *cc, char *p) {
    Migrate *m = cc ? cc->migrate : mig_active;
    if (!m || !p) return NULL;
    for (int i = 0; i < m->n_files; ++i) {
        MigFile *f = m->files[i];
        if (f->base && p >= f->base && p < f->base + f->len) return f;
        for (int k = 0; k < f->n_alt; ++k)
            if (p >= f->alt_base[k] && p < f->alt_base[k] + f->len) return f;
    }
    return NULL;
}

static s64 migOff(MigFile *f, Lexeme *t) {
    if (!t || !t->src || !f->base) return -1;
    if (t->src >= f->base && t->src < f->base + f->len) return t->src - f->base;
    for (int k = 0; k < f->n_alt; ++k)
        if (t->src >= f->alt_base[k] && t->src < f->alt_base[k] + f->len)
            return t->src - f->alt_base[k];
    return -1;
}

static int migLine(MigFile *f, s64 off) {
    int line = 1;
    for (s64 i = 0; i < off && i < f->len; ++i) {
        if (f->text[i] == '\n') line++;
    }
    return line;
}

static void migNote(MigFile *f, s64 off, const char *fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    aoStrCatPrintf(f->notes, "%s:%d: %s\n", f->path, off >= 0 ? migLine(f, off) : 0, buf);
}

static void migAdd(MigFile *f, s64 start, s64 end, const char *text,
                   const char *kind)
{
    char *key = mprintf("%lld:%lld:%s", (long long)start, (long long)end, text);
    if (setHas(f->seen, key)) return;
    setAdd(f->seen, key);
    MigEdit *e = (MigEdit *)malloc(sizeof(MigEdit));
    e->start = start;
    e->end = end;
    e->text = strdup(text);
    e->seq = f->seq++;
    e->kind = kind;
    e->segs = NULL;
    vecPush(f->edits, e);
}

/* Pieces for migAddSegs */
static MigSeg *migLit(const char *text) {
    MigSeg *g = (MigSeg *)calloc(1, sizeof(MigSeg));
    g->text = strdup(text);
    return g;
}

static MigSeg *migRange(s64 s, s64 e) {
    MigSeg *g = (MigSeg *)calloc(1, sizeof(MigSeg));
    g->s = s;
    g->e = e;
    return g;
}

/* Replace [start, end) with the pieces (a NULL-terminated list) */
static void migAddSegs(MigFile *f, s64 start, s64 end, const char *kind, ...) {
    va_list ap;
    va_start(ap, kind);
    Vec *segs = vecNew(&vec_unsigned_long_type);
    AoStr *key = aoStrPrintf("%lld:%lld:segs", (long long)start, (long long)end);
    MigSeg *g;
    while ((g = va_arg(ap, MigSeg *)) != NULL) {
        vecPush(segs, g);
        if (g->text) aoStrCatPrintf(key, "|%s", g->text);
        else aoStrCatPrintf(key, "|%lld-%lld", (long long)g->s, (long long)g->e);
    }
    va_end(ap);
    if (setHas(f->seen, key->data)) return;
    setAdd(f->seen, key->data);
    MigEdit *e = (MigEdit *)malloc(sizeof(MigEdit));
    e->start = start;
    e->end = end;
    e->text = strdup("");
    e->seq = f->seq++;
    e->kind = kind;
    e->segs = segs;
    vecPush(f->edits, e);
}

static int migIsIdent(char c) {
    return isalnum((unsigned char)c) || c == '_';
}

/* The closing quote of the string or character constant opening at i, or
 * i itself for a `'` that isn't one: a character constant ends on its
 * line (skipped #if text holds prose: `don't`) */
static s64 migQuoteEnd(const char *t, s64 i, s64 end) {
    char q = t[i];
    s64 j = i + 1;
    while (j < end && t[j] != q) {
        if (q == '\'' && t[j] == '\n') return i;
        if (t[j] == '\\') j++;
        j++;
    }
    return j < end ? j : (q == '\'' ? i : end);
}

/* The word map. `Main` only when the file's Main becomes main. */
static const char *migMapWord(MigFile *f, const char *w, s64 len, const char **kind) {
    static const char *map[][3] = {
        {"U0", "void", "type"}, {"I8", "i8", "type"}, {"U8", "u8", "type"},
        {"I16", "i16", "type"}, {"U16", "u16", "type"}, {"I32", "i32", "type"},
        {"U32", "u32", "type"}, {"I64", "i64", "type"}, {"U64", "u64", "type"},
        {"F32", "f32", "type"}, {"F64", "f64", "type"}, {"Bool", "bool", "type"},
        {"TRUE", "true", "literal"}, {"FALSE", "false", "literal"},
        {"NULL", "null", "literal"},
        {"class", "struct", "struct"}, {"public", "pub", "pub"},
    };
    for (u64 i = 0; i < sizeof(map) / sizeof(map[0]); ++i) {
        if ((s64)strlen(map[i][0]) == len && !memcmp(w, map[i][0], len)) {
            *kind = map[i][2];
            return map[i][1];
        }
    }
    if (f && f->has_main && len == 4 && !memcmp(w, "Main", 4)) {
        /* Code at file scope that calls Main: it goes into a new main,
         * and Main, now an ordinary function, gets a name of its own */
        *kind = "main";
        return f->top_calls_main ? "runMain" : "main";
    }
    return NULL;
}

/* Calls fn(f, word_start, word_len, ctx) for every word of [start, end)
 * that is code: not in a comment, a string, a character constant, an
 * `asm {}` block, or the macro name of #define/#undef/#ifdef/#ifndef or
 * `defined`. */
typedef void (*MigWordFn)(MigFile *f, s64 at, s64 len, void *ctx);

static void migScanWords(MigFile *f, char *text, s64 start, s64 end,
                         MigWordFn fn, void *ctx)
{
    s64 i = start;
    int line_start = 1, skip_next_word = 0;
    while (i < end) {
        char c = text[i];
        if (c == '\n') {
            line_start = 1;
            skip_next_word = 0;
            i++;
            continue;
        }
        if (c == ' ' || c == '\t' || c == '\r') { i++; continue; }
        if (c == '/' && i + 1 < end && text[i + 1] == '/') {
            while (i < end && text[i] != '\n') i++;
            continue;
        }
        if (c == '/' && i + 1 < end && text[i + 1] == '*') {
            i += 2;
            while (i + 1 < end && !(text[i] == '*' && text[i + 1] == '/')) i++;
            i += 2;
            continue;
        }
        if (c == '"' || c == '\'') {
            i = migQuoteEnd(text, i, end) + 1;
            line_start = 0;
            continue;
        }
        if (c == '#' && line_start) {
            s64 j = i + 1;
            while (j < end && (text[j] == ' ' || text[j] == '\t')) j++;
            s64 w = j;
            while (j < end && migIsIdent(text[j])) j++;
            if ((j - w == 6 && !memcmp(text + w, "define", 6)) ||
                (j - w == 5 && !memcmp(text + w, "undef", 5)) ||
                (j - w == 5 && !memcmp(text + w, "ifdef", 5)) ||
                (j - w == 6 && !memcmp(text + w, "ifndef", 6)))
            {
                skip_next_word = 1;
            }
            i = j;
            line_start = 0;
            continue;
        }
        if (migIsIdent(c)) {
            s64 w = i;
            while (i < end && migIsIdent(text[i])) i++;
            s64 len = i - w;
            if (isdigit((unsigned char)text[w])) { line_start = 0; continue; }
            if (len == 7 && !memcmp(text + w, "defined", 7)) {
                skip_next_word = 1;
                line_start = 0;
                continue;
            }
            if (len == 3 && !memcmp(text + w, "asm", 3)) {
                s64 j = i;
                while (j < end && isspace((unsigned char)text[j])) j++;
                if (j < end && text[j] == '{') {
                    while (j < end && text[j] != '}') j++;
                    i = j + 1;
                    line_start = 0;
                    continue;
                }
            }
            if (skip_next_word) {
                skip_next_word = 0;
            } else {
                fn(f, w, len, ctx);
            }
            line_start = 0;
            continue;
        }
        line_start = 0;
        i++;
    }
}

static s64 migSkipSpaceBack(char *t, s64 i);

static void migWordEdit(MigFile *f, s64 at, s64 len, void *ctx) {
    (void)ctx;
    const char *kind;
    const char *to = migMapWord(f, f->text + at, len, &kind);
    if (!to && len == 4 && !memcmp(f->text + at, "Main", 4)) {
        /* a demo `void Main()` in a skipped #if branch */
        for (u64 r = 0; r + 1 < f->skipped->size; r += 2) {
            if ((s64)(intptr_t)f->skipped->entries[r] <= at &&
                at < (s64)(intptr_t)f->skipped->entries[r + 1]) {
                to = "main";
                kind = "main";
                break;
            }
        }
    }
    if (to) migAdd(f, at, at + len, to, kind);
}

/* The text of [start, end) with the word map applied (a cast's type) */
typedef struct { AoStr *out; char *text; s64 pos; } MigMapCtx;

static void migMapEmit(MigFile *f, s64 at, s64 len, void *ctx) {
    MigMapCtx *m = ctx;
    const char *kind;
    const char *to = migMapWord(f, m->text + at, len, &kind);
    if (!to) return;
    aoStrCatLen(m->out, m->text + m->pos, at - m->pos);
    aoStrCat(m->out, to);
    m->pos = at + len;
}

static char *migMapText(MigFile *f, s64 start, s64 end) {
    MigMapCtx m = { aoStrNew(), f->text, start };
    migScanWords(f, f->text, start, end, migMapEmit, &m);
    aoStrCatLen(m.out, f->text + m.pos, end - m.pos);
    return m.out->data;
}

/* ------------------------------------------------------------------ */
/* The operand of a postfix cast: walking back from its `(` over one
 * postfix expression (a name, literal or parenthesised expression with
 * any `.x`, `->x`, `[i]` and `(args)` after it). -1 if it can't tell. */

static s64 migSkipSpaceBack(char *t, s64 i) {
    while (i >= 0 && isspace((unsigned char)t[i])) i--;
    return i;
}

static s64 migMatchBack(char *t, s64 i) {
    char close = t[i], open = close == ')' ? '(' : '[';
    int depth = 0;
    for (; i >= 0; --i) {
        if (t[i] == '"' || t[i] == '\'') {
            char q = t[i--];
            while (i >= 0 && !(t[i] == q && (i == 0 || t[i - 1] != '\\'))) i--;
            continue;
        }
        if (t[i] == close) depth++;
        else if (t[i] == open && --depth == 0) return i;
    }
    return -1;
}

static s64 migOperandStart(MigFile *f, s64 lp) {
    char *t = f->text;
    s64 i = migSkipSpaceBack(t, lp - 1);
    /* `x++(T)`: the cast applies to `x++` */
    if (i >= 1 && ((t[i] == '+' && t[i - 1] == '+') || (t[i] == '-' && t[i - 1] == '-'))) {
        i = migSkipSpaceBack(t, i - 2);
    }
    while (i >= 0) {
        char c = t[i];
        if (c == ')' || c == ']') {
            s64 open = migMatchBack(t, i);
            if (open < 0) return -1;
            s64 before = migSkipSpaceBack(t, open - 1);
            /* `return (x)(T)`: a keyword before `(` doesn't call it */
            int kw_before = 0;
            if (before >= 0 && migIsIdent(t[before])) {
                s64 w = before;
                while (w > 0 && migIsIdent(t[w - 1])) w--;
                static const char *kws[] = {"return", "case", "else", "do", "goto"};
                for (u64 k = 0; k < sizeof(kws) / sizeof(kws[0]); ++k) {
                    if ((s64)strlen(kws[k]) == before - w + 1 && !memcmp(t + w, kws[k], before - w + 1)) kw_before = 1;
                }
                /* `#define NAME (x)(T)`: NAME is the macro, not a call */
                s64 d = migSkipSpaceBack(t, w - 1);
                if (d >= 6 && !memcmp(t + d - 5, "define", 6)) kw_before = 1;
            }
            if (c == ']' || (before >= 0 && !kw_before && (migIsIdent(t[before]) ||
                                             t[before] == ')' || t[before] == ']')))
            {
                i = before;     /* a subscript or the arguments of a call */
                continue;
            }
            return open;        /* a parenthesised expression */
        }
        if (c == '"' || c == '\'') {
            s64 j = i - 1;
            while (j >= 0 && !(t[j] == c && (j == 0 || t[j - 1] != '\\'))) j--;
            return j;
        }
        if (migIsIdent(c)) {
            s64 j = i;
            while (j >= 0 && migIsIdent(t[j])) j--;
            s64 word = j + 1;
            s64 before = migSkipSpaceBack(t, j);
            if (before >= 0 && t[before] == '.' &&
                !(before > 0 && isdigit((unsigned char)t[before - 1]) &&
                  isdigit((unsigned char)t[word])))
            {
                i = migSkipSpaceBack(t, before - 1);
                continue;
            }
            if (before >= 0 && t[before] == '.') {   /* 3.14 */
                i = before - 1;
                continue;
            }
            if (before >= 1 && t[before] == '>' && t[before - 1] == '-') {
                i = migSkipSpaceBack(t, before - 2);
                continue;
            }
            return word;
        }
        return -1;
    }
    return -1;
}

static void migCastAt(MigFile *f, s64 lp, s64 rp);

/* An old type name (and `*`s) between lp's `(` and a `)`: its `)`, or -1 */
static s64 migOldCastEnd(MigFile *f, s64 lp, s64 end) {
    static const char *old[] = {"U0", "I8", "U8", "I16", "U16", "I32", "U32",
                                "I64", "U64", "F32", "F64", "Bool"};
    char *t = f->text;
    s64 i = lp + 1;
    while (i < end && (t[i] == ' ' || t[i] == '\t')) i++;
    s64 w = i;
    while (i < end && migIsIdent(t[i])) i++;
    int is_type = 0;
    for (u64 k = 0; k < sizeof(old) / sizeof(old[0]); ++k) {
        if ((s64)strlen(old[k]) == i - w && !memcmp(t + w, old[k], i - w)) is_type = 1;
    }
    if (!is_type) return -1;
    while (i < end && (t[i] == ' ' || t[i] == '\t' || t[i] == '*')) i++;
    return i < end && t[i] == ')' ? i : -1;
}

/* The lexer folds a #define's body, so the parser never sees a postfix
 * cast written there: find them in the text of #define lines */
static void migDefineCasts(MigFile *f) {
    char *t = f->text;
    s64 i = 0;
    while (i < f->len) {
        s64 ls = i, le = i;
        while (le < f->len && t[le] != '\n') le++;
        s64 j = ls;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        if (j < le && t[j] == '#') {
            j++;
            while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
            s64 body = -1;
            if (le - j >= 6 && !memcmp(t + j, "define", 6)) body = j + 6;
            else if (le - j >= 4 && !memcmp(t + j, "elif", 4)) body = j + 4;
            else if (le - j >= 2 && !memcmp(t + j, "if", 2) && !migIsIdent(t[j + 2])) body = j + 2;
            if (body >= 0) {
                j = body - 6;
                for (s64 k = body; k < le; ++k) {
                    if (t[k] == '"' || t[k] == '\'') {
                        k = migQuoteEnd(t, k, le);
                        continue;
                    }
                    if (t[k] == '/' && k + 1 < le && (t[k + 1] == '/' || t[k + 1] == '*')) break;
                    if (t[k] != '(') continue;
                    s64 rp = migOldCastEnd(f, k, le);
                    if (rp < 0) continue;
                    s64 b = migSkipSpaceBack(t, k - 1);
                    if (b < j + 6 || !(migIsIdent(t[b]) || t[b] == ')' || t[b] == ']' ||
                                       t[b] == '"' || t[b] == '\'')) continue;
                    migCastAt(f, k, rp);
                    k = rp;
                }
            }
        }
        i = le + 1;
    }
}

/* ------------------------------------------------------------------ */
/* Parser hooks */

void migrateNoteCast(Cctrl *cc, Lexeme *lparen, Lexeme *rparen) {
    MigFile *f = migFileOf(cc, lparen ? lparen->src : NULL);
    if (!f) return;
    s64 lp = migOff(f, lparen), rp = migOff(f, rparen);
    if (lp < 0 || rp < 0) {
        migNote(f, lp, "can't rewrite a postfix cast that spans a macro");
        return;
    }
    migCastAt(f, lp, rp);
}

/* `x(T)` with its `(` at lp and `)` at rp: `(t)x`, wrapped in parentheses
 * when a postfix operator follows */
static void migCastAt(MigFile *f, s64 lp, s64 rp) {
    s64 op = migOperandStart(f, lp);
    if (op < 0) {
        migNote(f, lp, "can't find the operand of a postfix cast");
        return;
    }
    char *type = migMapText(f, lp + 1, rp);
    s64 after = rp + 1;
    while (after < f->len && (f->text[after] == ' ' || f->text[after] == '\t')) after++;
    char *t = f->text;
    int wrap = after < f->len &&
        (t[after] == '[' || t[after] == '.' || t[after] == '(' ||
         (t[after] == '-' && after + 1 < f->len && (t[after + 1] == '>' || t[after + 1] == '-')) ||
         (t[after] == '+' && after + 1 < f->len && t[after + 1] == '+'));
    /* The parser hoists a cast out of a unary `&`: `&x(T)` is `(&x)(T)` */
    if (!wrap) {
        s64 amp = migSkipSpaceBack(t, op - 1);
        if (amp >= 0 && t[amp] == '&' && !(amp > 0 && t[amp - 1] == '&')) {
            s64 before = migSkipSpaceBack(t, amp - 1);
            if (before < 0 || !(migIsIdent(t[before]) || t[before] == ')' ||
                                t[before] == ']' || t[before] == '"' || t[before] == '\'')) {
                op = amp;
            }
        }
    }
    (void)type;
    migAddSegs(f, op, op, "cast", migLit(wrap ? "((" : "("), migRange(lp + 1, rp),
               migLit(")"), NULL);
    migAdd(f, lp, rp + 1, "", "cast");
    if (wrap) migAdd(f, rp + 1, rp + 1, ")", "cast");
}

/* `'ab';` prints its characters: printf("ab") (as the old statement did;
 * printf's %c would print one). The constant starts at s; returns the
 * offset just past it, or -1. */
static s64 migCharPrint(MigFile *f, s64 s) {
    char *t = f->text;
    s64 e = s + 1;
    AoStr *str = aoStrNew();
    aoStrCat(str, "printf(\"");
    while (e < f->len && t[e] != '\'') {
        if (t[e] == '\\' && e + 1 < f->len) {
            if (t[e + 1] == '\'') aoStrPutChar(str, '\'');
            else aoStrCatLen(str, t + e, 2);
            e += 2;
            continue;
        }
        if (t[e] == '"') aoStrCat(str, "\\\"");
        else if (t[e] == '%') aoStrCat(str, "%%");
        else aoStrPutChar(str, t[e]);
        e++;
    }
    if (e >= f->len) return -1;
    aoStrCat(str, "\")");
    migAdd(f, s, e + 1, str->data, "printf");
    return e + 1;
}

void migrateNotePrint(Cctrl *cc, Lexeme *first, Lexeme *semi, int is_char) {
    MigFile *f = migFileOf(cc, first ? first->src : NULL);
    if (!f) return;
    s64 s = migOff(f, first), e = migOff(f, semi);
    if (s < 0 || e < 0 || f->text[e] != ';') {
        migNote(f, s, "can't rewrite a print statement that spans a macro");
        return;
    }
    if (is_char) {
        if (migCharPrint(f, s) < 0) migNote(f, s, "can't rewrite a character constant statement");
        return;
    }
    migAdd(f, s, s, "printf(", "printf");
    migAdd(f, e, e, ")", "printf");
}

/* The matching `)` of the `(` at i, -1 if none */
static s64 migMatchFwd(char *t, s64 i, s64 len) {
    int depth = 0;
    for (; i < len; ++i) {
        if (t[i] == '"' || t[i] == '\'') { i = migQuoteEnd(t, i, len); continue; }
        if (t[i] == '(') depth++;
        else if (t[i] == ')' && --depth == 0) return i;
    }
    return -1;
}

/* `R (*name)(P)` -> `fn(P) R name`; `R (**pp)(P)` -> `fn(P) R *pp`;
 * `R (*ops[4])(P)` -> `fn(P) R ops[4]`; `R (*Name(a))(P)` (a function
 * returning one) -> `fn(P) R Name(a)`; the cast type `R (*)(P)` ->
 * `fn(P) R`. R is the type word before the `(` with its `*`s. */
void migrateNoteFnPtr(Cctrl *cc, Lexeme *open, Lexeme *close) {
    MigFile *f = migFileOf(cc, open ? open->src : NULL);
    if (!f) return;
    s64 lp = migOff(f, open), end = migOff(f, close);
    char *t = f->text;
    if (lp < 0 || end < 0 || t[lp] != '(' || t[end] != ')') {
        migNote(f, lp, "can't rewrite a function pointer declarator that spans a macro");
        return;
    }
    s64 mp = migMatchFwd(t, lp, f->len);
    s64 ps = mp < 0 ? -1 : mp + 1;
    while (ps >= 0 && ps < end && isspace((unsigned char)t[ps])) ps++;
    s64 re = migSkipSpaceBack(t, lp - 1);       /* R's last char */
    s64 comma = -1;
    if (re >= 0 && t[re] == ',') {
        /* `T (*a)(P) = x, (*b)(Q)`: b becomes a declaration of its own,
         * of the list's type, which starts after the last `;`, `{` or
         * `}` (or `(` for a for-init) before it */
        comma = re;
        s64 k = re - 1;
        int depth = 0;
        for (; k >= 0; --k) {
            if (t[k] == ')' || t[k] == ']') depth++;
            else if ((t[k] == '(' || t[k] == '[') && depth > 0) depth--;
            else if (depth == 0 && (t[k] == ';' || t[k] == '{' || t[k] == '}' || t[k] == '(')) break;
        }
        k++;
        for (;;) {
            for (;;) {      /* blanks and comments */
                while (k < comma && isspace((unsigned char)t[k])) k++;
                if (k + 1 < comma && t[k] == '/' && t[k + 1] == '/') {
                    while (k < comma && t[k] != '\n') k++;
                } else if (k + 1 < comma && t[k] == '/' && t[k + 1] == '*') {
                    k += 2;
                    while (k + 1 < comma && !(t[k] == '*' && t[k + 1] == '/')) k++;
                    k += 2;
                } else {
                    break;
                }
            }
            s64 w = k;
            while (k < comma && migIsIdent(t[k])) k++;
            if ((k - w == 6 && (!memcmp(t + w, "static", 6) || !memcmp(t + w, "public", 6))) ||
                (k - w == 3 && !memcmp(t + w, "pub", 3))) continue;
            re = k - 1;
            while (k < comma && (t[k] == ' ' || t[k] == '*')) {
                if (t[k] == '*') re = k;
                k++;
            }
            break;
        }
    }
    s64 rs = re;
    while (rs >= 0 && (t[rs] == '*' || t[rs] == ' ' || t[rs] == '\t')) rs--;
    while (rs >= 0 && (migIsIdent(t[rs]) || t[rs] == '.')) rs--;
    rs++;
    s64 star = lp + 1;
    while (star < mp && isspace((unsigned char)t[star])) star++;
    if (mp < 0 || ps < 0 || t[ps] != '(' || rs > re || t[star] != '*') {
        migNote(f, lp, "can't rewrite a function pointer declarator");
        return;
    }
    s64 ns = star + 1;                          /* after the first `*` */
    while (ns < mp && isspace((unsigned char)t[ns])) ns++;
    s64 ne = migSkipSpaceBack(t, mp - 1) + 1;
    int r_ptr = t[re] == '*';
    if (comma >= 0) {
        migAddSegs(f, comma, end + 1, "fn pointer", migLit("; fn("), migRange(ps + 1, end),
                   migLit(") "), migRange(rs, re + 1), migLit(r_ptr ? "" : " "),
                   migRange(ns, ne), NULL);
        return;
    }
    if (ns >= ne) {
        migAddSegs(f, rs, end + 1, "fn pointer", migLit("fn("), migRange(ps + 1, end),
                   migLit(") "), migRange(rs, re + 1), NULL);
    } else if (t[ns] == '*') {
        /* `R (**pp)(P)`: a pointer to one is `(fn(P) R) *pp` */
        migAddSegs(f, rs, end + 1, "fn pointer", migLit("(fn("), migRange(ps + 1, end),
                   migLit(") "), migRange(rs, re + 1), migLit(") "),
                   migRange(ns, ne), NULL);
    } else {
        migAddSegs(f, rs, end + 1, "fn pointer", migLit("fn("), migRange(ps + 1, end),
                   migLit(") "), migRange(rs, re + 1), migLit(r_ptr ? "" : " "),
                   migRange(ns, ne), NULL);
    }
}

/* `(...)` -> `(...args)`; argc -> args.len, argv -> args */
void migrateNoteOldVarArgs(Cctrl *cc, Lexeme *ellipsis) {
    MigFile *f = migFileOf(cc, ellipsis ? ellipsis->src : NULL);
    if (!f) return;
    s64 s = migOff(f, ellipsis);
    if (s < 0 || strncmp(f->text + s, "...", 3)) {
        migNote(f, s, "can't rewrite an old `...` that spans a macro");
        return;
    }
    migAdd(f, s + 3, s + 3, "args", "varargs");
}

void migrateNoteOldVarArgUse(Cctrl *cc, Lexeme *name) {
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) {
        migNote(f, s, "can't rewrite an argc/argv that comes from a macro");
        return;
    }
    if (name->len == 4 && !memcmp(f->text + s, "argc", 4)) {
        migAdd(f, s, s + 4, "args.len", "varargs");
    } else if (name->len == 4 && !memcmp(f->text + s, "argv", 4)) {
        migAdd(f, s, s + 4, "args", "varargs");
    }
}

void migrateNoteCatch(Cctrl *cc, Lexeme *kw, Lexeme *last) {
    MigFile *f = migFileOf(cc, kw ? kw->src : NULL);
    if (!f) return;
    s64 s = migOff(f, kw), e = migOff(f, last);
    if (s < 0 || e < 0) return;
    MigCatch *c = (MigCatch *)calloc(1, sizeof(MigCatch));
    c->kw_end = s + 5;
    c->end = e + last->len;
    vecPush(f->catches, c);
}

static void migWholeLines(MigFile *f, s64 *s, s64 *e);

/* The indentation of the line holding off */
static char *migLineIndent(MigFile *f, s64 off) {
    s64 ls = off;
    while (ls > 0 && f->text[ls - 1] != '\n') ls--;
    s64 e = ls;
    while (e < f->len && (f->text[e] == ' ' || f->text[e] == '\t')) e++;
    return mprintf("%.*s", (int)(e - ls), f->text + ls);
}

/* Y8: an arm's last `break;` goes (not when it is the arm's only
 * statement: `case 3: break;` does nothing, an empty arm would join the
 * next one); an arm that falls into the next label gets `fallthrough;` */
void migrateSwitchLabel(Cctrl *cc, Lexeme *label, int kind, Lexeme *prev_end, int n) {
    MigFile *f = migFileOf(cc, label ? label->src : NULL);
    if (!f || !prev_end) return;
    s64 lab = migOff(f, label), pe = migOff(f, prev_end);
    if (lab < 0 || pe < 0) {
        if (kind == 1 || kind == 2) migNote(f, lab, "can't rewrite a switch arm that comes from a macro");
        return;
    }
    char *t = f->text;
    int is_end = t[lab] == '}';
    if (kind == 2 && n > 1) {
        s64 semi = pe;
        if (t[semi] == '}') semi = migSkipSpaceBack(t, semi - 1);
        s64 b = migSkipSpaceBack(t, semi - 1);
        if (semi < 0 || t[semi] != ';' || b < 4 || memcmp(t + b - 4, "break", 5) ||
            (b > 4 && migIsIdent(t[b - 5]))) {
            migNote(f, pe, "check: an arm's last break was not where expected");
            return;
        }
        s64 s = b - 4, e = semi + 1;
        s64 ws = s, we = e;
        migWholeLines(f, &ws, &we);
        if (ws != s || we != e) {
            migAdd(f, ws, we, "", "switch");
        } else {
            while (s > 0 && (t[s - 1] == ' ' || t[s - 1] == '\t')) s--;
            migAdd(f, s, e, "", "switch");
        }
    } else if (kind == 1 && !is_end) {
        s64 at = pe + prev_end->len;
        /* on the label's line: ` fallthrough;`, else on a line of its own */
        int same_line = 1;
        for (s64 k = at; k < lab; ++k) if (t[k] == '\n') same_line = 0;
        if (same_line) {
            migAdd(f, at, at, " fallthrough;", "switch");
        } else {
            migAdd(f, at, at, mprintf("\n%sfallthrough;", migLineIndent(f, pe)), "switch");
        }
    }
}

/* Y13: `if (x) y;` -> `if (x) { y; }`; a body on lines of its own gets
 * `{` on the header's line and `}` on a line of its own at the header's
 * indentation; an empty body `;` becomes `{}` */
void migrateNoteBody(Cctrl *cc, Lexeme *hdr, Lexeme *first, Lexeme *last) {
    MigFile *f = migFileOf(cc, first ? first->src : NULL);
    if (!f) return;
    s64 h = migOff(f, hdr), b = migOff(f, first), e = migOff(f, last);
    if (h < 0 || b < 0 || e < 0 || e < b) {
        migNote(f, b, "can't add braces to a body that comes from a macro");
        return;
    }
    char *t = f->text;
    s64 he = h + hdr->len, be = e + last->len;
    char *key = mprintf("body:%lld:%lld", (long long)h, (long long)b);
    if (setHas(f->seen, key)) return;
    setAdd(f->seen, key);
    if (b == e && t[b] == ';') {
        migAdd(f, b, b + 1, b > 0 && (t[b - 1] == ' ' || t[b - 1] == '\t') ? "{}" : " {}", "braces");
        return;
    }
    int same_line = 1;
    for (s64 k = he; k < be; ++k) if (t[k] == '\n') same_line = 0;
    MigEdit *c = (MigEdit *)calloc(1, sizeof(MigEdit));
    c->start = c->end = be;
    c->kind = "braces";
    if (same_line) {
        migAdd(f, b, b, "{ ", "braces");
        c->text = strdup(" }");
    } else {
        migAdd(f, he, he, " {", "braces");
        c->text = mprintf("\n%s}", migLineIndent(f, h));
    }
    vecPush(f->closers, c);
}

/* ------------------------------------------------------------------ */
/* The naming rule (--names): camelCase functions, variables and fields,
 * PascalCase types, UPPER_CASE constants. src/std follows renames.tsv
 * (and renames-locals.tsv); our own code the rule. */

static Map *mig_map;

/* A row of the stdlib map (--map), see migLoadMap */
typedef struct MigName {
    const char *kind;      /* function type macro c */
    const char *module;    /* "-": dropped, "prelude": no prefix */
    const char *name;
    const char *note;
} MigName;
static char *migReadFile(char *path, s64 *_len);
static int migIsStdFile(Cctrl *cc, u32 file_id);
static Map *mig_renames = NULL;      /* old -> new, every kind */
static Set *mig_std_names = NULL;    /* every name the stdlib declares */
static Map *mig_scoped_renames = NULL; /* "file:name" -> new, locals */
static Map *mig_field_renames = NULL;

static int migLoadRenames(const char *path) {
    s64 len;
    char *text = migReadFile((char *)path, &len);
    if (!text) return 0;
    if (!mig_renames) {
        mig_std_names = setNew(4096, &set_cstring_type);
        mig_renames = mapNew(4096, &map_cstring_opaque_type);
        mig_scoped_renames = mapNew(1024, &map_cstring_opaque_type);
        mig_field_renames = mapNew(1024, &map_cstring_opaque_type);
    }
    for (char *line = text; line && *line; ) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        char *kind = line, *old = strchr(kind, '\t');
        char *new = old ? strchr(old + 1, '\t') : NULL;
        if (old && new) {
            *old++ = 0;
            *new++ = 0;
            char *end = strchr(new, '\t');
            if (end) *end = 0;
            if (strcmp(kind, "kind")) setAdd(mig_std_names, old);
            /* a local's row names its files: `local fn cb ...; fzf.ktm` */
            char *files = end ? strrchr(end + 1, ';') : NULL;
            if (!strcmp(kind, "local") && files) {
                for (char *w = strtok(strdup(files + 1), ", "); w; w = strtok(NULL, ", ")) {
                    mapAdd(mig_scoped_renames, mprintf("%s:%s", w, old), new);
                }
            }
            if (strcmp(kind, "kind") && strcmp(old, new)) {
                mapAdd(!strcmp(kind, "field") ? mig_field_renames : mig_renames, old, new);
            }
        }
        line = nl ? nl + 1 : NULL;
    }
    return 1;
}

/* `CountWord` -> countWord, `RBTreeNew` -> rbTreeNew, `g_correct` ->
 * gCorrect, `_tmp` -> tmp; NULL when it stays (camelCase already, all
 * capitals, a single word) */
static char *migCamel(const char *w, s64 len) {
    int lower = 0, upper = 0, under = 0;
    for (s64 i = 0; i < len; ++i) {
        if (islower((unsigned char)w[i])) lower = 1;
        else if (isupper((unsigned char)w[i])) upper = 1;
        else if (w[i] == '_') under = 1;
    }
    if (!lower) return NULL;                         /* MAX_LEN, X, I64 */
    AoStr *out = aoStrNew();
    s64 i = 0;
    while (i < len && w[i] == '_') i++;
    int first = 1, cap_next = 0;
    while (i < len) {
        char c = w[i];
        if (c == '_') { cap_next = !first; i++; continue; }
        if (first) {
            /* the first word in lower case: a run of capitals is an
             * acronym (`RBTree` -> rbTree, `I64ToStr` -> i64ToStr) */
            s64 j = i;
            while (j < len && (isupper((unsigned char)w[j]) || isdigit((unsigned char)w[j]))) j++;
            if (j - i > 1 && j < len && islower((unsigned char)w[j])) j--;   /* RBTree: R B | Tree */
            if (j == i) j = i + 1;
            for (s64 k = i; k < j; ++k) aoStrPutChar(out, tolower((unsigned char)w[k]));
            i = j;
            first = 0;
            continue;
        }
        aoStrPutChar(out, cap_next ? toupper((unsigned char)c) : c);
        cap_next = 0;
        i++;
    }
    (void)upper; (void)under;
    if ((s64)out->len == len && !memcmp(out->data, w, len)) return NULL;
    if (out->len == 0) return NULL;
    return out->data;
}

static int mig_names = 0;
static void migCollectWord(MigFile *f, s64 at, s64 len, void *ctx) {
    (void)ctx;
    setAdd(f->code_words, mprintf("%.*s", (int)len, f->text + at));
}
static int migUsesWord(MigFile *f, s64 s, s64 e, const char *name);

static const char *migFileModule(MigFile *f);

/* The naming rule for the name at s (see migCamel) */
static void migRename(Cctrl *cc, MigFile *f, s64 s, s64 len) {
    if (!mig_names) return;
    char *w = mprintf("%.*s", (int)len, f->text + s);
    if (!strcmp(w, "Main") || !strcmp(w, "argc") || !strcmp(w, "argv")) return;
    char *to = NULL;
    if (f->real && strstr(f->real, "/src/std/")) {
        /* a name the map moves into a module: the map's name. In its own
         * module that is the definition and its uses, bare (`len`); in
         * the others the map's edit (`str.len`) is the one */
        MigName *m = mig_map ? mapGet(mig_map, w) : NULL;
        if (m && strcmp(m->module, "-") && strcmp(m->kind, "c") && strcmp(m->kind, "macro") &&
            strcmp(m->module, "prelude")) {
            if (strcmp(migFileModule(f), m->module)) return;
            /* the map's name, even next to a local of that name (`len`)
             * or a C function the file calls (`free`, now c.free): a
             * local that hides it where it is called shows when the
             * module is compiled */
            if (strcmp(m->name, w)) migAdd(f, s, s + len, (char *)m->name, "name");
            return;
        } else {
            to = mig_renames ? mapGet(mig_renames, w) : NULL;
        }
    } else {
        /* not a type, not the stdlib's (1i), not a C function */
        if (cctrlGetKeyWord(cc, w, len)) return;
        if (mig_std_names && setHas(mig_std_names, w)) return;
        if (mig_map && mapGet(mig_map, w)) return;
        Ast *decl = findFunctionDecl(cc, w, len);
        if (!decl) decl = mapGet(cc->global_env, w);
        if (decl && (decl->kind == AST_EXTERN_FUNC || migIsStdFile(cc, decl->file_id))) return;
        to = migCamel(w, len);
    }
    if (!to) return;
    /* a name the file's code already has (`_a` next to `a`): left, for
     * a person */
    if (!f->code_words) {
        f->code_words = setNew(256, &set_cstring_type);
        migScanWords(f, f->text, 0, f->len, migCollectWord, NULL);
    }
    static const char *kw[] = {"if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "return", "goto", "struct", "union", "enum",
        "class", "const", "static", "extern", "inline", "auto", "pub", "public",
        "private", "try", "catch", "throw", "defer", "fn", "self", "null", "true",
        "false", "import", "module", "asm", "sizeof", "typeof", "alignof",
        "fallthrough", "void", "bool", "i8", "u8", "i16", "u16", "i32", "u32",
        "i64", "u64", "f32", "f64", "main"};
    int is_kw = 0;
    for (u64 k = 0; k < sizeof(kw) / sizeof(kw[0]); ++k) if (!strcmp(kw[k], to)) is_kw = 1;
    if (is_kw || setHas(f->code_words, to) || (mig_std_names && setHas(mig_std_names, to)) ||
        mapGet(cc->global_env, to)) {
        char *key = mprintf("clash:%s", w);
        if (!setHas(f->seen, key)) {
            setAdd(f->seen, key);
            migNote(f, s, "check: `%s` would be `%s`, which the file already uses: renamed by hand", w, to);
        }
        return;
    }
    migAdd(f, s, s + len, to, "name");
}

void migrateNoteStdField(Cctrl *cc, Lexeme *name) {
    /* with the stdlib converted in the same run (--map) */
    if (!mig_names || !mig_field_renames || !mig_map) return;
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    char *to = mapGet(mig_field_renames, mprintf("%.*s", (int)name->len, f->text + s));
    if (to) migAdd(f, s, s + name->len, to, "name");
}

/* A name that is a keyword or a type name of the new syntax: name_ (in
 * src/std listed instead, those get names picked by hand) */
void migrateNoteName(Cctrl *cc, Lexeme *name) {
    static const char *reserved[] = {
        "fn", "self", "defer", "enum", "const", "module", "import", "private",
        "pub", "fallthrough", "struct", "null", "true", "false",
        "void", "i8", "u8", "i16", "u16", "i32", "u32", "i64", "u64",
        "f32", "f64", "bool"};
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    int hit = 0;
    for (u64 k = 0; k < sizeof(reserved) / sizeof(reserved[0]); ++k) {
        if ((s64)strlen(reserved[k]) == name->len && !memcmp(f->text + s, reserved[k], name->len)) hit = 1;
    }
    if (!hit) {
        migRename(cc, f, s, name->len);
        return;
    }
    if (f->real && strstr(f->real, "/src/std/")) {
        /* the name picked for it (renames-locals.tsv per file, else the
         * field table) */
        char *base = strrchr(f->path, '/');
        base = base ? base + 1 : f->path;
        char *word = mprintf("%.*s", (int)name->len, f->text + s);
        char *to = mig_scoped_renames ? mapGet(mig_scoped_renames, mprintf("%s:%s", base, word)) : NULL;
        if (!to && mig_field_renames) to = mapGet(mig_field_renames, word);
        if (to) {
            migAdd(f, s, s + name->len, to, "keyword name");
            return;
        }
        char *key = mprintf("stdname:%lld", (long long)s);
        if (setHas(f->seen, key)) return;
        setAdd(f->seen, key);
        migNote(f, s, "check: `%.*s` is a keyword or type name now: pick a name (src/std)",
                (int)name->len, f->text + s);
        return;
    }
    migAdd(f, s, s + name->len, mprintf("%.*s_", (int)name->len, f->text + s), "keyword name");
}

/* ------------------------------------------------------------------ */
/* The stdlib map (--map stdlib-modules.tsv): old name -> module.new */



static Map *mig_map = NULL;

static int migLoadMap(const char *path) {
    s64 len;
    char *text = migReadFile((char *)path, &len);
    if (!text) return 0;
    mig_map = mapNew(512, &map_cstring_opaque_type);
    char *line = text;
    while (line && *line) {
        char *nl = strchr(line, '\n');
        if (nl) *nl = 0;
        char *col[5] = {0};
        int n = 0;
        for (char *c = line; c && n < 5; ++n) {
            col[n] = c;
            c = strchr(c, '\t');
            if (c) *c++ = 0;
        }
        if (n >= 4 && strcmp(col[0], "kind")) {
            if (!strcmp(col[0], "cfunctions") || !strcmp(col[0], "ctypes") ||
                !strcmp(col[0], "cconstants")) {
                /* one row lists every C name of a group: each is c.<name> */
                char *names = strdup(col[3]);
                for (char *w = strtok(names, " "); w; w = strtok(NULL, " ")) {
                    MigName *m = calloc(1, sizeof(MigName));
                    m->kind = "c";
                    m->module = col[2];
                    m->name = w;
                    m->note = col[4];
                    mapAdd(mig_map, w, m);
                }
            } else {
                MigName *m = calloc(1, sizeof(MigName));
                m->kind = col[0];
                m->module = col[2];
                m->name = col[3];
                m->note = col[4] ? col[4] : "";
                mapAdd(mig_map, col[1], m);
            }
        }
        line = nl ? nl + 1 : NULL;
    }
    return 1;
}

/* Is a declaration (by its file id) the stdlib's? */
static int migIsStdFile(Cctrl *cc, u32 file_id) {
    AoStr *where = cctrlLookUpFile(cc, file_id);
    return where && (strstr(where->data, "/src/std/") || strstr(where->data, "/include/"));
}

/* The module a file says it is in (`module str;`), "" for none */
static const char *migFileModule(MigFile *f) {
    if (f->module) return f->module;
    f->module = "";
    char *t = f->text;
    for (s64 i = 0; i < f->len; ) {
        while (i < f->len && isspace((unsigned char)t[i])) i++;
        if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '/') { while (i < f->len && t[i] != '\n') i++; continue; }
        if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '*') {
            i += 2;
            while (i + 1 < f->len && !(t[i] == '*' && t[i + 1] == '/')) i++;
            i += 2;
            continue;
        }
        if (!strncmp(t + i, "module", 6) && isspace((unsigned char)t[i + 6])) {
            s64 j = i + 6;
            while (j < f->len && isspace((unsigned char)t[j])) j++;
            s64 k = j;
            while (k < f->len && (migIsIdent(t[k]) || t[k] == '.')) k++;
            f->module = mprintf("%.*s", (int)(k - j), t + j);
        }
        break;
    }
    return f->module;
}

/* `module.name` as code: `c.write`, `map.strNew`, `EXIT_OK` (prelude);
 * in the name's own module just the name */
static char *migQualified(MigFile *f, MigName *m) {
    if (!strcmp(m->module, "prelude")) return (char *)m->name;
    if (!strcmp(migFileModule(f), m->module)) return (char *)m->name;
    setAdd(f->imports, (char *)m->module);
    return mprintf("%s.%s", m->module, m->name);
}

/* Container types whose functions become methods, and the kind prefix
 * their module names carry (`intSet`, `strGet`, `ptrPush`) */
static const char *migContainerPrefix(const char *type) {
    static const char *t[][2] = {
        {"IntMap", "int"}, {"StrMap", "str"}, {"RBTree", "rb"}, {"IntSet", "int"},
        {"IntVec", "int"}, {"FloatVec", "float"}, {"PtrVec", "ptr"}, {"BitVec", "bit"},
        {"List", ""}};
    for (u64 k = 0; k < sizeof(t) / sizeof(t[0]); ++k) {
        if (!strcmp(type, t[k][0])) return t[k][1];
    }
    return NULL;
}

/* The method a container function becomes (`intSet` -> `set`), NULL for
 * a function that stays a module function (constructors, ...) */
static char *migMethodName(Ast *decl, MigName *m) {
    if (!decl->params || decl->params->size == 0) return NULL;
    Ast *p0 = decl->params->entries[0];
    if (!p0 || !p0->type || p0->type->kind != AST_TYPE_POINTER || !p0->type->ptr ||
        !p0->type->ptr->clsname) return NULL;
    /* `List`, or `list.List` seen from inside its module */
    const char *cls = p0->type->ptr->clsname->data, *dot = strrchr(cls, '.');
    const char *prefix = migContainerPrefix(dot ? dot + 1 : cls);
    if (!prefix) return NULL;
    size_t pl = strlen(prefix);
    if (strncmp(m->name, prefix, pl)) return NULL;
    char *meth = strdup(m->name + pl);
    if (!*meth) return NULL;
    meth[0] = tolower((unsigned char)meth[0]);
    if (!strcmp(meth, "new")) return NULL;
    return meth;
}

/* In a container function's body, its first parameter is the method's
 * `self` */
static void migSelfWord(MigFile *f, s64 at, s64 len, void *ctx) {
    const char *pname = ctx;
    char *t = f->text;
    if ((s64)strlen(pname) != len || memcmp(t + at, pname, len)) return;
    if (at > 0 && t[at - 1] == '.') return;                       /* a field */
    if (at > 1 && t[at - 1] == '>' && t[at - 2] == '-') return;
    migAdd(f, at, at + len, "self", "method");
}

/* A container function of src/std defined in its module becomes the
 * method its calls become: `pub void append(List *ll, void *v) {` is
 * `pub void List.append(void *v) {`, `ll` being `self` in the body */
void migrateNoteFuncDef(Cctrl *cc, Lexeme *body, char *fname, int len, Vec *params) {
    if (!mig_map || !params || params->size == 0) return;
    MigFile *f = migFileOf(cc, body->src);
    if (!f || !f->real || !strstr(f->real, "/src/std/")) return;
    /* `body` is the body's first token: its `{` is the last one before */
    s64 b = migOff(f, body);
    if (b < 0) return;
    while (b > 0 && f->text[b] != '{') b--;
    if (f->text[b] != '{') return;
    /* a module's function name is its symbol: `_K4list10ListAppend` */
    if (len > 2 && fname[0] == '_' && fname[1] == 'K') {
        fname = astModuleDemangle(fname, len);
        len = strlen(fname);
    }
    const char *nm = fname;
    for (int i = len - 1; i >= 0; --i) if (fname[i] == '.') { nm = fname + i + 1; break; }
    char *key = mprintf("%.*s", (int)(fname + len - nm), nm);
    MigName *m = mapGet(mig_map, key);
    if (!m || strcmp(m->kind, "function") || strcmp(m->module, migFileModule(f))) return;
    Ast decl = {0};
    decl.params = params;
    Ast *p0 = params->entries[0];
    if (p0 && p0->kind == AST_DEFAULT_PARAM) return;
    char *meth = migMethodName(&decl, m);
    if (!meth || !p0 || p0->kind != AST_LVAR || !p0->lname) return;
    const char *cls = p0->type->ptr->clsname->data, *dot = strrchr(cls, '.');
    cls = dot ? dot + 1 : cls;
    MigName *tm = mapGet(mig_map, cls);
    const char *tname = tm && !strcmp(tm->kind, "type") ? tm->name : cls;
    char *t = f->text;
    /* the parameter list: the `)` before the body and its `(` */
    s64 close = b - 1;
    while (close > 0 && isspace((unsigned char)t[close])) close--;
    if (t[close] != ')') return;
    s64 open = close, depth = 0;
    for (; open >= 0; --open) {
        if (t[open] == ')') depth++;
        else if (t[open] == '(' && --depth == 0) break;
    }
    if (open < 0) return;
    s64 ne = open;
    while (ne > 0 && isspace((unsigned char)t[ne - 1])) ne--;
    s64 ns = ne;
    while (ns > 0 && migIsIdent(t[ns - 1])) ns--;
    /* the first parameter, up to its `,` (or the `)`) */
    s64 c = open + 1;
    for (depth = 0; c < close; ++c) {
        if (t[c] == '(' || t[c] == '[') depth++;
        else if (t[c] == ')' || t[c] == ']') depth--;
        else if (t[c] == ',' && depth == 0) break;
    }
    s64 after = c;
    if (c < close) {
        after = c + 1;
        while (after < close && isspace((unsigned char)t[after])) after++;
    }
    migAdd(f, ns, after, mprintf("%s.%s(", tname, meth), "method");
    /* the body: up to its matching `}` */
    s64 e = b;
    for (depth = 0; e < f->len; ++e) {
        if (t[e] == '"' || t[e] == '\'') { e = migQuoteEnd(t, e, f->len); continue; }
        if (t[e] == '{') depth++;
        else if (t[e] == '}' && --depth == 0) break;
    }
    migScanWords(f, t, b, e, migSelfWord, (void *)p0->lname->data);
}

/* `Old(recv, args)` -> `recv.method(args)`, the name at s */
static int migMethodCall(MigFile *f, s64 s, s64 nlen, const char *meth) {
    char *t = f->text;
    s64 p = s + nlen;
    while (p < f->len && isspace((unsigned char)t[p])) p++;
    if (p >= f->len || t[p] != '(') return 0;
    s64 a = p + 1, k = a;
    int depth = 0;
    for (; k < f->len; ++k) {
        if (t[k] == '"' || t[k] == '\'') { k = migQuoteEnd(t, k, f->len); continue; }
        if (t[k] == '(' || t[k] == '[' || t[k] == '{') depth++;
        else if (t[k] == ')' || t[k] == ']' || t[k] == '}') { if (depth-- == 0) break; }
        else if (t[k] == ',' && depth == 0) break;
    }
    if (k >= f->len) return 0;
    s64 rs = a, re = k;
    while (rs < re && isspace((unsigned char)t[rs])) rs++;
    while (re > rs && isspace((unsigned char)t[re - 1])) re--;
    if (rs >= re) return 0;
    int simple = 1;
    s64 q = rs;
    if (t[q] == '&') q++;
    for (s64 i = q; i < re; ++i) {
        char c = t[i];
        if (migIsIdent(c) || c == '.' || c == ' ') continue;
        if (c == '-' && i + 1 < re && t[i + 1] == '>') { i++; continue; }
        if (c == '[' || c == '(') {
            int d = 0;
            for (; i < re; ++i) {
                if (t[i] == '[' || t[i] == '(') d++;
                else if ((t[i] == ']' || t[i] == ')') && --d == 0) break;
            }
            continue;
        }
        simple = 0;
    }
    s64 after = k;
    if (t[k] == ',') {
        after = k + 1;
        while (after < f->len && (t[after] == ' ' || t[after] == '\t')) after++;
    }
    if (simple) {
        migAddSegs(f, s, after, "method", migRange(q, re), migLit(mprintf(".%s(", meth)), NULL);
    } else {
        migAddSegs(f, s, after, "method", migLit("("), migRange(rs, re),
                   migLit(mprintf(").%s(", meth)), NULL);
    }
    return 1;
}

/* Swap arguments a and b (0-based) of the call whose `(` follows p */
static void migSwapArgs(MigFile *f, s64 p, int a, int b) {
    char *t = f->text;
    while (p < f->len && isspace((unsigned char)t[p])) p++;
    if (p >= f->len || t[p] != '(') return;
    s64 starts[16], ends[16];
    int n = 0, depth = 0;
    starts[0] = p + 1;
    for (s64 k = p + 1; k < f->len && n < 15; ++k) {
        if (t[k] == '"' || t[k] == '\'') { k = migQuoteEnd(t, k, f->len); continue; }
        if (t[k] == '(' || t[k] == '[' || t[k] == '{') depth++;
        else if ((t[k] == ')' || t[k] == ']' || t[k] == '}') && depth-- == 0) { ends[n++] = k; break; }
        else if (t[k] == ',' && depth == 0) { ends[n++] = k; starts[n] = k + 1; }
    }
    if (a >= n || b >= n) return;
    for (int i = 0; i < n; ++i) {
        while (starts[i] < ends[i] && isspace((unsigned char)t[starts[i]])) starts[i]++;
        while (ends[i] > starts[i] && isspace((unsigned char)t[ends[i] - 1])) ends[i]--;
    }
    migAddSegs(f, starts[a], ends[a], "swap", migRange(starts[b], ends[b]), NULL);
    migAddSegs(f, starts[b], ends[b], "swap", migRange(starts[a], ends[a]), NULL);
}

void migrateNoteRef(Cctrl *cc, Lexeme *name) {
    if (!mig_map) return;
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    char *key = mprintf("%.*s", (int)name->len, f->text + s);
    MigName *m = mapGet(mig_map, key);
    if (!m || !strcmp(m->kind, "macro") || !strcmp(m->kind, "type")) return;
    /* only the stdlib's own name, not a local or a function of the
     * program that happens to share it */
    Ast *decl = findFunctionDecl(cc, key, strlen(key));
    if (!decl) decl = mapGet(cc->global_env, key);
    if (!decl || (cc->localenv && mapGet(cc->localenv, key))) return;
    if (!migIsStdFile(cc, decl->file_id) && !(decl->kind == AST_EXTERN_FUNC)) return;
    if (!strcmp(m->module, "-") || !strcmp(m->module, "rt")) {
        /* printf: C's, built in; and the stdlib's own private helpers
         * and runtime stay what they are inside src/std (S2) */
        if (!strcmp(m->kind, "c")) return;
        if (f->real && strstr(f->real, "/src/std/")) {
            /* the stdlib's own modules use the runtime: rt.x */
            if (!strcmp(m->module, "rt")) migAdd(f, s, s + name->len, migQualified(f, m), "stdlib");
            return;
        }
        migNote(f, s, "can't convert `%s`: %s in the stdlib map (%s)", key,
                !strcmp(m->module, "rt") ? "runtime-only" : "dropped", m->note);
        return;
    }
    char *meth = migMethodName(decl, m);
    if (meth && migMethodCall(f, s, name->len, meth)) return;
    migAdd(f, s, s + name->len, migQualified(f, m), "stdlib");
    /* decision 11: QSortGeneric(arr, high, low, lt) is vec.qsort(arr, low, high, lt) */
    if (!strcmp(key, "QSortGeneric")) migSwapArgs(f, s + name->len, 1, 2);
}

/* The map's name for the type at `name`, in a module of src/std that
 * defines (or uses) its own type: `struct CDate` in date is `Date` */
static void migOwnType(MigFile *f, s64 s, s64 len) {
    if (!mig_map || !f->real || !strstr(f->real, "/src/std/")) return;
    char *key = mprintf("%.*s", (int)len, f->text + s);
    MigName *m = mapGet(mig_map, key);
    if (!m || strcmp(m->kind, "type") || strcmp(m->module, migFileModule(f))) return;
    if (strcmp(m->name, key)) migAdd(f, s, s + len, (char *)m->name, "name");
}

void migrateNoteTypeDef(Cctrl *cc, Lexeme *name) {
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    migOwnType(f, s, name->len);
}

void migrateNoteType(Cctrl *cc, Lexeme *name, AstType *type) {
    if (!mig_map || !type) return;
    if (!type->in_std) {
        /* a module's own type, used in its own files */
        MigFile *f = migFileOf(cc, name ? name->src : NULL);
        s64 s = f ? migOff(f, name) : -1;
        if (s >= 0) migOwnType(f, s, name->len);
        return;
    }
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    char *key = mprintf("%.*s", (int)name->len, f->text + s);
    MigName *m = mapGet(mig_map, key);
    /* a stdlib type, or a C one (the map's ctypes rows: c.pthread_t) */
    if (!m || (strcmp(m->kind, "type") && strcmp(m->kind, "c"))) return;
    if (!strcmp(m->module, "-") || !strcmp(m->module, "rt")) {
        if (f->real && strstr(f->real, "/src/std/")) {
            if (!strcmp(m->module, "rt")) migAdd(f, s, s + name->len, migQualified(f, m), "stdlib");
            return;
        }
        migNote(f, s, "can't convert the type `%s`: %s in the stdlib map (%s)", key,
                !strcmp(m->module, "rt") ? "runtime-only" : "dropped", m->note);
        return;
    }
    migAdd(f, s, s + name->len, migQualified(f, m), "stdlib");
}

/* Does the file #define `name` itself? */
static int migDefinesMacro(MigFile *f, const char *name) {
    s64 n = strlen(name);
    for (char *p = f->text; (p = strstr(p, "#define")) != NULL; p += 7) {
        char *q = p + 7;
        while (*q == ' ' || *q == '\t') q++;
        if (!strncmp(q, name, n) && !migIsIdent(q[n])) return 1;
    }
    return 0;
}

void migrateNoteMacro(Cctrl *cc, Lexeme *use) {
    if (!mig_map) return;
    MigFile *f = migFileOf(cc, use ? use->src : NULL);
    if (!f) return;
    s64 s = migOff(f, use);
    if (s < 0) return;
    char *key = mprintf("%.*s", (int)use->len, f->text + s);
    MigName *m = mapGet(mig_map, key);
    if (!m || (strcmp(m->kind, "macro") && strcmp(m->kind, "c"))) return;
    if (migDefinesMacro(f, key)) return;
    if (!strcmp(m->module, "-") || !strcmp(m->module, "rt")) {
        if (!strcmp(m->name, "removed")) return;      /* TRUE FALSE NULL: stage 1 */
        if (f->real && strstr(f->real, "/src/std/")) {
            if (!strcmp(m->module, "rt")) migAdd(f, s, s + use->len, migQualified(f, m), "stdlib");
            return;
        }
        migNote(f, s, "can't convert the macro `%s`: %s in the stdlib map (%s)", key,
                !strcmp(m->module, "rt") ? "runtime-only" : "dropped", m->note);
        return;
    }
    migAdd(f, s, s + use->len, migQualified(f, m), "stdlib");
}

/* ------------------------------------------------------------------ */
/* 1k, layout (--layout): `->` becomes `.`; on the converted text, `{` on
 * the line of its function, struct or control statement, `} else`,
 * `} while`, `} catch` on one line, and 4-space indentation */

static int mig_layout = 0;

void migrateNoteArrow(Cctrl *cc, Lexeme *arrow) {
    if (!mig_layout) return;
    MigFile *f = migFileOf(cc, arrow ? arrow->src : NULL);
    if (!f) return;
    s64 s = migOff(f, arrow);
    if (s < 0 || f->text[s] != '-' || f->text[s + 1] != '>') return;
    migAdd(f, s, s + 2, ".", "layout");
}

/* The lines of text, with for each whether it starts inside a string or
 * a block comment (left as they are), and its brace depth at its start */
typedef struct { char *s; s64 len; int in_str, in_comment, depth, opens_first; } MigLine;

static MigLine *migLines(const char *text, int *_n) {
    int cap = 256, n = 0;
    MigLine *ls = malloc(cap * sizeof(MigLine));
    int in_str = 0, in_comment = 0, depth = 0;
    const char *p = text;
    while (*p) {
        const char *e = strchr(p, '\n');
        s64 len = e ? e - p : (s64)strlen(p);
        if (n == cap) { cap *= 2; ls = realloc(ls, cap * sizeof(MigLine)); }
        MigLine *l = &ls[n++];
        l->s = strndup(p, len);
        l->len = len;
        l->in_str = in_str;
        l->in_comment = in_comment;
        l->depth = depth;
        l->opens_first = 0;
        /* first code char a `}`: the line is one level out */
        int first_code = 1;
        for (s64 i = 0; i < len; ++i) {
            char c = p[i];
            if (in_comment) {
                if (c == '*' && i + 1 < len && p[i + 1] == '/') { in_comment = 0; i++; }
                continue;
            }
            if (in_str) {
                if (c == '\\') { i++; continue; }
                if (c == '"') in_str = 0;
                continue;
            }
            if (c == '/' && i + 1 < len && p[i + 1] == '/') break;
            if (c == '/' && i + 1 < len && p[i + 1] == '*') { in_comment = 1; i++; continue; }
            if (c == '"') { in_str = 1; first_code = 0; continue; }
            if (c == '\'') {
                s64 j = i + 1;
                while (j < len && p[j] != '\'') { if (p[j] == '\\') j++; j++; }
                i = j;
                first_code = 0;
                continue;
            }
            if (c == ' ' || c == '\t') continue;
            if (c == '}' && first_code) l->opens_first = 1;
            if (c == '{') depth++;
            else if (c == '}') depth--;
            first_code = 0;
        }
        p = e ? e + 1 : p + len;
        if (!e) break;
    }
    *_n = n;
    return ls;
}

/* Leading blanks as columns (a tab is `unit` columns) */
static int migIndentCols(const char *s, int unit, int *_chars) {
    int cols = 0, i = 0;
    for (; s[i] == ' ' || s[i] == '\t'; ++i) cols += s[i] == '\t' ? unit : 1;
    *_chars = i;
    return cols;
}

static int migEndsWithWord(const char *s, s64 len, const char *w) {
    s64 n = strlen(w);
    return len >= n && !memcmp(s + len - n, w, n) && (len == n || !migIsIdent(s[len - n - 1]));
}

static char *migLayout(const char *text) {
    int n;
    MigLine *ls = migLines(text, &n);
    /* the file's indentation unit: the smallest indent at depth 1 */
    int unit = 0;
    for (int i = 0; i < n; ++i) {
        if (ls[i].in_str || ls[i].in_comment || ls[i].depth != 1 || ls[i].opens_first) continue;
        int ch, cols = migIndentCols(ls[i].s, 2, &ch);
        if (ch == (int)ls[i].len) continue;
        if (ls[i].s[0] == '\t') { unit = -1; break; }
        if (cols > 0 && (unit == 0 || cols < unit)) unit = cols;
    }
    int tabs = unit == -1;
    if (unit <= 0) unit = tabs ? 4 : 2;
    AoStr *out = aoStrNew();
    for (int i = 0; i < n; ++i) {
        MigLine *l = &ls[i];
        char *s = l->s;
        s64 len = l->len;
        if (l->in_str) {          /* inside a string: as it is */
            aoStrCatLen(out, s, len);
            if (i + 1 < n) aoStrPutChar(out, '\n');
            continue;
        }
        int ch, cols = migIndentCols(s, unit, &ch);
        const char *body = s + ch;
        s64 blen = len - ch;
        /* a lone `{`: onto the line before when that is a header */
        if (!l->in_comment && blen == 1 && body[0] == '{' && out->len > 0) {
            s64 pe = out->len - 1;           /* the previous line's end */
            while (pe > 0 && out->data[pe - 1] != '\n' && out->data[pe] == '\n') break;
            s64 ls_ = out->len - 1;
            while (ls_ > 0 && out->data[ls_ - 1] != '\n') ls_--;
            char *prev = out->data + ls_;
            s64 plen = out->len - 1 - ls_;
            while (plen > 0 && (prev[plen - 1] == ' ' || prev[plen - 1] == '\t')) plen--;
            int has_comment = 0;
            for (s64 k = 0; k + 1 < plen; ++k) if (prev[k] == '/' && (prev[k + 1] == '/' || prev[k + 1] == '*')) has_comment = 1;
            char last = plen > 0 ? prev[plen - 1] : 0;
            int header = !has_comment && plen > 0 && prev[0] != '#' &&
                (last == ')' || migEndsWithWord(prev, plen, "else") || migEndsWithWord(prev, plen, "do") ||
                 migEndsWithWord(prev, plen, "try") ||
                 (migIsIdent(last) && (strncmp(prev + (plen - plen), "", 0) == 0) &&
                  (strstr(prev, "struct ") || strstr(prev, "union ") || strstr(prev, "enum ") ||
                   strstr(prev, "class "))));
            if (header) {
                out->len = ls_ + plen;
                out->data[out->len] = 0;
                aoStrCat(out, " {");
                if (i + 1 < n) aoStrPutChar(out, '\n');
                continue;
            }
        }
        /* `}` then `else`/`while`/`catch` on the next line: one line */
        if (!l->in_comment && blen > 0 && out->len > 1 &&
            (!strncmp(body, "else", 4) || !strncmp(body, "catch", 5) ||
             (!strncmp(body, "while", 5) && blen > 5 && (body[5] == ' ' || body[5] == '(') &&
              body[blen - 1] == ';'))) {
            s64 ls_ = out->len - 1;
            while (ls_ > 0 && out->data[ls_ - 1] != '\n') ls_--;
            char *prev = out->data + ls_;
            s64 plen = out->len - 1 - ls_;
            s64 k = 0;
            while (k < plen && (prev[k] == ' ' || prev[k] == '\t')) k++;
            if (plen - k == 1 && prev[k] == '}') {
                out->len = ls_ + plen;
                out->data[out->len] = 0;
                aoStrPutChar(out, ' ');
                aoStrCatLen(out, body, blen);
                if (i + 1 < n) aoStrPutChar(out, '\n');
                continue;
            }
        }
        if (blen == 0) {              /* a blank line: no trailing blanks */
            if (i + 1 < n) aoStrPutChar(out, '\n');
            continue;
        }
        if (body[0] == '#') {          /* the preprocessor: as it is */
            aoStrCatLen(out, s, len);
            if (i + 1 < n) aoStrPutChar(out, '\n');
            continue;
        }
        int depth = l->depth - (l->opens_first ? 1 : 0);
        if (depth < 0) depth = 0;
        int extra = cols - depth * unit;
        int newcols;
        if (extra < 0) {
            newcols = cols * 4 / unit;           /* indented less than its depth */
        } else {
            newcols = depth * 4 + (extra / unit) * 4 + extra % unit;
        }
        for (int k = 0; k < newcols; ++k) aoStrPutChar(out, ' ');
        aoStrCatLen(out, body, blen);
        if (i + 1 < n) aoStrPutChar(out, '\n');
    }
    if (text[0] && text[strlen(text) - 1] == '\n' && (out->len == 0 || out->data[out->len - 1] != '\n')) {
        aoStrPutChar(out, '\n');
    }
    return out->data ? out->data : strdup("");
}

void migrateNoteCall(Cctrl *cc, Lexeme *name) {
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    s64 s = migOff(f, name);
    if (s < 0) return;
    migAdd(f, s + name->len, s + name->len, "()", "call");
    if (name->len == 4 && !memcmp(f->text + s, "Main", 4)) {
        vecPush(f->main_calls, (void *)(intptr_t)s);
    }
}

void migrateNoteMain(Cctrl *cc, Lexeme *name, Lexeme *lbrace, int is_void) {
    MigFile *f = migFileOf(cc, name ? name->src : NULL);
    if (!f) return;
    f->has_main = 1;
    f->main_name = migOff(f, name);
    f->main_lbrace = migOff(f, lbrace);
    f->main_void = is_void;
    f->main_item = f->item_start;
}

Lexeme *migrateItemFirst(Cctrl *cc) {
    return cc->migrate ? cc->migrate->item_first : NULL;
}

void migrateItemStart(Cctrl *cc, Lexeme *first) {
    Migrate *m = cc->migrate;
    m->item_first = first;
    for (int i = 0; i < m->n_files; ++i) m->files[i]->item_start = -1;
    MigFile *f = migFileOf(cc, first ? first->src : NULL);
    if (f) f->item_start = migOff(f, first);
}

void migrateNoteRunningItem(Cctrl *cc, Lexeme *last, int is_decl) {
    MigFile *f = migFileOf(cc, last ? last->src : NULL);
    if (!f || f->item_start < 0) return;
    s64 e = migOff(f, last);
    if (e < 0) return;
    MigItem *it = (MigItem *)malloc(sizeof(MigItem));
    it->start = f->item_start;
    it->end = e + last->len;
    it->is_decl = is_decl;
    vecPush(f->items, it);
}

/* ------------------------------------------------------------------ */
/* Applying the edits */

static int migCmp(const void *a, const void *b) {
    const MigEdit *x = *(MigEdit *const *)a, *y = *(MigEdit *const *)b;
    if (x->start != y->start) return x->start < y->start ? -1 : 1;
    /* Code moved into main takes the edits at its start along with it */
    int xm = x->start != x->end && !strcmp(x->kind, "moved");
    int ym = y->start != y->end && !strcmp(y->kind, "moved");
    if (xm != ym) return xm ? -1 : 1;
    int xi = x->start == x->end, yi = y->start == y->end;
    if (xi != yi) return xi ? -1 : 1;            /* inserts first */
    if (xi) return x->seq > y->seq ? -1 : 1;     /* the newest insert first */
    if (x->end != y->end) return x->end > y->end ? -1 : 1;
    /* the same span: a method's (`self`) over a rename of the name */
    int xmeth = !strcmp(x->kind, "method"), ymeth = !strcmp(y->kind, "method");
    if (xmeth != ymeth) return xmeth ? -1 : 1;
    return x->seq > y->seq ? -1 : 1;             /* else the newest first */
}

static void migSort(MigFile *f) {
    qsort(f->edits->entries, f->edits->size, sizeof(void *), migCmp);
}

/* [rs, re) of the text with the edits inside it applied; an edit that
 * starts inside a range another one replaces is dropped */
static char *migApply(MigFile *f, s64 rs, s64 re, int to_eof);

/* What an edit puts in: its text, or its pieces rendered */
static char *migEditText(MigFile *f, MigEdit *e) {
    if (!e->segs) return e->text;
    AoStr *out = aoStrNew();
    for (u64 k = 0; k < e->segs->size; ++k) {
        MigSeg *g = e->segs->entries[k];
        /* an insert at the end of a moved range moves with it */
        aoStrCat(out, g->text ? g->text : migApply(f, g->s, g->e, 1));
    }
    return out->data ? out->data : "";
}

/* migApply's to_eof is 2 for the whole file */
static int to_eof_outer(int to_eof) { return to_eof == 2; }

static char *migApply(MigFile *f, s64 rs, s64 re, int to_eof) {
    AoStr *out = aoStrNew();
    s64 pos = rs;
    for (u64 i = 0; i < f->edits->size; ++i) {
        MigEdit *e = f->edits->entries[i];
        if (e->start < pos || e->start > re) continue;
        if (e->start == re && !(to_eof && e->start == e->end)) continue;
        if (e->end > re) continue;
        /* a swap's own range renders as written (its partner's edit
         * renders this one) */
        if (!strcmp(e->kind, "swap") && e->start == rs && e->end == re && !to_eof_outer(to_eof)) continue;
        aoStrCatLen(out, f->text + pos, e->start - pos);
        aoStrCat(out, migEditText(f, e));
        pos = e->end;
    }
    aoStrCatLen(out, f->text + pos, re - pos);
    return out->data;
}

/* Widen [s, e) to whole lines when nothing else is on them */
static void migWholeLines(MigFile *f, s64 *s, s64 *e) {
    s64 a = *s, b = *e;
    while (a > 0 && (f->text[a - 1] == ' ' || f->text[a - 1] == '\t')) a--;
    while (b < f->len && (f->text[b] == ' ' || f->text[b] == '\t')) b++;
    if ((a == 0 || f->text[a - 1] == '\n') && (b == f->len || f->text[b] == '\n')) {
        *s = a;
        *e = b < f->len ? b + 1 : b;
    }
}

static char *migIndent(const char *text, const char *indent) {
    AoStr *out = aoStrNew();
    aoStrCat(out, indent);
    for (const char *p = text; *p; ++p) {
        aoStrPutChar(out, *p);
        if (*p == '\n' && p[1] && p[1] != '\n') aoStrCat(out, indent);
    }
    return out->data;
}

/* The indentation of the first line of main's body */
static char *migBodyIndent(MigFile *f) {
    s64 i = f->main_lbrace + 1;
    while (i < f->len && f->text[i] != '\n') i++;
    while (i < f->len) {
        s64 j = i + 1, k = j;
        while (k < f->len && (f->text[k] == ' ' || f->text[k] == '\t')) k++;
        if (k < f->len && f->text[k] != '\n' && f->text[k] != '}') {
            return mprintf("%.*s", (int)(k - j), f->text + j);
        }
        if (k >= f->len || f->text[k] == '}') break;
        i = k;
    }
    return strdup("  ");
}

/* `T name = expr;` at file scope: the text to move (`name = expr;`) and
 * the edit that leaves `T name;`. NULL if it isn't that simple. */
static char *migSplitDecl(MigFile *f, MigItem *it) {
    char *t = f->text;
    s64 eq = -1, depth = 0;
    for (s64 i = it->start; i < it->end; ++i) {
        char c = t[i];
        if (c == '"' || c == '\'') {
            i = migQuoteEnd(t, i, it->end);
            continue;
        }
        if (c == '(' || c == '[' || c == '{') depth++;
        else if (c == ')' || c == ']' || c == '}') depth--;
        else if (depth == 0 && c == ',') return NULL;
        else if (depth == 0 && c == '=' && eq < 0 &&
                 !(i + 1 < it->end && t[i + 1] == '=')) eq = i;
    }
    if (eq < 0 || t[it->end - 1] != ';') return NULL;
    s64 n = migSkipSpaceBack(t, eq - 1), ne = n + 1;
    while (n >= it->start && migIsIdent(t[n])) n--;
    s64 name = n + 1;
    if (name >= ne) return NULL;
    s64 r = eq + 1;
    while (r < it->end && isspace((unsigned char)t[r])) r++;
    if (t[r] == '{') return NULL;
    for (s64 i = it->start; i + 4 <= name; ++i) {
        if (!memcmp(t + i, "auto", 4) && (i == it->start || !migIsIdent(t[i - 1])) &&
            !migIsIdent(t[i + 4])) return NULL;
    }
    char *moved = mprintf("%s = %s", migApply(f, name, ne, 0),
                          migApply(f, r, it->end, 0));
    migAdd(f, ne, it->end - 1, "", "moved");
    return moved;
}

/* Move the code at file scope into main (made if there is none or the
 * code calls Main itself) */
static void migMoveItems(MigFile *f) {
    if (f->items->size == 0) return;
    AoStr *moved = aoStrNew();
    int any = 0;
    for (u64 i = 0; i < f->items->size; ++i) {
        MigItem *it = f->items->entries[i];
        char *text;
        /* `I64 a = 4, b = a * 3;`: the item is the declarator `b = a * 3`
         * after the comma, a declaration too */
        s64 before = migSkipSpaceBack(f->text, it->start - 1);
        if (before >= 0 && f->text[before] == ',') it->is_decl = 1;
        if (it->is_decl) {
            text = migSplitDecl(f, it);
            if (!text) {
                migNote(f, it->start, any
                        ? "check: a global whose initialiser runs code stays, but code before it "
                          "moved into main: it now runs first (`I64 b = a * 2; I64 c[1] = {b};`)"
                        : "kept: a global whose initialiser runs code "
                          "(not a simple `T name = expr;`)");
                continue;
            }
            migNote(f, it->start, "moved the initialiser of a global into main");
        } else {
            /* with what is inserted at its end (a body's `}`) */
            text = migApply(f, it->start, it->end, 1);
            s64 s = it->start, e = it->end;
            migWholeLines(f, &s, &e);
            migAdd(f, s, e, "", "moved");
            migNote(f, it->start, "moved a statement at file scope into main");
        }
        aoStrCat(moved, any ? "\n" : "");
        aoStrCat(moved, text);
        any = 1;
    }
    if (!any) return;
    if (f->has_main && !f->top_calls_main && f->main_lbrace >= 0) {
        char *indent = migBodyIndent(f);
        migAdd(f, f->main_lbrace + 1, f->main_lbrace + 1,
               mprintf("\n%s", migIndent(moved->data, indent)), "moved");
    } else {
        int nl = f->len > 0 && f->text[f->len - 1] != '\n';
        migAdd(f, f->len, f->len,
               mprintf("%s\nvoid main()\n{\n%s\n}\n", nl ? "\n" : "",
                       migIndent(moved->data, "  ")), "main");
        migNote(f, f->len, f->has_main
                ? "made `void main()` for the code at file scope (it calls Main, now `runMain`)"
                : "made `void main()` for the code at file scope");
    }
}

/* Main's return type: void stays (the word map makes U0 void), anything
 * but I32 becomes i32 */
static void migMainType(MigFile *f) {
    if (!f->has_main || f->top_calls_main || f->main_void) return;
    if (f->main_item < 0 || f->main_name < 0) return;
    s64 s = f->main_item, e = f->main_name;
    while (s < e && isspace((unsigned char)f->text[s])) s++;
    s64 te = e;
    while (te > s && isspace((unsigned char)f->text[te - 1])) te--;
    for (s64 i = s; i < te; ++i) {
        if (!migIsIdent(f->text[i])) {
            migNote(f, s, "can't rewrite the return type of Main");
            return;
        }
    }
    if (te - s == 3 && !memcmp(f->text + s, "I32", 3)) return;
    migAdd(f, s, te, "i32", "main");
}

/* Postfix casts the parser didn't see (skipped #if branches, macros):
 * `x(i64)` or `p(u8 *)` left in the output */
/* A copy of `src` with comments and the insides of strings and character
 * constants blanked (newlines kept), for pattern checks on code only */
static char *migBlank(const char *src) {
    char *b = strdup(src);
    s64 len = (s64)strlen(b);
    for (char *p = b; *p; ++p) {
        if (p[0] == '/' && p[1] == '/') {
            while (*p && *p != '\n') *p++ = ' ';
            if (!*p) break;
        } else if (p[0] == '/' && p[1] == '*') {
            while (*p && !(p[0] == '*' && p[1] == '/')) { if (*p != '\n') *p = ' '; p++; }
            if (!*p) break;
            p[0] = ' ';
            p[1] = ' ';
            p++;
        } else if (*p == '"' || *p == '\'') {
            char q = *p;
            s64 i = p - b, end = migQuoteEnd(b, i, len);
            if (end == i) continue;     /* a lone apostrophe */
            p++;
            while (*p && *p != q) {
                if (*p == '\\' && p[1]) *p++ = ' ';
                if (*p != '\n') *p = ' ';
                p++;
            }
            if (!*p) break;
        }
    }
    return b;
}

static void migCheckLeftovers(MigFile *f, const char *raw) {
    const char *out = migBlank(raw);
    static const char *types[] = {"void", "i8", "u8", "i16", "u16", "i32", "u32",
                                  "i64", "u64", "f32", "f64", "bool",
                                  "U0", "I8", "U8", "I16", "U16", "I32", "U32",
                                  "I64", "U64", "F32", "F64", "Bool"};
    int line = 1;
    for (const char *p = out; *p; ++p) {
        if (*p == '\n') { line++; continue; }
        if (*p != '(' || p == out) continue;
        const char *b = p - 1;
        while (b > out && (*b == ' ' || *b == '\t')) b--;
        if (!(migIsIdent(*b) || *b == ')' || *b == ']')) continue;
        if (*b == ')') {
            /* `T (*name)(params)`: a function pointer's parameters */
            int depth = 0;
            const char *o = b;
            for (; o > out; --o) {
                if (*o == ')') depth++;
                else if (*o == '(' && --depth == 0) break;
            }
            const char *in = o + 1;
            while (*in == ' ') in++;
            if (*in == '*') continue;
        }
        if (migIsIdent(*b)) {
            const char *w = b;
            while (w > out && migIsIdent(w[-1])) w--;
            static const char *kw[] = {"sizeof", "alignof", "typeof", "return",
                                       "case", "if", "while", "for", "switch"};
            int is_kw = 0;
            for (u64 k = 0; k < sizeof(kw) / sizeof(kw[0]); ++k) {
                if ((s64)strlen(kw[k]) == b - w + 1 && !memcmp(w, kw[k], b - w + 1)) is_kw = 1;
            }
            if (is_kw) continue;
        }
        const char *q = p + 1;
        while (*q == ' ') q++;
        for (u64 k = 0; k < sizeof(types) / sizeof(types[0]); ++k) {
            u64 n = strlen(types[k]);
            if (strncmp(q, types[k], n) || migIsIdent(q[n])) continue;
            const char *r = q + n;
            while (*r == ' ' || *r == '*') r++;
            const char *after = r + 1;
            while (*after == ' ' || *after == '\t') after++;
            /* `(t)x` is a prefix cast: an operand follows it */
            if (*r == ')' && (migIsIdent(*after) || *after == '(' ||
                              *after == '"' || *after == '\'')) break;
            /* `F(void)`: an empty parameter list */
            if (*r == ')' && r == q + n && (n == 4 && !strncmp(q, "void", 4))) break;
            if (*r == ')') {
                aoStrCatPrintf(f->notes, "%s:%d: check: what looks like a postfix cast "
                               "is left (in a skipped #if branch or a macro?)\n",
                               f->path, line);
            }
            break;
        }
    }
}

/* Postfix casts in text an #if skipped (the parser never saw it): an old
 * type name in parentheses right after an operand. A declaration like
 * `I32 Foo(I32);` (the name after a type) is left alone. */
/* A print statement in skipped text: a string or character constant
 * where a statement starts (a line's start, after `{`, `}`, `;`, `)` or
 * `else`), up to its `;` */
static int migStatementStart(char *t, s64 s, s64 j) {
    s64 b = j - 1;
    while (b >= s && (t[b] == ' ' || t[b] == '\t')) b--;
    if (b < s || t[b] == '\n') return 1;
    if (t[b] == '{' || t[b] == '}' || t[b] == ';' || t[b] == ')') return 1;
    return b >= s + 3 && !memcmp(t + b - 3, "else", 4) &&
           (b - 4 < s || !migIsIdent(t[b - 4]));
}

static void migSkippedPrints(MigFile *f, s64 s, s64 e) {
    char *t = f->text;
    for (s64 j = s; j < e; ++j) {
        if (t[j] == '/' && j + 1 < e && t[j + 1] == '/') {
            while (j < e && t[j] != '\n') j++;
            continue;
        }
        if (t[j] == '/' && j + 1 < e && t[j + 1] == '*') {
            j += 2;
            while (j + 1 < e && !(t[j] == '*' && t[j + 1] == '/')) j++;
            j++;
            continue;
        }
        if (t[j] != '"' && t[j] != '\'') continue;
        if (migQuoteEnd(t, j, e) == j) continue;   /* a lone apostrophe */
        if (!migStatementStart(t, s, j)) {
            j = migQuoteEnd(t, j, e);
            continue;
        }
        {
            s64 k = j, depth = 0;
            for (; k < e; ++k) {
                if (t[k] == '"' || t[k] == '\'') {
                    k = migQuoteEnd(t, k, e);
                    continue;
                }
                if (t[k] == '(') depth++;
                else if (t[k] == ')') depth--;
                else if (t[k] == ';' && depth == 0) break;
            }
            if (k < e && t[j] == '\'') {
                migCharPrint(f, j);
            } else if (k < e) {
                migAdd(f, j, j, "printf(", "printf");
                migAdd(f, k, k, ")", "printf");
            }
            j = k;
        }
    }
}

/* A stdlib name in text an #if skipped (the parser never saw it): the
 * map's name, as the parser's notes would have made it */
static void migSkippedMapWord(MigFile *f, s64 at, s64 len, void *ctx) {
    (void)ctx;
    if (!mig_map) return;
    char *key = mprintf("%.*s", (int)len, f->text + at);
    MigName *m = mapGet(mig_map, key);
    if (!m || !strcmp(m->module, "-") || !strcmp(m->module, "prelude")) return;
    if (at > 0 && f->text[at - 1] == '.') return;           /* a field */
    int in_std = f->real && strstr(f->real, "/src/std/");
    if (!strcmp(m->module, "rt") && !in_std) return;
    migAdd(f, at, at + len, migQualified(f, m), "stdlib");
}

/* In src/std, a module's own types by the map's name everywhere (class
 * fields, platform branches): `cTimespec` in libc is `timespec` */
static void migOwnTypeWord(MigFile *f, s64 at, s64 len, void *ctx) {
    (void)ctx;
    if (at > 0 && f->text[at - 1] == '.') return;
    migOwnType(f, at, len);
}

static void migSkippedCasts(MigFile *f) {
    if (mig_map) {
        if (f->real && strstr(f->real, "/src/std/"))
            migScanWords(f, f->text, 0, f->len, migOwnTypeWord, NULL);
        for (u64 r = 0; r + 1 < f->skipped->size; r += 2) {
            s64 s = (s64)(intptr_t)f->skipped->entries[r];
            s64 e = (s64)(intptr_t)f->skipped->entries[r + 1];
            migScanWords(f, f->text, s, e, migSkippedMapWord, NULL);
        }
    }
    char *t = f->text;
    int branches = 0;
    s64 first = -1;
    for (u64 r = 0; r + 1 < f->skipped->size; r += 2) {
        s64 s = (s64)(intptr_t)f->skipped->entries[r];
        s64 e = (s64)(intptr_t)f->skipped->entries[r + 1];
        int code = 0;
        for (s64 k = s; k < e; ++k) {
            if (!isspace((unsigned char)t[k])) { code = 1; break; }
        }
        if (code) {
            if (first < 0) first = s;
            branches++;
        }
        migSkippedPrints(f, s, e);
        for (s64 k = s; k < e; ++k) {
            if (t[k] == '/' && k + 1 < e && t[k + 1] == '/') {
                while (k < e && t[k] != '\n') k++;
                continue;
            }
            if (t[k] == '/' && k + 1 < e && t[k + 1] == '*') {
                k += 2;
                while (k + 1 < e && !(t[k] == '*' && t[k + 1] == '/')) k++;
                k++;
                continue;
            }
            if (t[k] == '"' || t[k] == '\'') {
                k = migQuoteEnd(t, k, e);
                continue;
            }
            if (t[k] != '(') continue;
            s64 rp = migOldCastEnd(f, k, e);
            if (rp < 0) continue;
            s64 op = migOperandStart(f, k);
            if (op < 0) continue;
            s64 b = migSkipSpaceBack(t, op - 1);
            int decl = 0;
            if (b >= 0 && migIsIdent(t[b])) {
                s64 w = b;
                while (w > 0 && migIsIdent(t[w - 1])) w--;
                decl = !((b - w + 1 == 6 && !memcmp(t + w, "return", 6)) ||
                         (b - w + 1 == 4 && !memcmp(t + w, "case", 4)));
            } else if (b >= 0 && t[b] == '*') {
                s64 c = migSkipSpaceBack(t, b - 1);
                decl = c >= 0 && migIsIdent(t[c]);
            }
            if (decl) {
                k = rp;
                continue;
            }
            migCastAt(f, k, rp);
            migNote(f, k, "rewrote a postfix cast in a skipped #if branch (check it)");
            k = rp;
        }
    }
    if (branches) {
        migNote(f, first, "%d #if branch(es) not compiled here were rewritten from "
                "their text only (types, casts, print statements; not calls "
                "without parentheses): check them", branches);
    }
}

/* ------------------------------------------------------------------ */
/* Comments and strings */

static int migIsOldType(const char *w, s64 len) {
    static const char *old[] = {"U0", "I8", "U8", "I16", "U16", "I32", "U32",
                                "I64", "U64", "F32", "F64", "Bool"};
    for (u64 k = 0; k < sizeof(old) / sizeof(old[0]); ++k) {
        if ((s64)strlen(old[k]) == len && !memcmp(w, old[k], len)) return 1;
    }
    return 0;
}

/* Old type names (and, with `literals`, TRUE/FALSE/NULL) in [s, e) of
 * prose: a comment or a string that isn't code */
static void migProseWords(MigFile *f, s64 s, s64 e, int literals, const char *kind) {
    char *t = f->text;
    for (s64 i = s; i < e; ) {
        if (!migIsIdent(t[i])) { i++; continue; }
        s64 w = i;
        while (i < e && migIsIdent(t[i])) i++;
        if (w > 0 && migIsIdent(t[w - 1])) continue;
        const char *k;
        const char *to = migMapWord(NULL, t + w, i - w, &k);
        if (!to) continue;
        if (migIsOldType(t + w, i - w) || (literals && !strcmp(k, "literal"))) {
            migAdd(f, w, i, to, kind);
        }
    }
}

/* `// @TempleOS - Count occurrences of a char.` keeps what the function
 * does (`// Count occurrences of a char.`); a comment that only credits
 * the origin goes, with the spaces before it */
static void migAttribution(MigFile *f, s64 cs, s64 ce) {
    char *t = f->text;
    char *at = NULL;
    for (s64 i = cs; i + 9 <= ce; ++i) {
        if (!memcmp(t + i, "@TempleOS", 9)) { at = t + i; break; }
    }
    if (!at) return;
    s64 rest = (at - t) + 9;
    while (rest < ce && (t[rest] == ' ' || t[rest] == '-' || t[rest] == ':' ||
                         t[rest] == ',')) rest++;
    s64 end = ce;
    while (end > rest && isspace((unsigned char)t[end - 1])) end--;
    static const char *credit_only[] = {"only slight tweaks", "Not a 1:1 yet"};
    int drop = rest >= end;
    for (u64 k = 0; k < sizeof(credit_only) / sizeof(credit_only[0]); ++k) {
        if ((s64)strlen(credit_only[k]) == end - rest &&
            !strncasecmp(t + rest, credit_only[k], end - rest)) drop = 1;
    }
    if (drop) {
        s64 s0 = cs;
        while (s0 > 0 && (t[s0 - 1] == ' ' || t[s0 - 1] == '\t')) s0--;
        if (s0 > 0 && t[s0 - 1] == '\n' && (ce >= f->len || t[ce] == '\n')) {
            migAdd(f, s0, ce < f->len ? ce + 1 : ce, "", "attribution");  /* the whole line */
        } else {
            migAdd(f, s0, ce, "", "attribution");
        }
    } else {
        migAdd(f, cs, rest, "// ", "attribution");
    }
}

static void migSnippetAt(MigFile *outer, s64 *cs, s64 *ce, int np, char *install_dir,
                         int *n, int *n_text, int escaped);
static int migParse(Migrate *m, char *root, char *install_dir);
static MigFile *migFileNew(char *path);

/* Comments get the new type names and literals; a string that holds code
 * (a program a test compiles) is converted as code, any other string gets
 * the new type names (messages that name a type) */
static void migCommentsAndStrings(MigFile *f, char *install_dir, int outer) {
    char *t = f->text;
    int n = 0, n_text = 0;
    for (s64 i = 0; i < f->len; ) {
        if (t[i] == '/' && i + 1 < f->len && t[i + 1] == '/') {
            s64 e = i;
            while (e < f->len && t[e] != '\n') e++;
            migProseWords(f, i, e, 1, "comment");
            migAttribution(f, i, e);
            i = e;
            continue;
        }
        if (t[i] == '/' && i + 1 < f->len && t[i + 1] == '*') {
            s64 e = i + 2;
            while (e + 1 < f->len && !(t[e] == '*' && t[e + 1] == '/')) e++;
            migProseWords(f, i, e, 1, "comment");
            i = e + 2;
            continue;
        }
        if (t[i] == '\'') {
            i = migQuoteEnd(t, i, f->len) + 1;
            continue;
        }
        if (t[i] == '"') {
            /* One string: literals next to each other ("a" "b"), also with
             * a macro between them ("#include \"" HDR "\"\nU0 Main()...") */
            s64 cs[64], ce[64];
            int np = 0;
            s64 e = i;
            for (;;) {
                e++;
                cs[np] = e;
                while (e < f->len && t[e] != '"') { if (t[e] == '\\') e++; e++; }
                ce[np++] = e;
                s64 k = e + 1;
                while (k < f->len && isspace((unsigned char)t[k])) k++;
                if (k < f->len && (isupper((unsigned char)t[k]) || t[k] == '_')) {
                    s64 w = k;
                    while (k < f->len && migIsIdent(t[k])) k++;
                    int macro = 1;
                    for (s64 q = w; q < k; ++q) {
                        if (islower((unsigned char)t[q])) macro = 0;
                    }
                    if (!macro) break;
                    while (k < f->len && isspace((unsigned char)t[k])) k++;
                }
                if (k >= f->len || t[k] != '"' || np == 64) break;
                e = k;
            }
            if (outer) {
                migSnippetAt(f, cs, ce, np, install_dir, &n, &n_text, 1);
            }
            i = e + 1;
            continue;
        }
        i++;
    }
    if (n) {
        migNote(f, -1, "converted %d string(s) that hold code%s", n,
                n_text ? mprintf(" (%d from their text only: they don't compile "
                                 "on their own; check them)", n_text) : "");
    }
}

/* The edits migApply would apply, in order */
static Vec *migEffective(MigFile *f) {
    Vec *v = vecNew(&vec_unsigned_long_type);
    s64 pos = 0;
    for (u64 i = 0; i < f->edits->size; ++i) {
        MigEdit *e = f->edits->entries[i];
        if (e->start < pos || e->end > f->len) continue;
        vecPush(v, e);
        pos = e->end;
    }
    return v;
}

static char *migEscape(const char *text, int raw_newlines) {
    AoStr *out = aoStrNew();
    aoStrCat(out, "");
    for (const char *p = text; *p; ++p) {
        if (*p == '\\') aoStrCat(out, "\\\\");
        else if (*p == '"') aoStrCat(out, "\\\"");
        else if (*p == '\n' && !raw_newlines) aoStrCat(out, "\\n");
        else if (*p == '\t') aoStrCat(out, "\\t");
        else aoStrPutChar(out, *p);
    }
    return out->data;
}

static int migLooksLikeCode(const char *u) {
    if (!strchr(u, ';') || !(strchr(u, '(') || strchr(u, '{'))) return 0;
    for (const char *p = u; *p; ) {
        if (!migIsIdent(*p)) { p++; continue; }
        const char *w = p;
        while (migIsIdent(*p)) p++;
        if (w > u && migIsIdent(w[-1])) continue;
        const char *k;
        if (migMapWord(NULL, w, p - w, &k) ||
            (p - w == 4 && !memcmp(w, "Main", 4))) return 1;
    }
    return 0;
}

static int migConvert(MigFile *f, char *install_dir, int outer);

/* Code in [cs, ce) of `outer`: a string literal's content (`escaped`), or
 * the raw code of an HTML <pre> block */
/* A message, not code: the new type names; TRUE/FALSE/NULL too, except
 * in SQL (`IS NULL`) and in what a test looks for in the compiler's own
 * messages (`strstr(out, "NULL param")`) */
static void migProseString(MigFile *f, s64 *cs, s64 *ce, int np, const char *u) {
    int literals = !strstr(u, "SELECT ") && !strstr(u, "INSERT ") &&
                   !strstr(u, "UPDATE ") && !strstr(u, "CREATE ") &&
                   !strstr(u, "IS NULL");
    for (s64 b = cs[0] - 1; b >= 7 && cs[0] - b < 80; --b) {
        char c = f->text[b - 1];
        if (c == ';' || c == ')' || c == '{' || c == '}') break;
        if (!memcmp(f->text + b - 7, "strstr(", 7)) { literals = 0; break; }
    }
    for (int p = 0; p < np; ++p) migProseWords(f, cs[p], ce[p], literals, "string");
}

static void migSnippetAt(MigFile *outer, s64 *cs, s64 *ce, int np, char *install_dir,
                         int *n, int *n_text, int escaped) {
    char *t = outer->text;
    s64 len = 0;
    for (int p = 0; p < np; ++p) len += ce[p] - cs[p];
    char *u = (char *)malloc(len + 1);
    s64 *map = (s64 *)malloc((len + 2) * sizeof(s64));
    int *piece = (int *)malloc((len + 2) * sizeof(int));
    s64 k = 0;
    int raw_newlines = 0;
    for (int p = 0; p < np; ++p)
    for (s64 i = cs[p]; i < ce[p]; ) {
        piece[k] = p;
        map[k] = i;
        if (t[i] == '\n') raw_newlines = 1;
        if (escaped && t[i] == '\\' && i + 1 < ce[p]) {
            char c = t[i + 1];
            if (c == 'x' && i + 3 < ce[p] && isxdigit((unsigned char)t[i + 2]) &&
                isxdigit((unsigned char)t[i + 3])) {
                char hex[3] = { t[i + 2], t[i + 3], 0 };
                u[k++] = (char)strtol(hex, NULL, 16);
                i += 4;
                continue;
            }
            u[k++] = c == 'n' ? '\n' : c == 't' ? '\t' : c == 'r' ? '\r' :
                     c == '0' ? '\0' : c;
            i += 2;
            continue;
        }
        u[k++] = t[i++];
    }
    map[k] = ce[np - 1];
    piece[k] = np - 1;
    u[k] = '\0';
    if (!migLooksLikeCode(u)) {
        migProseString(outer, cs, ce, np, u);
        return;
    }
    char tmpl[] = "/tmp/ktm-convert-XXXXXX.ktm";
    int fd = mkstemps(tmpl, 4);
    if (fd < 0) return;
    FILE *w = fdopen(fd, "w");
    fwrite(u, 1, k, w);
    fclose(w);
    MigFile *f = migFileNew(strdup(tmpl));
    Migrate one = { &f, 1, NULL };
    fflush(stderr);
    int saved = dup(2), null = open("/dev/null", O_WRONLY);
    dup2(null, 2);
    int errs = migParse(&one, f->path, install_dir);
    fflush(stderr);
    dup2(saved, 2);
    close(saved);
    close(null);
    int quotes = 0;
    if (errs > 0 || !f->base) {
        f = migFileNew(strdup(tmpl));      /* text only */
        /* An apostrophe here is a shell quote (`printf 'U0 Main()...'`)
         * as often as a character constant: read it as neither */
        for (s64 q = 0; q < f->len; ++q) {
            if (f->text[q] == '\'') { f->text[q] = '\x01'; quotes = 1; }
            /* and a `\n` still escaped for the shell ends a line, or
             * `;\nI64 F();` would read as the word nI64 */
            if (f->text[q] == '\\' && q + 1 < f->len && f->text[q + 1] == 'n') {
                f->text[q] = '\x02';
                f->text[q + 1] = '\n';
                quotes = 1;
            }
        }
        f->base = f->text;
        vecPush(f->skipped, (void *)(intptr_t)0);
        vecPush(f->skipped, (void *)(intptr_t)f->len);
        if (strstr(f->text, "Main(")) f->has_main = 1;
        (*n_text)++;
    }
    unlink(tmpl);
    (*n)++;
    migConvert(f, install_dir, 0);
    Vec *eff = migEffective(f);
    /* Backwards: inserts at one offset are applied newest first */
    for (u64 i = eff->size; i-- > 0; ) {
        MigEdit *e = eff->entries[i];
        /* an edit across the gap between two literals can't be placed */
        if (e->end > e->start && piece[e->start] != piece[e->end - 1]) continue;
        char *text = migEditText(f, e);
        if (quotes) {
            text = strdup(text);
            for (char *q = text; *q; ++q) {
                if (*q == '\x01') *q = '\'';
                if (*q == '\x02') { *q = '\\'; if (q[1] == '\n') *++q = 'n'; }
            }
        }
        s64 to = map[e->end];
        if (e->end > e->start && piece[e->end] != piece[e->end - 1]) {
            to = ce[piece[e->end - 1]];    /* up to the closing quote */
        }
        migAdd(outer, map[e->start], to,
               escaped ? migEscape(text, raw_newlines) : text, "string code");
    }
}

/* ------------------------------------------------------------------ */
/* Driver */

static void migSkipped(char *from, char *to) {
    if (!mig_active || !from || !to) return;
    for (int i = 0; i < mig_active->n_files; ++i) {
        MigFile *f = mig_active->files[i];
        if (f->base && from >= f->base && to <= f->base + f->len && to > from) {
            vecPush(f->skipped, (void *)(intptr_t)(from - f->base));
            vecPush(f->skipped, (void *)(intptr_t)(to - f->base));
        }
    }
}

static void migDefineFolded(char *at, char *name, Lexeme *value) {
    if (!mig_active || !at) return;
    const char *type = NULL;
    if (value->tk_type == TK_I64) type = value->isu64 ? "u64" : "i64";
    else if (value->tk_type == TK_F64) type = "f64";
    else if (value->tk_type == TK_CHAR_CONST) type = "i64";
    if (!type) return;
    for (int i = 0; i < mig_active->n_files; ++i) {
        MigFile *f = mig_active->files[i];
        if (!f->base || at < f->base || at > f->base + f->len) continue;
        /* back to the line that defines `name` */
        s64 k = at - f->base;
        s64 nlen = strlen(name);
        while (k > 0) {
            s64 ls = k - 1;
            while (ls > 0 && f->text[ls - 1] != '\n') ls--;
            s64 j = ls;
            while (j < f->len && (f->text[j] == ' ' || f->text[j] == '\t')) j++;
            if (!strncmp(f->text + j, "#define", 7)) {
                j += 7;
                while (j < f->len && (f->text[j] == ' ' || f->text[j] == '\t')) j++;
                if (!strncmp(f->text + j, name, nlen) && !migIsIdent(f->text[j + nlen])) {
                    MigDefine *d = (MigDefine *)malloc(sizeof(MigDefine));
                    d->line = ls;
                    d->type = type;
                    vecPush(f->defines, d);
                    return;
                }
            }
            k = ls;
        }
    }
}

static void migFilePushed(LexFile *lf) {
    if (!mig_active) return;
    char real[PATH_MAX];
    if (!realpath(lf->filename->data, real)) return;
    for (int i = 0; i < mig_active->n_files; ++i) {
        MigFile *f = mig_active->files[i];
        if (!f->real || strcmp(f->real, real)) continue;
        int same = (s64)lf->src->len == f->len && !memcmp(lf->src->data, f->text, f->len);
        if (!f->base) {
            /* not what we read: leave it alone */
            if (same) f->base = lf->src->data;
        } else if (same && lf->src->data != f->base && f->n_alt < 4) {
            f->alt_base[f->n_alt++] = lf->src->data;
        }
    }
}

static int migParse(Migrate *m, char *root, char *install_dir) {
    CliArgs args;
    cliArgsInit(&args);
    Cctrl *cc = cctrlNew(args.target);
    cc->install_dir = install_dir;
    cc->migrate = m;
    cctrlAddDefine(cc, "__KTM_AOT__");

    Lexer *l = (Lexer *)calloc(1, sizeof(Lexer));
    AoStr *builtin_path = aoStrPrintf("%s/include/std.ktmh", install_dir);
    /* Converting the library's header itself: parse with that copy */
    for (int i = 0; i < m->n_files; ++i) {
        char *base = strrchr(m->files[i]->path, '/');
        base = base ? base + 1 : m->files[i]->path;
        if (!strcmp(base, "std.ktmh")) {
            builtin_path = aoStrDupRaw(m->files[i]->path, strlen(m->files[i]->path));
        }
    }
    char *root_dir = mprintf("%s/include/", install_dir);
    mig_active = m;
    lex_file_pushed_hook = migFilePushed;
    lex_skip_hook = migSkipped;
    lex_define_hook = migDefineFolded;
    lexInit(l, NULL, CCF_PRE_PROC);
    l->lineno = 1;
    lexSetBuiltinRoot(l, root_dir);
    lexPushFile(l, aoStrDupRaw(root, strlen(root)));
    lexPushFile(l, builtin_path);
    cctrlInitParse(cc, l);
    parseToAst(cc);
    int n_errors = cctrlDiagFlush(cc);
    lex_file_pushed_hook = NULL;
    lex_skip_hook = NULL;
    lex_define_hook = NULL;
    return n_errors;
}

static char *migReadFile(char *path, s64 *_len) {
    FILE *fp = fopen(path, "rb");
    if (!fp) return NULL;
    size_t cap = 8192, len = 0, n;
    char *buf = malloc(cap + 1);
    while ((n = fread(buf + len, 1, cap - len, fp)) > 0) {
        len += n;
        if (len == cap) {
            cap *= 2;
            buf = realloc(buf, cap + 1);
        }
    }
    fclose(fp);
    buf[len] = 0;
    *_len = (s64)len;
    return buf;
}

static MigFile *migFileNew(char *path) {
    MigFile *f = (MigFile *)calloc(1, sizeof(MigFile));
    f->path = path;
    char real[PATH_MAX];
    if (realpath(path, real)) f->real = strdup(real);
    f->text = migReadFile(path, &f->len);
    f->edits = vecNew(&vec_unsigned_long_type);
    f->items = vecNew(&vec_unsigned_long_type);
    f->main_calls = vecNew(&vec_unsigned_long_type);
    f->skipped = vecNew(&vec_unsigned_long_type);
    f->catches = vecNew(&vec_unsigned_long_type);
    f->defines = vecNew(&vec_unsigned_long_type);
    f->closers = vecNew(&vec_unsigned_long_type);
    f->imports = setNew(16, &set_cstring_type);
    f->seen = setNew(64, &set_cstring_type);
    f->notes = aoStrNew();
    f->item_start = -1;
    f->main_name = f->main_lbrace = f->main_item = -1;
    return f;
}

/* In inline <code>, the old keywords and Main are code: `class` is struct */
static void migInlineKeywords(MigFile *f, s64 s, s64 e) {
    char *t = f->text;
    for (s64 i = s; i < e; ) {
        if (!migIsIdent(t[i])) { i++; continue; }
        s64 w = i;
        while (i < e && migIsIdent(t[i])) i++;
        if (w > 0 && migIsIdent(t[w - 1])) continue;
        const char *to = NULL;
        if (i - w == 5 && !memcmp(t + w, "class", 5)) to = "struct";
        else if (i - w == 6 && !memcmp(t + w, "public", 6)) to = "pub";
        else if (i - w == 4 && !memcmp(t + w, "Main", 4)) to = "main";
        if (to) migAdd(f, w, i, to, "inline code");
    }
}

/* A docs page: the code of each <pre> block is converted (as a program
 * when it compiles, else from its text); inline <code> gets the new type
 * names and literals. The prose around it is left to a person. */
static void migConvertHtml(MigFile *f, char *install_dir) {
    char *t = f->text;
    int n = 0, n_text = 0;
    for (s64 i = 0; i < f->len; ++i) {
        if (!strncmp(t + i, "<pre", 4) && !migIsIdent(t[i + 4])) {
            s64 open_end = i;
            while (open_end < f->len && t[open_end] != '>') open_end++;
            s64 cs = open_end + 1;
            if (!strncmp(t + cs, "<code", 5)) {
                while (cs < f->len && t[cs] != '>') cs++;
                cs++;
            }
            char *close = strstr(t + cs, "</pre>");
            if (!close) break;
            s64 ce = close - t;
            if (ce >= cs + 7 && !strncmp(t + ce - 7, "</code>", 7)) ce -= 7;
            int is_output = 0;
            for (s64 k = i; k < open_end; ++k) {
                if (!strncmp(t + k, "class=\"output\"", 14)) is_output = 1;
            }
            if (!is_output) migSnippetAt(f, &cs, &ce, 1, install_dir, &n, &n_text, 0);
            i = close - t + 5;
            continue;
        }
        if (!strncmp(t + i, "<code>", 6)) {
            char *close = strstr(t + i + 6, "</code>");
            if (!close) break;
            migProseWords(f, i + 6, close - t, 1, "inline code");
            migInlineKeywords(f, i + 6, close - t);
            i = close - t + 6;
        }
    }
    if (n) {
        migNote(f, -1, "converted %d code block(s)%s", n,
                n_text ? mprintf(" (%d from their text only: fragments, check them)", n_text) : "");
    }
    migSort(f);
}

/* `#define TRUE 1` and friends: true, false and null are keywords now */
/* The text of the line [i, le) without its leading and trailing blanks
 * equals `want` */
static int migLineIs(const char *t, s64 i, s64 le, const char *want) {
    while (i < le && (t[i] == ' ' || t[i] == '\t')) i++;
    while (le > i && isspace((unsigned char)t[le - 1])) le--;
    return (s64)strlen(want) == le - i && !memcmp(t + i, want, le - i);
}

static void migDropLiteralDefines(MigFile *f) {
    char *t = f->text;
    s64 prev = -1, prev2 = -1;  /* starts of the two lines before */
    for (s64 i = 0; i < f->len; ) {
        s64 le = i;
        while (le < f->len && t[le] != '\n') le++;
        s64 j = i;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        if (le - j > 7 && !memcmp(t + j, "#define", 7)) {
            s64 w = j + 7;
            while (w < le && (t[w] == ' ' || t[w] == '\t')) w++;
            s64 we = w;
            while (we < le && migIsIdent(t[we])) we++;
            if ((we - w == 4 && (!memcmp(t + w, "TRUE", 4) || !memcmp(t + w, "NULL", 4))) ||
                (we - w == 5 && !memcmp(t + w, "FALSE", 5))) {
                s64 from = i, to = le < f->len ? le + 1 : le;
                /* `#ifndef TRUE` / `#define TRUE 1` / `#endif`: all of it,
                 * and the blank line after it when one comes before it */
                char *guard = mprintf("#ifndef %.*s", (int)(we - w), t + w);
                s64 ne = to;
                while (ne < f->len && t[ne] != '\n') ne++;
                if (prev >= 0 && migLineIs(t, prev, i - 1, guard) &&
                    to < f->len && migLineIs(t, to, ne, "#endif")) {
                    from = prev;
                    to = ne < f->len ? ne + 1 : ne;
                    s64 be = to;
                    while (be < f->len && t[be] != '\n') be++;
                    if (prev2 >= 0 && migLineIs(t, prev2, prev - 1, "") &&
                        be < f->len && migLineIs(t, to, be, "")) to = be + 1;
                }
                migAdd(f, from, to, "", "literal");
            }
        }
        prev2 = prev;
        prev = i;
        i = le + 1;
    }
}

/* ------------------------------------------------------------------ */
/* Exceptions: `throw('Neg')` -> `throw(Err.neg)` with a file-local
 * `enum Err` (every character constant with a thrown code's text too),
 * and `Fs->except_ch` in a catch -> `e.code` with `catch (Error e)` */

typedef struct { Vec *codes; Vec *names; } MigThrowCtx;

/* The member name for the code 'BlkDev': blkDev (`_` added to a keyword) */
static char *migErrMember(const char *t, s64 len) {
    static const char *kw[] = {"if", "else", "for", "while", "do", "switch", "case",
        "default", "break", "continue", "return", "goto", "struct", "union", "enum",
        "const", "static", "true", "false", "null", "fn", "self", "defer", "try",
        "catch", "throw", "import", "module", "pub", "private", "auto", "sizeof"};
    char *m = mprintf("%.*s", (int)len, t);
    m[0] = tolower((unsigned char)m[0]);
    for (u64 k = 0; k < sizeof(kw) / sizeof(kw[0]); ++k) {
        if (!strcmp(m, kw[k])) return mprintf("%s_", m);
    }
    return m;
}

/* A character constant of identifier characters only, as codes are */
static int migIsCodeConst(const char *t, s64 at, s64 end, s64 *_close) {
    if (t[at] != '\'') return 0;
    s64 j = at + 1;
    while (j < end && migIsIdent(t[j])) j++;
    if (j == at + 1 || j >= end || t[j] != '\'' || !isalpha((unsigned char)t[at + 1])) return 0;
    *_close = j;
    return 1;
}

static void migThrowWord(MigFile *f, s64 at, s64 len, void *ctx) {
    MigThrowCtx *x = ctx;
    char *t = f->text;
    if (len != 5 || memcmp(t + at, "throw", 5)) return;
    s64 j = at + 5;
    while (j < f->len && isspace((unsigned char)t[j])) j++;
    if (j >= f->len || t[j] != '(') return;
    j++;
    while (j < f->len && isspace((unsigned char)t[j])) j++;
    s64 close;
    if (!migIsCodeConst(t, j, f->len, &close)) return;
    char *code = mprintf("%.*s", (int)(close - j - 1), t + j + 1);
    for (u64 k = 0; k < x->codes->size; ++k) {
        if (!strcmp(x->codes->entries[k], code)) return;
    }
    vecPush(x->codes, code);
    vecPush(x->names, migErrMember(code, strlen(code)));
}

/* The end of the file's header: its comments, then its `module` and
 * `import` lines (and #includes) at the top; 0 when it has none */
static s64 migHeaderEnd(MigFile *f) {
    char *t = f->text;
    s64 i = 0, at = 0;
    while (i < f->len) {
        while (i < f->len && isspace((unsigned char)t[i])) i++;
        if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '/') {
            while (i < f->len && t[i] != '\n') i++;
            continue;
        }
        if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '*') {
            i += 2;
            while (i + 1 < f->len && !(t[i] == '*' && t[i + 1] == '/')) i++;
            i += 2;
            continue;
        }
        if ((!strncmp(t + i, "module", 6) || !strncmp(t + i, "import", 6)) &&
            i + 6 < f->len && isspace((unsigned char)t[i + 6])) {
            while (i < f->len && t[i] != '\n') i++;
            at = i < f->len ? i + 1 : i;
            continue;
        }
        break;
    }
    return at;
}

/* Where `enum Err` and `import` lines go: after the #include lines, and
 * after the `module`/`import` lines at the top */
static s64 migEnumAt(MigFile *f) {
    char *t = f->text;
    s64 at = migHeaderEnd(f);
    for (s64 i = 0; i < f->len; ) {
        s64 le = i;
        while (le < f->len && t[le] != '\n') le++;
        s64 j = i;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        if (le - j >= 8 && !memcmp(t + j, "#include", 8)) {
            s64 after = le < f->len ? le + 1 : le;
            if (after > at) at = after;
        }
        i = le + 1;
    }
    if (at > 0) return at;
    /* no #include: after the comment at the top of the file */
    s64 i = 0;
    for (;;) {
        while (i < f->len && (t[i] == ' ' || t[i] == '\t' || t[i] == '\n')) i++;
        if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '/') {
            while (i < f->len && t[i] != '\n') i++;
        } else if (i + 1 < f->len && t[i] == '/' && t[i + 1] == '*') {
            i += 2;
            while (i + 1 < f->len && !(t[i] == '*' && t[i + 1] == '/')) i++;
            i += 2;
        } else {
            break;
        }
    }
    /* the start of the first line of code */
    while (i > 0 && t[i - 1] != '\n') i--;
    return -i - 1;
}

/* A line (`import ...;`, `enum Err ...;`) at the top: after the #include
 * lines, or before the first code after the file's opening comment */
static void migTopInsert(MigFile *f, const char *line, const char *kind) {
    s64 at = migEnumAt(f);
    if (at > 0 && at == migHeaderEnd(f)) {
        /* under `module x;` / the imports: right after them, then a
         * blank line before the code if there was none */
        int blank = at < f->len && f->text[at] == '\n';
        migAdd(f, at, at, mprintf("%s%s", line, blank ? "" : "\n"), kind);
    } else if (at > 0) {
        migAdd(f, at, at, mprintf("\n%s", line), kind);
    } else {
        at = -at - 1;
        migAdd(f, at, at, mprintf("%s\n", line), kind);
    }
}

/* Is the identifier `name` used in [s, e)? */
static int migUsesWord(MigFile *f, s64 s, s64 e, const char *name) {
    s64 n = strlen(name);
    for (s64 i = s; i + n <= e; ++i) {
        if (!memcmp(f->text + i, name, n) && (i == 0 || !migIsIdent(f->text[i - 1])) &&
            !migIsIdent(f->text[i + n])) return 1;
    }
    return 0;
}

static void migExceptChWord(MigFile *f, s64 at, s64 len, void *ctx) {
    (void)ctx;
    char *t = f->text;
    if (len != 2 || memcmp(t + at, "Fs", 2)) return;
    s64 j = at + 2;
    if (j + 1 < f->len && t[j] == '(' && t[j + 1] == ')') j += 2;
    if (j + 11 > f->len || memcmp(t + j, "->except_ch", 11) || migIsIdent(t[j + 11])) return;
    s64 end = j + 11;
    MigCatch *in = NULL;
    for (u64 k = 0; k < f->catches->size; ++k) {
        MigCatch *c = f->catches->entries[k];
        if (c->kw_end <= at && at < c->end && (!in || c->kw_end > in->kw_end)) in = c;
    }
    if (!in) {
        migNote(f, at, "can't convert Fs->except_ch outside a catch: use catch (Error e) and e.code");
        return;
    }
    if (!in->name) in->name = migUsesWord(f, in->kw_end, in->end, "e") ? "err" : "e";
    migAdd(f, at, end, mprintf("%s.code", in->name), "error");
}

static void migExceptions(MigFile *f) {
    MigThrowCtx x = { vecNew(&vec_unsigned_long_type), vecNew(&vec_unsigned_long_type) };
    migScanWords(f, f->text, 0, f->len, migThrowWord, &x);
    migScanWords(f, f->text, 0, f->len, migExceptChWord, NULL);
    for (u64 k = 0; k < f->catches->size; ++k) {
        MigCatch *c = f->catches->entries[k];
        if (c->name) migAdd(f, c->kw_end, c->kw_end, mprintf(" (Error %s)", c->name), "error");
    }
    if (x.codes->size == 0) return;
    /* every character constant with a code's text: the throws and what
     * they are compared with */
    char *t = f->text;
    for (s64 i = 0; i < f->len; ) {
        if (t[i] == '"') { i = migQuoteEnd(t, i, f->len) + 1; continue; }
        if (t[i] == '/' && i + 1 < f->len && t[i + 1] == '/') { while (i < f->len && t[i] != '\n') i++; continue; }
        if (t[i] == '/' && i + 1 < f->len && t[i + 1] == '*') {
            i += 2;
            while (i + 1 < f->len && !(t[i] == '*' && t[i + 1] == '/')) i++;
            i += 2;
            continue;
        }
        s64 close;
        if (t[i] == '\'' && migIsCodeConst(t, i, f->len, &close)) {
            for (u64 k = 0; k < x.codes->size; ++k) {
                char *code = x.codes->entries[k];
                if ((s64)strlen(code) == close - i - 1 && !memcmp(t + i + 1, code, close - i - 1)) {
                    migAdd(f, i, close + 1, mprintf("Err.%s", (char *)x.names->entries[k]), "error");
                }
            }
            i = close + 1;
            continue;
        }
        if (t[i] == '\'') { i = migQuoteEnd(t, i, f->len) + 1; continue; }
        i++;
    }
    AoStr *decl = aoStrNew();
    aoStrCat(decl, "enum Err { ");
    for (u64 k = 0; k < x.names->size; ++k) {
        aoStrCatPrintf(decl, "%s%s", k ? ", " : "", (char *)x.names->entries[k]);
    }
    aoStrCat(decl, " };\n");
    migTopInsert(f, decl->data, "error");
}

/* ------------------------------------------------------------------ */
/* `#define MAX 64` -> `const i64 MAX = 64;` for object-like defines whose
 * value folds to an integer or float: literals, operators, parentheses
 * and names converted before. Not when the name is used by the
 * preprocessor (#if, #ifdef, defined()), #undef'd or defined twice, nor
 * in a header (.ktmh: its macros move into the modules). */

static int migPreprocUses(MigFile *f, const char *name, s64 nlen) {
    char *t = f->text;
    int defs = 0;
    for (s64 i = 0; i < f->len; ) {
        s64 le = i;
        /* a directive continued with `\` goes on to the next line */
        while (le < f->len && (t[le] != '\n' || (le > i && t[le - 1] == '\\'))) le++;
        s64 j = i;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        if (j < le && t[j] == '#') {
            j++;
            while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
            s64 w = j;
            while (j < le && migIsIdent(t[j])) j++;
            int is_define = j - w == 6 && !memcmp(t + w, "define", 6);
            s64 k = j;
            while (k < le && (t[k] == ' ' || t[k] == '\t')) k++;
            s64 nw = k;
            while (k < le && migIsIdent(t[k])) k++;
            int names_it = k - nw == nlen && !memcmp(t + nw, name, nlen);
            if (is_define && names_it) defs++;
            else if (!is_define) {
                /* any other directive that mentions it */
                for (s64 q = j; q + nlen <= le; ++q) {
                    if (!memcmp(t + q, name, nlen) && !migIsIdent(t[q - 1]) &&
                        (q + nlen == le || !migIsIdent(t[q + nlen]))) return 1;
                }
            }
        }
        i = le + 1;
    }
    return defs != 1;
}

static void migDefines(MigFile *f) {
    s64 plen = strlen(f->path);
    if (plen > 5 && !strcmp(f->path + plen - 5, ".ktmh")) return;
    char *t = f->text;
    for (u64 di = 0; di < f->defines->size; ++di) {
        MigDefine *d = f->defines->entries[di];
        s64 le = d->line;
        while (le < f->len && t[le] != '\n') le++;
        if (le > d->line && t[le - 1] == '\\') continue;      /* continued line */
        s64 j = d->line;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        s64 line_start = j;
        j += 7;
        while (j < le && (t[j] == ' ' || t[j] == '\t')) j++;
        s64 nw = j;
        while (j < le && migIsIdent(t[j])) j++;
        s64 nlen = j - nw;
        /* the value: up to a comment or the line's end */
        s64 vs = j, ve = le;
        for (s64 k = j; k < le; ++k) {
            if (t[k] == '"' || t[k] == '\'') { k = migQuoteEnd(t, k, le); continue; }
            if (t[k] == '/' && k + 1 < le && (t[k + 1] == '/' || t[k + 1] == '*')) { ve = k; break; }
        }
        while (vs < ve && (t[vs] == ' ' || t[vs] == '\t')) vs++;
        while (ve > vs && (t[ve - 1] == ' ' || t[ve - 1] == '\t')) ve--;
        if (vs >= ve) continue;
        char *name = mprintf("%.*s", (int)nlen, t + nw);
        if (migPreprocUses(f, name, nlen)) continue;
        /* pub when other modules use it: everything in libc, and a
         * constant the map puts in this file's module */
        const char *mod = migFileModule(f);
        MigName *mn = mig_map ? mapGet(mig_map, name) : NULL;
        int own = *mod && mn && !strcmp(mn->module, mod);
        int pub = *mod && (!strcmp(mod, "libc") || own);
        /* the map's name in its module (CSV_FIELD is csv's FIELD) */
        const char *cname = own && strcmp(mn->kind, "c") ? mn->name : name;
        migAddSegs(f, line_start, ve, "const", migLit(mprintf("%sconst %s %s = ", pub ? "pub " : "",
                                                              d->type, cname)),
                   migRange(vs, ve), migLit(";"), NULL);
    }
}

/* `import a, b;` for the modules the file uses (--map) */
static void migImports(MigFile *f) {
    if (!f->imports->size) return;
    Vec *names = vecNew(&vec_unsigned_long_type);
    SetIter it;
    setIterInit(f->imports, &it);
    char *m;
    while ((m = setNext(&it)) != NULL) vecPush(names, m);
    for (u64 i = 0; i < names->size; ++i)
        for (u64 j = i + 1; j < names->size; ++j)
            if (strcmp(names->entries[j], names->entries[i]) < 0) {
                void *x = names->entries[i]; names->entries[i] = names->entries[j]; names->entries[j] = x;
            }
    AoStr *line = aoStrNew();
    aoStrCat(line, "import ");
    for (u64 i = 0; i < names->size; ++i) aoStrCatPrintf(line, "%s%s", i ? ", " : "", (char *)names->entries[i]);
    aoStrCat(line, ";\n");
    migTopInsert(f, line->data, "import");
}

/* Every pass but writing: the edits of f, sorted */
static int migConvert(MigFile *f, char *install_dir, int outer) {
    /* Code at file scope that calls Main: Main stays a function and a new
     * main runs that code */
    for (u64 c = 0; c < f->main_calls->size; ++c) {
        s64 at = (s64)(intptr_t)f->main_calls->entries[c];
        for (u64 k = 0; k < f->items->size; ++k) {
            MigItem *it = f->items->entries[k];
            if (at >= it->start && at < it->end) f->top_calls_main = 1;
        }
    }
    /* body `}`s: reported innermost first; at one offset the newest
     * insert comes first, so the outermost is added first */
    for (u64 k = f->closers->size; k-- > 0; ) {
        MigEdit *c = f->closers->entries[k];
        c->seq = f->seq++;
        vecPush(f->edits, c);
    }
    migScanWords(f, f->text, 0, f->len, migWordEdit, NULL);
    migExceptions(f);
    migDefines(f);
    migDropLiteralDefines(f);
    migCommentsAndStrings(f, install_dir, outer);
    migDefineCasts(f);
    migSkippedCasts(f);
    /* after every rewrite that can use a module (skipped #if text too) */
    migImports(f);
    migSort(f);
    migMoveItems(f);
    migMainType(f);
    migSort(f);
    return 0;
}

static void migUsage(void) {
    fprintf(stderr,
        "usage: ktm-convert [--install-dir=DIR] [--check] [--stdout] [--root FILE] FILE...\n"
        "  Rewrites old syntax (U0/I64/Bool, TRUE/FALSE/NULL, class, public,\n"
        "  postfix casts, print statements, calls without parentheses, Main,\n"
        "  code at file scope) to the new syntax, in place.\n"
        "  --check     list what would change; exit 1 if anything would\n"
        "  --stdout    print the result instead of writing the files\n"
        "  --report    the dry run's per-file counts and notes (see dry-run.sh)\n"
        "  --map TSV   also move stdlib names into modules (stdlib-modules.tsv)\n"
        "  --names     rename to the naming rule (camelCase functions/variables)\n"
        "  --layout    `->` to `.`, braces on the header's line, 4-space indents (last)\n"
        "  --renames TSV  the stdlib's own renames (renames.tsv, renames-locals.tsv)\n"
        "  --root FILE parse FILE (e.g. a library's all.ktm) and rewrite the\n"
        "              listed files it includes\n");
}

/* The kinds of edit on each line, for --check */
static void migListChanges(MigFile *f) {
    int last_line = -1;
    AoStr *kinds = aoStrNew();
    for (u64 i = 0; i <= f->edits->size; ++i) {
        MigEdit *e = i < f->edits->size ? f->edits->entries[i] : NULL;
        int line = e ? migLine(f, e->start) : -2;
        if (line != last_line) {
            if (last_line >= 0) printf("%s:%d: %s\n", f->path, last_line, kinds->data);
            kinds = aoStrNew();
            last_line = line;
        }
        if (e && !strstr(kinds->data ? kinds->data : "", e->kind)) {
            if (kinds->len) aoStrCat(kinds, ", ");
            aoStrCat(kinds, e->kind);
        }
    }
}

static int strnstr_safe(const char *s, int len, const char *needle) {
    int n = strlen(needle);
    for (int i = 0; i + n <= len; ++i) {
        if (!memcmp(s + i, needle, n)) return 1;
    }
    return 0;
}

/* --report, for the dry run (dry-run.sh adds these up): one
 *   EDITS <path> <n> <kind>=<count>...
 * line per file, then a CANNOT line for each thing it couldn't convert or
 * that needs a look, and a NOTE line for each other note */
static void migReport(MigFile *f) {
    const char *kinds[64];
    int counts[64], nk = 0;
    for (u64 i = 0; i < f->edits->size; ++i) {
        MigEdit *e = f->edits->entries[i];
        int k = 0;
        while (k < nk && strcmp(kinds[k], e->kind)) k++;
        if (k == nk && nk < 64) { kinds[nk] = e->kind; counts[nk++] = 0; }
        if (k < nk) counts[k]++;
    }
    printf("EDITS %s %llu", f->path, (unsigned long long)f->edits->size);
    for (int k = 0; k < nk; ++k) {
        printf(" ");
        for (const char *c = kinds[k]; *c; ++c) putchar(*c == ' ' ? '_' : *c);
        printf("=%d", counts[k]);
    }
    printf("\n");
    char *line = f->notes->data;
    while (line && *line) {
        char *nl = strchr(line, '\n');
        int len = nl ? (int)(nl - line) : (int)strlen(line);
        int cannot = strnstr_safe(line, len, ": can't") || strnstr_safe(line, len, ": check:");
        printf("%s %.*s\n", cannot ? "CANNOT" : "NOTE", len, line);
        line = nl ? nl + 1 : NULL;
    }
}

int main(int argc, char **argv) {
    char *install_dir = INSTALL_PREFIX;
    /* the report's lines and the notes on stderr stay in order */
    setvbuf(stdout, NULL, _IOLBF, 0);
    convertMemoryInit();
    int check = 0, to_stdout = 0, report = 0, rc = 0;
    char *root = NULL;
    Migrate m = {0};
    m.files = (MigFile **)calloc(argc, sizeof(MigFile *));

    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--check")) check = 1;
        else if (!strcmp(argv[i], "--stdout")) to_stdout = 1;
        else if (!strcmp(argv[i], "--report")) report = 1;
        else if (!strcmp(argv[i], "--names")) mig_names = 1;
        else if (!strcmp(argv[i], "--layout")) mig_layout = 1;
        else if (!strcmp(argv[i], "--renames") && i + 1 < argc) {
            if (!migLoadRenames(argv[++i])) {
                fprintf(stderr, "ktm-convert: can't read %s\n", argv[i]);
                return 2;
            }
        }
        else if (!strcmp(argv[i], "--map") && i + 1 < argc) {
            if (!migLoadMap(argv[++i])) {
                fprintf(stderr, "ktm-convert: can't read the stdlib map %s\n", argv[i]);
                return 2;
            }
        }
        else if (!strcmp(argv[i], "--root") && i + 1 < argc) root = argv[++i];
        else if (!strncmp(argv[i], "--install-dir=", 14)) install_dir = argv[i] + 14;
        else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) { migUsage(); return 0; }
        else if (argv[i][0] == '-') { migUsage(); return 2; }
        else {
            MigFile *f = migFileNew(argv[i]);
            if (!f->text) {
                fprintf(stderr, "ktm-convert: can't read %s\n", argv[i]);
                rc = 2;
                continue;
            }
            m.files[m.n_files++] = f;
        }
    }
    if (m.n_files == 0) { migUsage(); return 2; }

    if (root) {
        if (migParse(&m, root, install_dir) > 0) {
            fprintf(stderr, "ktm-convert: %s doesn't compile; nothing was changed\n", root);
            return 2;
        }
    } else {
        Migrate one = {0};
        for (int i = 0; i < m.n_files; ++i) {
            s64 plen = strlen(m.files[i]->path);
            if (plen > 5 && !strcmp(m.files[i]->path + plen - 5, ".html")) {
                m.files[i]->base = m.files[i]->text;   /* not parsed */
                continue;
            }
            one.files = &m.files[i];
            one.n_files = 1;
            if (migParse(&one, m.files[i]->path, install_dir) > 0) {
                m.files[i]->failed = 1;
            }
        }
    }

    int changed = 0;
    for (int i = 0; i < m.n_files; ++i) {
        MigFile *f = m.files[i];
        if (f->failed) {
            fprintf(stderr, "ktm-convert: %s doesn't compile; it wasn't changed\n", f->path);
            rc = 2;
            continue;
        }
        if (!f->base) {
            fprintf(stderr, "ktm-convert: %s isn't included by %s; it wasn't changed\n",
                    f->path, root);
            rc = 2;
            continue;
        }
        s64 plen = strlen(f->path);
        if (plen > 5 && !strcmp(f->path + plen - 5, ".html")) {
            migConvertHtml(f, install_dir);
        } else {
            migConvert(f, install_dir, 1);
        }
        char *out = migApply(f, 0, f->len, 1);
        /* layout last, on the whole result (not on docs pages: their
         * code blocks would need it per block) */
        if (mig_layout && !(strlen(f->path) > 5 && !strcmp(f->path + strlen(f->path) - 5, ".html"))) {
            out = migLayout(out);
        }
        migCheckLeftovers(f, out);
        int differs = strlen(out) != (size_t)f->len || memcmp(out, f->text, f->len);
        if (differs) changed++;
        if (report) {
            migReport(f);
            continue;
        } else if (check) {
            if (differs) migListChanges(f);
        } else if (to_stdout) {
            fputs(out, stdout);
        } else if (differs) {
            char *tmp = mprintf("%s.migrate-tmp", f->path);
            FILE *w = fopen(tmp, "w");
            if (!w || fwrite(out, 1, strlen(out), w) != strlen(out) || fclose(w) != 0 ||
                rename(tmp, f->path) != 0)
            {
                fprintf(stderr, "ktm-convert: can't write %s\n", f->path);
                unlink(tmp);
                rc = 2;
                continue;
            }
        }
        if (f->notes->len) fputs(f->notes->data, stderr);
    }
    if (report) {
        return rc;
    } else if (check && changed) {
        fprintf(stderr, "ktm-convert: %d of %d file(s) would change\n", changed, m.n_files);
        if (rc == 0) rc = 1;
    } else if (!to_stdout && !check) {
        fprintf(stderr, "ktm-convert: %d of %d file(s) changed\n", changed, m.n_files);
    }
    return rc;
}
