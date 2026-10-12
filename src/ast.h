#ifndef AST_H
#define AST_H

#include "aostr.h"
#include "containers.h"
#include "list.h"

typedef enum AstUnOp {
    /* postfix (evaluated after value is yielded) */
    AST_UN_OP_POST_INC,   /* x++ */
    AST_UN_OP_POST_DEC,   /* x-- */

    /* prefix (evaluated before value is yielded) */
    AST_UN_OP_PRE_INC,    /* ++x */
    AST_UN_OP_PRE_DEC,    /* --x */

    /* arithmetic sign */
    AST_UN_OP_PLUS,       /* +x */
    AST_UN_OP_MINUS,      /* -x */

    /* logical & bitwise */
    AST_UN_OP_LOG_NOT,    /* !x */
    AST_UN_OP_BIT_NOT,    /* ~x */

    /* pointer & address */
    AST_UN_OP_ADDR_OF,    /* &x */
    AST_UN_OP_DEREF,      /* *p */

    AST_UN_OP_SIZEOF,
    AST_UN_OP_ALIGNOF,
    AST_UN_OP_CAST
} AstUnOp;

const char *astUnOpKindToString(AstUnOp op);

typedef enum AstBinOp {
    /* multiplicative */
    AST_BIN_OP_MUL,          /* a * b */
    AST_BIN_OP_DIV,          /* a / b */
    AST_BIN_OP_MOD,          /* a % b */

    /* additive */
    AST_BIN_OP_ADD,          /* a + b */
    AST_BIN_OP_SUB,          /* a - b */

    /* bit-shift */
    AST_BIN_OP_SHL,          /* a << b */
    AST_BIN_OP_SHR,          /* a >> b */

    /* relational */
    AST_BIN_OP_LT,           /* a <  b */
    AST_BIN_OP_LE,           /* a <= b */
    AST_BIN_OP_GT,           /* a >  b */
    AST_BIN_OP_GE,           /* a >= b */

    /* equality */
    AST_BIN_OP_EQ,           /* a == b */
    AST_BIN_OP_NE,           /* a != b */

    /* bitwise */
    AST_BIN_OP_BIT_AND,      /* a &  b */
    AST_BIN_OP_BIT_XOR,      /* a ^  b */
    AST_BIN_OP_BIT_OR,       /* a |  b */

    /* logical */
    AST_BIN_OP_LOG_AND,      /* a && b */
    AST_BIN_OP_LOG_OR,       /* a || b */

    /* assignment (simple + compound) */
    AST_BIN_OP_ASSIGN,       /* a  =  b */
    AST_BIN_OP_ADD_ASSIGN,   /* a += b */
    AST_BIN_OP_SUB_ASSIGN,   /* a -= b */
    AST_BIN_OP_MUL_ASSIGN,   /* a *= b */
    AST_BIN_OP_DIV_ASSIGN,   /* a /= b */
    AST_BIN_OP_MOD_ASSIGN,   /* a %= b */
    AST_BIN_OP_SHL_ASSIGN,   /* a <<= b */
    AST_BIN_OP_SHR_ASSIGN,   /* a >>= b */
    AST_BIN_OP_AND_ASSIGN,   /* a &= b */
    AST_BIN_OP_XOR_ASSIGN,   /* a ^= b */
    AST_BIN_OP_OR_ASSIGN     /* a |= b */
} AstBinOp;

const char *astBinOpKindToString(AstBinOp op);

/* Relates to the 'kind' property on the AstType struct */
typedef enum AstTypeKind {
    AST_TYPE_VOID         = 0,
    AST_TYPE_INT          = 1,
    AST_TYPE_FLOAT        = 2,
    AST_TYPE_CHAR         = 3,
    AST_TYPE_ARRAY        = 4,
    AST_TYPE_POINTER      = 5,
    AST_TYPE_FUNC         = 6,
    AST_TYPE_CLASS        = 7,
    AST_TYPE_VIS_MODIFIER = 8,
    AST_TYPE_INLINE       = 9,
    AST_TYPE_UNION        = 10,
    AST_TYPE_AUTO         = 11
} AstTypeKind;

