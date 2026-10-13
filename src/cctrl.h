#ifndef CCTRL_H
#define CCTRL_H

#include <setjmp.h>
#include <stdarg.h>
#include <sys/types.h>

#include "aostr.h"
#include "ast.h"
#include "cli.h"
#include "config.h"
#include "containers.h"
#include "lexer.h"

#define CCTRL_TOKEN_BUFFER_SIZE 64
#define CCTRL_TOKEN_BUFFER_MASK CCTRL_TOKEN_BUFFER_SIZE-1
/* Expanded macro uses remembered; only a token in the ring buffer can be
 * peeked again, and every one was expanded as it was loaded, so twice
 * its size is plenty */
#define CCTRL_MACRO_USE_CACHE_SIZE (CCTRL_TOKEN_BUFFER_SIZE*2)

#define CCTRL_ASM_HAS_INITIALISERS (1<<4)
/* REPL mode: re-defining a function replaces the previous definition
 * instead of raising "Cannot redefine function", and floating
 * expressions (`2+2;`, `(x*3);`) are legal at the top level so they
 * can be evaluated and echoed. */
#define CCTRL_REPL                 (1<<6)
/* Treat warnings as errors */
#define CCTRL_WERROR               (1<<7)
/* Create wrappers over MAlloc/Free and friends for tracking allocations
 * JIT only presently */
#define CCTRL_MEMSAFE              (1<<8)
/* The language server: REPL-style redefinition, but overloads as in a
 * compile (each analysis parses the document afresh) */
#define CCTRL_LSP                  (1<<9)

/* For messages */
#define CCTRL_ICE   0
#define CCTRL_INFO  1
#define CCTRL_WARN  2
#define CCTRL_ERROR 3

/* A single accumulated parser/lexer diagnostic. Carries enough
 * spatial info (file + start line/col + end line/col) for an LSP
 * to render a squiggle without re-tokenising. `message` and
 * `suggestion` are owned by the diagnostic. `file` is borrowed
 * from the Lexer's LexFile list and outlives the diagnostic. */
/* A `type Name = T;`: T, and where Name was written (for the LSP) */
typedef struct TypeAlias {
    AstType *type;
    int line, col;
    u32 file_id;
} TypeAlias;

typedef struct CctrlDiagnostic {
    int severity;          /* CCTRL_INFO / CCTRL_WARN / CCTRL_ERROR */
    struct LexFile *file;  /* borrowed; NULL if the source is unknown */
    int line;
    int col;
    int end_line;
    int end_col;
    AoStr *message;        /* owned */
    AoStr *suggestion;     /* owned; may be NULL */
} CctrlDiagnostic;

typedef struct TokenRingBuffer {
    s64 tail;
    s64 head;
    s64 size;
    s64 capacity;
    Lexeme **entries;
    /* The last cctrlTokenGet found no token (end of input); a rewind
     * then has nothing to give back. */
    int got_eof;
    /* Tokens taken by cctrlTokenGet minus those given back by
     * cctrlTokenRewind: orders two positions in the token stream. */
    s64 pos;
} TokenRingBuffer;

TokenRingBuffer *tokenRingBufferNew(void);

void tokenBufferPrint(TokenRingBuffer *ring_buffer);
int tokenRingBufferEmpty(TokenRingBuffer *ring_buffer);
void tokenRingBufferPush(TokenRingBuffer *ring_buffer, Lexeme *token);
Lexeme *tokenRingBufferPop(TokenRingBuffer *ring_buffer);
Lexeme *tokenRingBufferPeek(TokenRingBuffer *ring_buffer);
Lexeme *tokenRingBufferPeekBy(TokenRingBuffer *ring_buffer, s64 offset);
int tokenRingBufferRewind(TokenRingBuffer *ring_buffer);

/* A macro use and its expansion: a copy of the macro's value placed
 * where the macro name is written. */
typedef struct CctrlMacroUse {
    Lexeme *use;       /* the TK_IDENT naming the macro */
    Lexeme *macro;     /* the value in macro_defs */
    Lexeme *expansion; /* `macro` at `use`'s line and column */
} CctrlMacroUse;

/* A call to an overloaded function and the overload it picked, for the
 * LSP's hover and definition */
typedef struct CctrlOverloadCall {
    u32 file_id;
    int line, col, len; /* the name as written, col 1-based */
    struct Ast *fn;
} CctrlOverloadCall;

/* A generic declaration (v0.9.0): `struct Vec<T> {...}`, a method
 * `T Vec<T>.get(...)` or a function `T max<T>(...)`, kept as its tokens
 * and parsed again for each instance with the parameters bound */
