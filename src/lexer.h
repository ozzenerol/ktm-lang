#ifndef LEXER_H
#define LEXER_H

#include <sys/types.h>

#include "aostr.h"
#include "containers.h"
#include "list.h"

#define LEX_MAX_IDENT_LEN  128
#define LEX_CHAR_CONST_LEN 8

/* TOKENS */
#define TK_EOF          0
#define TK_SUPERSCRIPT  0x301
#define TK_SUBSCRIPT    0x302
#define TK_NORMALSCRIPT 0x303
#define TK_IDENT        0x300
#define TK_STR          0x301
#define TK_I64          0x302
#define TK_CHAR_CONST   0x303
#define TK_F64          0x304
#define TK_PLUS_PLUS    0x305
#define TK_MINUS_MINUS  0x306
#define TK_DEREFERENCE  0x307
#define TK_DBL_COLON    0x308
#define TK_SHL          0x309
#define TK_SHR          0x30A
#define TK_EQU_EQU      0x30B
#define TK_NOT_EQU      0x30C
#define TK_LESS_EQU     0x30D
#define TK_GREATER_EQU  0x30E
#define TK_AND_AND      0x30F
#define TK_OR_OR        0x310
#define TK_XOR_XOR      0x311
#define TK_SHL_EQU      0x312
#define TK_SHR_EQU      0x313
#define TK_MUL_EQU      0x314
#define TK_DIV_EQU      0x315
#define TK_AND_EQU      0x316
#define TK_OR_EQU       0x317
#define TK_XOR_EQU      0x318
#define TK_ADD_EQU      0x319
#define TK_SUB_EQU      0x31A
#define TK_IF           0x31B
#define TK_IFDEF        0x31C
#define TK_IFNDEF       0x31D
#define TK_IFAOT        0x31E
#define TK_IFJIT        0x31F
#define TK_ENDIF        0x320
#define TK_ELSE         0x321
#define TK_MOD_EQU      0x322
#define TK_DOT_DOT      0x323
#define TK_ELLIPSIS     0x324
#define TK_INS_BIN      0x325
#define TK_INS_BIN_SIZE 0x326
#define TK_TKS_NUM      0x327
#define TK_ARROW        0x328
#define TK_PUNCT        0x329
#define TK_PRE_PLUS_PLUS 0x330
#define TK_PRE_MINUS_MINUS 0x331
#define TK_KEYWORD      0x332
#define TK_COMMENT      0x333

#define KW_CLASS       (1<<0) /* Easy to see if struct or union */
#define KW_UNION       (1<<1)
#define KW_U0           0
/* Integers are odd numbers */
#define KW_BOOL         3
#define KW_I8           5
#define KW_U8           7
#define KW_I16          9
#define KW_U16          11
#define KW_I32          13
#define KW_U32          15
#define KW_I64          17
#define KW_U64          19
/* back to even */
#define KW_F64          4
#define KW_PUBLIC       6
#define KW_ATOMIC       8
#define KW_DEFINE       10
#define KW_PP_INCLUDE   12
#define KW_SIZEOF       16
#define KW_RETURN       18
#define KW_SWITCH       20
#define KW_CASE         22
#define KW_BREAK        24
#define KW_CONTINUE     26
#define KW_ASM          28
#define KW_ASM_EXTERN   30
#define KW_EXTERN       32
#define KW_PRIVATE      34
#define KW_INLINE       36
#define KW_FOR          38
#define KW_WHILE        40
#define KW_VOLATILE     42
#define KW_GOTO         44
#define KW_IF           46
#define KW_ELSE         48
#define KW_ELIF         50
#define KW_IF_NDEF      52
#define KW_IF_DEF       54
#define KW_ENDIF        56
#define KW_DEFINED      58
#define KW_UNDEF        60
#define KW_AUTO         62
#define KW_DEFAULT      64
#define KW_DO           66
#define KW_STATIC       67
#define KW_ELIF_DEF     68
#define KW_PP_IF        69
#define KW_PP_ELSE      70
#define KW_PP_DEFINE    71
#define KW_PP_IF_NDEF   72
#define KW_PP_IF_DEF    73
#define KW_PP_ELIF_DEF  74
#define KW_PP_ENDIF     75
#define KW_PP_ELIF      76
#define KW_PP_DEFINED   77
#define KW_PP_UNDEF     78
#define KW_PP_ERROR     79
/* structured exceptions. */
#define KW_TRY          80
#define KW_CATCH        81
#define KW_THROW        82
/* register pinning for locals/params:
 *   I64 reg R15 i = 5;    // bound to R15 for the function
 *   I64 noreg j = 4;      // explicit stack local
 * `noreg` is also required for any local in a function that contains
 * an asm { } block (no implicit "compiler picks"). */