/* Relates to the 'kind' property on the Ast struct */
typedef enum AstKind {
    AST_GVAR          = 256,
    AST_GOTO          = 258,
    AST_LABEL         = 259,
    AST_LVAR          = 261,
    AST_FUNC          = 262,
    AST_DECL          = 263,
    AST_STRING        = 264,
    AST_FUNCALL       = 265,
    AST_LITERAL       = 266,
    AST_ARRAY_INIT    = 267,
    AST_IF            = 268,
    AST_FOR           = 269,
    AST_RETURN        = 270,
    AST_WHILE         = 271,
    AST_CLASS_REF     = 273,
    AST_COMPOUND_STMT = 274,
    AST_ASM_STMT      = 275,
    AST_ASM_FUNC_BIND = 276,
    AST_ASM_FUNCALL   = 277,
    AST_FUNPTR        = 278,
    AST_FUNPTR_CALL   = 279,
    AST_BREAK         = 280,
    AST_CONTINUE      = 281,
    AST_DEFAULT_PARAM = 282,
    AST_VAR_ARGS      = 283,
    AST_ASM_FUNCDEF   = 284,
    AST_CAST          = 285,
    AST_FUN_PROTO     = 286,
    AST_CASE          = 287,
    AST_JUMP          = 288,
    AST_EXTERN_FUNC   = 289,
    AST_DO_WHILE      = 290,
    AST_PLACEHOLDER   = 291,
    AST_SWITCH        = 292,
    AST_DEFAULT       = 293,
    AST_BINOP         = 296,
    AST_UNOP          = 297,
    AST_TRY           = 298,  /* try { ... } catch <stmt> */
    AST_THROW         = 299,  /* throw(u64_value); */
    AST_TERNARY       = 300,  /* cond ? then : els (the conditional operator) */
    AST_DEFER         = 301,  /* defer <stmt>; runs <stmt> when its block is left */
    AST_VA_PACK       = 302,  /* the extras of a `T ...name` call, see astVaPack */
    AST_SLICE_MAKE    = 303,  /* a `T[]` built from a pointer and a length, see astSliceMake */
    AST_SLICE_ELEM    = 304,  /* &s[i] of a slice, bounds-checked, see astSliceElem */
    AST_SUBSLICE      = 305,  /* s[a:b] of a slice, array or pointer, see astSubSlice */
    AST_TEMP_ADDR     = 306,  /* &tmp of a struct value put in a hidden variable, see astTempAddr */
} AstKind;

/* @Cleanup
 * Urgently get rid of this, we do not need `n` ways of setting a label on 
 * an AST it is extremely confusing */
#define astHackedGetLabel(ast) \
    ((ast)->kind == AST_GOTO || (ast)->kind == AST_LABEL ? ((ast)->slabel ? (ast)->slabel : (ast)->sval) : \
    ((ast)->kind == AST_JUMP ? (ast)->jump_label : (ast)->kind == AST_CASE ? (ast)->case_label : NULL))