#define GEN_STRUCT 1
#define GEN_METHOD 2
#define GEN_FUNC   3
typedef struct GenericDef {
    int kind;
    AoStr *name;         /* the struct or function (`vec.Vec`, `max`) */
    AoStr *method;       /* GEN_METHOD: the method's name */
    Vec *params;         /* AoStr *: T, K, V */
    Vec *toks;           /* Lexeme *: the whole declaration */
    u32 module_id;
    AoStr *module_path;          /* its module's name, for another lexer's ids */
    int is_pub;
    struct AstType *tmpl;        /* GEN_STRUCT: the type `Vec` names */
    struct GenericDef *of;       /* GEN_METHOD: its struct's definition */
    Vec *insts;                  /* GEN_STRUCT/FUNC: GenericInst * made */
    Lexeme *at;                  /* its name where it is declared */
    u32 file_id;                 /* and that file */
} GenericDef;

/* One instance being made (or made): the definition and its arguments */
typedef struct GenericInst {
    GenericDef *def;
    Vec *args;                   /* AstType * */
    AoStr *name;                 /* `Vec<i64>`, `max<f64>` (unqualified for a function) */
    struct AstType *cls;         /* a struct's instance type (methods: their struct's) */
    int proto_only;              /* the declaration only, its body later */
    Lexeme *at;                  /* the use that asked for it */
    struct GenericInst *outer;
} GenericInst;

/* A @json/@yaml struct's pointer field whose struct had no body yet
 * (parseJsonCheckDeferred) */
typedef struct JsonDeferred {
    struct AstType *pointee;
    struct AstType *cls;
    char *field;
    const char *mod;
    int line, col;
    struct LexFile *file;
} JsonDeferred;

