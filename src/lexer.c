#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pwd.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <dirent.h>
#include <assert.h>

#include "aostr.h"
#include "ast.h"
#include "arena.h"
#include "cctrl.h"
#include "containers.h"
#include "lexer.h"
#include "list.h"
#include "pkg.h"
#include "prslib.h"
#include "prsutil.h"
#include "util.h"
#include "version.h"

typedef struct {
    char *name;
    int kind;
} LexerType;

void vecLexemeToString(AoStr *buf, void *tok) {
    char *lexeme_str = lexemeToString(tok);
    aoStrCatPrintf(buf, "%s\n", lexeme_str);
}

int vecLexemeRelease(void *_tok) {
    (void)_tok;
    return 1;
}

VecType vec_lexeme_type = {
    .stringify = vecLexemeToString,
    .match     = NULL,
    .release   = vecLexemeRelease,
    .type_str  = "Lexeme *",
};

Vec *lexemeVecNew(void) {
    return vecNew(&vec_lexeme_type);
}

AoStr *mapLexerTypeToString(void *ltype) {
    LexerType *t = (LexerType *)ltype;
    return aoStrPrintf("\"%s\" %d", t->name, t->kind);
}

MapType map_cstring_builtin_lexer_type = {
    .match           = mapCStringEq,
    .hash            = mapCStringHash,
    .get_key_len     = mapCStringLen,
    .key_to_string   = mapCStringToString,
    .key_release     = NULL,
    .value_to_string = mapLexerTypeToString,
    .value_release   = NULL,
    .key_type        = "char *",
    .value_type      = "LexerType *",
};

/* prototypes */
int lexPreProcIf(Map *macro_defs, Lexer *l, const char *directive);

static Arena lexeme_arena;
static int lexeme_arena_init = 0;

void lexemeMemoryInit(void) {
    if (!lexeme_arena_init) {
        lexeme_arena_init = 1;
        arenaInit(&lexeme_arena, sizeof(Lexeme) * 1000);
    }
}

void lexemeMemoryRelease(void) {
    if (lexeme_arena_init) {
        lexeme_arena_init = 0;
        arenaClear(&lexeme_arena);
    }
}

void lexemeMemoryStats(void) {
    printf("Lexeme Arena:\n");
    arenaPrintStats(&lexeme_arena);
}

Lexeme *lexerAllocLexeme(void) {
    Lexeme *le = (Lexeme *)arenaAlloc(&lexeme_arena, sizeof(Lexeme));
    memset(le, 0, sizeof(Lexeme));
    return le;
}

char *lexerAllocateBuffer(u32 size) {
    return (char *)arenaAlloc(&lexeme_arena, size);
}

char *lexerReAllocBuffer(char *ptr, u32 old_size, u32 new_size) {
    (void)ptr;
    char *buffer = lexerAllocateBuffer(new_size);
    memcpy(buffer,ptr,old_size);
    return buffer;
}

/* Name, kind, size, issigned */
static LexerType lexer_types[] = {
    /* Ktm Types, this language is semi interoperable */
    {"U0",   KW_U0},
    {"Bool", KW_BOOL},
    {"I8",   KW_I8},
    {"U8",   KW_U8},
    {"I16",  KW_I16},
    {"U16",  KW_U16},
    {"I32",  KW_I32},
    {"U32",  KW_U32},
    {"I64",  KW_I64},
    {"U64",  KW_U64},
    {"F32",  KW_F32},
    {"F64",  KW_F64},

    {"auto", KW_AUTO},

    {"_extern", KW_ASM_EXTERN},
    {"extern",  KW_EXTERN},
    {"asm",     KW_ASM},

    {"switch",   KW_SWITCH},
    {"case",     KW_CASE},
    {"break",    KW_BREAK},
    {"continue", KW_CONTINUE},
    {"while",    KW_WHILE},
    {"do",       KW_DO},
    {"for",      KW_FOR},
    {"goto",     KW_GOTO},
    {"default",  KW_DEFAULT},
    {"return",   KW_RETURN},
    {"try",      KW_TRY},
    {"catch",    KW_CATCH},
    {"throw",    KW_THROW},
    {"defer",    KW_DEFER},
    {"reg",      KW_REG},
    {"noreg",    KW_NOREG},

    {"if",      KW_IF},
    {"else",    KW_ELSE},
    {"define",  KW_DEFINE},
    {"ifndef",  KW_IF_NDEF},
    {"ifdef",   KW_IF_DEF},
    {"elifdef", KW_ELIF_DEF},
    {"endif",   KW_ENDIF},
    {"elif",    KW_ELIF},
    {"defined", KW_DEFINED},
    {"undef",   KW_UNDEF},

    {"#if",      KW_PP_IF},
    {"#else",    KW_PP_ELSE},
    {"#define",  KW_PP_DEFINE},
    {"#ifndef",  KW_PP_IF_NDEF},
    {"#ifdef",   KW_PP_IF_DEF},
    {"#elifdef", KW_PP_ELIF_DEF},
    {"#endif",   KW_PP_ENDIF},
    {"#elif",    KW_PP_ELIF},
    {"#defined", KW_PP_DEFINED},
    {"#undef",   KW_PP_UNDEF},
    {"#error",   KW_PP_ERROR},
    {"#include", KW_PP_INCLUDE},
    {"#link",    KW_PP_LINK},
    {"#ifjit",   KW_PP_IF_JIT},
    {"#ifaot",   KW_PP_IF_AOT},

    {"sizeof",   KW_SIZEOF},
    {"alignof",  KW_ALIGNOF},
    {"typeof",   KW_TYPEOF},
    {"true",     KW_TRUE},
    {"false",    KW_FALSE},
    {"null",     KW_NULL},
    {"inline",   KW_INLINE},
    {"volatile", KW_VOLATILE},

    {"public",  KW_PUBLIC},
    {"pub",     KW_PUBLIC},     /* ktm syntax v0.2 */
    {"private", KW_PRIVATE},
    {"class",   KW_CLASS},
    {"struct",  KW_CLASS},      /* ktm syntax v0.2: the same as class */
    {"union",   KW_UNION},
    {"enum",    KW_ENUM},        /* ktm syntax v0.2 */

    {"static",  KW_STATIC},
    {"const",   KW_CONST},
};


#define isNum(ch) ((ch) >= '0' && (ch) <= '9')
#define isHex(ch) \
    (isNum(ch) || (ch >= 'a' && ch <= 'f') || (ch >= 'A' && ch <= 'F'))
#define isNumTerminator(ch) (!isNum(ch) && !isHex(ch) && ch != '.' && ch != 'x' \
        && ch != 'X' && ch != '\\' && ch != '_')

Lexeme *lexemeNew(char *start, int len) {
    Lexeme *le = lexerAllocLexeme();
    le->start = start;
    le->len = len;
    le->line = -1;
    le->col = 0;
    le->tk_type = -1;
    le->isu64 = 0;
    return le;
}

Lexeme *lexemeSentinal(void) {
    Lexeme *le = lexerAllocLexeme();
    le->start = "(-sentinal-)";
    le->len = 12;
    le->line = 1;
    le->col = 0;
    le->tk_type = -1;
    le->isu64 = 0;
    return le;
}

void lexemeAssignOp(Lexeme *le, char *start, int len, s64 op, int line) {
    le->tk_type = TK_PUNCT;
    le->line = line;
    /* Column is filled in by the `lex()` wrapper for tokens that
     * come from the main lexer loop; constructors used elsewhere
     * (e.g. macro expansion) leave it at 0 and the diagnostic
     * renderer falls back to its line-only path. */
    le->col = 0;
    le->start = start;
    le->len = len;
    le->i64 = op; /* instead of 'char'*/
}

Lexeme *lexemeTokNew(char *start, int len, int line, s64 ch) {
    Lexeme *copy = lexerAllocLexeme();
    copy->tk_type = TK_PUNCT;
    copy->start = start;
    copy->len = len;
    copy->line = line;
    copy->col = 0;
    copy->i64 = ch;
    return copy;
}

Lexeme *lexemeCopy(Lexeme *le) {
    Lexeme *copy = lexerAllocLexeme();
    memcpy(copy,le,sizeof(Lexeme));
    return copy;
}

Lexeme *lexemeNewOp(char *start, int len, s64 op, int line) {
    Lexeme *le = lexemeNew(start,len);
    lexemeAssignOp(le,start,len,op,line);
    return le;
}

static Cctrl *macro_proccessor = NULL;

/* Queue a user-visible lex diagnostic on the lexer's Cctrl; with no
 * Cctrl back-pointer (re-lexer, macro stub) print it, and for an error
 * exit, the loggerPanic-style fallback. It is reported at `line`:`col`,
 * `len` wide, or with `line` 0 at the token being lexed. A CCTRL_WARN
 * is an error under -Werror, like cctrlWarning. */
static void lexDiagVa(Lexer *l, int severity, s64 line, s64 col, s64 len,
                      const char *fmt, va_list ap)
{
    if (severity == CCTRL_WARN && l && l->cc &&
        (l->cc->flags & CCTRL_WERROR)) {
        severity = CCTRL_ERROR;
    }
    if (l && l->cc) {
        char *body = mprintVa((char *)fmt, ap, NULL);

        AoStr *bold = aoStrNew();
        aoStrCatColoured(bold, ESC_BOLD, body);

        if (line <= 0) {
            line = l->tok_start_line > 0 ? l->tok_start_line : l->lineno;
            col  = l->tok_start_col;
            len  = (l->start && l->ptr && l->ptr > l->start)
                   ? (s64)(l->ptr - l->start) : 1;
        }
        if (len < 1) len = 1;

        /* About where the lexer is, not the last token the parser took;
         * the rest of an import list is a file of its own whose lines
         * are its owner's, which has the text around them */
        LexFile *parser_file = l->cc->tok_file;
        LexFile *at_file = l->cur_file;
        while (at_file && at_file->import_owner) at_file = at_file->import_owner;
        l->cc->tok_file = at_file;
        AoStr *buf = cctrlCreateErrorLineAt(l->cc, line, col, len,
                                            bold->data, severity, NULL);
        aoStrRelease(bold);

        /* cctrlMakeDiag fills line/col from cctrlTokenPeek by
         * default; override with the lexer's view since peek will
         * be holding a stale token from before the broken lexeme. */
        CctrlDiagnostic *d = cctrlMakeDiag(l->cc, severity, buf, NULL);
        l->cc->tok_file = parser_file;
        d->line = (int)line;
        d->col = (int)col;
        d->end_line = (int)line;
        d->end_col = (int)(col + len);
        cctrlDiagPush(l->cc, d);
        return;
    }
    if (severity == CCTRL_WARN) {
        fprintf(stderr, "\033[0;35mWARNING: \033[0m");
        vfprintf(stderr, fmt, ap);
        fprintf(stderr, "\n");
        return;
    }
    fprintf(stderr, "\033[0;31mERROR: \033[0m");
    vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    exit(EXIT_FAILURE);
}

static void lexReportVa(Lexer *l, s64 line, s64 col, s64 len,
                        const char *fmt, va_list ap)
{
    lexDiagVa(l, CCTRL_ERROR, line, col, len, fmt, ap);
}

/* Report a lex error and keep lexing. For errors that leave the lexer
 * on a clean boundary: a directive whose line has been consumed, or a
 * bad token that has been stepped over and is handed back as a stand-in
 * (a 0 for a malformed number). The parser lexes ahead into a 16-token
 * ring, so a directive or token is often lexed while the parser is
 * still inside the previous declaration; lexRaise's longjmp would
 * abandon that declaration halfway (a class left registered without
 * its fields) and leave its tail to misparse. */
static void lexReport(Lexer *l, const char *fmt, ...) {
    /* Text in a skipped #if group is only scanned for directives; like
     * C, it reports nothing (a bad number, an unknown `#foo`) */
    if (l->flags & CCF_COND_SKIP) return;
    va_list ap;
    va_start(ap, fmt);
    lexReportVa(l, 0, 0, 0, fmt, ap);
    va_end(ap);
}

/* lexReport at an explicit position. A directive's errors are usually
 * found once its line has been read, when the token being lexed is the
 * newline at its end (one column past the line). */
static void lexReportAt(Lexer *l, s64 line, s64 col, s64 len,
                        const char *fmt, ...)
{
    if (l->flags & CCF_COND_SKIP) return;
    va_list ap;
    va_start(ap, fmt);
    lexReportVa(l, line, col, len, fmt, ap);
    va_end(ap);
}

/* A lex warning at the token being lexed; compilation carries on.
 * Quiet in a skipped #if group and in the CCF_PERMISSIVE re-lexer,
 * which sees the same text again to colour an error line. */
static void lexWarning(Lexer *l, const char *fmt, ...) {
    if (l->flags & (CCF_COND_SKIP|CCF_PERMISSIVE)) return;
    va_list ap;
    va_start(ap, fmt);
    lexDiagVa(l, CCTRL_WARN, 0, 0, 0, fmt, ap);
    va_end(ap);
}

/* Report a lex error and longjmp to the parser's recovery point. Only
 * for errors the lexer cannot step over. */
__noreturn static void lexRaise(Lexer *l, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    lexReportVa(l, 0, 0, 0, fmt, ap);
    va_end(ap);
    cctrlTerminate(l->cc);
}

void lexInit(Lexer *l, char *source, int flags) {
    l->ptr = source;
    l->line_start_ptr = source;
    l->cur_ch = -1;
    l->lineno = 1;
    l->cur_f64 = 0;
    l->cur_i64 = 0;
    l->cur_str = NULL;
    l->cur_strlen = 0;
    l->cur_file = NULL;
    l->ishex = 0;
    l->isu64 = 0;
    l->flags = flags;
    l->files = listNew();
    l->all_source = listNew();
    l->symbol_table = mapNew(32, &map_cstring_builtin_lexer_type);
    l->seen_files = setNew(32, &set_cstring_type);
    l->conds = NULL;
    l->cond_depth = 0;
    l->cond_cap = 0;
    l->modules = NULL;
    l->load_order = NULL;
    l->load_order_len = 0;
    l->load_order_cap = 0;
    l->modules_len = 0;
    l->modules_cap = 0;
    l->repl = 0;
    l->repl_imports = NULL;
    l->mod_depth = 0;
    l->program_root = NULL;
    l->building_module = NULL;
    l->prebuilding = 0;
    l->building_tree = NULL;
    l->test_dir = NULL;
    l->test_module_id = 0;
    l->source_for = NULL;
    l->source_arg = NULL;
    l->std_modules = NULL;
    /* Default: no diagnostic engine wired up; lex errors fall
     * back to loggerPanic. cctrlInitParse plugs the real Cctrl
     * in when this lexer is being driven by a real parse. */
    l->cc = NULL;
    if (macro_proccessor == NULL) {
        macro_proccessor = ccMacroProcessor(NULL);
    }

    if (!lexeme_arena_init) {
        lexemeMemoryInit();
    }

    /* XXX: create one symbol table for the whole application ;
     * hoist to 'compile.c'*/
    for (int i = 0; i < (int)static_size(lexer_types); ++i) {
        LexerType *bilt = &lexer_types[i]; 
        mapAddLen(l->symbol_table, bilt->name, strlen(bilt->name), bilt);
    }
}

void lexerRelease(Lexer *l) {
    if (!l) return;
    lexReleaseAllFiles(l);
    mapRelease(l->symbol_table);
    setRelease(l->seen_files);
    free(l->conds);
    free(l);
}

void lexSetBuiltinRoot(Lexer *l, char *root) {
    l->builtin_root = root;
}

/* Is the lexeme both of type TK_PUNCT and does 'ch' match */
int tokenPunctIs(Lexeme *tok, s64 ch) {
    return tok && (tok->tk_type == TK_PUNCT || tok->tk_type == TK_EOF) && tok->i64 == ch;
}

/* Is the token an identifier and does the string match */
int tokenIdentIs(Lexeme *tok, char *ident, int len) {
    return tok && tok->tk_type == TK_IDENT 
               && tok->len == len 
               && !memcmp(tok->start,ident,len);
}

char *lexemeTypeToString(int tk_type) {
    switch (tk_type) {
    case TK_IDENT:      return "identifier";
    case TK_PUNCT:      return "character";
    case TK_I64:        return "integer";
    case TK_F64:        return "float";
    case TK_EOF:        return "end of file";
    case TK_STR:        return "string";
    case TK_KEYWORD:    return "keyword";
    case TK_CHAR_CONST: return "character constant";
    }
    return "UNKNOWN";
}

/* This is for something that will play nicely with HTML or GraphViz*/
char *lexemePunctToEncodedString(s64 op) {
    static char buf[4];
    switch(op) {
    case '\\':               return "\\";
    case '\n':               return "\\n";
    case '\t':               return "\\t";
    case '\r':               return "\\r";
    case '\"':               return "&#34;";
    case '\'':               return "&#39;";
    case ' ':                return "&#32;";
    case '>':                return "&#62;";
    case '<':                return "&#60;";
    case '&':                return "&#38;";
    case '|':                return "&#124;";
    case ';':                return "&#59;";
    case '@':                return "&#64;";
    case '=':                return "&#61;";
    case '(':                return "&#40;";
    case ')':                return "&#41;";
    case '~':                return "&#126;";
    case '^':                return "&#94;";
    case '_':                return "&#65;";
    case '!':                return "&#33;";
    case '{':                return "&#123;";
    case '}':                return "&#125;";
    case '[':                return "&#91;";
    case ']':                return "&#93;";
    case '`':                return "&#96;";
    case '*':                return "&#42;";
    case '+':                return "&#43;";
    case '-':                return "&#45;";
    case '/':                return "&#47;";
    case '%':                return "&#37;";
    case '$':                return "&#36;";
    case '#':                return "&#35;";
    case ',':                return "&#44;";
    case '.':                return "&#46;";
    case '?':                return "&#63;";
    case ':':                return "&#48;";

    case TK_EQU_EQU:         return "&#61;&#61;";
    case TK_NOT_EQU:         return "&#33;&#61;";
    case TK_LESS_EQU:        return "&#60;&#61;";
    case TK_GREATER_EQU:     return "&#62;&#61;";      
    case TK_AND_AND:         return "&#38;&#38;";
    case TK_OR_OR:           return "&#124;&#124;";
    case TK_SHL:             return "&#60;&#60;";
    case TK_SHL_EQU:         return "&#60;&#60;&#61;";
    case TK_SHR:             return "&#62;&#62;";
    case TK_SHR_EQU:         return "&#62;&#62;&#61;";
    case TK_MUL_EQU:         return "&#42;&#61;";
    case TK_DIV_EQU:         return "&#47;&#61;";
    case TK_OR_EQU:          return "&#124;&#61;";
    case TK_XOR_EQU:         return "&#94;&#61;";
    case TK_AND_EQU:         return "&#38;&#61;";
    case TK_SUB_EQU:         return "&#45;&#61;";
    case TK_ADD_EQU:         return "&#43;&#61;";
    case TK_MOD_EQU:         return "&#37;&#61;";
    case TK_ELLIPSIS:        return "&#48;&#48;&#48;";
    case TK_ARROW:           return "&#45;&#62;";
    case TK_PRE_PLUS_PLUS:   return "&#43;&#43;";
    case TK_PLUS_PLUS:       return "&#43;&#43;";
    case TK_PRE_MINUS_MINUS: return "&#45;&#45;";
    case TK_MINUS_MINUS:     return "&#45;&#45;";
    default: {
        int len = snprintf(buf,sizeof(buf),"%c",(char)op);
        buf[len] = '\0';
        return buf;
    }
    }
}