typedef struct AstType AstType;
/* Type of the variable or type of return type of a function */
typedef struct AstType {
    AstTypeKind kind;
    int size;
    u8 has_var_args;

    /* Alignment of a struct or union */
    u32 alignment;

    /* Provenance of the DEFINITION (class/union tag token) for
     * jump-to-definition; 0 = unknown/builtin. Only stamped for
     * named class/union definitions - see parseClassOrUnion. */
    int line;
    int col;
    u32 file_id;

    /* Value signed or unsigned? */
    int issigned;

    /* Pointer or an array */
    AstType *ptr;

    /* Array length */
    int len;

    /* Class */
    AoStr *clsname;
    Map *fields;
    int offset;
    int is_intrinsic;
    /* `class D : B`: B, whose fields D starts with; NULL otherwise */
    struct AstType *base_cls;
    /* A class or union the standard library defines: only it can add
     * methods to it */
    u8 in_std;
    /* On a class field marked `private`: the module that defines its
     * class (Lexer.modules, 1-based; 0 for a public field), the only one
     * that can use it, and the class's name for the messages */
    u32 private_module;
    AoStr *private_of;
    /* Bool: a 1-byte char type, but a value converted to it becomes 0 or
     * 1 (value != 0), as in C, instead of keeping its low byte. */
    u8 is_bool;
    /* A scoped enum (`enum Name : IntType { ... };`): an intrinsic class
     * (`is_intrinsic`) with no instance fields, whose members live in
     * `enum_members` (member name -> its constant Ast, typed as this
     * enum) instead. Only another enum of the SAME name converts to/from
     * it without a cast; every other integer type needs one. */
    u8 is_enum;
    Map *enum_members;
    /* A slice `T[]` (astMakeSliceType): a class of two fields, `T *ptr`
     * and `i64 len`, interned per element type, whose element is `elem` */
    u8 is_slice;
    struct AstType *elem;
    /* Generics: a generic struct's template (`Vec` before its arguments)
     * points at its definition; an instance (`Vec<i64>`) at its
     * definition and arguments; a type parameter stand-in (inference) */
    void *generic;
    Vec *generic_args;
    u8 is_type_param;
    /* On a class field: a union member after the first one (directly or
     * through an anonymous class/union flattened into the class). A
     * union takes one initialiser item, for its first member, so
     * positional initialisers skip it. */
    int init_skip;

    /* Function */
    AstType *rettype;
    Vec *params;
} AstType;


#define AST_FLAG_INLINE  (1<<0)
#define AST_FLAG_BUILTIN (1<<1)
/* Set on an AST_GVAR / AST_DECL that came from `extern Type name;` -
 * a forward declaration. The parser registers the
 * symbol in `cc->global_env` so user code can reference it, but the
 * codegen skips emitting storage: the canonical definition lives in
 * another translation unit (typically libktm). */
#define AST_FLAG_EXTERN  (1<<2)
/* Set on the AST_FUNCALL to C's printf that a ktm print statement
 * (`"%d\n", x;`) lowers to. Its integer conversions were rewritten to
 * 64-bit (`%d` -> `%lld`), so the IR widens every narrow integer
 * argument to a full 64-bit value before the call. */
#define AST_FLAG_PRINT_STMT (1<<3)
/* Set on a function declared `static` at file scope: its symbol is local
 * to the object, so it neither overrides nor is overridden by a function
 * of the same name elsewhere (a library internal, a user function). */
#define AST_FLAG_STATIC (1<<5)
/* Set on an AST_GVAR / AST_LVAR declared `const`: assigning to it (`=`,
 * a compound assignment, `++`/`--`) is a compile error. */
#define AST_FLAG_CONST (1<<6)
/* Set alongside AST_FLAG_CONST only when the const's own initialiser
 * was itself a compile-time-constant integer expression: `deref_symbol`
 * (otherwise unused on these kinds) then holds that value, so the
 * variable itself folds in evalIntConstExprOrErr - usable in array
 * sizes, case labels and other constant expressions. A `const` whose
 * initialiser is not foldable (`const i64 n = f();`) keeps AST_FLAG_CONST
 * (still not assignable) without this one: it behaves like an ordinary
 * runtime variable everywhere evalIntConstExprOrErr is consulted. */
#define AST_FLAG_CONST_VALUE (1<<7)
/* Set on the AST_LVAR `name` of a `T ...name` / `...name` parameter: a
 * pointer to the caller's array of the extra arguments, next to a hidden
 * count parameter `name#len`. `name.len`, `name[i]` and range-for read
 * them; `name` itself can't be assigned. */
#define AST_FLAG_VARARGS (1<<8)

/* A main-program function or global (not a module's, not extern "c", not
 * main): its symbol is astProgramSymbol's `_K0<len><name>`, so a program's
 * `read` or `environ` can't replace the C library's or libktm's symbol of
 * that name. Messages and lookups keep the plain name. */
#define AST_FLAG_PROGRAM (1<<9)
/* `extern "c" T name;`: C data, its symbol the C name */
#define AST_FLAG_EXTERN_C (1<<10)
/* A local u8[] initialised from a string literal (`u8[] a = "abc";`):
 * a write through it is a compile error unless the local is reassigned
 * or its address taken (AST_FLAG_SLICE_MOVED) somewhere in the function */