#define KW_REG          83
#define KW_NOREG        84
/* Extended to have F32 support, a lot of C libraries use floats, so without
 * an F32 they become very hard to use */
#define KW_F32          85
/* Compile-time alignment query: `alignof(<type or expr>)`. */
#define KW_ALIGNOF      86
/* `#link "<path>"` / `#link <libname>` - record a shared library
 * dependency in the source itself. AOT passes it to the linker,
 * the JIT dlopen's it. */
#define KW_PP_LINK      87
/* `#ifjit` / `#ifaot` - sugar for `#ifdef __KTM_JIT__` /
 * `#ifdef __KTM_AOT__` (main() defines exactly one of the two).
 * Compose with #else / #endif like any other #if. */
#define KW_PP_IF_JIT    88
#define KW_PP_IF_AOT    89
/* Compile-time type query: `typeof(<type or expr>)` folds to a string
 * literal naming the type, e.g. typeof(1+1) == "I64". */
#define KW_TYPEOF       90
/* Literals (ktm syntax v0.2): true and false are bool, null is a void *. */
#define KW_TRUE         91
#define KW_FALSE        92
#define KW_NULL         93
/* `const` on a declaration (ktm syntax v0.2): marks it read-only, not a
 * type qualifier like C's. */
#define KW_CONST        94
/* ktm syntax v0.2: a scoped enum, `enum Name [: IntType] { ... };` */
#define KW_ENUM         95
/* `defer <stmt>` (ktm syntax v0.2): run <stmt> when the block is left. */
#define KW_DEFER        96

/* Compiler Flags*/
#define CCF_MULTI_CHAR_OP     (1<<0)
#define CCF_PRE_PROC          (1<<1)
#define CCF_ACCEPT_NEWLINES   (1<<2)
#define CCF_MULTI_COLON       (1<<3)
#define CCF_ASM_BLOCK         (1<<4)
#define CCF_BLOCK             (1<<5)
#define CCF_ACCEPT_WHITESPACE (1<<6)
#define CCF_ACCEPT_COMMENTS   (1<<7)
/* Permissive mode: don't raise on malformed input. Lexing
 * continues by absorbing the offending byte literally (e.g.
 * unknown escape `\q` becomes the two chars `\q` in the string
 * body). Used by the diagnostic renderer's per-line re-lexer,
 * which only needs to colourise, not validate. */
#define CCF_PERMISSIVE        (1<<8)
/* Lexing a skipped #if group: like C, a character with no use outside
 * an asm block (`@@1` in an `#ifdef IS_X86_64` asm block skipped on
 * arm64) is not an error there. */
#define CCF_COND_SKIP         (1<<9)

#define LEXEME_RAW_PUNCT              (1<<0)
#define LEXEME_ENCODE_PUNCT           (1<<1)
#define LEXEME_GRAPH_VIZ_ENCODE_PUNCT (1<<2)