/* Convert all of this to a massive lookup table */
char *lexemePunctToString(s64 op) {
    static char buf[4];
    switch (op) {
    case '\\':               return "\\";
    case '\n':               return "\\n";
    case '\t':               return "\\t";
    case '\r':               return "\\r";
    case '\"':               return "\\\"";
    case '\'':               return "\\\'";
    case '\0':               return "\\0'";
    case TK_AND_AND:         return "&&";
    case TK_OR_OR:           return "||";
    case TK_EQU_EQU:         return "==";
    case TK_NOT_EQU:         return "!=";
    case TK_LESS_EQU:        return "<=";
    case TK_GREATER_EQU:     return ">=";
    case TK_PLUS_PLUS:       return "++";
    case TK_MINUS_MINUS:     return "--";
    case TK_SHL:             return "<<";
    case TK_SHR:             return ">>";
    case TK_ARROW:           return "->";
    case TK_DBL_COLON:       return "::";
    case TK_ELLIPSIS:        return "...";
    case TK_SHL_EQU:         return "<<=";
    case TK_SHR_EQU:         return ">>=";
    case TK_MUL_EQU:         return "*=";
    case TK_DIV_EQU:         return "/=";
    case TK_OR_EQU:          return "|=";
    case TK_XOR_EQU:         return "^=";
    case TK_AND_EQU:         return "&=";
    case TK_SUB_EQU:         return "-=";
    case TK_ADD_EQU:         return "+=";
    case TK_MOD_EQU:         return "%=";
    case TK_PRE_PLUS_PLUS:   return "++";
    case TK_PRE_MINUS_MINUS: return "--";
    default: {
        int len = snprintf(buf,sizeof(buf),"%c",(char)op);
        buf[len] = '\0';
        return buf;
    }
    }
}

char *lexemePunctToStringWithFlags(s64 op, u64 flags) {
    if (flags & LEXEME_ENCODE_PUNCT) return lexemePunctToEncodedString(op);
    else                             return lexemePunctToString(op);
}

/* `tok` as written in the source, for a diagnostic: a string or a
 * character constant with its quotes (its start and len are what is
 * between them), anything else its text. */
char *lexemeAsWritten(Lexeme *tok) {
    if (tok->tk_type == TK_STR) {
        return mprintf("\"%.*s\"", tok->len, tok->start);
    }
    if (tok->tk_type == TK_CHAR_CONST) {
        return mprintf("'%.*s'", tok->len, tok->start);
    }
    return mprintf("%.*s", tok->len, tok->start);
}

AoStr *lexemeToAoStr(Lexeme *tok) {
    if (!tok) {
        return aoStrPrintf("(null)");
    }
    AoStr *str = aoStrNew();
    char *tmp;
    switch (tok->tk_type) {
        case TK_COMMENT: {
            aoStrCatPrintf(str,"TK_COMMENT    %.*s",tok->len,tok->start);
            return str;
        }
        case TK_IDENT:
            aoStrCatPrintf(str,"TK_IDENT      %.*s",tok->len,tok->start);
            return str;
        case TK_CHAR_CONST:
            aoStrCatPrintf(str,"TK_CHAR_CONST %x",tok->i64);
            return str;
        case TK_PUNCT: {
            tmp = lexemePunctToString(tok->i64);
            aoStrCatPrintf(str,"TK_PUNCT      %s", tmp);
            return str;
        }
        case TK_I64:
            aoStrCatPrintf(str,"TK_I64        %lld",tok->i64);
            return str;
        case TK_F64:
            aoStrCatPrintf(str,"TK_F64        %g",tok->f64);
            return str;
        case TK_STR:
            aoStrCatPrintf(str,"TK_STR        \"%.*s\"",tok->len,tok->start);
            return str;
        case TK_EOF:
            aoStrCatPrintf(str,"TK_EOF");
            return str;
        case TK_KEYWORD: {
            aoStrCatPrintf(str,"TK_KEYWORD    ");
            switch (tok->i64) {
                case KW_CLASS:       aoStrCatPrintf(str,"class");   break;
                case KW_UNION:       aoStrCatPrintf(str,"union");   break;
                case KW_ENUM:        aoStrCatPrintf(str,"enum");    break;
                case KW_U0:          aoStrCatPrintf(str,"U0");      break;
                case KW_BOOL:        aoStrCatPrintf(str,"Bool");    break;
                case KW_I8:          aoStrCatPrintf(str,"I8");      break;
                case KW_U8:          aoStrCatPrintf(str,"U8");      break;
                case KW_I16:         aoStrCatPrintf(str,"I16");     break;
                case KW_U16:         aoStrCatPrintf(str,"U16");     break;
                case KW_I32:         aoStrCatPrintf(str,"I32");     break;
                case KW_U32:         aoStrCatPrintf(str,"U32");     break;
                case KW_I64:         aoStrCatPrintf(str,"I64");     break;
                case KW_U64:         aoStrCatPrintf(str,"U64");     break;
                case KW_F32:         aoStrCatPrintf(str,"F32");     break;
                case KW_F64:         aoStrCatPrintf(str,"F64");     break;
                case KW_PUBLIC:      aoStrCatPrintf(str,"public");  break;
                case KW_DEFINE:      aoStrCatPrintf(str,"define");  break;
                case KW_SIZEOF:      aoStrCatPrintf(str,"sizeof");  break;
                case KW_ALIGNOF:     aoStrCatPrintf(str,"alignof"); break;
                case KW_TYPEOF:      aoStrCatPrintf(str,"typeof");  break;
                case KW_TRUE:        aoStrCatPrintf(str,"true");    break;
                case KW_FALSE:       aoStrCatPrintf(str,"false");   break;
                case KW_NULL:        aoStrCatPrintf(str,"null");    break;
                case KW_RETURN:      aoStrCatPrintf(str,"return");  break;
                case KW_TRY:         aoStrCatPrintf(str,"try");     break;
                case KW_CATCH:       aoStrCatPrintf(str,"catch");   break;
                case KW_THROW:       aoStrCatPrintf(str,"throw");   break;
                case KW_REG:         aoStrCatPrintf(str,"reg");     break;
                case KW_NOREG:       aoStrCatPrintf(str,"noreg");   break;
                case KW_SWITCH:      aoStrCatPrintf(str,"switch");  break;
                case KW_CASE:        aoStrCatPrintf(str,"case");    break;
                case KW_BREAK:       aoStrCatPrintf(str,"break");   break;
                case KW_CONTINUE:    aoStrCatPrintf(str,"continue"); break;
                case KW_ASM:         aoStrCatPrintf(str,"asm");     break;
                case KW_ASM_EXTERN:  aoStrCatPrintf(str,"_extern"); break;
                case KW_EXTERN:      aoStrCatPrintf(str,"extern");  break;
                case KW_PRIVATE:     aoStrCatPrintf(str,"private");  break;
                case KW_INLINE:      aoStrCatPrintf(str,"inline");  break;
                case KW_FOR:         aoStrCatPrintf(str,"for");     break;
                case KW_WHILE:       aoStrCatPrintf(str,"while");   break;
                case KW_VOLATILE:    aoStrCatPrintf(str,"volatile"); break;
                case KW_GOTO:        aoStrCatPrintf(str,"goto");    break;
                case KW_IF:          aoStrCatPrintf(str,"if");      break;
                case KW_ELSE:        aoStrCatPrintf(str,"else");    break;
                case KW_ELIF:        aoStrCatPrintf(str,"elif");    break;
                case KW_IF_NDEF:     aoStrCatPrintf(str,"ifndef");  break;
                case KW_IF_DEF:      aoStrCatPrintf(str,"ifdef");   break;
                case KW_ELIF_DEF:    aoStrCatPrintf(str,"elifdef");   break;
                case KW_ENDIF:       aoStrCatPrintf(str,"endif");   break;
                case KW_UNDEF:       aoStrCatPrintf(str,"undef");   break;
                case KW_AUTO:        aoStrCatPrintf(str,"auto");    break;
                case KW_DEFAULT:     aoStrCatPrintf(str,"default"); break;
                case KW_DO:          aoStrCatPrintf(str,"do");      break;
                case KW_STATIC:      aoStrCatPrintf(str,"static");  break;
                case KW_CONST:       aoStrCatPrintf(str,"const");   break;
                case KW_DEFER:       aoStrCatPrintf(str,"defer");   break;
                case KW_DEFINED:     aoStrCatPrintf(str,"defined"); break;

                case KW_PP_INCLUDE:  aoStrCatPrintf(str,"#include"); break;
                case KW_PP_IF:       aoStrCatPrintf(str,"#if"); break;   
                case KW_PP_ELSE:     aoStrCatPrintf(str,"#else"); break;   
                case KW_PP_DEFINE:   aoStrCatPrintf(str,"#define"); break;  
                case KW_PP_IF_NDEF:  aoStrCatPrintf(str,"#ifndef"); break; 
                case KW_PP_IF_DEF:   aoStrCatPrintf(str,"#ifdef"); break;
                case KW_PP_ELIF_DEF: aoStrCatPrintf(str,"#elifdef"); break;
                case KW_PP_ENDIF:    aoStrCatPrintf(str,"#endif"); break;   
                case KW_PP_ELIF:     aoStrCatPrintf(str,"#elif"); break;    
                case KW_PP_DEFINED:  aoStrCatPrintf(str,"#defined"); break; 
                case KW_PP_UNDEF:    aoStrCatPrintf(str,"#undef"); break;  
                case KW_PP_ERROR:    aoStrCatPrintf(str,"#error"); break;
                case KW_PP_LINK:     aoStrCatPrintf(str,"#link"); break;
                case KW_PP_IF_JIT:   aoStrCatPrintf(str,"#ifjit"); break;
                case KW_PP_IF_AOT:   aoStrCatPrintf(str,"#ifaot"); break;

                default:
                    loggerPanic("line %d: Keyword %.*s: is not defined\n",
                            tok->line,tok->len,tok->start);
            }
            return str;
        }
    }
    loggerPanic("line %d: Unexpected type %s |%.*s|\n",
            tok->line,lexemeTypeToString(tok->tk_type),tok->len,tok->start);
}

char *lexemeToString(Lexeme *tok) {
    return aoStrMove(lexemeToAoStr(tok));
}

/* Print one lexeme */
void lexemePrint(Lexeme *le) {
    if (le) {
        char *str = lexemeToString(le);
        printf("%d: %s\n", le->line, str);
      //  free(str);
    }
}

/* A lex warning at an explicit position */
static void lexWarningAt(Lexer *l, s64 line, s64 col, s64 len,
                         const char *fmt, ...)
{
    if (l->flags & (CCF_COND_SKIP|CCF_PERMISSIVE)) return;
    va_list ap;
    va_start(ap, fmt);
    lexDiagVa(l, CCTRL_WARN, line, col, len, fmt, ap);
    va_end(ap);
}

/* `file` has been read to its end: the imports it never used */
static void lexFileEnded(Lexer *l, LexFile *file) {
    if (!file || file->ended) return;
    file->ended = 1;
    if (LEX_PASS_QUIET(file->mod_pass)) return;
    for (LexImport *imp = file->imports; imp; imp = imp->next) {
        /* `import _ = m;`: imported for what it does when the program
         * starts (net.https gives net.http's client https://) */
        if (imp->prefix && imp->prefix->len == 1 && imp->prefix->data[0] == '_') continue;
        if (!imp->used) {
            lexWarningAt(l, imp->line, imp->col, imp->len,
                         imp->module->package ? "package \"%s\" is imported but not used"
                                              : "module %s is imported but not used",
                         imp->module->package ? imp->module->package
                                              : imp->module->path->data);
        }
    }
}

/* `m` has been lexed in full: one more in the load order */
static void lexModuleLoaded(Lexer *l, LexModule *m) {
    m->loaded = 1;
    if (l->load_order_len == l->load_order_cap) {
        l->load_order_cap = l->load_order_cap ? l->load_order_cap * 2 : 16;
        l->load_order = realloc(l->load_order, sizeof(u32) * l->load_order_cap);
    }
    l->load_order[l->load_order_len++] = m->id;
}

static char lexNextChar(Lexer *l) {
    l->start = l->ptr;
    LexFile *lex_file;
    char ch = '\0';
    if (l->ptr) {
        ch = *l->ptr;
    }
    if (ch == '\0') {
        lexFileEnded(l, l->cur_file);
        if (listEmpty(l->files)) return '\0';
        if ((lex_file = listPop(l->files)) == NULL) {
            return '\0';
        }
        /* The file that ended was its module's last: it is loaded */
        if (l->cur_file && l->cur_file->last_of_module) {
            lexModuleLoaded(l, l->modules[l->cur_file->module_id - 1]);
            if (l->mod_depth > 0) l->mod_depth--;
        }
        l->cur_file = lex_file;
        l->ptr = lex_file->ptr;
        l->lineno = lex_file->lineno;
        l->line_start_ptr = lex_file->line_start_ptr;
        ch = *l->ptr;
        /* The file we returned to has nothing left either (an empty
         * file, or one that ends with its `#include`): don't step past
         * its terminator. */
        if (ch == '\0') {
            return lexNextChar(l);
        }
        l->cur_ch = ch;
        l->start = l->ptr;
        l->ptr++;
        return ch;
    } else {
        l->cur_ch = ch;
        l->start = l->ptr;
        l->ptr++;
    }
    return ch;
}

static void lexRewindChar(Lexer *l) {
    l->ptr--;
}

/* Doese the next character match the expected character */
static int lexPeekMatch(Lexer *l, char expected) {
    return *l->ptr == expected;
}

static char lexPeek(Lexer *l) {
    return *l->ptr;
}

/* Read an entire file to a mallocated buffer, or return NULL with
 * `errno` set if it can't be opened or read (a directory, say: open()
 * succeeds on one but read() fails with EISDIR). */
static char *lexTryReadfile(char *path, s64 *_len) {
    int fd;
    struct stat st;
    if ((fd = open(path, O_RDONLY, 0644)) == -1) {
        return NULL;
    }
    if (fstat(fd, &st) == -1) {
        int err = errno;
        close(fd);
        errno = err;
        return NULL;
    }

    s64 len = (s64)st.st_size;

    /* Add a `+1` for `\0` */
    char *buf = (char *)malloc((sizeof(char) * len)+1);
    s64 size = 0;
    ssize_t rbytes = 0;
    while (size < len && (rbytes = read(fd,buf+size,len-size)) > 0) {
        size += rbytes;
    }

    if (rbytes == -1 || size != len) {
        int err = rbytes == -1 ? errno : EIO;
        free(buf);
        close(fd);
        errno = err;
        return NULL;
    }

    *_len = len;
    buf[len] = '\0';
    close(fd);

    /* Allow the file to be run as a script via a shebang, e.g.
     * `#!/usr/bin/env -S ktm -jit`. `#!` ... Jsut skip the line */
    if (len >= 2 && buf[0] == '#' && buf[1] == '!') {
        for (int i = 0; i < len && buf[i] != '\n'; ++i) {
            buf[i] = ' ';
        }
    }

    return buf;
}

/* Read an entire file to a mallocated buffer, exiting on failure */
char *lexReadfile(char *path, s64 *_len) {
    char *buf = lexTryReadfile(path, _len);
    if (!buf) {
        loggerPanic("Failed to read file '%s': %s\n", path, strerror(errno));
    }
    return buf;
}

/* Make `src`, the contents of `filename`, the file being lexed */
static void lexPushFileSource(Lexer *l, AoStr *filename, char *src,
                              s64 file_len)
{
    /* We need to save what we are currently lexing and 
     * make the file we've just seen the file we want to lex */
    LexFile *f = (LexFile *)malloc(sizeof(LexFile));
    AoStr *src_code = aoStrNew();
    src_code->data = src;
    src_code->len = file_len;
    src_code->capacity = 0;

    f->ptr = src_code->data;
    f->src = src_code;
    f->lineno = 1;
    f->line_start_ptr = src_code->data;
    f->filename = filename;
    f->file_id = 0; /* registered lazily by cctrlTokenGet */
    f->module_id = 0;
    f->mod_pass = LEX_PASS_NONE;
    f->last_of_module = 0;
    f->header_done = 0;
    f->saw_module = 0;
    f->ended = 0;
    f->is_std = l->builtin_root &&
        !strncmp(filename->data, l->builtin_root, strlen(l->builtin_root));
    f->imports = NULL;
    f->import_owner = NULL;
    f->import_kw = NULL;
    /* The program's modules are found from its main file's directory */
    if (!l->program_root) {
        char *slash = strrchr(filename->data, '/');
        l->program_root = slash
            ? mprintf("%.*s", (int)(slash - filename->data), filename->data)
            : mprintf(".");
    }
    setAdd(l->seen_files,filename->data);
    if (l->cur_file) {
        /* Snapshot the outgoing file's cursor state so column maths
         * still works when we pop back to it after an `#include`. */
        l->cur_file->ptr = l->ptr;
        l->cur_file->lineno = l->lineno;
        l->cur_file->line_start_ptr = l->line_start_ptr;
        listAppend(l->files,l->cur_file);
    }
    l->cur_file = f;
    l->ptr = f->ptr;
    l->lineno = f->lineno;
    l->line_start_ptr = f->line_start_ptr;
    l->start = f->ptr;
}

void lexPushFile(Lexer *l, AoStr *filename) {
    l->after_member = 0;
    s64 file_len = 0;
    char *src = l->source_for
              ? l->source_for(l->source_arg, filename->data, &file_len) : NULL;
    if (!src) src = lexReadfile(filename->data, &file_len);
    lexPushFileSource(l, filename, src, file_len);
}

/* Push an in-memory buffer as if it were a file. The REPL lexes each
 * input through here rather than lexInit(l, source, ...) because a
 * bare string source has no LexFile - and the diagnostic renderer
 * (and `#include` save/restore) both need `l->cur_file` to be real.
 * `src` is borrowed; it must outlive the parse of this buffer. */
void lexPushString(Lexer *l, char *name, char *src, s64 len) {
    l->after_member = 0;
    LexFile *f = (LexFile *)malloc(sizeof(LexFile));
    AoStr *src_code = aoStrNew();
    src_code->data = src;
    src_code->len = len;
    src_code->capacity = 0;

    f->ptr = src_code->data;
    f->src = src_code;
    f->lineno = 1;
    f->line_start_ptr = src_code->data;
    f->filename = aoStrDupRaw(name, strlen(name));
    f->file_id = 0; /* registered lazily by cctrlTokenGet */
    /* A document (the LSP's, the REPL's): its modules are found from
     * its directory */
    if (!l->program_root && strchr(name, '/')) {
        char *slash = strrchr(name, '/');
        l->program_root = mprintf("%.*s", (int)(slash - name), name);
    }
    f->module_id = 0;
    f->mod_pass = LEX_PASS_NONE;
    f->last_of_module = 0;
    f->header_done = 0;
    f->saw_module = 0;
    f->ended = 0;
    f->is_std = 0;
    f->imports = NULL;
    /* the REPL's earlier rounds' imports */
    for (LexImport *imp = l->repl_imports; imp; imp = imp->next) {
        LexImport *copy = (LexImport *)malloc(sizeof(LexImport));
        *copy = *imp;
        copy->used = 1;
        copy->next = f->imports;
        f->imports = copy;
    }
    f->import_owner = NULL;
    f->import_kw = NULL;
    if (l->cur_file) {
        l->cur_file->ptr = l->ptr;
        l->cur_file->lineno = l->lineno;
        l->cur_file->line_start_ptr = l->line_start_ptr;
        listAppend(l->files,l->cur_file);
    }
    l->cur_file = f;
    l->ptr = f->ptr;
    l->lineno = f->lineno;
    l->line_start_ptr = f->line_start_ptr;
    l->start = f->ptr;
}