#define AST_FLAG_LIT_SLICE (1<<11)
#define AST_FLAG_SLICE_MOVED (1<<12)
/* An AST_SLICE_ELEM proven in range (`s[i]` in `for (i64 i = 0; i < s.len;
 * i++)` when nothing else changes i or s): no bounds check */
#define AST_FLAG_NOCHECK (1<<13)
/* A local whose address is taken somewhere in the function */
#define AST_FLAG_ADDR_TAKEN (1<<14)
/* A function with overloads (others of its name with other parameter
 * types): its fname is its full symbol, the base symbol + `_` + its
 * parameter types (astOverloadSymbol), and `overloads` lists the set */
#define AST_FLAG_OVERLOAD (1<<15)
/* `&f` of an overloaded `f` whose target type hasn't picked one yet
 * (parseResolveOverloadRef) */
#define AST_FLAG_OVERLOAD_REF (1<<16)
/* A module's function declared `pub`: for an overload, whose access is
 * checked on the one a call picks */
#define AST_FLAG_PUB (1<<17)
/* A local declared with `ad` (autodefer): freed at the end of its scope */
#define AST_FLAG_AUTODEFER (1<<18)

/* AST_LVAR.pinned_kind values. */
#define LVAR_AUTO  0  /* compiler chooses storage (default) */
#define LVAR_REG   1  /* pinned to a specific machine register */
#define LVAR_NOREG 2  /* explicit stack slot (required by asm functions) */

/* AsmFragment.kind values. An inline `asm { ... }` block is parsed
 * as a list of fragments: literal TEXT chunks and LVAR_REFs
 * (`&var` references to ktm locals). The per-arch emitter renders
 * LVAR_REFs using the local's stack offset, which isn't known until
 * the layout pass runs (well after parse time). */
#define ASM_FRAG_TEXT     1
#define ASM_FRAG_LVAR_REF 2

typedef struct Ast Ast;