typedef struct Cctrl {
    u64 flags;
    /* The global environment for user defined types, functions and global
     * variables */
    Map *global_env;
    /* A module's extern "c" functions by their C name (they are in
     * global_env as `a.b.name`): what a call to the C symbol calls */
    Map *c_externs;
    /* M2: the directories (real paths) of the modules with an `_init`,
     * whose .ktmlib markers a -lib build marks `init` */
    Set *init_module_dirs;

    /* key words defined in the language */
    Map *symbol_table;

    /* Every file this compile has lexed: `Map<u32, AoStr *>` keyed by
     * the 1-based id stamped onto Ast->file_id (0 = unknown). */
    Map *file_map;

    /* Class definitions */
    Map *clsdefs;

    /* Union definitions */
    Map *uniondefs;
    /* `type Name = T;`: Name (a module's qualified, as its types) -> its
     * TypeAlias. An alias is its type: the same AstType, nothing new. */
    Map *type_aliases;

    /* Local environment for a function */
    Map *localenv;

    /* assembly functions that have been bound to ktm, this is the ktm 
     * name, not the assembly name */
    Map *asm_funcs;

    /* Macro definitions */
    Map *macro_defs;

    /* Registers */
    Set *x86_registers;

    /* libc function names */
    Set *libc_functions;

    /* Assembly function name to assembly block mapping */
    Map *asm_functions;

    /* asm blocks */
    List *asm_blocks;

    /* Strings */
    Map *strs;

    /* The Ast Tree */
    List *ast_list;

    /* Current function return type */
    AstType *tmp_rettype;

    /* Temporary function parameters, for trying to keep track of function 
     * pointers */
    Vec *tmp_params;

    /* Local variables */
    List *tmp_locals;

    /* For parsing a switch statement */
    Vec *tmp_case_list;

    /* function parameters */
    List *func_params;

    /* Top level statements, calls and globals */
    List *initalisers;
    List *initaliser_locals;

    /* For parsing a switch */
    Ast *tmp_default_case;
    /* how many cases came before the switch's `default` */
    int tmp_default_at;

    /* Function being parsed */
    Ast *tmp_func;
    /* The overload set the function parseFunctionDef is about to create
     * joins (parseOverloadDecl), before its body can call it */
    Vec *tmp_overload_set;
    /* `&f` of overloaded functions no target type has picked yet
     * (AST_FLAG_OVERLOAD_REF): an error at the end of the function */
    List *overload_refs;
    /* CctrlOverloadCall *: every call of an overloaded function */
    Vec *overload_calls;

    /* Writes through a local initialised from a string literal
     * (AST_FLAG_LIT_SLICE), each an AST_SLICE_ELEM: errors at the end of
     * the function unless the local was reassigned */
    List *lit_writes;

    /* `for (i64 i = 0; i < s.len; i++)` loops whose `s[i]` may need no
     * bounds check (ElideLoop *): the open ones, innermost last, and the
     * finished ones, settled at the end of the function */
    List *elide_open;
    /* reflect.typeOf: the hidden reflect.Type global of each type described,
     * by its name (typeof's), made once per compilation unit */
    Map *reflect_types;
    List *elide_done;

    /* Global variable whose declaration is mid-parse. If the
     * initialiser errors, top-level recovery unregisters it from
     * global_env. Otherwise later code could name a variable whose
     * storage (the AST_DECL) was rolled back. */
    Ast *tmp_gvar_decl;

    /* `I64 a = 1, *b, c[2];` at file scope: parseToplevelDef returns
     * one declarator per call, so after a `,` the base type (sans the
     * per-declarator `*`s) is stashed here for the next call. */
    AstType *tmp_gvar_base_type;

    /* When parsing & converting to assembly these keep a reference to the
     * current loop's labels */
    AoStr *tmp_loop_begin;
    AoStr *tmp_loop_end;
    /* A `fallthrough;` not yet followed by a case or default label of
     * its switch: where it is, for the error if the switch ends first */
    int fallthrough_pending;
    /* The local whose initialiser is being parsed (it doesn't hide an
     * import prefix there yet) */
    struct Ast *decl_in_init;
    /* A token to hand out before the ring's next one (cctrlTokenGet) */
    struct Lexeme *pending_token;
    s64 fallthrough_line, fallthrough_col;
    /* Deferred statements (`defer <stmt>`) being parsed around the
     * current token: a return, or a break/continue with no loop inside
     * the deferred statement, would leave one. */
    int defer_depth;
    /* The module of the declaration being parsed (Lexer.modules, 1-based;
     * 0: the main program), and whether this is its declarations-only
     * pass (function bodies and global initialisers skipped) */
    u32 cur_module;
    int decls_only;
    /* M5: a module's type declarations (struct/union bodies, enums,
     * consts), recorded by its names pass and parsed again from the
     * recorded tokens until no more succeed, so the files of a module can
     * use each other's types in any order. `replaying` is set while one
     * is parsed from its tokens; diagnostics are then dropped and only
     * counted (`quiet_errors`), the declarations pass reports them. */
    Vec *mod_type_decls;
    Set *mod_decls_done;
    Set *mod_types_done;   /* module ids parseModuleTypes has run for */
    int replaying;
    /* Generics: struct and function definitions by name, every generic
     * method, the type parameters bound while an instance is parsed, the
     * instance being parsed, bodies still to parse, declarations seen */
    Map *generic_structs;
    Map *generic_funcs;
    Vec *generic_methods;
    Map *type_bindings;
    GenericInst *inst;
    Vec *generic_pending;
    Set *generic_seen;
    int generic_replay;
    int pending_gt;     /* a `>>` closed an inner type-argument list */
    /* `((fn(i64) i64)raw + 1)`: the cast already parsed, handed back
     * by the next parseUnaryExpr as the start of the expression */
    Ast *pending_unary;
    /* `@json @yaml` read before a declaration (AstAttr *), for the struct
     * it must be (parseAttrs) */
    Vec *pending_attrs;
    /* the @json structs whose methods were made (parseJsonGenerate) */
    Set *json_done;
    Vec *json_deferred;    /* JsonDeferred * */
    int quiet_errors;
    /* The declaration being parsed is `pub`; it comes from the main
     * program (not a module, not a stdlib header) */
    int decl_pub;
    int decl_from_main;
    /* the `%.*s` lengths parseLowerSliceFormat added to the call being
     * parsed, which its format check doesn't count as written */
    int fmt_lowered;
    /* `a.b.name`s declared pub; names the main program declares, which
     * a module must not see */
    Set *pub_names;
    Set *main_names;
    /* A method definition (`void User.describe()`) being parsed: the
     * type of its implicit `self` parameter (`User *`), NULL otherwise */
    AstType *method_self;
    /* The file of the last token the parser took: where diagnostics
     * and new Ast nodes are (the lexer may already be in the next) */
    LexFile *tok_file;

    /* Temporary asm function name */
    AoStr *tmp_asm_fname;

    /* Temporary name of the function being parsed */
    AoStr *tmp_fname;

    /* current line number */
    s64 lineno;

    s64 stack_local_space;

    /* Is the current type or function static,
     * with functions this has no real difference */
    int is_static;
    TokenRingBuffer *token_buffer;
    Lexer *lexer_;

    /* Recent macro expansions, so a diagnostic on one can find the
     * macro use it came from */
    CctrlMacroUse macro_uses[CCTRL_MACRO_USE_CACHE_SIZE];
    int macro_use_next;

    /* Accumulated diagnostics emitted during this compilation.
     * Pushed in source order. Includes info and warnings. Not just errors. */ 
    Vec *diagnostics;

    /* `n_errors` counts only `CCTRL_ERROR` entries so the driver can decide
     * whether to proceed to codegen. */
    int n_errors;

    /* Innermost active parser recovery point. `NULL` means errors
     * are fatal. non-NULL means `cctrlRaiseException` will record the
     * diagnostic and longjmp here instead of calling `exit()`. Set/cleared by
     * the parser around top-level decls, statements which are points of
     * recovery. */
    jmp_buf *current_recovery;

    /* Externl compiler command e.g `clang --target=x86_64-apple-darwin`.
     * This will default to clang on mac and gcc on x86_64 linux */
    char *CC;
    
    /* Target triple */
    enum CliTarget target;

    /* Install prefix (`--install-dir`, defaults to INSTALL_PREFIX).
     * Headers live at <install_dir>/include, libraries at
     * <install_dir>/lib - the JIT probes the latter for libktm. */
    char *install_dir;

    /* Are we compiling position independent code? */
    int is_pic;

    /* Building a library (`-lib`): its functions are emitted as weak
     * definitions, so a program that defines a function of the same
     * name links with its own one (see asmEmitFunctionGlobal). */
    int is_library;
    /* Building the standard library itself (`-lib ktm`) */
    int is_libktm;
    /* The builtin header (std.ktmh) as read: a name whose declaring token
     * lies in this buffer is part of the library's public API */
    char *header_src;
    s64 header_len;
    /* Names the header declares (functions, asm-bound labels, data).
     * When building the library, only these are exported; every other
     * library symbol is local (libktm is one translation unit). */
    Set *header_names;

    /* .so files that may have been passed on the commandline or recorded
     * by a `#link "<path>"` directive, these should be usable with the
     * JIT */
    List *shared_object_files;

    /* .o files that may have been passed on the commandline */
    List *object_files;

    /* Library names recorded by `#link <name>` directives. AOT passes
     * each as `-l<name>` to the linker; the JIT probes
     * lib<name>.{dylib,so} with dlopen. */
    List *link_libs;
} Cctrl;