static void lexSkipCodeComment(Lexer *l) {
    if (*l->ptr == '/') {
        while (*l->ptr != '\0') {
            if (*l->ptr == '\n') {
                break;
            }
            l->ptr++;
        }
    } else if (*l->ptr == '*') {
        int start_line = l->lineno;
        /* `l->ptr` is on the `*`; the opener's `/` is one before it. */
        int start_col = (int)(l->ptr - l->line_start_ptr);
        /* Step past the opening `*`: in `/` `*` `/` it does not start
         * the closing delimiter. */
        l->ptr++;
        while (*l->ptr != '\0') {
            if (*l->ptr == '*' && *(l->ptr + 1) == '/') {
                l->ptr += 2;
                return;
            }
            if (*l->ptr == '\n') {
                l->lineno++;
                l->line_start_ptr = l->ptr + 1;
            }
            l->ptr++;
        }
        /* Walked off the end of the file without seeing the closing
         * delimiter: report it at the opener and treat the comment as
         * running to the end of the file. lexReport, not lexRaise:
         * the raise can fire during token PREFILL when no recovery
         * point is armed, and cctrlTerminate's fallback is exit().
         * The CCF_PERMISSIVE re-lexer only sees one line of a
         * multi-line comment, so it stays quiet. */
        if (!(l->flags & CCF_PERMISSIVE)) {
            /* Reported in a skipped #if group too, as in C: it swallows
             * the #endif */
            int skip = l->flags & CCF_COND_SKIP;
            l->flags &= ~CCF_COND_SKIP;
            lexReportAt(l, start_line, start_col, 2,
                        "unterminated comment");
            l->flags |= skip;
        }
    }
}

/* The CCF_PERMISSIVE re-lexer (error-line colouring) sees the same
 * malformed literal again: only the real lexer should warn about it,
 * and not in a skipped #if group either. */
#define lexNumWarning(l, ...)                     \
    do {                                          \
        if (!((l)->flags & (CCF_PERMISSIVE|CCF_COND_SKIP))) { \
            loggerWarning(__VA_ARGS__);           \
        }                                         \
    } while (0)

static int countNumberLen(Lexer *l, char *ptr, int *isfloat, int *ishex,
                          int *isbin, int *err) {
    char *start = ptr;
    int seen_e = 0;

    /* `0b1010`: binary literal. Only a `b`/`B` straight after a leading
     * `0` is a prefix, elsewhere they are hex digits (`0xB1`). */
    if (ptr[0] == '0' && (ptr[1] == 'b' || ptr[1] == 'B')) {
        ptr += 2;
        while (!isNumTerminator(*ptr) && !(*ptr == '.' && *(ptr + 1) == '.')) {
            if (*ptr == '.') {
                lexNumWarning(l, "line %d: binary literals can't have a "
                              "fractional part\n", l->lineno);
                *err = 1;
                return -1;
            }
            if (*ptr == '_') {
                char prev = ptr[-1], next = ptr[1];
                int prev_ok = prev == '0' || prev == '1';
                int next_ok = next == '0' || next == '1';
                if (!prev_ok || !next_ok) {
                    lexNumWarning(l, "line %d: misplaced '_' in number\n",
                                  l->lineno);
                    *err = 1;
                    return -1;
                }
                ptr++;
                continue;
            }
            if (*ptr != '0' && *ptr != '1') {
                lexNumWarning(l, "line %d: invalid binary digit: '%c'\n",
                              l->lineno, *ptr);
                *err = 1;
                return -1;
            }
            ptr++;
        }
        if (ptr - start == 2) {
            lexNumWarning(l, "line %d: binary literal has no digits\n", l->lineno);
            *err = 1;
            return -1;
        }
        *isbin = 1;
        return ptr - start;
    }

    /* `case 1...3`: the number ends before the `...` range operator
     * rather than reading `1.` as a float. Leaving the loop, not
     * returning, keeps the checks below: `0x...3` is a hex literal with
     * no digits, not 0. */
    while (!isNumTerminator(*ptr) && !(*ptr == '.' && *(ptr + 1) == '.')) {
        switch (*ptr) {
        case 'e':
        case 'E':
            /* In a hex literal `e`/`E` is a digit (`0x1E3`). Otherwise it
             * starts an exponent: `1e3`, `1.5E-7`, `.5e+2` are all F64
             * literals. The sign is part of the exponent (it is a number
             * terminator everywhere else) and at least one digit must
             * follow, so `1e` / `1e+` are errors rather than `1`. */
            if (!*ishex) {
                if (seen_e) {
                    lexNumWarning(l, "line %d: number has more than one "
                                  "exponent\n", l->lineno);
                    *err = 1;
                    return -1;
                }
                seen_e = 1;
                *isfloat = 1;
                if (*(ptr + 1) == '+' || *(ptr + 1) == '-') {
                    ptr++;
                }
                if (*(ptr + 1) == '_') {
                    lexNumWarning(l, "line %d: misplaced '_' in number\n",
                                  l->lineno);
                    *err = 1;
                    return -1;
                }
                if (!isNum(*(ptr + 1))) {
                    lexNumWarning(l, "line %d: exponent has no digits\n",
                                  l->lineno);
                    *err = 1;
                    return -1;
                }
            }
            break;
        case 'x':
        case 'X':
            /* Only a leading `0x` is a hex prefix: `12x5` is not 0x125,
             * and `0xx` / `0x1x` have a second one. */
            if (*ishex || ptr != start + 1 || *start != '0') {
                lexNumWarning(l, "line %d: '%c' can only appear in the 0x "
                              "prefix of a hex literal\n", l->lineno, *ptr);
                *err = 1;
                return -1;
            }
            *ishex = 1;
            break;
        case '-':
        case '+':
            break;

        case '.':
            /* Floating point hex does not exist (`0x1.5`). */
            if (*ishex) {
                lexNumWarning(l, "line %d: hex literals can't have a "
                              "fractional part\n", l->lineno);
                *err = 1;
                return -1;
            }
            /* No `.` after an exponent (`1e3.5`), nor a second one. */
            if (seen_e) {
                lexNumWarning(l, "line %d: the exponent of a number must be "
                              "a whole number\n", l->lineno);
                *err = 1;
                return -1;
            }
            if (*isfloat) {
                lexNumWarning(l, "line %d: a number can only have one "
                              "decimal point\n", l->lineno);
                *err = 1;
                return -1;
            }
            *isfloat = 1;
            break;
        /* `1_000_000`, `0xFF_FF`, `1_000.25`: a digit separator, valid
         * only between two digits of the literal's own base (decimal
         * everywhere but a hex literal, where it's hex). Misplaced at
         * the start, the end, next to `.`/the exponent, or doubled, it
         * has no digit on one side and is rejected. */
        case '_': {
            char prev = ptr[-1], next = ptr[1];
            int prev_ok = *ishex ? isHex((unsigned char)prev) : isNum((unsigned char)prev);
            int next_ok = *ishex ? isHex((unsigned char)next) : isNum((unsigned char)next);
            if (!prev_ok || !next_ok) {
                lexNumWarning(l, "line %d: misplaced '_' in number\n", l->lineno);
                *err = 1;
                return -1;
            }
            break;
        }

        /* Anything else is invalid */
        default:
            /* `1f32` / `2f64`: a float suffix ends the literal, so
             * `1f325` is an invalid suffix rather than 1.0 followed by
             * ignored digits. Letters straight after it (`1f32g`) are
             * reported by lexNumeric. */
            if (*ptr == 'f' && !*ishex &&
                ((ptr[1] == '3' && ptr[2] == '2') ||
                 (ptr[1] == '6' && ptr[2] == '4'))) {
                char *end = ptr + 3;
                if (!isNumTerminator(*end) && !(*end == '.' && end[1] == '.')) {
                    while (!isNumTerminator(*end)) end++;
                    lexNumWarning(l, "line %d: invalid suffix '%.*s' on number\n",
                                  l->lineno, (int)(end - ptr), ptr);
                    *err = 1;
                    return -1;
                }
                *isfloat = 1;
                return end - start;
            }
            /* Letters are only digits in a hex literal: `123abc` used
             * to be read as 123 (strtoull stops at the `a`). */
            if (!isNum(*ptr) && !(*ishex && isHex(*ptr))) {
                lexNumWarning(l, "line %d: invalid character '%c' in number\n",
                              l->lineno,*ptr);
                *err = 1;
                return -1;
            }
            break;
        }
        ptr++;
    }

    /* `0x` on its own is not 0, like `0b` above. */
    if (*ishex && ptr - start == 2) {
        lexNumWarning(l, "line %d: hex literal has no digits\n", l->lineno);
        *err = 1;
        return -1;
    }

    return ptr - start;
}

int lexIdentifier(Lexer *l, char ch) {
    int i = 0;
    while (ch && (isalnum(ch) || ch == '_' ||  ch == '$')) {
        i++;
        ch = lexNextChar(l);
    }

    l->cur_strlen = i;
    if (ch != '\0') {
        lexRewindChar(l);
    }
    return TK_IDENT;
}

/* Append byte `v` to a string buffer as a three digit octal escape.
 * Strings are kept escaped because they are pasted into the assembly
 * as `.asciz "..."`; `\x41` can't be passed on as written because GNU
 * as and clang read every hex digit after `\x` (`"\x01b"` is the one
 * byte 0x1B to them), while an octal escape always ends after three
 * digits for both, and for C and the JIT's decoder. */
static int lexOctalEscape(char *buffer, int len, unsigned int v) {
    buffer[len++] = '\\';
    buffer[len++] = '0' + ((v >> 6) & 3);
    buffer[len++] = '0' + ((v >> 3) & 7);
    buffer[len++] = '0' + (v & 7);
    return len;
}

/* As this function escapes strings we pass in `_real_len` to be able 
 * to capture the length of the string minus escape sequences, plus the
 * NUL. Escapes are normalised to ones every consumer decodes the same
 * way: `\x` takes at most two hex digits and `\0`..`\7` at most three
 * octal ones (as in C), and both become a three digit octal escape.
 * The string is allocated from the lexers arean not the global allocator */
char *lexString(Lexer *l, char terminator, s64 *_real_len, int *_buffer_len) {
    u32 capacity = 64;
    int len = 0;
    s64 real_len = 0;

    char *buffer = lexerAllocateBuffer(64);
    char ch = '\0';

    while ((ch = lexNextChar(l)) != terminator) {
        real_len++;
        if (ch == '\n') {
            l->lineno++;
            l->line_start_ptr = l->ptr;
        }

        if ((unsigned int)(len + 5) >= capacity) {
            buffer = lexerReAllocBuffer(buffer, len, capacity * 2);
            capacity *= 2;
        }

        if (!ch || ch == terminator) {
            goto done;
        } else if (ch == '\\') {
            ch = lexNextChar(l);

            switch (ch) {
                case '\\': buffer[len++] = '\\'; buffer[len++] = '\\'; break;
                case '"':  buffer[len++] = '\\'; buffer[len++] = '"';  break;
                case 'n':  buffer[len++] = '\\'; buffer[len++] = 'n';  break;
                case 'r':  buffer[len++] = '\\'; buffer[len++] = 'r';  break;
                case 't':  buffer[len++] = '\\'; buffer[len++] = 't';  break;
                case 'b':  buffer[len++] = '\\'; buffer[len++] = 'b';  break;
                case 'f':  buffer[len++] = '\\'; buffer[len++] = 'f';  break;
                /* Need no escape inside "" (and clang's assembler
                 * rejects `\'`) */
                case '\'': buffer[len++] = '\''; break;
                case '`':  buffer[len++] = '`';  break;
                case 'v':  len = lexOctalEscape(buffer, len, '\v'); break;
                case 'a':  len = lexOctalEscape(buffer, len, '\a'); break;
                /* `$$` is an escaped `$` (DolDoc) */
                case 'd':  buffer[len++] = '$';  break;

                case '0': case '1': case '2': case '3':
                case '4': case '5': case '6': case '7': {
                    unsigned int v = ch - '0';
                    for (int i = 1; i < 3 && lexPeek(l) >= '0' &&
                                    lexPeek(l) <= '7'; ++i) {
                        v = v * 8 + (lexNextChar(l) - '0');
                    }
                    len = lexOctalEscape(buffer, len, v);
                    break;
                }

                case 'x':
                case 'X': {
                    unsigned int v = 0;
                    int digits = 0;
                    /* Peek so a non-hex character (or the closing
                     * quote) after `\x` stays part of the string. */
                    while (digits < 2 && isHex(lexPeek(l))) {
                        char h = toupper(lexNextChar(l));
                        v = v * 16 + (h <= '9' ? h - '0' : h - 'A' + 10);
                        digits++;
                    }
                    if (!digits && !(l->flags & CCF_PERMISSIVE)) {
                        lexReport(l, "\\x used with no following hex digits");
                    }
                    len = lexOctalEscape(buffer, len, v);
                    break;
                }
            default:
                if (l->flags & CCF_PERMISSIVE) {
                    /* Absorb literally - the renderer just wants
                     * a syntax-coloured echo, not validation. */
                    buffer[len++] = '\\';
                    buffer[len++] = (char)ch;
                    break;
                }
                /* Report it and keep the character as written. */
                lexReport(l, "Invalid escape character: '\\%c'", (char)ch);
                buffer[len++] = '\\';
                buffer[len++] = (char)ch;
                break;
            }
    } else {
            /* Because ktm can have multi line strings, tabs or other escaped 
             * characters which are typeable we need to escape them. */
            switch (ch) {
                case '\n':
                    buffer[len++] = '\\';
                    buffer[len++] = 'n';
                    break;
                case '\t':
                    buffer[len++] = '\\';
                    buffer[len++] = 't';
                    break;
                case '\r':
                    buffer[len++] = '\\';
                    buffer[len++] = 'r';
                    break;
                case '\f':
                    buffer[len++] = '\\';
                    buffer[len++] = 'f';
                    break;
                case '\v':
                    len = lexOctalEscape(buffer, len, '\v');
                    break;
                default:
                    buffer[len++] = ch;
                    break;
            }
        }
    }

done:
    /* The input ended inside the string: reported, and the text up to
     * there is kept so lexing carries on. */
    l->str_unterminated = ch != terminator;
    if (l->str_unterminated && !(l->flags & CCF_PERMISSIVE)) {
        lexReport(l, "Unterminated string");
    }
    /* The NUL */
    real_len++;
    buffer[len] = '\0';
    *_real_len = real_len;
    *_buffer_len = len;
    return buffer;
}

/* A character literal: exactly one character, an escape counting as one
 * (Y9b); more is an error, though up to 8 are still read into the value
 * so the error recovers. l->cur_strlen is set to its length in the
 * source, between the quotes (`'\n'` is 2), as for a string. */
u64 lexCharConst(Lexer *l) {
    u64 char_const = 0, idx;
    s64 hex_num = 0;
    s64 len, overflowed = 0;
    char ch = 0;
    char *begin = l->ptr;

    for (len = 0; ; ++len) {
        /* Skipped #if text need not be ktm: an apostrophe there
         * (`don't`) ends with its line, as in C, rather than running
         * on over the #endif */
        if ((l->flags & CCF_COND_SKIP) && lexPeek(l) == '\n') {
            break;
        }
        ch = lexNextChar(l);
        if (!ch || ch == '\'') {
            break;
        }
        if (len >= LEX_CHAR_CONST_LEN) {
            /* Absorb through to the closing quote so the raise below
             * (or the CCF_PERMISSIVE renderer, which only wants a
             * syntax-coloured echo) resumes after the constant rather
             * than in the middle of it. Skip the escaped character so
             * `\'` cannot end the constant early. */
            overflowed = 1;
            if (ch == '\\') lexNextChar(l);
            continue;
        }
        idx = len * 8;
        if (ch == '\\') {
            ch = lexNextChar(l);
            u64 v;
            /* The same escapes as a string (see lexString) */
            switch (ch) {
                case '\'': v = '\''; break;
                case '`':  v = '`';  break;
                case '\"': v = '\"'; break;
                case '\\': v = '\\'; break;
                case 'd':  v = '$';  break;
                case 'a':  v = '\a'; break;
                case 'b':  v = '\b'; break;
                case 'n':  v = '\n'; break;
                case 'r':  v = '\r'; break;
                case 't':  v = '\t'; break;
                case 'v':  v = '\v'; break;
                case 'f':  v = '\f'; break;
                case '0': case '1': case '2': case '3':
                case '4': case '5': case '6': case '7':
                    v = ch - '0';
                    for (int i = 1; i < 3 && lexPeek(l) >= '0' &&
                                    lexPeek(l) <= '7'; ++i) {
                        v = v * 8 + (lexNextChar(l) - '0');
                    }
                    v &= 0xFF;
                    break;
                case 'x':
                case 'X':
                    hex_num = 0;
                    int i;
                    for (i = 0; i < 2; ++i) {
                        ch = toupper(lexPeek(l));
                        if (!isHex(ch)) {
                            break;
                        }
                        lexNextChar(l);
                        if (ch <= '9') {
                            hex_num = (hex_num<<4)+ch-'0';
                        } else {
                            hex_num = (hex_num<<4)+ch-'A'+10;
                        }
                    }
                    if (!i && !(l->flags & CCF_PERMISSIVE)) {
                        lexReport(l, "\\x used with no following hex digits");
                    }
                    v = hex_num;
                    break;
                default:
                    /* Report it and keep the character as written, as
                     * lexString does. `!ch` is reported below. */
                    if (ch && !(l->flags & CCF_PERMISSIVE)) {
                        lexReport(l, "Invalid escape character: '\\%c'",
                                  (char)ch);
                    }
                    v = (unsigned char)ch;
                    break;
            }
            char_const |= v << (unsigned long)idx;
            /* `'\`: the input ended */
            if (!ch) break;
        } else {
            char_const |= (unsigned long)(((unsigned long)ch) << ((unsigned long)(idx)));
        }
    }

    if (!(l->flags & CCF_PERMISSIVE)) {
        /* Both leave the lexer past the constant (at its closing quote
         * or the end of input); the truncated value is handed back. */
        if (!ch) {
            lexReport(l, "Unterminated char const");
        } else if (len != 1 || overflowed) {
            /* Y9b: one character, an escape counting as one */
            lexReport(l, "a character literal holds one character; use a "
                         "string or an enum");
        }
    }

    l->cur_i64 = char_const;
    l->cur_strlen = (l->ptr - begin) - (ch == '\'' ? 1 : 0);
    return TK_CHAR_CONST;
}

/* ktm has no octal literals: `0644` is the decimal number 644, where
 * C reads 420. The meaning stays (it always read that way), but
 * code copied from C would silently change value, so warn when C would
 * read a different number: only octal digits after the leading zero
 * and a value of 8 or more (`00`, `07` are the same in both; `08` is
 * not octal at all, so C code never has it). */
static void lexLeadingZeroWarning(Lexer *l, char *start, int numlen) {
    int i, ndigits = numlen;
    unsigned long long oct = 0;

    if (start[0] != '0' || numlen < 2) return;
    if (start[numlen - 1] == 'U' || start[numlen - 1] == 'u') ndigits--;
    for (i = 1; i < ndigits; i++) {
        if (start[i] < '0' || start[i] > '7') return;
        oct = oct * 8 + (start[i] - '0');
    }
    if (oct < 8) return;
    lexWarning(l, "leading zero: %.*s is the decimal number %llu (ktm has "
               "no octal literals); use 0x%llX for octal %.*s",
               ndigits, start, (unsigned long long)l->cur_i64,
               oct, ndigits, start);
}

/* `_` is kept through the scan so countNumberLen can check its
 * placement, but strtoull/strtold don't know it: copies [start,
 * start+len) into `out` (which must hold at least len+1 bytes),
 * dropping every `_`, and returns the copy's length. */