typedef struct AsmFragment {
    int kind;
    AoStr *text;   /* ASM_FRAG_TEXT */
    Ast *lvar;     /* ASM_FRAG_LVAR_REF: must point to an AST_LVAR */
} AsmFragment;
typedef struct Ast {
    AstKind kind;
    u64 flags;
    AstType *type;
    int loff;
    /* Source line of the token being consumed when this node was
     * created (statement-level accuracy). 0 = unknown/synthetic. */
    int line;
    /* 1-based column of that token's first byte. 0 = unknown. */
    int col;
    /* Which file (cctrlLookUpFile) - 0 = unknown. */
    u32 file_id;
    s64 deref_symbol;
    union {
        struct {
        /* 8, 16, 32, 64 bit number */
            s64 i64;
        };

        /* Float & Double */
        struct {
            double f64;
            AoStr *f64_label;
        };

        /* asm block */
        struct {
            AoStr *asm_stmt;
            List *funcs;
            /* Optional list of AsmFragment* when the asm body uses
             * `&var` refs that need stack-offset
             * substitution at emit time. NULL for pure-text asm
             * blocks (the existing code keeps using `asm_stmt`). */
            List *asm_fragments;
        };

        /* String */
        struct {
            AoStr *sval;
            AoStr *slabel;
            s64 real_len;
        };

        /* Local variable */
        struct {
            u32 lvar_id;
            AoStr *lname;
            /* register pinning. `pinned_kind` defaults
             * to LVAR_AUTO (compiler chooses storage); `LVAR_REG` ties
             * the local to a specific machine register named by
             * `pinned_reg`; `LVAR_NOREG` explicitly forces a stack
             * slot. The latter only matters semantically inside
             * functions that contain `asm { }` blocks, where AUTO is
             * forbidden. */
            int pinned_kind;
            AoStr *pinned_reg;
        };

        /* Global variable */
        struct {
            int is_static;
            AoStr *gname;
            AoStr *glabel;
        };

        /* Local reference */
        struct {
            Ast *lref;
            int lrefoff;
        };

        /* Global reference */
        struct {
            Ast *gref;
            int goff;
        };

        /* Binary operator */
        struct {
            AstBinOp binop;
            Ast *left;
            Ast *right;
        };

        /* Unary operator */
        struct {
            AstUnOp unop;
            Ast *operand;
        };

        /* Function call, declaration, pointer or assembly */
        struct {
            /* asm function binding */
            AoStr *asmfname;
            AoStr *fname;
            Vec *args;
            Vec *params;
            u32 fn_ptr_id;

            /* Declaration */
            List *locals;
            Ast *body;
            Ast *ref; /* for function pointers, a reference to the variable 
                       * allows for keeping track of the offset when converting 
                       * to assembly. */
            Ast *default_fn; /* For function pointers, allows setting a default
                              * value... we could use this for all default vals
                              * might be easier? */
            int has_var_args;
            /* AST_FLAG_OVERLOAD: every function of this name, shared by
             * all of them */
            Vec *overloads;
        };

        /* Declaration */
        struct {
            Ast *declvar;
            Ast *declinit;
        };

        struct {
            /* Array initialiser */
            List *arrayinit;
        };

        /* If statement */
        struct {
            Ast *cond;
            Ast *then;
            Ast *els;
        };

        /* For statement */
        struct {
            Ast *forinit;
            Ast *forcond;
            Ast *forstep;
            Ast *forbody;
            AoStr *for_begin;
            AoStr *for_middle;
            AoStr *for_end;
        };

        /* while statement */
        struct {
            Ast *whilecond;
            Ast *whilebody;
            AoStr *while_begin;
            AoStr *while_end;
        };

        struct {
            /* Return statement */
            Ast *retval;
        };

        /* try { body } catch <handler> */
        struct {
            Ast *try_body;
            Ast *catch_body;
        };

        /* throw(code), or throw(code, fmt, ...) / throw(e) as a call */
        struct {
            Ast *throw_value;  /* the code; NULL with throw_call */
            Ast *throw_call;   /* KTM_ThrowFmtAt(...) or KTM_ThrowError(&e) */
            Ast *throw_file;   /* AST_STRING: the file the throw is in */
        };

        /* defer <stmt> */
        struct {
            Ast *defer_stmt;
        };

        /* The extras of a call to a `T ...name` function, stored into the
         * caller's hidden array va_array (an AST_LVAR) one per item;
         * evaluates to its address. Untyped (`...name`): 64-bit slots. */
        struct {
            Ast *va_array;
            Vec *va_items;
            int va_untyped;
        };

        /* A `T[]` value made from `slice_ptr` and `slice_len`, written
         * into the hidden `slice_var` (an AST_LVAR, or an AST_GVAR in
         * file-scope code); evaluates to its address, as a struct does */
        struct {
            Ast *slice_var;
            Ast *slice_ptr;
            Ast *slice_len;
        };

        /* `&s[i]` (AST_SLICE_ELEM: sub_lo is i) and `s[a:b]`
         * (AST_SUBSLICE, into the hidden sub_var; sub_lo/sub_hi NULL when
         * left out) of `sub_base`, a slice, an array or (a sub-slice only,
         * unchecked) a pointer. A failed check throws rt.ERR_BOUNDS at
         * `sub_file`:line. */
        struct {
            Ast *sub_base;
            Ast *sub_lo;
            Ast *sub_hi;
            Ast *sub_var;
            Ast *sub_file;
        };

        /* @Typeo
         * Compound statement */
        struct {
            Ast *inline_ret;
            List *stms;
        };

        /* Class */
        struct {
            Ast *cls;
            char *field;
            AstType *fieldtype;
        };

        /* Var Args */
        struct {
            Ast *argc;
            Ast *argv;
        };

        /* For a case */
        struct {
            s64 case_begin;
            s64 case_end;
            AoStr *case_label;
            /* Scopes ast nodes to the case label... Think this makes sense;
             * in my head it does. As then all the top level case nodes 
             * have the begining and end ranges... making creating a jump table
             * feasible... or degrade into a series of 'ifs' if the range is 
             * sparse */
            List *case_asts;
            /* the arm ends in `fallthrough;` (at case_ft_line:col) and
             * runs on into the next one; every other arm leaves the
             * switch at its end */
            int case_fallthrough;
            s64 case_ft_line, case_ft_col;
        };

        /* For a switch */
        struct {
            int switch_bounds_checked;
            Ast *switch_cond;
            Ast *case_default;
            Ast **jump_table_order;
            Vec *cases;
            AoStr *case_end_label;
        };

        struct {
            AoStr *jump_label;
        };
    };
} Ast;