/* Instantiate a new compiler control struct */
Cctrl *cctrlNew(enum CliTarget target);
/* Slimmed down Cctrl, for expanding macros */
Cctrl *ccMacroProcessor(Map *macro_defs);
/* File-id registry over cc->file_map; ids are what Ast->file_id holds. */
u32 cctrlRegisterFile(Cctrl *cc, AoStr *filename);
void cctrlNoteHeaderName(Cctrl *cc, char *start, int len);
int cctrlIsExported(Cctrl *cc, AoStr *name);
Ast *cctrlCalleeDecl(Cctrl *cc, char *name, int len);
AoStr *cctrlLookUpFile(Cctrl *cc, u32 file_id);

Lexeme *cctrlTokenGet(Cctrl *cc);
Lexeme *cctrlTokenGetRequired(Cctrl *cc);
Lexeme *cctrlAsmTokenGet(Cctrl *cc);
Lexeme *cctrlTokenPeek(Cctrl *cc);
Lexeme *cctrlTokenPeekBy(Cctrl *cc, int cnt);
void cctrlInitMacroProcessor(Cctrl *cc);
void cctrlTokenRewind(Cctrl *cc);
void cctrlTokenExpect(Cctrl *cc, s64 expected);
void cctrlSetCommandLineDefines(Cctrl *cc, List *defines_list);

void cctrlInitParse(Cctrl *cc, Lexer *lexer_);