static int stripDigitSeparators(const char *start, int len, char *out) {
    int j = 0;
    for (int i = 0; i < len; i++) {
        if (start[i] != '_') out[j++] = start[i];
    }
    out[j] = '\0';
    return j;
}

int lexNumeric(Lexer *l, int _isfloat) {
    int ishex, isbin, isfloat, err, numlen;
    char *endptr;

    isfloat = _isfloat;

    ishex = isbin = isfloat = err = 0;
    char *start = l->ptr - 1;
    numlen = countNumberLen(l, start, &isfloat, &ishex, &isbin, &err);
    /* C's `U` suffix on an integer (`0x0800U`, as copied from C
     * headers) is accepted and has no effect on the value. */
    if (!err && !isfloat && (start[numlen] == 'U' || start[numlen] == 'u')) {
        numlen++;
    }
    /* Any other letter straight after the literal (`0x12G`, `0b101z`,
     * `1.5g`) is an invalid suffix, not the start of the next token. */
    if (!err && (isalpha(start[numlen]) || start[numlen] == '_')) {
        lexNumWarning(l, "line %d: invalid suffix '%c' on number\n",
                      l->lineno, start[numlen]);
        err = 1;
    }
    if (err) {
        /* Step over the whole malformed literal (`1e+`, `0b12`) so the
         * error underlines all of it and lexing resumes after it,
         * instead of on its tail (`e`, `b12` as stray identifiers). */
        char *end = start + 1;
        while (!isNumTerminator(*end) || isalpha(*end) || *end == '_' ||
               ((*end == '+' || *end == '-') &&
                (end[-1] == 'e' || end[-1] == 'E'))) {
            end++;
        }
        l->cur_strlen = end - start;
        l->ptr = end;
        /* The CCF_PERMISSIVE lexer only re-lexes a line to colour an
         * error report. It must not raise (it has no Cctrl, so lexRaise
         * would exit() and swallow the real diagnostic). Hand the text
         * back as a plain token so the report shows it verbatim. */
        if (l->flags & CCF_PERMISSIVE) {
            return TK_IDENT;
        }
        return -1;
    }

    l->cur_strlen = numlen;
    l->ptr += numlen - 1;

    char cleaned[numlen + 1];
    int cleanlen = stripDigitSeparators(start, numlen, cleaned);

    if (isfloat) {
        l->cur_f64 = strtold(cleaned, &endptr);
        return TK_F64;
    } else if (ishex) {
        l->cur_i64 = strtoull(cleaned, &endptr, 16);
        l->ishex = ishex;
    } else if (isbin) {
        l->cur_i64 = strtoull(cleaned + 2, &endptr, 2);
    } else {
        l->cur_i64 = strtoull(cleaned, &endptr, 10);
        lexLeadingZeroWarning(l, cleaned, cleanlen);
    }
    /* A literal above I64_MAX only fits in a U64, so it is typed U64. */
    l->isu64 = l->cur_i64 < 0;
    return TK_I64;
}

LexerType *lexPreProcDirective(Lexer *l) {
    Lexeme le;
    memset(&le, 0, sizeof(le));
    /* Where the `#` is; lex() below moves tok_start to the name */
    s64 hash_line = l->tok_start_line;
    s64 hash_col = l->tok_start_col;
    if (!lex(l,&le)) return 0;

    /* Documentation/assertion directives: `#help_index "..."`,
     * `#help_file "..."` and `#assert <expr>` have no meaning here -
     * consume to end of line so older sources parse. NULL
     * tells the caller to keep lexing. */
    if ((le.len == 10 && !memcmp(le.start, str_lit("help_index"))) ||
        (le.len == 9  && !memcmp(le.start, str_lit("help_file")))  ||
        (le.len == 6  && !memcmp(le.start, str_lit("assert"))))
    {
        while (*l->ptr != '\0' && *l->ptr != '\n') l->ptr++;
        return NULL;
    }

    char buffer[32];
    s64 len = snprintf(buffer,sizeof(buffer),"#%.*s",le.len,le.start);
    LexerType *type = mapGetLen(l->symbol_table,buffer,len);
    if (!type) {
        /* Report it and drop the line, as for the skipped ones above. */
        lexReportAt(l, hash_line, hash_col,
                    l->ptr - (l->line_start_ptr + hash_col - 1),
                    "invalid preprocessor directive '%s'", buffer);
        while (*l->ptr != '\0' && *l->ptr != '\n') l->ptr++;
        return NULL;
    }
    l->tok_start_line = hash_line;
    l->tok_start_col = hash_col;
    return type;
}

/* `ch`, at `start`, is not a character ktm has a use for here (`?`,
 * a backtick, `$`/`@` outside an asm block, a stray control or
 * non-ASCII byte): report it and step over it. A UTF-8 character is
 * stepped over whole so it is reported once, not once per byte. The
 * CCF_PERMISSIVE re-lexer echoes it as written and stays quiet. */
static void lexUnknownChar(Lexer *l, char *start, char ch) {
    unsigned char uch = (unsigned char)ch;
    int is_utf8 = uch >= 0xC0;
    if (is_utf8) {
        while (((unsigned char)*l->ptr & 0xC0) == 0x80) l->ptr++;
    }
    if (l->flags & (CCF_PERMISSIVE|CCF_COND_SKIP)) return;
    if (is_utf8 || isprint(uch)) {
        lexReportAt(l, l->tok_start_line, l->tok_start_col, 1,
                    "unexpected character '%.*s'",
                    (int)(l->ptr - start), start);
    } else {
        lexReportAt(l, l->tok_start_line, l->tok_start_col, 1,
                    "unexpected character '\\x%02x'", uch);
    }
}

/* The old syntax's spellings (ktm syntax v0.2 replaced them): each use
 * is an error naming the new one, and the token is lexed as before so
 * the parse goes on (one error per use, no cascade). Quiet where the
 * lexer only re-reads: the diagnostic renderer, skipped #if text and a
 * module's names and declarations passes. */
static void lexOldSpelling(Lexer *l, Lexeme *le) {
    static const char *old_new[][2] = {
        {"U0", "void"}, {"Bool", "bool"}, {"I8", "i8"}, {"U8", "u8"},
        {"I16", "i16"}, {"U16", "u16"}, {"I32", "i32"}, {"U32", "u32"},
        {"I64", "i64"}, {"U64", "u64"}, {"F32", "f32"}, {"F64", "f64"},
        {"class", "struct"}, {"public", "pub"},
    };
    if (l->flags & (CCF_PERMISSIVE|CCF_COND_SKIP)) return;
    if (l->cur_file && LEX_PASS_QUIET(l->cur_file->mod_pass)) return;
    for (u64 i = 0; i < sizeof(old_new) / sizeof(old_new[0]); ++i) {
        if ((int)strlen(old_new[i][0]) == le->len &&
            !memcmp(old_new[i][0], le->start, le->len)) {
            lexReport(l, "`%s` is the old spelling: write `%s`",
                      old_new[i][0], old_new[i][1]);
            return;
        }
    }
}