/* Source line of the token the parser is currently consuming -
 * published by cctrlTokenGet, stamped onto every astNew node so the
 * IR (and ultimately JIT line tables) can attribute code to source. */
extern int ast_line_hint;
/* 1-based column of the token being consumed (Lexeme->col). */
extern int ast_col_hint;
/* Which cc->file_map entry the parser is currently consuming tokens
 * from - resolve with cctrlLookUpFile. 0 = unknown. */
extern u32 ast_file_hint;

extern AstType *ast_int_type;
extern AstType *ast_uint_type;
extern AstType *ast_u8_type;
extern AstType *ast_i8_type;
extern AstType *ast_float_type;
extern AstType *ast_void_type;
extern AstType *ast_i16_type;
extern AstType *ast_u16_type;
extern AstType *ast_i32_type;
extern AstType *ast_u32_type;
extern Ast *placeholder_arg;
extern Ast *ast_loop_sentinal;
extern Ast *ast_forever_sentinal;

extern VecType vec_ast_type;
Vec *astVecNew(void);

extern MapType map_asttype_type;
extern MapType map_ast_type;
Map *astTypeMapNew(void);
Map *astMapNew(void);

void astMemoryInit(void);
void astMemoryRelease(void);
void astMemoryStats(void);

AstType *astTypeCopy(AstType *type);

/* Literals */
Ast *astI64Type(s64 val);
Ast *astF64Type(double val);
Ast *astCharType(s64 ch);
Ast *astString(char *str, int len, s64 real_len);
/* Decode a string literal's escapes into `out` (sval->len + 1 bytes). */
int astStringDecode(Ast *str, char *out);

/* Declarations */
Ast *astDecl(Ast *var, Ast *init);

/* Symbol operators i.e: +-*&^><*/
Ast *astBinaryOp(AstBinOp operation, Ast *left, Ast *right, int *_is_err);
Ast *astUnaryOperator(AstType *type, AstUnOp operation, Ast *operand);

/* Variable definitions */
Ast *astLVar(AstType *type, char *name, int len);
char *astRuntimeName(const char *name);
char *astRuntimeSymbol(const char *name);
char *astModuleMangle(char *qual, int len);
char *astModuleDemangle(char *sym, int len);
char *astOverloadSymbol(const char *base, Vec *params, int from);
/* A function's overload set (AST_FLAG_OVERLOAD), NULL for anything else */
#define astOverloads(ast) \
    ((ast) && ((ast)->kind == AST_FUNC || (ast)->kind == AST_FUN_PROTO) \
     ? (ast)->overloads : NULL)
char *astProgramSymbol(char *name, int len);
const char *astSymbolDisplayName(const char *sym);
Ast *astGVar(AstType *type, char *name, int len, int is_static);

/* More beefy data structures */
Ast *astArrayInit(List *init);
Ast *astCompountStatement(List *stmts);

/* Control */
Ast *astFor(Ast *init, Ast *cond, Ast *step, Ast *body, AoStr *for_begin,
        AoStr *for_middle, AoStr *for_end);
Ast *astIf(Ast *cond, Ast *then, Ast *els);
Ast *astTernary(AstType *type, Ast *cond, Ast *then, Ast *els);
Ast *astWhile(Ast *whilecond, Ast *whilebody, AoStr *while_begin,
        AoStr *while_end);
Ast *astDoWhile(Ast *whilecond, Ast *whilebody, AoStr *while_begin, 
        AoStr *while_end);
Ast *astContinue(AoStr *continue_label);
Ast *astBreak(AoStr *break_label);
Ast *astCase(AoStr *case_label, s64 case_begin, s64 case_end, List *case_asts);
/* Do it with lists, then do it with a vector */
Ast *astSwitch(Ast *cond, Vec *cases, Ast *case_default,
        AoStr *case_end_label, int switch_bounds_checked);