Ast *cctrlGetVar(Cctrl *cc, char *varname, int len);
int cctrlIsKeyword(Cctrl *cc, char *name, int len);
int cctrlTokIsKeyword(Cctrl *cc, Lexeme *tok);
AoStr *cctrlModulePath(Cctrl *cc, u32 module_id);
char *cctrlQualifyName(Cctrl *cc, char *name, int len);
int cctrlInPrelude(Cctrl *cc);
int cctrlInCModule(Cctrl *cc);
char *cctrlDeclName(Cctrl *cc, char *name, int len);
int cctrlModuleHides(Cctrl *cc, char *name, int len);
LexFile *cctrlCurFile(Cctrl *cc);
void cctrlCheckModuleAccess(Cctrl *cc, char *name, int len);
void cctrlCheckPrefixHidden(Cctrl *cc, Lexeme *name);
AstType *cctrlGetKeyWord(Cctrl *cc, char *name, int len);
void cctrlInfo(Cctrl *cc, char *fmt, ...);
void cctrlWarning(Cctrl *cc, char *fmt, ...);
void cctrlWarningAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...);
void cctrlRaiseExceptionAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...);
void cctrlErrorAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...);
void cctrlInfoAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...);
void cctrLoadNextTokens(Cctrl *cc, s64 token_count);
void cctrlWarningFromTo(Cctrl *cc, char *suggestion, char from, char to, char *fmt, ...);
__noreturn void cctrlRaiseExceptionFromTo(Cctrl *cc, char *suggestion, char from, char to, char *fmt, ...);
__noreturn void cctrlRaiseException(Cctrl *cc, char *fmt, ...);
__noreturn void cctrlRaiseSuggestion(Cctrl *cc, char *suggestion, char *fmt, ...);
__noreturn void cctrlIce(Cctrl *cc, char *fmt, ...);
Ast *cctrlGetOrSetString(Cctrl *cc, char *str, int len, s64 real_len);
void cctrlRewindUntilPunctMatch(Cctrl *cc, s64 ch, int *_count);
void cctrlRewindUntilStrMatch(Cctrl *cc, char *str, int len, int *_count);
AoStr *cctrlMessagePrintF(Cctrl *cc, int severity, char *fmt,...);
/* va_list variant of cctrlMessagePrintF. Public so subsystems
 * outside cctrl.c (the lexer) can build pre-rendered diagnostic
 * messages without re-implementing the printf-+-position dance. */
AoStr *cctrlMessagVnsPrintF(Cctrl *cc, char *fmt, va_list ap, int severity);

/* Build a fully-rendered diagnostic at an explicit (line, col, len).
 * Bypasses cctrlTokenPeek so callers that know their own position
 * (the lexer, mid-token) aren't subject to whatever stale token the
 * parser's ring buffer is holding. `col` is 1-based; pass 0 to skip
 * the underline entirely. */
AoStr *cctrlCreateErrorLineAt(Cctrl *cc, s64 lineno, s64 col, s64 len,
                              char *msg, int severity, char *suggestion);

/* Diagnostic accumulation. `cctrlDiagPush` adds an entry without
 * printing it */
void cctrlDiagPush(Cctrl *cc, CctrlDiagnostic *d);
/* prints every queued diagnostic to stderr (in source order) and returns the
 * number of CCTRL_ERROR entries. */
int cctrlDiagFlush(Cctrl *cc);
/* Drop queued diagnostics and reset the error count (REPL: between inputs). */
void cctrlDiagClear(Cctrl *cc);
/* Empty the token ring buffer so a fresh parse doesn't consume tokens
 * left over from a previous (possibly errored) one. */
void cctrlResetTokenBuffer(Cctrl *cc);

/* Build a CctrlDiagnostic from a pre-rendered message and the current source
 * position. Takes ownership of `rendered` and `suggestion`. */
CctrlDiagnostic *cctrlMakeDiag(Cctrl *cc,
                               int severity,
                               AoStr *rendered,
                               AoStr *suggestion);

/* Hand off to the nearest active recovery point (longjmp) if one is set.
 * Otherwise flush queued diagnostics and exit. */
__noreturn void cctrlTerminate(Cctrl *cc);

/* Walk the token ring back to a token on a strictly earlier line than
 * `tok->line`, render a CCTRL_INFO message positioned there. */
CctrlDiagnostic *cctrlInfoAtPreviousLine(Cctrl *cc, Lexeme *tok,
                                         const char *fmt, ...);

/* Token-stream resync after an error. Both skip tokens until they
 * reach a brace-depth-zero boundary; the caller's recovery loop
 * then resumes from there.
 *  - cctrlSyncToplevel: consume up to and including a top-level
 *    `;` or stop right before a `}` (which would close a top-level
 *    block) or a token that looks like the start of a new top-level
 *    decl (keyword / `#`).
 *  - cctrlSyncStatement: consume up to and including a statement
 *    `;` or stop right before a `}` (lets the enclosing block
 *    close cleanly). */
void cctrlSyncToplevel(Cctrl *cc);
void cctrlSyncStatement(Cctrl *cc);

Map *cctrlCreateAstMap(Map *parent);
Map *cctrlCreateLexemeMap(void);
void cctrlAddDefine(Cctrl *cc, char *name);

#endif // !CCTRL_H