typedef struct Lexeme {
    int tk_type;
    int len;
    int line;
    /* 1-based column of the first byte of `start` within its source
     * line. Derived at lex time from `start - line_start_ptr + 1`;
     * preserved when the lexer cursor moves so we don't have to
     * rescan the line later which could lead to the wrong characters. */
    int col;
    char *start;
#ifdef KTM_CONVERT
    /* The token's first byte in its source buffer (for a string, the
     * opening quote; `start` points at the unescaped copy). NULL for
     * tokens the lexer makes up. tools/convert maps it to an offset. */
    char *src;
#endif
    int ishex;
    /* An integer literal too big for an I64 (or a #define folded to a
     * U64): typed U64, like C does for such hex literals. */
    int isu64;
    union {
        s64 i64;
        double f64;
    };
    /* The module the token's file belongs to (Lexer.modules, 1-based; 0:
     * the main program or a header) and which pass of it (LEX_PASS_*) */
    u32 module_id;
    u8 mod_pass;
    /* From a stdlib header (under the builtin root), not the program */
    u8 in_std;
    /* An identifier that is one of its file's import prefixes, not
     * followed by `.`: declaring it would hide the module */
    u8 is_prefix;
    /* The file it was lexed from (the lexer runs ahead of the parser,
     * so its current file can already be the next one) */
    struct LexFile *file;
} Lexeme;

/* A module's files are lexed three times: its type names (the parser
 * records its type declarations and parses them in an order that works,
 * see parseModuleTypes), then declarations only, then in full */
#define LEX_PASS_NONE  0
#define LEX_PASS_DECLS 1
#define LEX_PASS_FULL  2
#define LEX_PASS_NAMES 3
/* A pass that only reads declarations: nothing in it is reported twice */
#define LEX_PASS_QUIET(pass) \
    ((pass) == LEX_PASS_DECLS || (pass) == LEX_PASS_NAMES)

/* A module (a directory of .ktm files) as `import a.b;` finds it */
typedef struct LexModule {
    AoStr *path;   /* "a.b" */
    AoStr *dir;    /* the directory it was found in */
    u32 id;        /* index in Lexer.modules + 1 */
    int loaded;    /* all of its files have been lexed */
} LexModule;

/* `import [alias =] a.b;` in one file: `prefix.name` becomes the token
 * `a.b.name` */
typedef struct LexImport {
    AoStr *prefix;
    LexModule *module;
    int used;
    s64 line, col, len;
    struct LexImport *next;
} LexImport;

/* Forward declaration. The lexer holds a back-pointer to its
 * owning Cctrl (when there is one) so user-visible lex errors
 * can route through the diagnostic accumulator instead of the
 * fail-fast `loggerPanic`. NULL for standalone lexer instances
 * (the macro-processor stub, the per-line re-lexer used by the
 * diagnostic renderer). */
struct Cctrl;

struct LexFile;
typedef struct LexFile {
    AoStr *filename; /* name of the file */
    /* cctrlRegisterFile id, assigned lazily on first token consumed
     * from this file; 0 = not yet registered. */
    u32 file_id;
    char *ptr; /* Where we are in the file */
    int lineno; /* line number in the file */
    /* First byte of the current line in `src->data`. Saved/restored
     * alongside `ptr` and `lineno` when crossing file boundaries so
     * columns stays consistent across includes. */
    char *line_start_ptr;
    AoStr *src; /* source */
    /* Module files: which module and pass (see Lexeme.module_id), and
     * whether it is the module's last file in its last pass */
    u32 module_id;
    u8 mod_pass;
    u8 last_of_module;
    /* Past the `module` and `import` lines at the top */
    u8 header_done;
    /* A module's file had its `module` line; under the builtin root */
    u8 saw_module;
    u8 is_std;
    /* Its unused imports were reported */
    u8 ended;
    LexImport *imports;
} LexFile;

/* An open #if/#ifdef/#ifndef/#ifjit/#ifaot: where it is (for
 * "Unterminated #if"), whether one of its branches has been taken and
 * whether its #else has been seen. */
typedef struct LexCond {
    s64 line;
    s64 col;
    s64 len;
    int taken;
    int seen_else;
} LexCond;