Ast *astDefault(AoStr *case_label,List *case_asts);

/* Functions */
Ast *astFunctionCall(AstType *type, char *fname, int len, Vec *argv);
Ast *astFunction(AstType *rettype, char *fname, int len, Vec *params,
                 Ast *body, List *locals, int has_var_args);
Ast *astReturn(Ast *retval, AstType *rettype);
Ast *astTry(Ast *try_body, Ast *catch_body);
Ast *astThrow(Ast *value);
Ast *astDefer(Ast *stmt);
Ast *astVaPack(Ast *array, Vec *items, int untyped);
AstType *astMakeSliceType(AstType *elem);
int astTypeIsSlice(AstType *type);
int astTypeHasSlice(AstType *type);
int astTypeIsString(AstType *type);
Ast *astSliceMake(Ast *var, Ast *ptr, Ast *len);
Ast *astSliceElem(Ast *base, Ast *index, Ast *file, int line);
Ast *astSubSlice(Ast *var, Ast *base, Ast *lo, Ast *hi, Ast *file, int line);
Ast *astTempAddr(Ast *var, Ast *value);
Ast *astFunctionPtr(AstType *type,
                    char *fname,
                    int fname_len, 
                    Vec *params);

Ast *astFunctionPtrCall(AstType *type,
                        char *fname,
                        int fname_len,
                        Vec *argv,
                        Ast *ref);

Ast *astFunctionDefaultParam(Ast *var, Ast *init);
Ast *astVarArgs(void);

Ast *astAsmBlock(AoStr *asm_stmt, List *funcs);
AsmFragment *asmFragmentText(AoStr *text);
AsmFragment *asmFragmentLvarRef(Ast *lvar);
Ast *astAsmFunctionBind(AstType *rettype, AoStr *asm_fname, 
        AoStr *fname, Vec *params);
Ast *astAsmFunctionCall(AstType *rettype, AoStr *asm_fname, Vec *argv);
Ast *astAsmFunctionDef(AoStr *asm_fname, AoStr *asm_stmt);

/* Gotos */
Ast *astGoto(AoStr *label);
Ast *astLabel(AoStr *label);
Ast *astJump(char *jumpname, int len);

/* Pointers */
AstType *astMakePointerType(AstType *type);
AstType *astMakeArrayType(AstType *type, int len);
AstType *astMakeClassField(AstType *type, int offset);
AstType *astMakeFunctionType(AstType *rettype, Vec *param_types);
AstType *astConvertArray(AstType *ast_type);
Ast *astClassRef(AstType *type, Ast *cls, char *field_name);
AstType *astClassType(Map *fields, AoStr *clsname, int size, int is_intrinsic);
Ast *astCast(Ast *var, AstType *to);

AstType *astGetResultType(AstBinOp op, AstType *a, AstType *b);
AstType *astTypeCheck(AstType *expected, Ast *ast, AstBinOp op);

/* Queries */
int astIsIntType(AstType *type);
int astIsIntOrIntrinsic(AstType *type);
int astIsU64Type(AstType *type);
int astIsFloatType(AstType *type);
int astTypeAlign(AstType *type);

/* Iterate a class/union's fields in declaration order. The callback gets
 * each field's name + type; returning non-zero stops iteration early and
 * that value is returned (0 if it ran to completion). Beats poking at the
 * fields Map's index vector by hand at every call site. */
typedef int (*AstClassFieldCb)(AoStr *field_name, AstType *field, void *ud);
int astForEachClassField(AstType *cls, AstClassFieldCb cb, void *ud);
/* The field the `idx`-th positional initialiser item sets (union
 * members after the first are skipped), or NULL past the last one. */
MapNode *astClassInitFieldAt(AstType *cls, int idx);
AstType *astClassFieldAt(AstType *cls, int idx);

/* Platform C-ABI classification of an aggregate passed/returned by value:
 *  - AAPCS_HFA: Homogeneous Floating-point Aggregate (1-4 same FP members)
 *               -> FP registers; hfa_elem (4/8) + hfa_count written.
 *  - AAPCS_INTEGER: any other aggregate <= 16 bytes -> 1-2 GP registers.
 *  - AAPCS_INDIRECT: aggregate > 16 bytes -> passed/returned by reference. */