static int lexCore(Lexer *l, Lexeme *le) {
    char ch, *start;
    int tk_type;
    LexerType *type;

    while (1) {
        ch = lexNextChar(l);
        start = l->start;

        /* Capture the *start* position of the token. Most iterations are
         * ignored like whitespace or comments but the iteration that returns a
         * lexeme will have the right values here. Multi-line tokens like strings
         * still report the opening line/column even after `lexString` bumps
         * `lineno` and line_start_ptr past the embedded newlines. */
        l->tok_start_line = l->lineno;
        if (start >= l->line_start_ptr) {
            l->tok_start_col = (int)(start - l->line_start_ptr) + 1;
        } else {
            l->tok_start_col = 1;
        }

        switch (ch) {
            case '\r':
            case '\n':
                l->lineno++;
                l->line_start_ptr = l->ptr;
                if (l->flags & (CCF_ACCEPT_NEWLINES|CCF_ACCEPT_WHITESPACE)) {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                    return 1;
                }
                break;

            case '\t':
            case '\v':
            case '\f':
            case ' ':
                if (l->flags & (CCF_ACCEPT_WHITESPACE)) {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                    return 1;
                }
                break;


            case '\0':
                return 0;

            case '0':
            case '1':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
            case '9':
                if ((tk_type = lexNumeric(l,0)) == -1) {
                    /* lexNumeric stepped over it; carry on with a 0. */
                    lexReport(l, "malformed numeric literal");
                    tk_type = TK_I64;
                    l->cur_i64 = 0;
                }
                le->len = l->cur_strlen;
                le->tk_type = tk_type;
                le->line = l->lineno;
                le->start = start;
                if (tk_type == TK_F64) {
                    le->f64 = l->cur_f64;
                } else {
                    le->i64 = l->cur_i64;
                }
                le->ishex = l->ishex;
                le->isu64 = tk_type == TK_I64 && l->isu64;
                l->ishex = 0;
                l->isu64 = 0;
                return 1;

            case '\"': {
                le->len = 0;
                le->i64 = 0;
                le->start = lexString(l,'"',&le->i64,&le->len);
                le->tk_type = TK_STR;
                le->line = l->lineno;
                l->cur_str = NULL;
                l->cur_strlen = 0;
                return 1;
            }

            case '\'':
                lexCharConst(l);
                le->start = start+1;
                le->len = l->cur_strlen;
                le->i64 = l->cur_i64;
                le->tk_type = TK_CHAR_CONST;
                l->cur_str = NULL;
                l->cur_strlen = 0;
                le->line = l->lineno;
                return 1;

            case '/':
                if (*l->ptr == '/' || *l->ptr == '*') {
                    start = l->ptr-1;
                    lexSkipCodeComment(l);
                    s64 lineno = l->lineno;
                    if (l->flags & CCF_ACCEPT_COMMENTS) {
                        s64 len = l->ptr-start;
                        le->start = start;
                        le->len = len;
                        le->line = lineno;
                        le->tk_type = TK_COMMENT;
                        return 1;
                    }
                } else {
                    if (lexPeekMatch(l,'=')) {
                        lexNextChar(l);
                        lexemeAssignOp(le,start,2,TK_DIV_EQU,l->lineno);
                        return 1;
                    } else {
                    /* Divide */
                        lexemeAssignOp(le,start,1,ch,l->lineno);
                        return 1;
                    }
                }
                break;

            case '=':
                /* Check for equality */
                if (lexPeekMatch(l,'=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_EQU_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;
            
            case '<':
                if (lexPeekMatch(l,'=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_LESS_EQU,l->lineno);
                } else if (lexPeekMatch(l, '<')) {
                    lexNextChar(l);
                    if (lexPeekMatch(l, '=')) {
                        lexNextChar(l);
                        lexemeAssignOp(le,start,3,TK_SHL_EQU,l->lineno);
                    } else {
                        lexemeAssignOp(le,start,2,TK_SHL,l->lineno);
                    }
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;
            
            case '>':
                if (lexPeekMatch(l,'=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_GREATER_EQU,l->lineno);
                } else if (lexPeekMatch(l, '>')) {
                    lexNextChar(l);
                    if (lexPeekMatch(l, '=')) {
                        lexNextChar(l);
                        lexemeAssignOp(le,start,3,TK_SHR_EQU,l->lineno);
                    } else {
                        lexemeAssignOp(le,start,2,TK_SHR,l->lineno);
                    }
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '+':
                if (lexPeekMatch(l,'+')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_PLUS_PLUS,l->lineno);
                } else if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_ADD_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '-':
                if (lexPeekMatch(l,'-')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_MINUS_MINUS,l->lineno);
                } else if (lexPeekMatch(l, '>')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_ARROW,l->lineno);
                } else if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_SUB_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;
            
            case '!':
                if (lexPeekMatch(l,'=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_NOT_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '&':
                if (lexPeekMatch(l,'&')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_AND_AND,l->lineno);
                } else if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_AND_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '|':
                if (lexPeekMatch(l,'|')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_OR_OR,l->lineno);
                } else if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_OR_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '*':
                if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_MUL_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '%':
                if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_MOD_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;
            
            case '^':
                if (lexPeekMatch(l, '=')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_XOR_EQU,l->lineno);
                } else {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                }
                return 1;

            case '@':
                /* `@json`, `@key("x")`: an attribute (v0.14.0); the
                 * parser says where one can go. The methods @json makes
                 * use the file's `import json` (or yaml's). */
                lexemeAssignOp(le,start,1,ch,l->lineno);
                for (int f = 0; f < 2; ++f) {
                    const char *mod = f ? "yaml" : "json";
                    if (strncmp(l->ptr, mod, 4) || isalnum((unsigned char)l->ptr[4]) ||
                        l->ptr[4] == '_' || !l->cur_file) continue;
                    for (LexImport *imp = l->cur_file->imports; imp; imp = imp->next) {
                        if (!strcmp(imp->module->path->data, mod)) imp->used = 1;
                    }
                }
                return 1;

            case '$':
                if (l->flags & CCF_ASM_BLOCK) {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                    return 1;
                }
                lexUnknownChar(l, start, ch);
                break;
            case '.':
                if (isNum(lexPeek(l))) {
                    if ((tk_type = lexNumeric(l,0)) == -1) {
                        /* As above: carry on with a 0. */
                        lexReport(l, "malformed numeric literal");
                        tk_type = TK_I64;
                        l->cur_i64 = 0;
                    }
                    le->start = start;
                    le->len = l->ptr - start;
                    le->tk_type = tk_type;
                    le->line = l->lineno;
                    if (tk_type == TK_F64) {
                        le->f64 = l->cur_f64;
                    } else {
                        le->i64 = l->cur_i64;
                    }
                    le->ishex = l->ishex;
                    le->isu64 = tk_type == TK_I64 && l->isu64;
                    l->ishex = 0;
                    l->isu64 = 0;
                    return 1;
                } else if (lexPeekMatch(l,'.')) {
                    lexNextChar(l);
                    if (lexPeekMatch(l,'.')) {
                        lexNextChar(l);
                        lexemeAssignOp(le,start,3,TK_ELLIPSIS,l->lineno);
                        return 1;
                    }
                    /* Most likely a mistyped `...`; carry on as one.
                     * Not reported by the CCF_PERMISSIVE re-lexer, which
                     * has no Cctrl and would exit (see lexNumeric). */
                    if (!(l->flags & CCF_PERMISSIVE)) {
                        lexReport(l, "`..` is an invalid token sequence");
                    }
                    lexemeAssignOp(le,start,2,TK_ELLIPSIS,l->lineno);
                    return 1;
                }
                
                lexemeAssignOp(le,start,1,ch,l->lineno);
                return 1;

            case '\\':
                /* A `\` that ends a line joins the next one to it, as in
                 * C: how a long #define goes on over several lines */
                if (lexPeek(l) == '\n' ||
                    (lexPeek(l) == '\r' && l->ptr[1] == '\n')) {
                    if (lexPeek(l) == '\r') l->ptr++;
                    l->ptr++;
                    l->lineno++;
                    l->line_start_ptr = l->ptr;
                    break;
                }
                lexemeAssignOp(le,start,1,'\\',l->lineno);
                return 1;

            case '#': {
                /* Inside an asm{} block `#` could be the immediate
                 * marker preprocessor directives can't appear there anyway,
                 * so surface it as a plain punctuation token. */
                if (l->flags & CCF_ASM_BLOCK) {
                    lexemeAssignOp(le,start,1,ch,l->lineno);
                    return 1;
                }
                /* The CCF_PERMISSIVE lexer only re-lexes a line to echo
                 * it in an error report: hand `#name` back verbatim, as
                 * one token, rather than acting on the directive (which
                 * dropped it from the echo, and for an unknown one made
                 * this Cctrl-less lexer exit). */
                if (l->flags & CCF_PERMISSIVE) {
                    while (isalnum(*l->ptr) || *l->ptr == '_') l->ptr++;
                    lexemeAssignOp(le,start,l->ptr - start,ch,l->lineno);
                    le->tk_type = TK_IDENT;
                    return 1;
                }
                type = lexPreProcDirective(l);
                if (type == NULL) {
                    /* Skipped directive (#help_index et al) - the
                     * line is consumed; hand back the next token. */
                    return lex(l, le);
                }
                /* Span the whole `#name`, positioned at the `#` (which
                 * lexPreProcDirective put back in tok_start) */
                le->start = start;
                le->len = l->ptr - start;
                le->tk_type = TK_KEYWORD;
                le->i64 = type->kind;
                return 1;
            }

            case ':': {
                if (l->flags & CCF_MULTI_COLON && lexPeekMatch(l,':')) {
                    lexNextChar(l);
                    lexemeAssignOp(le,start,2,TK_DBL_COLON,l->lineno);
                    return 1;
                }
                lexemeAssignOp(le,start,1,ch,l->lineno);
                return 1;
            }
            case '~':
            case '?':
            case '(':
            case ')':
            case ',':
            case ';':
            case '[':
            case ']':
            case '{':
            case '}':
                lexemeAssignOp(le,start,1,ch,l->lineno);
                return 1;
            
            default: {
                if (isalpha(ch) || ch == '_') {
                    if ((tk_type = lexIdentifier(l, ch)) == -1) {
                        lexRaise(l, "malformed identifier");
                    }

                    le->start = start;
                    le->len = l->cur_strlen;
                    le->tk_type = tk_type;
                    le->line = l->lineno;

                    if ((type = mapGetLen(l->symbol_table,le->start,le->len)) != NULL) {
                        le->tk_type = TK_KEYWORD;
                        le->i64 = type->kind;
                        type = NULL;
                    }
                    lexOldSpelling(l, le);
                    return 1;
                }
                lexUnknownChar(l, start, ch);
                break;
            }
        }
    }
    lexRaise(l, "Lexer error");
    return 0;
}

int lex(Lexer *l, Lexeme *le) {
    /* callers lex into stack Lexemes: no stale qualified prefix */
    le->qual_prefix = NULL;
    le->qual_prefix_len = 0;
    int rc = lexCore(l, le);
    if (rc != 1) return rc;
    le->line = l->tok_start_line;
    le->col = l->tok_start_col;
    return rc;
}

/* Drop the rest of a malformed directive's line before reporting it,
 * so lexing resumes on the next line instead of handing the parser the
 * directive's leftover tokens. */
static void lexSkipLine(Lexer *l) {
    l->flags &= ~CCF_ACCEPT_NEWLINES;
    while (*l->ptr && *l->ptr != '\n') l->ptr++;
}

/* A source span to report an error at */
typedef struct LexPos {
    s64 line;
    s64 col;
    s64 len;
} LexPos;

/* The directive just lexed (`#include` et al): lexCore leaves tok_start
 * on its `#` and the lexer just past its name. */
static LexPos lexDirectivePos(Lexer *l) {
    LexPos pos;
    pos.line = l->tok_start_line;
    pos.col = l->tok_start_col;
    pos.len = l->ptr - (l->line_start_ptr + pos.col - 1);
    return pos;
}

/* Drop a malformed #include/#link/#undef whose operand `next`
 * was lexed with newlines skipped, and return where to report it. If
 * `next` is on the directive's line the error is at `next` and the rest
 * of that line goes; if the operand was missing and `next` is the first
 * token of a later line, the error is at the directive and lexing steps
 * back so that line is kept. */
static LexPos lexDropDirective(Lexer *l, const LexPos *dir, Lexeme *next) {
    if (next->line == dir->line) {
        LexPos pos;
        pos.line = next->line;
        pos.col = next->col;
        pos.len = l->ptr - (l->line_start_ptr + next->col - 1);
        lexSkipLine(l);
        return pos;
    }
    if (l->tok_start_line == l->lineno) {
        l->ptr = l->line_start_ptr + l->tok_start_col - 1;
    }
    return *dir;
}

/* `#error`: the message is the rest of the line as written, up to a
 * comment and trimmed, as in C; a lone string literal is shown without
 * its quotes. Reported at the directive, spanning the whole line. */
static void lexErrorDirective(Lexer *l) {
    LexPos dir = lexDirectivePos(l);
    char *msg = l->ptr;
    char *end = msg;
    char quote = 0;

    for (; *end && *end != '\n'; ++end) {
        if (quote) {
            if (*end == '\\' && end[1] && end[1] != '\n') end++;
            else if (*end == quote) quote = 0;
        } else if (*end == '"' || *end == '\'') {
            quote = *end;
        } else if (*end == '/' && (end[1] == '/' || end[1] == '*')) {
            break;
        }
    }
    /* Lexing resumes at the newline, or at a comment, which the lexer
     * steps over as usual (a block comment may run on past the line) */
    l->ptr = end;

    while (msg < end && isspace((unsigned char)*msg)) msg++;
    while (end > msg && isspace((unsigned char)end[-1])) end--;
    if (end > msg) dir.len = end - (l->line_start_ptr + dir.col - 1);

    int len = (int)(end - msg);
    if (len >= 2 && *msg == '"' && end[-1] == '"' &&
        !memchr(msg + 1, '"', len - 2))
    {
        msg++;
        len -= 2;
    }
    if (len == 0) {
        lexReportAt(l, dir.line, dir.col, dir.len, "#error");
    } else {
        lexReportAt(l, dir.line, dir.col, dir.len, "%.*s", len, msg);
    }
}

/* Errors in #include, #link and #undef are reported with lexReport and
 * the directive dropped (see lexReport for why they don't longjmp). A
 * missing #include file is reported the same way and lexing carries on
 * without it. */
void lexInclude(Lexer *l) {
    AoStr *ident, *include_path;
    Lexeme next;
    LexPos dir = lexDirectivePos(l), at;

    if (!lex(l, &next)) {
        lexReportAt(l, dir.line, dir.col, dir.len,
                    "Syntax is: #include \"<value>\" got nothing");
        return;
    }
    if (tokenPunctIs(&next, '<')) {
        ident = aoStrNew();
        for (;;) {
            if (!lex(l, &next)) {
                lexReportAt(l, dir.line, dir.col, dir.len,
                            "Unterminated #include <...>");
                aoStrRelease(ident);
                return;
            }
            if (tokenPunctIs(&next, '>')) break;
            aoStrCatPrintf(ident, "%.*s", next.len, next.start);
        }
        include_path = aoStrNew();
        aoStrCatPrintf(include_path, "%s/%s",
                l->builtin_root, ident->data);
        aoStrRelease(ident);
    } else if (next.tk_type == TK_STR) {
        /* Already reported; the path is cut short. */
        if (l->str_unterminated) {
            return;
        }
        include_path = aoStrDupRaw(next.start, next.len);
        /* Resolve a relative include against the INCLUDING file's
         * directory, not the process cwd - and record the joined path
         * so consumers (LSP uris, diagnostics) get a real location.
         * Absolute includes pass through untouched. */
        if (include_path->data[0] != '/' && l->cur_file &&
            l->cur_file->filename)
        {
            char *base = l->cur_file->filename->data;
            char *slash = strrchr(base, '/');
            if (slash) {
                const char *rel = include_path->data;
                if (rel[0] == '.' && rel[1] == '/') rel += 2;
                AoStr *joined = aoStrPrintf("%.*s/%s",
                        (int)(slash - base), base, rel);
                aoStrRelease(include_path);
                include_path = joined;
            }
        }
    } else {
        at = lexDropDirective(l, &dir, &next);
        lexReportAt(l, at.line, at.col, at.len,
                "Syntax is: #include \"<value>\" got: %s",
                lexemeToString(&next));
        return;
    }

    if (!setHas(l->seen_files,include_path->data)) {
        if (access(include_path->data, R_OK) != 0) {
            lexReport(l, "#include: cannot open '%s'", include_path->data);
            aoStrRelease(include_path);
            return;
        }
        /* Only ktm files can be included: a file whose name has an
         * extension must end in .ktm or .ktmh (one without an extension
         * is taken as it is; directories get their own error below). */
        {
            struct stat st;
            char *name = strrchr(include_path->data, '/');
            char *dot = strrchr(name ? name : include_path->data, '.');
            if (stat(include_path->data, &st) == 0 && S_ISREG(st.st_mode) &&
                dot && strcmp(dot, ".ktm") && strcmp(dot, ".ktmh")) {
                lexReport(l, "#include: `%s`: ktm sources use .ktm (headers .ktmh)",
                          include_path->data);
                aoStrRelease(include_path);
                return;
            }
        }
        s64 file_len = 0;
        char *src = lexTryReadfile(include_path->data, &file_len);
        if (!src) {
            lexReport(l, "#include: cannot read '%s': %s",
                      include_path->data, strerror(errno));
            aoStrRelease(include_path);
            return;
        }
        lexPushFileSource(l, include_path, src, file_len);
    } else {
        aoStrRelease(include_path);
    }
}

static int listContainsAoStr(List *ll, AoStr *needle) {
    if (listEmpty(ll)) return 0;
    listForEach(ll) {
        if (aoStrEq((AoStr *)it->value, needle)) return 1;
    }
    return 0;
}

/* `#link` records a shared library dependency in the source itself:
 *   #link "./file.so"   - literal path; spliced into the AOT link
 *                         command verbatim, dlopen'd by the JIT.
 *   #link <name>        - library name; `-lname` for the AOT linker,
 *                         dlopen("lib<name>.{dylib,so}") for the JIT.
 * Duplicates are dropped so headers can #link freely. */
static void lexLink(Lexer *l) {
    Lexeme next;
    AoStr *name;
    int is_path = 0;
    LexPos dir = lexDirectivePos(l), at;

    if (!lex(l, &next)) {
        lexReportAt(l, dir.line, dir.col, dir.len,
                    "Syntax is: #link \"<path>\" or #link <libname>");
        return;
    }
    if (tokenPunctIs(&next, '<')) {
        name = aoStrNew();
        for (;;) {
            if (!lex(l, &next)) {
                lexReportAt(l, dir.line, dir.col, dir.len,
                            "Unterminated #link <...>");
                aoStrRelease(name);
                return;
            }
            if (tokenPunctIs(&next, '>')) break;
            aoStrCatPrintf(name, "%.*s", next.len, next.start);
        }
    } else if (next.tk_type == TK_STR) {
        name = aoStrDupRaw(next.start, next.len);
        is_path = 1;
    } else {
        at = lexDropDirective(l, &dir, &next);
        lexReportAt(l, at.line, at.col, at.len,
                "Syntax is: #link \"<path>\" or #link <libname> got: %s",
                lexemeToString(&next));
        return;
    }

    /* Standalone lexers (e.g. the `-tokens` dump) have nothing to
     * link against; parse and drop. */
    if (!l->cc) {
        aoStrRelease(name);
        return;
    }

    /* Paths ride the existing shared-object plumbing (same list the
     * CLI's positional `.so` arguments land in); names get their own
     * list as they need `-l`/dlopen-probe treatment downstream. */
    List **libs = is_path ? &l->cc->shared_object_files
                          : &l->cc->link_libs;
    if (*libs == NULL) {
        *libs = listNew();
    }
    if (!listContainsAoStr(*libs, name)) {
        listAppend(*libs, name);
    } else {
        aoStrRelease(name);
    }
}

/* Errors here are reported with lexReport, not lexRaise: each one
 * leaves the rest of the define's line consumed, so lexing can carry on
 * with the macro left undefined. A longjmp would land in whatever
 * construct the parser was in when its token ring refilled (a class
 * body, say), abandoning it halfway and leaving its tail to misparse. */
Lexeme *lexDefine(Map *macro_defs, Lexer *l) {
    int tk_type,iters;
    Lexeme next,*start,*end,*expanded,*macro;
    AoStr *ident;
    Vec *tokens = lexemeVecNew();
    /* The `#define` (tok_start still holds it) and, once read, the
     * span of its value on that line, for the errors below (volatile:
     * read after the setjmp below) */
    volatile s64 def_line = l->tok_start_line, def_col = l->tok_start_col;
    volatile s64 val_col = 0, val_end = 0;

    tk_type = -1;
    /* A define must be on one line a \n determines the end of a define */
    l->flags |= CCF_ACCEPT_NEWLINES;
    /* <ident> <value> */
    if (!lex(l, &next) || tokenPunctIs(&next,'\n') || tokenPunctIs(&next,'\0')) {
        l->flags &= ~CCF_ACCEPT_NEWLINES;
        lexReportAt(l, def_line, def_col, 7,
                    "Syntax is: #define <TK_IDENT> <value> got nothing");
        vecRelease(tokens);
        return NULL;
    }
    if (next.tk_type != TK_IDENT) {
        lexSkipLine(l);
        lexReport(l,
                "Syntax is: #define <TK_IDENT> <value> got %s",
                lexemeToString(&next));
        vecRelease(tokens);
        return NULL;
    }

    /* `#define NAME(` with no space before the `(` is a C-style
     * function-like macro. ktm has none and a define's value is
     * evaluated eagerly to one token, so reject it here. `#define NAME (x)`
     * (with a space) is an ordinary define with a parenthesised value. */
    if (next.start[next.len] == '(') {
        char *eol = next.start;
        while (*eol && *eol != '\n' && *eol != '\r') eol++;
        lexSkipLine(l);
        lexReport(l, "function-like macros are not supported: #define %.*s",
                  (int)(eol - next.start), next.start);
        vecRelease(tokens);
        return NULL;
    }

    ident = aoStrDupRaw(next.start, next.len);
    iters = 0;
    do {
        iters++;
        if (!lex(l, &next)) break;

        if (!tokenPunctIs(&next,'\n') && next.line == def_line) {
            if (!val_col) val_col = next.col;
            val_end = l->ptr - l->line_start_ptr + 1;
        }

        if (next.tk_type == TK_IDENT) {
            if ((macro = mapGetLen(macro_defs,next.start,next.len)) != NULL) {
                vecPush(tokens, lexemeCopy(macro));
                tk_type = macro->tk_type;
                continue;
            }
        }

        if (tk_type == -1) {
            switch (next.tk_type) {
                case TK_F64:
                case TK_I64:
                case TK_STR: 
                case TK_CHAR_CONST:
                    tk_type = next.tk_type;
                    break;
            }
        }
        if (!tokenPunctIs(&next,'\n') && !tokenPunctIs(&next,'\0')) {
            vecPush(tokens, lexemeCopy(&next));
        }
    } while (!tokenPunctIs(&next,'\n') && !tokenPunctIs(&next,'\0'));
    /* Turn off the flag */
    l->flags &= ~CCF_ACCEPT_NEWLINES;

    if (tokens->size == 0) {
        /* `#define NAME` then newline/EOF: a bare flag define. */
        mapAdd(macro_defs,ident->data,lexemeSentinal());
        vecRelease(tokens);
        return NULL;
    }
    start = tokens->entries[0]; 
    end = tokens->entries[tokens->size - 1];

    if (start == end && iters == 1) {
        mapAdd(macro_defs,ident->data,lexemeSentinal());
        vecRelease(tokens);
        return NULL;
    }

    /* Point at the value, or at the `#define` if it ran onto
     * another line (a multi-line string) */
    s64 err_col = val_col ? val_col : def_col;
    s64 err_len = val_col ? val_end - val_col : 7;

    if (tk_type == -1) {
        lexReportAt(l, def_line, err_col, err_len,
                "Error while parsing #define %s; #define must be a numerical expression or a string",
                ident->data);
        vecRelease(tokens);
        return NULL;
    }

    if (start == end) {
        expanded = lexemeCopy(start);
        mapAdd(macro_defs,ident->data,expanded);
    } else {
        /* As for #if: hand the parser a ring padded out with sentinels,
         * its error paths rewind into the slots past the expression. */
        cctrlInitMacroProcessor(macro_proccessor);
        u64 ring_cap = roundUpToNextPowerOf2(tokens->size + 1);
        Lexeme **ring = (Lexeme **)malloc(ring_cap * sizeof(Lexeme *));
        for (u64 i = 0; i < ring_cap; ++i) {
            ring[i] = i < tokens->size
                      ? (Lexeme *)tokens->entries[i]
                      : lexemeSentinal();
        }
        macro_proccessor->token_buffer->entries = ring;
        macro_proccessor->token_buffer->size = tokens->size;
        macro_proccessor->token_buffer->capacity = ring_cap;

        /* A parse error in the value (`#define X (1+`, `#define X (a+1)`
         * with `a` not a macro) lands here and is reported against the
         * define's line, instead of exiting from inside the stub. */
        jmp_buf recovery;
        macro_proccessor->current_recovery = &recovery;
        if (setjmp(recovery) != 0) {
            macro_proccessor->current_recovery = NULL;
            cctrlDiagClear(macro_proccessor);
            free(ring);
            lexReportAt(l, def_line, err_col, err_len,
                        "#define %s: the value must be a constant expression "
                        "or a string, made of literals and other macros",
                        ident->data);
            vecRelease(tokens);
            return NULL;
        }
        Ast *ast = parseExpr(macro_proccessor,16);
        macro_proccessor->current_recovery = NULL;
        free(ring);
        /* A numeric define takes the type of its folded value, as C's
         * arithmetic conversions give it: any float operand makes it an
         * F64 (`(1.5 * N)`, `(N * 1.5)`), a cast picks its own type
         * (`5(F64)` is an F64, `2.7(I64)` an I64), and an integer-only
         * value is an I64. The tokens alone can't tell: the first
         * literal or the last macro would win. */
        if (ast && ast->type &&
            (tk_type == TK_I64 || tk_type == TK_F64 ||
             tk_type == TK_CHAR_CONST)) {
            if (astIsFloatType(ast->type)) {
                tk_type = TK_F64;
            } else if (tk_type == TK_F64) {
                tk_type = TK_I64;
            }
        }
        expanded = lexemeNew(start->start,end->len-start->len);
        expanded->tk_type = tk_type;
        if (tk_type == TK_STR) {
            if (!ast && start->tk_type == TK_STR) {
                expanded->start = strndup(start->start,start->len);
                expanded->len = start->len;
                expanded->i64 = start->i64;     /* its decoded length */
            } else if (ast && ast->kind != TK_STR && ast->kind != AST_STRING) {
                lexReportAt(l, def_line, err_col, err_len,
                            "#define %s expected string but got: %s",
                            ident->data, astKindToString(ast->kind));
                vecRelease(tokens);
                return NULL;
            } else if (ast && ast->kind == AST_STRING) {
                /* Copy as we will free the AST which will free the string*/
                expanded->start = strndup(ast->sval->data,ast->sval->len);
                expanded->len = ast->sval->len;
                /* the decoded length (NUL included) a string token carries,
                 * which `u8[]` conversions use: it was left 0, so
                 * `#define APP PREFIX "/app"` made a slice too short */
                expanded->i64 = ast->real_len;
            } else {
                lexReportAt(l, def_line, err_col, err_len,
                            "failed to parse #define %s", ident->data);
                vecRelease(tokens);
                return NULL;
            }
        } else if (tk_type == TK_F64 || tk_type == TK_I64 ||
                   tk_type == TK_CHAR_CONST) {
            /* A value that parses but doesn't fold (`(Y + "s")`, a
             * string among numbers) is reported like a parse error:
             * evalIntConstExpr/evalFloatExpr would exit with no
             * location. */
            int ok = ast != NULL;
            if (ok && tk_type == TK_F64) {
                expanded->f64 = (f64)evalFloatExprOrErr(ast, &ok);
            } else if (ok) {
                expanded->i64 = evalIntConstExprOrErr(ast, &ok);
                expanded->isu64 = astIsU64Type(ast->type);
            }
            if (!ok) {
                lexReportAt(l, def_line, err_col, err_len,
                            "#define %s: the value must be a constant "
                            "expression or a string, made of literals and "
                            "other macros", ident->data);
                vecRelease(tokens);
                return NULL;
            }
            /* Spell the folded value: start/len was a span that came
             * out empty, and errors print a token as `%.*s` of it */
            char num[64];
            int num_len;
            if (tk_type == TK_F64) {
                num_len = snprintf(num, sizeof(num), "%g", expanded->f64);
                /* keep it reading as a float: `3.0`, not `3` */
                if (!strpbrk(num, ".eni") && num_len + 2 < (int)sizeof(num)) {
                    num_len += snprintf(num + num_len, 3, ".0");
                }
            } else if (expanded->isu64) {
                num_len = snprintf(num, sizeof(num), "%llu",
                                   (unsigned long long)expanded->i64);
            } else {
                num_len = snprintf(num, sizeof(num), "%lld",
                                   (long long)expanded->i64);
            }
            expanded->start = strndup(num, num_len);
            expanded->len = num_len;
            expanded->line = start->line;
        }
        mapAdd(macro_defs,ident->data,expanded);
    }
    vecRelease(tokens);
    return expanded;
}

void lexUndef(Map *macro_defs, Lexer *l) {
    Lexeme next;
    char tmp[256];
    int tmp_len = 0;
    LexPos dir = lexDirectivePos(l), at;

    if (!lex(l, &next)) {
        lexReportAt(l, dir.line, dir.col, dir.len,
                    "Syntax is: #undef <TK_IDENT>");
        return;
    }
    /* the name is on the directive's line: a word on the next one (now
     * `void`, an identifier) starts the code after it */
    if (next.tk_type != TK_IDENT || next.line != dir.line) {
        at = lexDropDirective(l, &dir, &next);
        lexReportAt(l, at.line, at.col, at.len,
                    "Syntax is: #undef <TK_IDENT>");
        return;
    }
    tmp_len = snprintf(tmp,sizeof(tmp),"%.*s",
            next.len,next.start);
    tmp[tmp_len] = '\0';
    mapRemove(macro_defs,tmp);
}

/* As for #define: a parse error in a #if/#elif condition (`#if 1 +`,
 * `#if (`) raises on the macro-processor stub, which has no file and
 * used to exit with "Parsing macro:1". Catch it and set `*failed` so
 * lexPreProcIf reports a normal diagnostic on the directive's line (and
 * the LSP gets a diagnostic instead of a crashed child). Kept out of
 * lexPreProcIf so setjmp does not clobber that function's locals. */
static Ast *lexPreProcParseCond(int *failed) {
    jmp_buf recovery;
    macro_proccessor->current_recovery = &recovery;
    if (setjmp(recovery) != 0) {
        macro_proccessor->current_recovery = NULL;
        cctrlDiagClear(macro_proccessor);
        *failed = 1;
        return NULL;
    }
    Ast *ast = parseExpr(macro_proccessor,16);
    macro_proccessor->current_recovery = NULL;
    return ast;
}

/* Load `macro_tokens` into the macro processor as a ring padded out
 * with sentinels: parsePrimary rewinds one slot to inspect the token
 * preceding an expression (and error paths rewind further), so every
 * slot the ring can reach must hold a valid lexeme. The +1 also
 * guarantees capacity exceeds size - with capacity == size the rewind
 * lands on the expression's own last token. With `terminate` the slot
 * after the tokens is a `;` the parser can read. */
static Lexeme **lexPreProcRing(Vec *macro_tokens, int terminate) {
    cctrlInitMacroProcessor(macro_proccessor);
    u64 ring_cap = roundUpToNextPowerOf2(macro_tokens->size + 1);
    Lexeme **ring = (Lexeme **)malloc(ring_cap * sizeof(Lexeme *));
    for (u64 i = 0; i < ring_cap; ++i) {
        ring[i] = i < macro_tokens->size
                  ? (Lexeme *)macro_tokens->entries[i]
                  : lexemeSentinal();
    }
    if (terminate) {
        Lexeme *semi = ring[macro_tokens->size];
        semi->tk_type = TK_PUNCT;
        semi->i64 = ';';
        semi->start = ";";
        semi->len = 1;
    }
    macro_proccessor->token_buffer->entries = ring;
    macro_proccessor->token_buffer->size = macro_tokens->size + !!terminate;
    macro_proccessor->token_buffer->capacity = ring_cap;
    return ring;
}

/* Evaluate a #if/#elif condition: 1 to take the branch, 0 not to.
 * A malformed condition is reported with lexReport and taken as false,
 * so lexing carries on into the next #elif/#else/#endif as usual. Every
 * error is reported with the directive's line consumed; a longjmp from
 * here would abandon whatever the parser was in the middle of (see
 * lexReport). */
int lexPreProcIf(Map *macro_defs, Lexer *l, const char *directive) {
    int tk_type,should_collect,in_defined,failed = 0;
    Vec *macro_tokens;
    Lexeme next,*start,*end,*expanded,*macro;

    tk_type = -1;
    should_collect = 0;
    in_defined = 0;
    macro_tokens = lexemeVecNew();
    /* The directive (tok_start still holds it) and, once read, the span
     * of the condition on its line: the errors below are found after
     * the whole line has been read. */
    s64 dir_line = l->tok_start_line, dir_col = l->tok_start_col;
    s64 dir_len = (s64)strlen(directive);
    s64 cond_col = 0, cond_end = 0;

    /* An if must be on one line a \n determines the end of a define */
    l->flags |= CCF_ACCEPT_NEWLINES;

    if (!lex(l,&next)) {
        l->flags &= ~CCF_ACCEPT_NEWLINES;
        vecRelease(macro_tokens);
        lexReportAt(l, dir_line, dir_col, dir_len,
                    "a %s must evaluate some expression", directive);
        return 0;
    }

    while (!tokenPunctIs(&next,'\n') && !tokenPunctIs(&next,'\0')){ 
        if (next.line == dir_line) {
            if (!cond_col) cond_col = next.col;
            cond_end = l->ptr - l->line_start_ptr + 1;
        }
        if (tokenPunctIs(&next,'\\')) {
            if (!lex(l,&next)) break;
            if (!tokenPunctIs(&next,'\n')) {
                char *got = lexemeToString(&next);
                lexSkipLine(l);
                vecRelease(macro_tokens);
                lexReport(l,
                        "Invalid use of '\\' should be `\\ \\n` got %s",
                        got);
                return 0;
            }
            if (!lex(l,&next)) break;
        } 

        if (next.tk_type == TK_IDENT) {
            macro = mapGetLen(macro_defs,next.start,next.len);
            if (in_defined) {
                /* `defined(X)` works by presence: the parser sees
                 * `defined()` for an undefined X (the identifier is
                 * dropped) and `defined(<value>)` for a defined one.
                 * Don't substitute literals here. */
                if (macro != NULL) vecPush(macro_tokens,lexemeCopy(macro));
            } else if (macro != NULL && macro->tk_type != -1) {
                vecPush(macro_tokens,lexemeCopy(macro));
                tk_type = macro->tk_type;
            } else if (macro == NULL &&
                       cctrlGetKeyWord(macro_proccessor,next.start,next.len)) {
                /* A type name (`u64`, an identifier unlike `U64`): kept
                 * for a cast, `(u64)7 % 4`, not taken for an undefined
                 * macro */
                vecPush(macro_tokens,lexemeCopy(&next));
            } else {
                /* Valueless flag macros (`#define FOO`, the builtin
                 * platform defines - stored as sentinels) count as 1;
                 * undefined identifiers count as 0, as in C.
                 * Substituting a literal keeps the expression
                 * well-formed either way. */
                Lexeme *lit = lexemeCopy(&next);
                lit->tk_type = TK_I64;
                lit->i64 = (macro != NULL);
                lit->isu64 = 0;
                vecPush(macro_tokens,lit);
                if (tk_type == -1) tk_type = TK_I64;
            }
            if (!lex(l,&next)) break;
            /* Re-check the loop condition rather than falling through:
             * if the identifier was the last token on the line, the
             * unconditional advance at the bottom of the loop would
             * swallow the newline and start consuming the NEXT source
             * line into this expression. */
            continue;
        }

        if (tk_type == -1) {
            switch (next.tk_type) {
                case TK_F64:
                case TK_I64:
                case TK_CHAR_CONST:
                    tk_type = next.tk_type;
                    break;
            }
        }
        if (!tokenPunctIs(&next,'\n') && !tokenPunctIs(&next,'\0')) {
            vecPush(macro_tokens,lexemeCopy(&next));
            if (next.tk_type == TK_KEYWORD && next.i64 == KW_DEFINED) {
                in_defined = 1;
            } else if (tokenPunctIs(&next,')')) {
                in_defined = 0;
            }
        }
        if (!lex(l,&next)) break;
    }
    /* Turn off the flag */
    l->flags &= ~CCF_ACCEPT_NEWLINES;

    /* Guard BEFORE touching entries: `#if` with nothing on the line
     * used to read entries[0] / entries[-1] of an empty vector. A
     * single-token expression (`#if 1`, `#if FLAG`) is legal. */
    if (macro_tokens->size == 0) {
        vecRelease(macro_tokens);
        lexReportAt(l, dir_line, dir_col, dir_len,
                    "a %s must evaluate some expression", directive);
        return 0;
    }
    s64 cond_len = cond_end - cond_col;

    start = macro_tokens->entries[0];
    end = macro_tokens->entries[macro_tokens->size-1];
    Lexeme **ring = lexPreProcRing(macro_tokens, 0);
    Ast *ast = lexPreProcParseCond(&failed);
    free(ring);
    if (failed) goto bad_condition;

    /* parseExpr stops at the first token that cannot continue the
     * expression, so `#if 0 1` used to evaluate `0` and silently drop
     * the `1`. Running off the end of the ring leaves the same state as
     * stopping on one extra token (the parser rewinds after the NULL),
     * so parse again with a `;` after the condition and check that
     * the parser stopped on it. Only once the condition parsed: a `;`
     * where an incomplete one (`#if foo(`) wanted more would send the
     * parser down paths the macro processor can't take. */
    if (ast) {
        ring = lexPreProcRing(macro_tokens, 1);
        lexPreProcParseCond(&failed);
        if (failed) {
            free(ring);
            goto bad_condition;
        }
        for (s64 i = macro_proccessor->token_buffer->tail;
             i < (s64)macro_tokens->size; ++i) {
            Lexeme *extra = ring[i];
            if (extra->tk_type != TK_COMMENT) {
                char extra_str[64];
                snprintf(extra_str, sizeof(extra_str), "%.*s",
                         extra->len, extra->start);
                free(ring);
                vecRelease(macro_tokens);
                /* At the token itself unless it came from a macro */
                int own = extra->line == dir_line && extra->col > 0;
                lexReportAt(l, dir_line, own ? extra->col : cond_col,
                            own ? extra->len : cond_len,
                            "%s: unexpected '%s' after the condition",
                            directive, extra_str);
                return 0;
            }
        }
        free(ring);
    }

    int ok = ast != NULL;
    expanded = lexemeNew(start->start,end->len-start->len);
    expanded->tk_type = tk_type;

    if (ok && tk_type == TK_STR) {
        should_collect = 1;
    } else if (ok && tk_type == TK_F64) {
        expanded->f64 = (s64)evalFloatExprOrErr(ast, &ok);
        if (expanded->f64 != 0) {
            should_collect = 1;
        }
        expanded->line = start->line;
    } else if (ok) {
        expanded->i64 = evalIntConstExprOrErr(ast, &ok);
        if (expanded->i64 != 0) {
            should_collect = 1;
        }
        expanded->line = start->line;
    }
    if (!ok) goto bad_condition;
    vecRelease(macro_tokens);

    return should_collect;

bad_condition:
    vecRelease(macro_tokens);
    lexReportAt(l, dir_line, cond_col, cond_len,
                "%s: the condition must be a constant expression made "
                "of literals, macros and defined()", directive);
    return 0;
}

/* The innermost open conditional, NULL outside any */
static LexCond *lexCondTop(Lexer *l) {
    return l->cond_depth ? &l->conds[l->cond_depth - 1] : NULL;
}

static void lexCondPush(Lexer *l, Lexeme *dir, int taken) {
    if (l->cond_depth == l->cond_cap) {
        l->cond_cap = l->cond_cap ? l->cond_cap * 2 : 8;
        l->conds = (LexCond *)realloc(l->conds,
                                      l->cond_cap * sizeof(LexCond));
    }
    LexCond *cond = &l->conds[l->cond_depth++];
    cond->line = dir->line;
    cond->col = dir->col;
    cond->len = dir->len;
    cond->taken = taken;
    cond->seen_else = 0;
}

/* At the end of the input: report every conditional still open, at
 * its directive, and let the parser finish what it has. */
static void lexCondUnterminated(Lexer *l) {
    for (int i = 0; i < l->cond_depth; ++i) {
        LexCond *cond = &l->conds[i];
        lexReportAt(l, cond->line, cond->col, cond->len, "Unterminated #if");
    }
    l->cond_depth = 0;
}

/* Is the macro named after #ifdef/#ifndef/#elifdef `dir` defined? A
 * missing or malformed name is reported and the directive taken as
 * false; lexDropDirective keeps the next line when the name is missing
 * rather than reading its first token as the name. */
static int lexCondDefined(Map *macro_defs, Lexer *l, Lexeme *dir,
                          int *defined) {
    Lexeme next;
    LexPos pos = lexDirectivePos(l), at;

    if (!lex(l, &next)) {
        lexReportAt(l, pos.line, pos.col, pos.len,
                    "Syntax is: %.*s <TK_IDENT>", dir->len, dir->start);
        return 0;
    }
    if (next.tk_type != TK_IDENT || next.line != pos.line) {
        at = lexDropDirective(l, &pos, &next);
        lexReportAt(l, at.line, at.col, at.len,
                    "Syntax is: %.*s <TK_IDENT>", dir->len, dir->start);
        return 0;
    }
    *defined = mapGetLen(macro_defs, next.start, next.len) != NULL;
    return 1;
}

/* Evaluate the condition of #if/#elif `dir` and its variants: 1 to take
 * the branch, 0 not to. A malformed one is reported and taken as false. */
static int lexCondEval(Map *macro_defs, Lexer *l, Lexeme *dir) {
    int defined = 0;
    switch (dir->i64) {
        case KW_PP_IF:   return lexPreProcIf(macro_defs, l, "#if");
        case KW_PP_ELIF: return lexPreProcIf(macro_defs, l, "#elif");
        case KW_PP_IF_DEF:
        case KW_PP_ELIF_DEF:
            return lexCondDefined(macro_defs, l, dir, &defined) && defined;
        case KW_PP_IF_NDEF:
            return lexCondDefined(macro_defs, l, dir, &defined) && !defined;
        case KW_PP_IF_JIT:
        case KW_PP_IF_AOT: {
            /* `#ifdef` with an implicit identifier: main() defines
             * exactly one of __KTM_JIT__ / __KTM_AOT__, so each form
             * is the other's negation. */
            char *flag = dir->i64 == KW_PP_IF_JIT ? "__KTM_JIT__"
                                                  : "__KTM_AOT__";
            return mapGetLen(macro_defs, flag, (s64)strlen(flag)) != NULL;
        }
        default:
            return 0;
    }
}

static int lexIsCondOpen(Lexeme *le) {
    if (le->tk_type != TK_KEYWORD) return 0;
    switch (le->i64) {
        case KW_PP_IF:
        case KW_PP_IF_DEF:
        case KW_PP_IF_NDEF:
        case KW_PP_IF_JIT:
        case KW_PP_IF_AOT:
            return 1;
        default:
            return 0;
    }
}

/* lex() a token of a skipped #if group */
static int lexSkippedToken(Lexer *l, Lexeme *le) {
    int had = l->flags & CCF_COND_SKIP;
    l->flags |= CCF_COND_SKIP;
    int rc = lex(l, le);
    if (!had) l->flags &= ~CCF_COND_SKIP;
    return rc;
}

/* Skip the dead text of the innermost conditional: up to a branch to
 * take (an #elif that holds or an #else, while no branch has been
 * taken) or its #endif, which closes it. Conditionals nested in the
 * dead text are skipped whole and their conditions not evaluated.
 * Returns 0 at the end of the input, having reported what's open. */
static int lexCondSkip(Map *macro_defs, Lexer *l) {
    Lexeme le;
    memset(&le, 0, sizeof(le));
    LexCond *cond = lexCondTop(l);
    int depth = 0;

    while (lexSkippedToken(l, &le)) {
        if (lexIsCondOpen(&le)) {
            depth++;
            continue;
        }
        if (le.tk_type != TK_KEYWORD || depth > 0) {
            if (le.tk_type == TK_KEYWORD && le.i64 == KW_PP_ENDIF) depth--;
            continue;
        }
        switch (le.i64) {
            case KW_PP_ENDIF:
                l->cond_depth--;
                return 1;

            case KW_PP_ELSE:
                if (cond->seen_else) {
                    lexReportAt(l, le.line, le.col, le.len,
                                "#else after #else");
                    break;
                }
                cond->seen_else = 1;
                if (!cond->taken) {
                    cond->taken = 1;
                    return 1;
                }
                break;

            case KW_PP_ELIF:
            case KW_PP_ELIF_DEF:
                if (cond->seen_else) {
                    lexReportAt(l, le.line, le.col, le.len,
                                "%.*s after #else", le.len, le.start);
                    break;
                }
                if (!cond->taken && lexCondEval(macro_defs, l, &le)) {
                    cond->taken = 1;
                    return 1;
                }
                break;
        }
    }
    lexCondUnterminated(l);
    return 0;
}

void lexSetAsmFlags(Lexer *l) {
    l->flags |= (CCF_MULTI_COLON|CCF_ACCEPT_NEWLINES|CCF_ASM_BLOCK);
}

void lexUnSetAsmFlags(Lexer *l) {
    l->flags &= ~(CCF_MULTI_COLON|CCF_ACCEPT_WHITESPACE|CCF_ACCEPT_NEWLINES|CCF_ASM_BLOCK);
}

static Lexeme *lexTokenRaw(Map *macro_defs, Lexer *l) {
    Lexeme le,*copy;

    macro_proccessor->macro_defs = macro_defs;

    while (1) {
        memset(&le, 0, sizeof(le));
        if (!lex(l,&le)) {
            lexCondUnterminated(l);
            return NULL;
        }

        if (l->flags & (CCF_ASM_BLOCK) && tokenPunctIs(&le, '}')) {
            copy = lexemeCopy(&le);
            /* turn off assembly lexing */
            lexUnSetAsmFlags(l);
            return copy;
        }

        /* Whitespace is kept inside the block so the asm keeps its
         * columns for libtasm's errors. Switched on here, as the lexer
         * reads the `{`: the parser runs several tokens behind, so a
         * flag set when the parser reaches the `{` could land after the
         * lexer had already passed the closing `}`, and then the code
         * after the block was lexed with whitespace tokens. */
        if (l->flags & (CCF_ASM_BLOCK) && tokenPunctIs(&le, '{')) {
            l->flags |= CCF_ACCEPT_WHITESPACE;
            return lexemeCopy(&le);
        }

        if (le.tk_type == TK_KEYWORD) {
            switch (le.i64) {
                case KW_ASM:
                    lexSetAsmFlags(l);
                    copy = lexemeCopy(&le);
                    return copy;

                case KW_PP_INCLUDE: {
                    lexInclude(l);
                    continue;
                }
                case KW_PP_LINK: {
                    lexLink(l);
                    continue;
                }
                case KW_PP_DEFINE: {
                    copy = lexDefine(macro_defs,l);
                    continue;
                }
                case KW_PP_UNDEF: {
                    lexUndef(macro_defs, l);
                    continue;
                }

                case KW_PP_IF_DEF:
                case KW_PP_IF_NDEF:
                case KW_PP_IF_JIT:
                case KW_PP_IF_AOT:
                case KW_PP_IF: {
                    int taken = lexCondEval(macro_defs,l,&le);
                    lexCondPush(l,&le,taken);
                    if (!taken && !lexCondSkip(macro_defs,l)) return NULL;
                    continue;
                }

                case KW_PP_ELIF_DEF:
                case KW_PP_ELIF:
                case KW_PP_ELSE: {
                    /* Reached from a branch being collected, so one
                     * branch was taken and the rest of the #if is dead */
                    LexCond *cond = lexCondTop(l);
                    if (!cond) {
                        /* Dropped, with an #elif's condition */
                        if (le.i64 != KW_PP_ELSE) lexSkipLine(l);
                        lexReportAt(l, le.line, le.col, le.len,
                                    "%.*s without #if", le.len, le.start);
                        continue;
                    }
                    if (cond->seen_else) {
                        lexReportAt(l, le.line, le.col, le.len,
                                    "%.*s after #else", le.len, le.start);
                    }
                    if (le.i64 == KW_PP_ELSE) cond->seen_else = 1;
                    if (!lexCondSkip(macro_defs,l)) return NULL;
                    continue;
                }

                case KW_PP_ENDIF:
                    if (!l->cond_depth) {
                        lexReportAt(l, le.line, le.col, le.len,
                                    "#endif without #if");
                    } else {
                        l->cond_depth--;
                    }
                    continue;

                case KW_PP_ERROR:
                    /* Reported like any other error; the rest of the
                     * line goes and lexing carries on. */
                    lexErrorDirective(l);
                    continue;

                default:
                    copy = lexemeCopy(&le);
                    return copy;
            }
        }
        
        if (le.tk_type == TK_IDENT) {
            copy = lexemeCopy(&le);
            return copy;
        }

        copy = lexemeCopy(&le);
        return copy;
    }
    return NULL;
}

/* ---- modules (ktm M1) ----
 *
 * `import a.b;` at the top of a file finds the directory a/b (under the
 * main file's directory, then <install>/modules, then each KTM_PATH
 * entry; found twice is an error) and pushes its *.ktm files, in name
 * order, twice: a declarations-only pass, then the full one (the parser
 * reads Lexeme.mod_pass). Its own imports load first, so a module's code
 * comes before its importer's. Each file of the directory starts with
 * `module b;`. In a file that imports it, `b.name` (or `alias.name`) is
 * the single token `a.b.name`. */

static int lexIsDir(const char *path);

/* <install>/modules from the builtin root <install>/include/: by text, as
 * an install without include/ (the stdlib is modules) can't resolve
 * `include/..` */
static char *lexInstallModules(Lexer *l) {
    if (l->std_modules) return l->std_modules;
    char *root = l->builtin_root;
    size_t len = strlen(root);
    while (len > 0 && root[len - 1] == '/') len--;
    if (len >= 7 && !strncmp(root + len - 7, "include", 7) &&
        (len == 7 || root[len - 8] == '/')) {
        return mprintf("%.*smodules", (int)(len - 7), root);
    }
    return mprintf("%s../modules", root);
}

/* Is `file` part of one of the standard library's modules
 * (<install>/modules)? */
static int lexFileInStdModules(Lexer *l, LexFile *file) {
    if (!file || !file->module_id || !l->builtin_root) return 0;
    char *root = realpath(lexInstallModules(l), NULL);
    char *dir = realpath(l->modules[file->module_id - 1]->dir->data, NULL);
    size_t len = root ? strlen(root) : 0;
    int inside = root && dir && !strncmp(dir, root, len) && dir[len] == '/';
    free(root);
    free(dir);
    return inside;
}

static LexModule *lexModuleFind(Lexer *l, AoStr *path) {
    for (int i = 0; i < l->modules_len; ++i) {
        if (aoStrCmp(l->modules[i]->path, path)) return l->modules[i];
    }
    return NULL;
}

static int lexIsDir(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
}

/* The directory of module `path` (dots as slashes) or NULL; an error
 * when two of the places have it */
static char *lexModuleDir(Lexer *l, AoStr *path, Lexeme *at) {
    char *rel = mprintf("%s", path->data);
    for (char *p = rel; *p; ++p) if (*p == '.') *p = '/';

    char *roots[64];
    int nroots = 0;
    roots[nroots++] = l->program_root ? l->program_root : ".";
    if (l->builtin_root) {
        roots[nroots++] = lexInstallModules(l);
    }
    char *ktm_path = getenv("KTM_PATH");
    if (ktm_path && *ktm_path) {
        char *copy = mprintf("%s", ktm_path), *save = NULL;
        for (char *dir = strtok_r(copy, ":", &save); dir && nroots < 64;
             dir = strtok_r(NULL, ":", &save)) {
            roots[nroots++] = dir;
        }
    }

    char *found = NULL;
    for (int i = 0; i < nroots; ++i) {
        char *cand = mprintf("%s/%s", roots[i], rel);
        if (!lexIsDir(cand)) continue;
        if (found) {
            /* the same directory through two roots (a tree built where
             * the standard library's modules are) is found once */
            char *a = realpath(found, NULL), *b = realpath(cand, NULL);
            int same = a && b && !strcmp(a, b);
            free(a);
            free(b);
            if (same) continue;
            lexReportAt(l, at->line, at->col, at->len,
                        "module %s is found twice: %s and %s",
                        path->data, found, cand);
            return found;
        }
        found = cand;
    }
    return found;
}

/* Is there a module `name` (len bytes, dots as slashes) where import
 * looks: for "module vec is not imported" hints, so nothing is reported */
int lexModuleExists(Lexer *l, const char *name, int len) {
    char *rel = mprintf("%.*s", len, name);
    for (char *p = rel; *p; ++p) if (*p == '.') *p = '/';
    if (lexIsDir(mprintf("%s/%s", l->program_root ? l->program_root : ".", rel))) return 1;
    if (l->builtin_root && lexIsDir(mprintf("%s/%s", lexInstallModules(l), rel))) return 1;
    char *ktm_path = getenv("KTM_PATH");
    if (ktm_path && *ktm_path) {
        char *copy = mprintf("%s", ktm_path), *save = NULL;
        for (char *dir = strtok_r(copy, ":", &save); dir; dir = strtok_r(NULL, ":", &save)) {
            if (lexIsDir(mprintf("%s/%s", dir, rel))) return 1;
        }
    }
    return 0;
}

/* `ktm -lib a.b dir/a/b`: modules are found from dir. 0 when the
 * directory doesn't end in a/b. */
int lexSetModuleLibRoot(Lexer *l, char *dir, char *path) {
    char *rel = mprintf("%s", path);
    for (char *p = rel; *p; ++p) if (*p == '.') *p = '/';
    int dlen = strlen(dir), rlen = strlen(rel);
    while (dlen > 1 && dir[dlen - 1] == '/') dlen--;
    if (dlen < rlen || memcmp(dir + dlen - rlen, rel, rlen) ||
        (dlen > rlen && dir[dlen - rlen - 1] != '/')) {
        return 0;
    }
    l->program_root = dlen == rlen ? mprintf(".")
                                   : mprintf("%.*s", dlen - rlen - 1, dir);
    l->building_module = mprintf("%s", path);
    return 1;
}

static int lexCmpStr(const void *a, const void *b);

static int lexHasKtmFiles(const char *path) {
    DIR *dir = opendir(path);
    struct dirent *ent;
    int found = 0;
    while (dir && !found && (ent = readdir(dir)) != NULL) {
        int len = strlen(ent->d_name);
        found = len > 4 && !strcmp(ent->d_name + len - 4, ".ktm");
    }
    if (dir) closedir(dir);
    return found;
}

/* The module directories under `dir` (`rel` its path from the tree's
 * root, "" at the root), into paths ("net.http") and dirs */
static void lexModuleTreeWalk(char *dir, char *rel, Vec *paths, Vec *dirs) {
    DIR *d = opendir(dir);
    char **names = NULL;
    int n = 0, cap = 0;
    struct dirent *ent;
    while (d && (ent = readdir(d)) != NULL) {
        if (ent->d_name[0] == '.') continue;
        char *sub = mprintf("%s/%s", dir, ent->d_name);
        if (!lexIsDir(sub)) continue;
        if (n == cap) {
            cap = cap ? cap * 2 : 8;
            names = realloc(names, sizeof(char *) * cap);
        }
        names[n++] = mprintf("%s", ent->d_name);
    }
    if (d) closedir(d);
    qsort(names, n, sizeof(char *), lexCmpStr);
    for (int i = 0; i < n; ++i) {
        /* the prelude is read by every compile, never a library's */
        if (!*rel && !strcmp(names[i], "prelude")) continue;
        /* nor the crash handler, compiled into every program
         * (lexPushRuntime) */
        if (!strcmp(rel, "rt") && !strcmp(names[i], "crash")) continue;
        char *sub = mprintf("%s/%s", dir, names[i]);
        char *path = *rel ? mprintf("%s.%s", rel, names[i]) : names[i];
        if (lexHasKtmFiles(sub)) {
            vecPush(paths, path);
            vecPush(dirs, sub);
        }
        lexModuleTreeWalk(sub, path, paths, dirs);
    }
    free(names);
}

/* `ktm -lib name dir` where dir is not module `name`'s directory: every
 * module under dir goes into one library. Collects their paths and
 * directories, finds modules from dir, and gives the program that
 * imports them all; NULL when there is no module under dir. */
char *lexModuleTreeImports(Lexer *l, char *dir, Vec *paths, Vec *dirs) {
    char *real = realpath(dir, NULL);
    if (!real) return NULL;
    lexModuleTreeWalk(real, "", paths, dirs);
    if (paths->size == 0) {
        free(real);
        return NULL;
    }
    l->program_root = mprintf("%s", real);
    l->building_tree = real;
    AoStr *src = aoStrNew();
    for (u64 i = 0; i < paths->size; ++i) {
        aoStrCatPrintf(src, "import %s;\n", (char *)paths->entries[i]);
    }
    return src->data;
}

static int lexIsFile(const char *path) {
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
}

/* The host's target: a package is prebuilt only for it (the assembler
 * and linker that build its library are the host's) */
static enum CliTarget lexHostTarget(void) {
#if IS_ARM_64 && IS_LINUX
    return TARGET_AARCH64_UNKNOWN_LINUX_GNU;
#elif IS_ARM_64 && IS_BSD
    return TARGET_AARCH64_APPLE_DARWIN;
#elif IS_X86_64 && IS_LINUX
    return TARGET_X86_64_UNKNOWN_LINUX_GNU;
#else
    return TARGET_X86_64_APPLE_DARWIN;
#endif
}

/* The compiler itself, hashed once: a rebuilt ktm (same version, new
 * code) makes new libraries, whose records rt.crash reads */
static const char *lexSelfHash(void) {
    static char *hash = NULL;
    if (!hash) {
        char *self = pkgSelfPath(NULL);
        hash = self ? pkgSha256File(self) : NULL;
        if (!hash) hash = "";
    }
    return hash;
}

/* A package from the module cache, prebuilt once into
 * <cache>/.prebuilt/<key>: its library and .ktmlib marker, made by
 * running this ktm with --prebuild-pkg on a program that imports it. The
 * key covers the compiler (and its stdlib), the target, -Memsafe, the
 * -D names, the package and the program's whole build list (a
 * package's dependencies are the versions the program picks), so any
 * change makes a new one. Its own imports of packages are prebuilt the
 * same way while it builds, so each package's code is in exactly one
 * library (two copies would split its globals). Not for a package
 * replaced by a directory (being edited) or a program whose ktm.mod
 * has such a replace, nor for another target, the JIT, the LSP, the
 * REPL or with KTM_PREBUILD=0. A failed build is remembered (<key>.failed, its
 * output) and the package is built from source, as before. NULL: from
 * source. */
static char *lexPackagePrebuilt(Lexer *l, LexModule *m) {
    Cctrl *cc = l->cc;
    if (!cc || !m->package || l->building_tree) return NULL;
    if (cc->flags & (CCTRL_LSP | CCTRL_REPL)) return NULL;
    /* not for the JIT: a shared library can't call into JIT'd code (the
     * stdlib modules the program compiles itself), so it compiles
     * packages as before */
    if (mapGet(cc->macro_defs, "__KTM_JIT__")) return NULL;
    const char *off = getenv("KTM_PREBUILD");
    if (off && !strcmp(off, "0")) return NULL;
    if (cc->target != lexHostTarget()) return NULL;
    char *dir = realpath(m->dir->data, NULL);
    char *real_cache = realpath(pkgCacheDir(), NULL);
    if (!dir || !real_cache) return NULL;
    size_t clen = strlen(real_cache);
    if (strncmp(dir, real_cache, clen) || dir[clen] != '/') return NULL;
    const char *root = l->program_root ? l->program_root : ".";
    char *list = pkgBuildListKey(root);
    if (!list) return NULL;

    AoStr *k = aoStrNew();
    aoStrCatPrintf(k, "ktm %s %s\ninstall %s\n", cctrlGetVersion(), lexSelfHash(),
                   cc->install_dir ? cc->install_dir : "");
    char *libktm = mprintf("%s/lib/libktm.a", cc->install_dir ? cc->install_dir : "/usr/local");
    struct stat st;
    if (stat(libktm, &st) == 0) {
        aoStrCatPrintf(k, "libktm %lld %lld\n", (long long)st.st_size, (long long)st.st_mtime);
    }
    aoStrCatPrintf(k, "target %d memsafe %d\n", (int)cc->target,
                   (cc->flags & CCTRL_MEMSAFE) ? 1 : 0);
    if (cc->cmdline_defines) {
        listForEach(cc->cmdline_defines) aoStrCatPrintf(k, "-D%s\n", (char *)it->value);
    }
    aoStrCatPrintf(k, "package %s %s %s\n%s", m->package, m->path->data, dir + clen + 1, list);
    char *key = pkgSha256Hex(k->data, k->len);
    char *base = mprintf("%s/.prebuilt", real_cache);
    char *out = mprintf("%s/%s", base, key);
    char *marker = mprintf("%s/.ktmlib", out);
    char *failed = mprintf("%s.failed", out);
    free(real_cache);
    if (lexIsFile(marker)) return out;
    if (lexIsFile(failed)) return NULL;

    /* one build of it at a time, whatever builds it */
    mkdir(base, 0755);
    char *lock = mprintf("%s.lock", out);
    int fd = open(lock, O_CREAT | O_RDWR, 0644);
    if (fd < 0) return NULL;
    flock(fd, LOCK_EX);
    if (lexIsFile(marker) || lexIsFile(failed)) {
        flock(fd, LOCK_UN);
        close(fd);
        return lexIsFile(marker) ? out : NULL;
    }
    char *tmp = mprintf("%s.tmp%d", out, (int)getpid());
    mkdir(tmp, 0755);
    char *self = pkgSelfPath(NULL);
    AoStr *cmd = aoStrNew();
    aoStrCatPrintf(cmd, "'%s'", self ? self : "ktm");
    if (cc->install_dir) aoStrCatPrintf(cmd, " --install-dir '%s'", cc->install_dir);
    if (cc->flags & CCTRL_MEMSAFE) aoStrCatPrintf(cmd, " -Memsafe");
    if (cc->cmdline_defines) {
        listForEach(cc->cmdline_defines) aoStrCatPrintf(cmd, " -D%s", (char *)it->value);
    }
    char *root_real = realpath(root, NULL);
    aoStrCatPrintf(cmd, " --test-root '%s' --prebuild-pkg '%s' --prebuild-mod '%s' "
                   "--prebuild-dir '%s' -lib '%s' '%s' > '%s/build.log' 2>&1",
                   root_real ? root_real : root, m->package, m->path->data, dir,
                   m->path->data, tmp, tmp);
    free(root_real);
    int rc = system(cmd->data);
    char *tmp_marker = mprintf("%s/.ktmlib", tmp);
    if (rc == 0 && lexIsFile(tmp_marker) && rename(tmp, out) == 0) {
        flock(fd, LOCK_UN);
        close(fd);
        return out;
    }
    /* remembered, with what it said, so it isn't tried on every build */
    char *mv = mprintf("mv -f '%s/build.log' '%s' 2>/dev/null; rm -rf '%s'", tmp, failed, tmp);
    if (system(mv) != 0 || !lexIsFile(failed)) {
        FILE *f = fopen(failed, "w");
        if (f) fclose(f);
    }
    flock(fd, LOCK_UN);
    close(fd);
    return NULL;
}

/* The libraries a .ktmlib marker names (relative to `base`, the
 * marker's directory) added to the program's: the archives for AOT,
 * the shared objects for the JIT; and whether the module has an _init */
static void lexReadMarker(Lexer *l, LexModule *m, const char *marker, const char *base) {
    s64 len = 0;
    char *text = lexTryReadfile((char *)marker, &len);
    if (!text) return;
    int jit = mapGet(l->cc->macro_defs, "__KTM_JIT__") != NULL;
    char *save = NULL;
    for (char *line = strtok_r(text, "\n", &save); line;
         line = strtok_r(NULL, "\n", &save)) {
        char *path = NULL;
        List **libs = NULL;
        /* its globals have run-time initialisers: the library has its
         * `_init`, which the program calls (M2) */
        if (!strcmp(line, "init")) {
            m->has_init = 1;
            continue;
        }
        if (!jit && !strncmp(line, "static ", 7)) {
            path = line + 7;
            libs = &l->cc->object_files;
        } else if (jit && !strncmp(line, "shared ", 7)) {
            path = line + 7;
            libs = &l->cc->shared_object_files;
        }
        if (!libs) continue;
        if (path[0] != '/') {
            char *joined = mprintf("%s/%s", base, path);
            char *real = realpath(joined, NULL);
            path = real ? mprintf("%s", real) : joined;
            free(real);
        }
        AoStr *lib = aoStrDupRaw(path, strlen(path));
        if (*libs == NULL) *libs = listNew();
        if (!listContainsAoStr(*libs, lib)) listAppend(*libs, lib);
    }
}

/* A module with a .ktmlib marker (written by `ktm -lib`), or a package
 * prebuilt into the cache (lexPackagePrebuilt): only its declarations
 * are read, and its library is linked: the archive by AOT, the shared
 * object by the JIT. 0 when it has none. */
static int lexModulePrebuilt(Lexer *l, LexModule *m) {
    if (l->building_module && !strcmp(l->building_module, m->path->data)) {
        return 0;
    }
    if (l->test_module_id == m->id) return 0;
    if (l->building_tree) {
        char *dir = realpath(m->dir->data, NULL);
        size_t len = strlen(l->building_tree);
        int inside = dir && !strncmp(dir, l->building_tree, len) &&
                     (dir[len] == '/' || dir[len] == '\0');
        free(dir);
        if (inside) return 0;
    }
    char *marker = mprintf("%s/.ktmlib", m->dir->data);
    if (!lexIsFile(marker)) {
        if (l->prebuilding && !m->package) return 1;
        char *pre = lexPackagePrebuilt(l, m);
        if (!pre) return 0;
        lexReadMarker(l, m, mprintf("%s/.ktmlib", pre), pre);
        return 1;
    }
    if (!l->cc) return 1;
    lexReadMarker(l, m, marker, m->dir->data);
    return 1;
}

static int lexCmpStr(const void *a, const void *b) {
    return strcmp(*(char **)a, *(char **)b);
}

/* Push module `m`'s files: they are read next, the names pass first,
 * then the declarations-only pass, then the full pass (a prebuilt module
 * has no full pass: its code is in its library) */
static void lexModuleLoad(Lexer *l, LexModule *m, Lexeme *at) {
    DIR *dir = opendir(m->dir->data);
    char **names = NULL;
    int n = 0, cap = 0;
    struct dirent *ent;
    /* its tests (*_test.ktm) only when `ktm test` tests it */
    int tests = 0;
    if (l->test_dir) {
        char *real = realpath(m->dir->data, NULL);
        tests = real && !strcmp(real, l->test_dir);
        free(real);
        if (tests) l->test_module_id = m->id;
    }
    while (dir && (ent = readdir(dir)) != NULL) {
        int len = strlen(ent->d_name);
        if (len < 5 || strcmp(ent->d_name + len - 4, ".ktm")) continue;
        if (!tests && len > 9 && !strcmp(ent->d_name + len - 9, "_test.ktm")) continue;
        if (n == cap) {
            cap = cap ? cap * 2 : 8;
            names = realloc(names, sizeof(char *) * cap);
        }
        names[n++] = mprintf("%s/%s", m->dir->data, ent->d_name);
    }
    if (dir) closedir(dir);
    if (n == 0) {
        lexReportAt(l, at->line, at->col, at->len,
                    "module %s has no .ktm files in %s",
                    m->path->data, m->dir->data);
        lexModuleLoaded(l, m);
        return;
    }
    qsort(names, n, sizeof(char *), lexCmpStr);

    int prebuilt = lexModulePrebuilt(l, m);
    m->prebuilt = prebuilt;
    int last_pass = prebuilt ? LEX_PASS_DECLS : LEX_PASS_FULL;
    int passes[] = {LEX_PASS_FULL, LEX_PASS_DECLS, LEX_PASS_NAMES};
    l->mod_stack[l->mod_depth++] = m->id;
    /* The file stack is last in, first out */
    for (int p = prebuilt ? 1 : 0; p < 3; ++p) {
        int pass = passes[p];
        for (int i = n - 1; i >= 0; --i) {
            lexPushFile(l, aoStrDupRaw(names[i], strlen(names[i])));
            l->cur_file->module_id = m->id;
            l->cur_file->mod_pass = pass;
            l->cur_file->last_of_module = pass == last_pass && i == n - 1;
        }
    }
    free(names);
}

/* The module path after `import` or `module`: ident {. ident}, then `;`.
 * NULL (reported) when malformed. */
static AoStr *lexModulePath(Map *macro_defs, Lexer *l, Lexeme *first,
                            Lexeme **_last, int *_more)
{
    if (_more) *_more = 0;
    AoStr *path = aoStrNew();
    Lexeme *tok = first;
    while (1) {
        if (!tok || tok->tk_type != TK_IDENT) {
            Lexeme *at = tok ? tok : first;
            lexReportAt(l, at->line, at->col, at->len,
                        "expected a module name, as in `import net;`");
            return NULL;
        }
        aoStrCatLen(path, tok->start, tok->len);
        *_last = tok;
        tok = lexTokenRaw(macro_defs, l);
        if (tokenPunctIs(tok, ';')) return path;
        /* `import a, b;`: another module follows */
        if (_more && tokenPunctIs(tok, ',')) {
            *_more = 1;
            return path;
        }
        if (!tokenPunctIs(tok, '.')) {
            Lexeme *at = tok ? tok : *_last;
            lexReportAt(l, at->line, at->col, at->len,
                        _more ? "expected `;` or `,` after the module name"
                              : "expected `;` after the module name");
            return NULL;
        }
        aoStrPutChar(path, '.');
        tok = lexTokenRaw(macro_defs, l);
    }
}

static void lexImportOne(Lexer *l, LexFile *file, Lexeme *kw, AoStr *alias,
                         AoStr *path, Lexeme *first, Lexeme *last,
                         char *pkg, char *pkg_dir);

/* The module path a package gets inside the compiler: its import path's
 * elements as names, then its `module` name ("github.com/x/ktm-pg" with
 * `module pg;`: github_com.x.ktm_pg.pg) */
static AoStr *lexPackageModulePath(const char *pkg, const char *name) {
    AoStr *out = aoStrNew();
    int at_start = 1;
    for (const char *p = pkg; *p; ++p) {
        if (*p == '/') {
            aoStrPutChar(out, '.');
            at_start = 1;
            continue;
        }
        if (at_start && isdigit((unsigned char)*p)) aoStrPutChar(out, '_');
        aoStrPutChar(out, isalnum((unsigned char)*p) || *p == '_' ? *p : '_');
        at_start = 0;
    }
    aoStrCatPrintf(out, ".%s", name);
    return out;
}

/* `import [alias =] "path"`, the string just read: the package's
 * directory, its module path and, without an alias, its module name as
 * the prefix. 0 (reported) when it can't be used. */
static int lexPackageImport(Lexer *l, Lexeme *str, AoStr **_alias, AoStr **_path,
                            char **_pkg, char **_dir)
{
    char *pkg = mprintf("%.*s", (int)str->len, str->start);
    char *err = NULL;
    char *dir = pkgResolveImport(l->program_root ? l->program_root : ".", pkg, &err);
    if (!dir) {
        lexReportAt(l, str->line, str->col, str->len + 2, "%s", err);
        return 0;
    }
    char *name = pkgModuleName(dir);
    if (!name) {
        lexReportAt(l, str->line, str->col, str->len + 2,
                    "package \"%s\" has no .ktm file starting with `module <name>;` in %s",
                    pkg, dir);
        return 0;
    }
    if (!*_alias) *_alias = aoStrDupRaw(name, strlen(name));
    *_path = lexPackageModulePath(pkg, name);
    *_pkg = pkg;
    *_dir = dir;
    return 1;
}

/* `import [alias =] a.b {, [alias =] c};`, the `import` just lexed. The
 * whole list is read first: importing a module pushes its files, which
 * are read next. They are pushed last first, so read in the list's order. */
static void lexImport(Map *macro_defs, Lexer *l, Lexeme *kw) {
    /* the importing file (an import line's rest is read from a file of
     * its own, see below) */
    LexFile *file = l->cur_file;
    Lexeme *here = kw;      /* the `import` lexed, in the file being read */
    if (file && file->import_owner) {
        if (file->import_kw) kw = file->import_kw;
        file = file->import_owner;
    }
    AoStr *aliases[64], *paths[64];
    Lexeme *firsts[64], *lasts[64];
    char *item_starts[64], *pkgs[64], *pkg_dirs[64];
    int n = 0, more = 1;
    while (more) {
        /* where the item starts in the source (a string token's start
         * is its decoded copy) */
        char *item_start = l->ptr;
        while (*item_start == ' ' || *item_start == '\t' || *item_start == '\n' ||
               *item_start == '\r') item_start++;
        Lexeme *first = lexTokenRaw(macro_defs, l);
        if (first && first->tk_type != TK_STR) item_start = first->start;
        AoStr *alias = NULL;
        if (first && first->tk_type == TK_IDENT) {
            Lexeme *eq = lexTokenRaw(macro_defs, l);
            if (tokenPunctIs(eq, '=')) {
                alias = aoStrDupRaw(first->start, first->len);
                first = lexTokenRaw(macro_defs, l);
            } else {
                /* not an alias: give the token back by re-lexing from it */
                l->ptr = eq ? eq->start : l->ptr;
            }
        }
        Lexeme *last = first;
        AoStr *path = NULL;
        char *pkg = NULL, *pkg_dir = NULL;
        if (first && first->tk_type == TK_STR) {
            /* `import "github.com/x/y";`: a package */
            Lexeme *after = lexTokenRaw(macro_defs, l);
            more = tokenPunctIs(after, ',');
            if (!more && !tokenPunctIs(after, ';')) {
                Lexeme *at = after ? after : first;
                lexReportAt(l, at->line, at->col, at->len,
                            "expected `;` or `,` after the package path");
                break;
            }
            if (!lexPackageImport(l, first, &alias, &path, &pkg, &pkg_dir)) continue;
        } else {
            path = lexModulePath(macro_defs, l, first, &last, &more);
        }
        if (!path) break;
        if (n < 64) {
            pkgs[n] = pkg;
            pkg_dirs[n] = pkg_dir;
            aliases[n] = alias;
            paths[n] = path;
            firsts[n] = first;
            lasts[n] = last;
            item_starts[n] = item_start;
            n++;
        }
    }
    if (n == 0) return;
    /* One module at a time: a module's files are read before the next
     * import, so the next one's own imports see it loaded. The rest of
     * the list goes into a file read after the first module's files,
     * which are pushed on top of it. */
    if (n > 1) {
        /* The statement's own lines, from the start of the `import` line
         * to its `;`, with everything but `import` before the second
         * module blanked: the rest of the list keeps its lines and
         * columns, so its diagnostics point into the file as written */
        AoStr *rest = aoStrNew();
        char *line_start = here->start - (here->col - 1);
        for (char *p = line_start; p < l->ptr; ++p) {
            char c = *p;
            int keep = (p >= here->start && p < here->start + here->len) ||
                       p >= item_starts[1] || c == '\n';
            aoStrPutChar(rest, keep ? c : ' ');
        }
        aoStrCat(rest, "\n");
        LexFile *cur = l->cur_file;
        lexPushString(l, cur->filename->data, rest->data, rest->len);
        LexFile *more = l->cur_file;
        more->lineno = here->line;
        l->lineno = here->line;
        more->import_owner = file;
        more->import_kw = lexemeCopy(kw);
        more->module_id = cur->module_id;
        more->mod_pass = cur->mod_pass;
        more->is_std = cur->is_std;
        more->file_id = cur->file_id;
    }
    lexImportOne(l, file, kw, aliases[0], paths[0], firsts[0], lasts[0],
                 pkgs[0], pkg_dirs[0]);
}

/* How many columns `import ... last` takes, for an underline. By the
 * columns: a later module of a list is read again from a copy of the
 * line (lexImport), so its tokens' pointers aren't in the keyword's
 * buffer (subtracting them printed megabytes of ^). */
static s64 lexImportSpan(Lexeme *kw, Lexeme *last) {
    if (last->line != kw->line || last->col < kw->col) {
        return kw->len;
    }
    return last->col + last->len - kw->col;
}

/* <install>/modules/a/b for module a.b */
static char *lexInstallModuleDir(Lexer *l, const char *path) {
    char *dir = mprintf("%s/%s", lexInstallModules(l), path);
    char *rel = dir + strlen(dir) - strlen(path);
    for (; *rel; ++rel) {
        if (*rel == '.') *rel = '/';
    }
    return dir;
}

/* One module of an `import` line of `file` */
static void lexImportOne(Lexer *l, LexFile *file, Lexeme *kw, AoStr *alias,
                         AoStr *path, Lexeme *first, Lexeme *last,
                         char *pkg, char *pkg_dir)
{
    AoStr *prefix = alias ? alias : aoStrDupRaw(last->start, last->len);
    int auto_prelude = file && file->filename &&
                       !strcmp(file->filename->data, LEX_PRELUDE_FILE);
    int auto_rt = file && file->filename &&
                  !strcmp(file->filename->data, LEX_RT_FILE);
    /* rt, the runtime: in every program, imported by no program. The
     * standard library's own modules may, and a -lib build of a tree
     * that has it (its generated program imports every module). */
    if (!strcmp(path->data, "rt") && !auto_rt &&
        !(l->building_tree && !file->module_id) &&
        !lexFileInStdModules(l, file)) {
        if (!LEX_PASS_QUIET(file->mod_pass)) {
            lexReportAt(l, kw->line, kw->col,
                        lexImportSpan(kw, last),
                        "rt is internal: the runtime is part of every program "
                        "and can't be imported");
        }
        return;
    }
    if (!auto_prelude && !strcmp(path->data, "prelude")) {
        if (!LEX_PASS_QUIET(file->mod_pass)) {
            lexReportAt(l, kw->line, kw->col,
                        lexImportSpan(kw, last),
                        "the prelude is always imported: its names need no "
                        "import and no prefix");
        }
        return;
    }

    LexModule *m = lexModuleFind(l, path);
    if (m && !m->loaded) {
        /* still being loaded: it imports itself through the chain */
        AoStr *chain = aoStrNew();
        int start = 0;
        for (int i = 0; i < l->mod_depth; ++i) {
            if (l->mod_stack[i] == m->id) { start = i; break; }
        }
        for (int i = start; i < l->mod_depth; ++i) {
            aoStrCatPrintf(chain, "%s -> ", l->modules[l->mod_stack[i] - 1]->path->data);
        }
        aoStrCatPrintf(chain, "%s", path->data);
        if (!LEX_PASS_QUIET(file->mod_pass)) {
            lexReportAt(l, kw->line, kw->col, kw->len,
                        "circular import: %s", chain->data);
        }
        return;
    }
    if (!m) {
        /* the prelude is the install's, whatever else is called that */
        /* and the runtime's (rt, str): the install's too */
        char *dir = pkg_dir ? pkg_dir
                  : auto_prelude
                  ? mprintf("%s/prelude", lexInstallModules(l))
                  : auto_rt ? lexInstallModuleDir(l, path->data)
                  : lexModuleDir(l, path, first);
        if (!dir) {
            lexReportAt(l, first->line, first->col,
                        (last->start + last->len) - first->start,
                        "module %s not found (no directory %s under %s, "
                        "the stdlib modules or KTM_PATH)",
                        path->data, path->data, l->program_root);
            return;
        }
        m = (LexModule *)calloc(1, sizeof(LexModule));
        m->path = path;
        m->package = pkg;
        m->foreign = pkg && !pkgIsProjectPath(l->program_root ? l->program_root : ".", pkg);
        m->dir = aoStrDupRaw(dir, strlen(dir));
        if (pkg && l->cc) cctrlAddPackage(l->cc, path->data, pkg, dir);
        if (l->modules_len == l->modules_cap) {
            l->modules_cap = l->modules_cap ? l->modules_cap * 2 : 8;
            l->modules = realloc(l->modules, sizeof(LexModule *) * l->modules_cap);
        }
        l->modules[l->modules_len++] = m;
        m->id = l->modules_len;
    }

    LexImport *imp = (LexImport *)calloc(1, sizeof(LexImport));
    imp->prefix = prefix;
    imp->module = m;
    /* the `import` of `ktm -lib`'s generated program */
    imp->used = ((l->building_module || l->building_tree) && !file->module_id) ||
                auto_prelude || auto_rt || (l->repl && !file->module_id);
    imp->line = kw->line;
    imp->col = kw->col;
    imp->len = kw->len;
    imp->next = file->imports;
    file->imports = imp;
    if (l->repl && !file->module_id) {
        LexImport *keep = (LexImport *)malloc(sizeof(LexImport));
        *keep = *imp;
        keep->next = l->repl_imports;
        l->repl_imports = keep;
    }

    if (!m->loaded && l->mod_depth < 64) {
        lexModuleLoad(l, m, first);
    }
}

/* The prelude (<install>/modules/prelude), whose pub names every file
 * sees with no import and no prefix: imported by an internal file read
 * before everything else. Pushed last, so it is popped first. Nothing
 * when the install has none. */
void lexPushPrelude(Lexer *l) {
    if (!l->builtin_root) return;
    char *dir = mprintf("%s/prelude", lexInstallModules(l));
    if (!lexIsDir(dir)) return;
    char *src = "import prelude;\n";
    lexPushString(l, LEX_PRELUDE_FILE, src, strlen(src));
}

/* rt (<install>/modules/rt), the runtime: loaded into every program,
 * after std.ktmh and before the program, but bound to no prefix, so a
 * program can't name it. Not for a -lib build (a library doesn't need
 * it linked in) or an install without one. */
void lexPushRuntime(Lexer *l) {
    if (!l->builtin_root || l->building_module || l->building_tree) return;
    char *dir = mprintf("%s/rt", lexInstallModules(l));
    if (!lexIsDir(dir)) return;
    /* and str, whose String methods every program may call (when the
     * install has it) */
    char *with_str = mprintf("%s/str", lexInstallModules(l));
    /* and rt.crash, the crash report's handler: compiled into the
     * program (it is no library's), it needs only libc */
    char *with_crash = mprintf("%s/rt/crash", lexInstallModules(l));
    l->with_crash = !l->no_crash && lexIsDir(with_crash);
    char *src = mprintf("import rt;\n%s%s",
                        lexIsDir(with_str) ? "import str;\n" : "",
                        l->with_crash ? "import rt.crash;\n" : "");
    lexPushString(l, LEX_RT_FILE, src, strlen(src));
}

/* `module b;` at the top of a module's file, the `module` just lexed */
static void lexModuleLine(Map *macro_defs, Lexer *l, Lexeme *kw) {
    LexFile *file = l->cur_file;
    Lexeme *first = lexTokenRaw(macro_defs, l);
    Lexeme *last = first;
    AoStr *name = lexModulePath(macro_defs, l, first, &last, NULL);
    if (!name) return;
    file->saw_module = 1;
    if (!file->module_id) {
        lexReportAt(l, kw->line, kw->col, kw->len,
                    "`module %s;` in a file that isn't part of a module: "
                    "put it in a directory %s and import it", name->data,
                    name->data);
        return;
    }
    AoStr *path = l->modules[file->module_id - 1]->path;
    char *dot = strrchr(path->data, '.');
    char *want = dot ? dot + 1 : path->data;
    if (strcmp(want, name->data) && !LEX_PASS_QUIET(file->mod_pass)) {
        lexReportAt(l, first->line, first->col,
                    (last->start + last->len) - first->start,
                    "this file is in module %s, so it starts with `module %s;`, not `module %s;`",
                    path->data, want, name->data);
    }
}

/* `prefix.name` right after an imported prefix: the token `a.b.name` */
static void lexQualify(Lexer *l, Lexeme *tok) {
    LexFile *file = l->cur_file;
    if (!file || !file->imports || *l->ptr != '.') return;
    char c = l->ptr[1];
    if (!(isalpha((unsigned char)c) || c == '_')) return;
    /* a member named like a prefix (`v.str.len` with `import str`): after
     * `.` or `->` it is a field, not the module */
    if (l->after_member) return;
    for (LexImport *imp = file->imports; imp; imp = imp->next) {
        if ((int)imp->prefix->len != tok->len ||
            memcmp(imp->prefix->data, tok->start, tok->len)) continue;
        char *name = l->ptr + 1, *end = name;
        while (isalnum((unsigned char)*end) || *end == '_') end++;
        char *text = mprintf("%s.%.*s", imp->module->path->data,
                             (int)(end - name), name);
        l->ptr = end;
        tok->qual_prefix = tok->start;
        tok->qual_prefix_len = tok->len;
        tok->start = text;
        tok->len = strlen(text);
        imp->used = 1;
        return;
    }
}

Lexeme *lexToken(Map *macro_defs, Lexer *l) {
    while (1) {
        Lexeme *tok = lexTokenRaw(macro_defs, l);
        if (!tok) return NULL;
        LexFile *file = l->cur_file;
        if (file && !file->header_done && tok->tk_type == TK_IDENT &&
            !(l->flags & CCF_PERMISSIVE))
        {
            if (tok->len == 6 && !memcmp(tok->start, "import", 6)) {
                lexImport(macro_defs, l, tok);
                continue;
            }
            if (tok->len == 6 && !memcmp(tok->start, "module", 6)) {
                lexModuleLine(macro_defs, l, tok);
                continue;
            }
        }
        tok->is_prefix = 0;
        tok->qual_prefix = NULL;
        tok->qual_prefix_len = 0;
        if (file) {
            if (!file->header_done && file->module_id && !file->saw_module &&
                file->mod_pass == LEX_PASS_FULL)
            {
                AoStr *path = l->modules[file->module_id - 1]->path;
                char *dot = strrchr(path->data, '.');
                lexReportAt(l, tok->line, tok->col, tok->len,
                            "every file of module %s starts with `module %s;`",
                            path->data, dot ? dot + 1 : path->data);
            }
            file->header_done = 1;
            tok->module_id = file->module_id;
            tok->mod_pass = file->mod_pass;
            tok->in_std = file->is_std;
            tok->file = file;
            int member = l->after_member;
            l->after_member = tok->tk_type == TK_PUNCT &&
                              (tok->i64 == '.' || tok->i64 == TK_ARROW);
            /* `throw` with a message calls rt.__ktmThrowFmt: a use of the
             * file's `import rt`, though the source never names it */
            if (tok->tk_type == TK_KEYWORD && tok->i64 == KW_THROW) {
                for (LexImport *imp = file->imports; imp; imp = imp->next) {
                    if (!strcmp(imp->module->path->data, "rt")) imp->used = 1;
                }
            }
            if (tok->tk_type == TK_IDENT) {
                char *before = tok->start;
                int saved = l->after_member;
                l->after_member = member;
                lexQualify(l, tok);
                l->after_member = saved;
                if (tok->start == before) {
                    for (LexImport *imp = file->imports; imp; imp = imp->next) {
                        if ((int)imp->prefix->len == tok->len &&
                            !memcmp(imp->prefix->data, tok->start, tok->len)) {
                            tok->is_prefix = 1;
                            break;
                        }
                    }
                }
            }
        } else {
            tok->module_id = 0;
            tok->mod_pass = LEX_PASS_NONE;
            tok->in_std = 0;
        }
        return tok;
    }
}

void lexemeFree(void *_le) {
    if (_le) {
        Lexeme *le = (Lexeme *)_le;
        (void)le;
    }
}

static void lexReleaseLexFile(LexFile *lex_file) {
    free(lex_file);
}

void lexReleaseAllFiles(Lexer *l) {
    listRelease(l->all_source,
            ((void (*)(void *))&lexReleaseLexFile));
}

char *lexerReportLine(Lexer *l, s64 lineno) {
    return lexerReportLineIn(l->cur_file, lineno);
}

char *lexerReportLineIn(LexFile *file, s64 lineno) {
    AoStr *buf = aoStrAlloc(256);

    char *ptr = file->src->data;
    s64 size = file->src->len;
    s64 line = 1;
    s64 i = 0;

    /* Walk forwards until `line == lineno`. For line 1 the loop exits
     * immediately at i == 0 (no preceding newline to skip); for higher lines,
     * we break at the `\n` that terminates the previous line and step over it */
    while (i < size && line < lineno) {
        if (ptr[i] == '\0') {
            return aoStrMove(buf);
        }
        if (ptr[i] == '\n') {
            line++;
        }
        i++;
    }

    if (line != lineno) {
        return aoStrMove(buf);
    }

    /* Preserve leading whitespace. The diagnostic renderer
     * positions the `^` underline from the token's source column,
     * which only lines up if the rendered line has the same
     * leading indent as the source. */
    while (i < size && ptr[i] && ptr[i] != '\n') {
        aoStrPutChar(buf, ptr[i++]);
    }
    aoStrPutChar(buf, '\0');
    return aoStrMove(buf);
}

int lexemeEq(Lexeme *l1, Lexeme *l2) {
    if (l1->tk_type  != l2->tk_type) {
        return 0;
    }

    switch (l1->tk_type) {
        case TK_STR:
        case TK_IDENT: return l1->len == l2->len && !memcmp(l1->start,l2->start,l1->len);

        case TK_KEYWORD:
        case TK_I64:
        case TK_PUNCT:
        case TK_EOF:
        case TK_CHAR_CONST: return l1->i64 == l2->i64;
        case TK_F64: return l1->f64 == l2->f64;
        default:
            loggerPanic("Cannot compare %s with %s\n",
                        lexemeToString(l1),
                        lexemeToString(l2));
    }
}