typedef struct Lexer {
    int tk_type;
    char *ptr;
    char *start;
    /* Pointer to the start of the current source line. */
    char *line_start_ptr;
    /* Line and column of the first byte of the token currently
     * being lexed. */
    int tok_start_line;
    int tok_start_col;
#ifdef KTM_CONVERT
    char *tok_start_ptr;
#endif

    char cur_ch;
    char *cur_str;
    s64 cur_strlen;
    s64 cur_i64;
    double cur_f64;
    int lineno;
    int flags;
    int ishex;
    int isu64;
    /* The last string lexed ran into the end of the input */
    int str_unterminated;
    /* The open conditionals, innermost last */
    LexCond *conds;
    int cond_depth;
    int cond_cap;
    char *builtin_root;
    List *files;
    List *all_source;/* This saves all of the files we see so we can free them later */
    Set *seen_files;
    Map *symbol_table;
    LexFile *cur_file;
    /* Modules: those found so far, the chain being loaded (for cycles),
     * the directory of the program's main file and the stdlib's
     * module directory */
    LexModule **modules;
    int modules_len, modules_cap;
    u32 mod_stack[64];
    int mod_depth;
    char *program_root;
    /* `ktm -lib a.b dir/a/b`: the module being built into a library,
     * compiled in full even if it has a .ktmlib marker */
    char *building_module;
    /* `ktm -lib name dir` over a tree of modules: dir (a real path),
     * whose modules are all compiled in full */
    char *building_tree;
    /* Diagnostic engine back-pointer. When non-NULL the lexer
     * routes user-visible errors through cctrlRaiseException so
     * they accumulate in the same Vec the parser writes to and
     * honour the recovery longjmp. NULL means fall back to
     * `loggerPanic` (standalone lexer / macro processor). */
    struct Cctrl *cc;
} Lexer;

void lexemeMemoryInit(void);
void lexemeMemoryRelease(void);
void lexemeMemoryStats(void);
void lexerRelease(Lexer *l);

Lexeme *lexemeTokNew(char *start, int len, int line, s64 ch);
Lexeme *lexemeNew(char *start, int len);
Lexeme *lexemeCopy(Lexeme *le);
Lexeme *lexemeSentinal(void);
void lexSetAsmFlags(Lexer *l);
void lexUnSetAsmFlags(Lexer *l);
void lexSetBuiltinRoot(Lexer *l, char *root);
void lexInit(Lexer *l, char *source, int flag);
void lexPushFile(Lexer *l, AoStr *filename);
/* Push an in-memory buffer as if it were a file (REPL input). `src`
 * is borrowed and must outlive the parse of this buffer. */
void lexPushString(Lexer *l, char *name, char *src, s64 len);
/* The name of the internal file that imports the prelude */
#define LEX_PRELUDE_FILE "<prelude>"
void lexPushPrelude(Lexer *l);
int lex(Lexer *l, Lexeme *le);
Lexeme *lexToken(Map *macro_defs, Lexer *l);
int lexSetModuleLibRoot(Lexer *l, char *dir, char *path);
char *lexModuleTreeImports(Lexer *l, char *dir, Vec *paths, Vec *dirs);
void lexemePrint(Lexeme *le);
char *lexemeTypeToString(int tk_type);
char *lexemePunctToString(s64 op);
char *lexemePunctToStringWithFlags(s64 op, u64 flags);
char *lexemePunctToEncodedString(s64 op);
char *lexemeToString(Lexeme *tok);
char *lexemeAsWritten(Lexeme *tok);
AoStr *lexemeToAoStr(Lexeme *tok);
void lexReleaseAllFiles(Lexer *l);
int tokenPunctIs(Lexeme *tok, s64 ch);
int tokenIdentIs(Lexeme *tok, char *ident, int len);
void lexemeFree(void *_le);
char *lexerReportLine(Lexer *l, s64 lineno);
char *lexerReportLineIn(LexFile *file, s64 lineno);
int lexemeEq(Lexeme *l1, Lexeme *l2);
char *lexReadfile(char *path, s64 *_len);
#ifdef KTM_CONVERT
extern void (*lex_file_pushed_hook)(LexFile *f);
extern void (*lex_skip_hook)(char *from, char *to);
/* A #define whose value folded to one token `value` (a number, or the
 * single token of a one-token define); `at` is in the line after it */
extern void (*lex_define_hook)(char *at, char *name, Lexeme *value);
#endif


#endif // !LEXER_H