typedef enum { AAPCS_INTEGER, AAPCS_HFA, AAPCS_INDIRECT } AapcsClass;
AapcsClass astAapcsClassify(AstType *type, int *hfa_elem, int *hfa_count);

/* System V AMD64 classification of an aggregate passed/returned by value.
 * The aggregate is split into 8-byte "eightbytes"; each is classified
 * SYSV_INTEGER (-> GP reg) or SYSV_SSE (-> XMM reg). Aggregates larger than
 * 16 bytes (more than two eightbytes) are class SYSV_MEMORY and passed on
 * the stack by value. astSysvClassify fills `classes[0..*n_eightbytes-1]`
 * and returns 1 for MEMORY (in which case `classes` is not meaningful), 0
 * otherwise. */
typedef enum { SYSV_NO_CLASS, SYSV_INTEGER, SYSV_SSE, SYSV_MEMORY } SysvClass;
int astSysvClassify(AstType *type, SysvClass *classes, int *n_eightbytes);
int astTypeIsPtr(AstType *type);
int astTypeIsArray(AstType *type);
int astIsIntrinsicClass(AstType *ty);
int astIsEnumType(AstType *ty);
int astFnTypesMatch(AstType *a, AstType *b);
int astFnPtrMismatch(AstType *expected, AstType *actual);
int astFnPtrConvertible(AstType *expected, AstType *actual);
int astFnPtrOnlyDirect(AstType *expected, AstType *actual);
int astIsDifferentEnum(AstType *a, AstType *b);
int astIsVarArg(Ast *ast);
int astIsRangeOperator(AstBinOp op);
int astIsBinCmp(s64 op);
int astIsAssignment(s64 op);
int astIsLabelMatch(Ast *ast, AoStr *goto_label);
int astIsAddr(Ast *ast);
int astIsDeref(Ast *ast);
int astIsUnOp(Ast *ast);
/* Check if the ast exists, if it is a unary operator and what op it is */
int astIsUnOpKind(Ast *ast, AstUnOp op);

int astIsBinOp(Ast *ast);
/* Check if the ast exists, if it is a binary operator and what op it is */
int astIsBinOpKind(Ast *ast, AstBinOp op);

int astIsBinOpCmp(Ast *ast);

int astIsFnLike(Ast *maybe_fn);

AoStr *astMakeLabel(void);
AoStr *astMakeTmpName(void);
Ast *astGlobalCmdArgs(void);

AoStr *astNormaliseFunctionName(char *fname);
Ast *astMakeForeverSentinal(void);
Ast *astMakeLoopSentinal(void);
Ast *astMakePlaceHolder(void);
void astResetLVarId(void);
u32 astGetLVarId(void);
void astSetLVarId(u32 id);

/* Returns `1` on successful conversion and `0` on failure */
int astUnaryOpFromToken(s64 op, AstUnOp *_result);
/* Returns `1` on successful conversion and `0` on failure */
int astBinOpFromToken(s64 op, AstBinOp *_result);

/* For debugging */
AoStr *astTypeToAoStr(AstType *type);
char *astTypeToString(AstType *type);
char *astTypeToColorString(AstType *type);
AoStr *astTypeToColorAoStr(AstType *type);
char *astKindToString(AstKind kind);
char *astTypeKindToString(AstTypeKind kind);
char *astFunctionToString(Ast *func);
char *astFunctionNameToString(AstType *rettype, char *fname, int len);
char *astToString(Ast *ast);
AoStr *astToAoStr(Ast *ast);
char *astLValueToString(Ast *ast, u64 lexme_flags);
AoStr *astLValueToAoStr(Ast *ast, u64 lexeme_flags);
AoStr *astClassToAoStr(AstType *cls);
void astPrint(Ast *ast);
void astTypePrint(AstType *type);
void astKindPrint(int kind);
const char *astTypeKindToHumanReadable(AstType *type);
const char *astKindToHumanReadable(Ast *ast);

#endif
