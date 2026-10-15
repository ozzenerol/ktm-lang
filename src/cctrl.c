#include <assert.h>
#include <ctype.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/time.h>
#include <unistd.h>

#include "aostr.h"
#include "ast.h"
#include "cctrl.h"
#include "config.h"
#include "lexer.h"
#include "list.h"
#include "util.h"
#include "version.h"

/* VecType for the CtrlDiagnostic accumulator. Owns its entries,
 * release frees the heap-allocated CctrlDiagnostic plus its message /
 * suggestion AoStrs. */
static void cctrlDiagnosticStringify(AoStr *buf, void *ptr) {
    CctrlDiagnostic *d = (CctrlDiagnostic *)ptr;
    if (d && d->message) {
        aoStrCatFmt(buf, "%S", d->message);
    }
}

static int cctrlDiagnosticMatch(void *a, void *b) {
    return a == b;
}

static int cctrlDiagnosticRelease(void *ptr) {
    CctrlDiagnostic *d = (CctrlDiagnostic *)ptr;
    if (d) {
        if (d->message) aoStrRelease(d->message);
        if (d->suggestion) aoStrRelease(d->suggestion);
        free(d);
    }
    return 1;
}

static VecType vec_diagnostic_type = {
    .stringify = cctrlDiagnosticStringify,
    .match     = cctrlDiagnosticMatch,
    .release   = cctrlDiagnosticRelease,
    .type_str  = "CctrlDiagnostic *",
};

/* `Map<char *, Lexeme *>` Map does not own either the key nor the Lexeme */
MapType map_cstring_lexeme_type = {
    .match           = mapCStringEq,
    .hash            = mapCStringHash,
    .get_key_len     = mapCStringLen,
    .key_to_string   = mapCStringToString,
    .key_release     = NULL,
    .value_to_string = (mapValueToString *)lexemeToAoStr,
    .value_release   = NULL,
    .key_type        = "char *",
    .value_type      = "Lexeme *",
};

Map *cctrlCreateLexemeMap(void) {
    return mapNew(32, &map_cstring_lexeme_type);
}

Map *cctrlCreateAstMap(Map *parent) {
    return mapNewWithParent(parent, 32, &map_ast_type);
}

static char *x86_registers = "rax,rbx,rcx,rdx,rsi,rdi,rbp,rsp,r8,r9,r10,r11,r12,"
    "r13,r14,r15,cs,ds,es,fs,gs,ss,rip,rflags,st0,st1,st2,st3,st4,st5,st6,st7,"
    "mm0,mm1,mm2,mm3,mm4,mm5,mm6,mm7,xmm0,xmm1,xmm2,xmm3,xmm4,xmm5,xmm6,xmm7,"
    "xmm8,xmm9,xmm10,xmm11,xmm12,xmm13,xmm14,xmm15,ymm0,ymm1,ymm2,ymm3,ymm4,"
    "ymm5,ymm6,ymm7,ymm8,ymm9,ymm10,ymm11,ymm12,ymm13,ymm14,ymm15,cr0,cr2,"
    "cr3,cr4,cr8,dr0,dr1,dr2,dr3,dr6,dr7,eax,ebx,ecx,edx,esi,edi,ebp,eip,esp,"
    "ax,bx,cx,dx,ah,al,bh,bl,ch,cl,dh,dl,"
    // Not registers but need % in front of them
    "sil";

/* eventually it would be nice to remove the dependency on these functions 
 * syscall can be used for a lot of them */
static char *libc_functions = "printf,snprintf,exit,malloc,free,open,read,close,"
    "exit,system";

typedef struct BuiltInType {
    char *name;
    int kind;
    int size;
    int issigned;
} BuiltInType;

/* Name, kind, size, issigned, essentially duplicated in the 
 * lexer */
static BuiltInType built_in_types[] = {
    /* XXX: merge with lexers types
     * Ktm Types */
    {"U0",AST_TYPE_VOID,0,0},
    {"Bool",AST_TYPE_CHAR,1,1},
    {"I8",AST_TYPE_CHAR,1,1},
    {"U8",AST_TYPE_CHAR,1,0},
    {"I16",AST_TYPE_INT,2,1},
    {"U16",AST_TYPE_INT,2,0},
    {"I32",AST_TYPE_INT,4,1},
    {"U32",AST_TYPE_INT,4,0},
    {"I64",AST_TYPE_INT,8,1},
    {"U64",AST_TYPE_INT,8,0},
    {"F64",AST_TYPE_FLOAT,8,0},
    {"F32",AST_TYPE_FLOAT,4,0},
    {"inline",AST_TYPE_INLINE,0,0},
    {"public",AST_TYPE_VIS_MODIFIER,0,0},
    {"pub",AST_TYPE_VIS_MODIFIER,0,0},
    {"auto",AST_TYPE_AUTO,0,0},
    {"private",AST_TYPE_VIS_MODIFIER,0,0},
};

/* New name -> the built-in type it names. */
static struct { const char *name, *old_name; } type_aliases[] = {
    {"void", "U0"}, {"bool", "Bool"},
    {"i8", "I8"},   {"u8", "U8"},   {"i16", "I16"}, {"u16", "U16"},
    {"i32", "I32"}, {"u32", "U32"}, {"i64", "I64"}, {"u64", "U64"},
    {"f32", "F32"}, {"f64", "F64"},
};

static void cctrlAddX86_64Defines(Cctrl *cc, Lexeme *le) {
    mapAdd(cc->macro_defs,"IS_X86_64",le);
    mapAdd(cc->macro_defs,"__x86_64__",le);
    le = lexemeNew(str_lit("x86_64"));
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__ARCH__",le);
}

static void cctrlAddAArch64Defines(Cctrl *cc, Lexeme *le) {
    mapAdd(cc->macro_defs,"IS_ARM_64",le);
    mapAdd(cc->macro_defs,"__aarch64__",le);
    le = lexemeNew(str_lit("AArch64"));
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__ARCH__",le);
}

static void cctrlAddLinuxDefines(Cctrl *cc, Lexeme *le) {
    mapAdd(cc->macro_defs,"__linux__",le);
    mapAdd(cc->macro_defs,"IS_LINUX",le);
}

static void cctrlAddMacOsDefines(Cctrl *cc, Lexeme *le) {
    mapAdd(cc->macro_defs,"__macos__",le);
    mapAdd(cc->macro_defs,"IS_MACOS",le);
}

void cctrlAddDefine(Cctrl *cc, char *name) {
    Lexeme *le = lexemeSentinal();
    mapAdd(cc->macro_defs,name,le);
}

static void cctrlAddBuiltinMacros(Cctrl *cc) {
    s64 bufsize = sizeof(char)*128;
    Lexeme *le = lexemeSentinal();

    /* This is so we can also cross compile, often the library uses ifdefs but
     * using what the host platform is would mean for `#ifdefs` on assembly we
     * can assemble the wrong code for the wrong platform. */
    switch (cc->target) {
        case TARGET_AARCH64_APPLE_DARWIN:
            cctrlAddMacOsDefines(cc, le);
            cctrlAddAArch64Defines(cc, le);
            break;
        case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
            cctrlAddLinuxDefines(cc, le);
            cctrlAddAArch64Defines(cc, le);
            break;
        case TARGET_X86_64_APPLE_DARWIN:
            cctrlAddMacOsDefines(cc, le);
            cctrlAddX86_64Defines(cc, le);
            break;
        case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            cctrlAddLinuxDefines(cc, le);
            cctrlAddX86_64Defines(cc, le);
            break;
    }


    struct timeval tm;
    gettimeofday(&tm,NULL);
    s64 milliseconds = (tm.tv_sec*1000) +
        (tm.tv_usec/1000);
    time_t seconds = milliseconds / 1000;
    struct tm *ptm = localtime(&seconds);

    char *time = (char *)malloc(bufsize);
    char *date = (char *)malloc(bufsize);
    char *time_stamp = (char *)malloc(bufsize);

    s64 len = snprintf(time,bufsize,"%02d:%02d:%02d",
            ptm->tm_hour,ptm->tm_min,ptm->tm_sec);
    time[len] = '\0';
    le = lexemeNew(time,len);
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__TIME__",le);

    len = snprintf(date,bufsize,"%04d/%02d/%02d",
            ptm->tm_year+1900,ptm->tm_mon+1,ptm->tm_mday);
    date[len] = '\0';
    le = lexemeNew(date,len);
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__DATE__",le);

    len = snprintf(time_stamp,bufsize,
            "%d-%02d-%02d %02d:%02d:%02d",
            ptm->tm_year+1900,
            ptm->tm_mon+1,ptm->tm_mday,ptm->tm_hour,
            ptm->tm_min,ptm->tm_sec);
    date[len] = '\0';
    le = lexemeNew(time_stamp,len);
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__TIMESTAMP__",le);

#ifdef KTM_LINK_SQLITE3
    le = lexemeSentinal();
    mapAdd(cc->macro_defs,"__KTM_LINK_SQLITE3__",le);
#endif

    char *version = (char *)cctrlGetVersion();
    le = lexemeNew(version,strlen(version));
    le->tk_type = TK_STR;
    mapAdd(cc->macro_defs,"__KTM_VERSION__",le);
}

/* C's printf, built in (the stdlib map: no module, the compiler checks
 * it), declared as the old std.ktmh did: an install that still has that
 * header redeclares it the same */
static void cctrlAddBuiltinPrintf(Cctrl *cc) {
      Vec *pf_params = vecNew(&vec_ast_type);
      vecPush(pf_params, astLVar(astMakePointerType(ast_i8_type), str_lit("fmt")));
      vecPush(pf_params, astVarArgs());
      AstType *pf_type = astMakeFunctionType(ast_int_type, pf_params);
      pf_type->has_var_args = 1;
      Ast *pf = astFunction(pf_type, str_lit("printf"), pf_params, NULL, NULL, 0);
      pf->kind = AST_EXTERN_FUNC;
      pf->flags |= AST_FLAG_BUILTIN;
      mapAdd(cc->global_env, pf->fname->data, pf);
}

static void cctrlAddBuiltinTypes(Map *symbol_table) {
    for (int i = 0; i < (int)static_size(built_in_types); ++i) {
        AstType *type = (AstType *)malloc(sizeof(AstType));
        BuiltInType *built_in = &built_in_types[i];
        memset(type, 0, sizeof(AstType));
        type->size = built_in->size;
        type->issigned = built_in->issigned;
        type->kind = built_in->kind;
        type->is_bool = !strcmp(built_in->name, "Bool");
        type->ptr = NULL;
        mapAdd(symbol_table, built_in->name, type);
    }
    /* The C-like names (ktm syntax v0.2) for the same types. They are type
     * names, not keywords, so a struct field can still be called `i64`. */
    for (int i = 0; i < (int)static_size(type_aliases); ++i) {
        /* the map only reads its keys: the names stay const */
        AstType *type = mapGet(symbol_table, (void *)type_aliases[i].old_name);
        mapAdd(symbol_table, (void *)type_aliases[i].name, type);
    }
}

Cctrl *ccMacroProcessor(Map *macro_defs) {
    Cctrl *cc = (Cctrl *)calloc(1, sizeof(Cctrl));
    cc->macro_defs = macro_defs;
    cc->strs = mapNew(32, &map_ast_type);
    /* Always empty, but the parser looks names up in them: an unknown
     * identifier in a #define's value must reach "not defined", not
     * dereference a garbage map. */
    cc->global_env = mapNew(32, &map_ast_type);
    cc->asm_funcs = mapNew(32, &map_ast_type);
    cc->c_externs = mapNew(32, &map_ast_type);
    cc->init_module_dirs = NULL;
    /* A postfix cast in a #define's value, `#define X (5(U64) % 3)`,
     * looks the type name up: give it the built-in types (classes and
     * unions are not known while lexing, so those stay empty). */
    cc->symbol_table = mapNew(32, &map_asttype_type);
    cctrlAddBuiltinTypes(cc->symbol_table);
    cc->clsdefs = mapNew(32, &map_asttype_type);
    cc->uniondefs = mapNew(32, &map_asttype_type);
    cc->type_aliases = mapNew(16, &map_cstring_opaque_type);
    cc->ast_list = NULL;
    /* The macro-processor stub Cctrl is passed to helpers that may
     * call cctrlRaiseException; keep diagnostics state consistent
     * so those paths don't read uninitialised fields. Errors are
     * collected rather than printed: lexDefine re-raises them on the
     * real Cctrl. */
    cc->diagnostics = vecNew(&vec_diagnostic_type);
    cc->n_errors = 0;
    cc->current_recovery = NULL;
    return cc;
}

/* Instantiate a new compiler control struct */
void cctrlAddPackage(Cctrl *cc, const char *module_path, const char *package,
                     const char *dir)
{
    for (int i = 0; i < cc->packages_len; ++i) {
        if (!strcmp(cc->packages[i].module_path, module_path)) return;
    }
    if (cc->packages_len == cc->packages_cap) {
        cc->packages_cap = cc->packages_cap ? cc->packages_cap * 2 : 4;
        cc->packages = realloc(cc->packages, sizeof(CctrlPackage) * cc->packages_cap);
    }
    char *real = realpath(dir, NULL);
    CctrlPackage *p = &cc->packages[cc->packages_len++];
    p->module_path = strdup(module_path);
    p->package = strdup(package);
    p->dir = real ? real : strdup(dir);
}

Cctrl *cctrlNew(enum CliTarget target) {
    /* zeroed: a field nothing sets here (pending_unary) must start NULL */
    Cctrl *cc = (Cctrl *)calloc(1, sizeof(Cctrl));

    cc->flags = 0;
    cc->defer_depth = 0;
    cc->cur_module = 0;
    cc->decls_only = 0;
    cc->mod_type_decls = vecNew(&vec_unsigned_long_type);
    cc->mod_decls_done = setNew(16, &set_cstring_type);
    cc->mod_types_done = setNew(16, &set_cstring_type);
    cc->replaying = 0;
    cc->quiet_errors = 0;
    cc->decl_pub = 0;
    cc->decl_from_main = 0;
    cc->pub_names = setNew(16, &set_cstring_type);
    cc->tok_file = NULL;
    cc->main_names = setNew(16, &set_cstring_type);
    cc->method_self = NULL;
    cc->file_map = mapNew(16, &map_u32_to_aostr_type);
    cc->global_env = mapNew(32, &map_ast_type);
    cc->clsdefs = mapNew(32, &map_asttype_type);
    cc->uniondefs = mapNew(32, &map_asttype_type);
    cc->type_aliases = mapNew(16, &map_cstring_opaque_type);
    cc->symbol_table = mapNew(32, &map_asttype_type);
    cc->asm_funcs = mapNew(32, &map_ast_type);
    cc->c_externs = mapNew(32, &map_ast_type);
    cc->init_module_dirs = NULL;
    cc->macro_defs = cctrlCreateLexemeMap();
    cc->x86_registers = setNew(32, &set_cstring_type);
    cc->libc_functions = setNew(32, &set_cstring_type);
    cc->header_names = setNew(256, &set_cstring_type);
    cc->header_src = NULL;
    cc->header_len = 0;
    cc->strs = mapNew(32, &map_ast_type);
    cc->asm_blocks = listNew();
    cc->asm_functions = mapNew(32, &map_ast_type);
    cc->ast_list = listNew();
    cc->initalisers = listNew();
    cc->initaliser_locals = listNew();
    /* These are temoraries that the parser will allocate and 
     * NULL out between parses of classes and functions */
    cc->localenv = NULL;
    cc->tmp_rettype = NULL;
    cc->func_params = NULL;
    cc->tmp_params = NULL;
    cc->tmp_loop_begin = NULL;
    cc->tmp_loop_end = NULL;
    cc->tmp_func = NULL;
    cc->tmp_gvar_decl = NULL;
    cc->tmp_gvar_base_type = NULL;
    cc->token_buffer = NULL;
    memset(cc->macro_uses, 0, sizeof(cc->macro_uses));
    cc->macro_use_next = 0;
    cc->diagnostics = vecNew(&vec_diagnostic_type);
    cc->n_errors = 0;
    cc->current_recovery = NULL;
    /* main() overwrites the first two with the CLI's lists; `#link`
     * appends to all three during lexing (creating on demand). */
    cc->object_files = NULL;
    cc->shared_object_files = NULL;
    cc->link_libs = NULL;

    int len;
    AoStr **str_array = aoStrSplit(x86_registers,',',&len);
    for (int i = 0; i < len; ++i) {
        AoStr *upper_reg = aoStrDup(str_array[i]);
        aoStrToUpperCase(upper_reg);
        // Add both lower and upper case
        setAdd(cc->x86_registers,str_array[i]->data);
        setAdd(cc->x86_registers,upper_reg->data);

        //free(upper_reg);
        //free(str_array[i]);
    }
    free(str_array);

    str_array = aoStrSplit(libc_functions,',',&len);
    for (int i = 0; i < len; ++i) {
        char *libc_func = aoStrMove(str_array[i]);
        setAdd(cc->libc_functions,libc_func);
    }
    free(str_array);

    cctrlAddBuiltinTypes(cc->symbol_table);

    cc->target = target;

    cctrlAddBuiltinMacros(cc);
    cctrlAddBuiltinPrintf(cc);

    Ast *cmd_args = astGlobalCmdArgs();
    listAppend(cc->ast_list,cmd_args->argc);
    listAppend(cc->ast_list,cmd_args->argv);
    /* not in scope: a function's `argc` used to find these, always 0
     * and null (main's parameters are the real ones) */
    return cc;
}

static Lexeme *token_ring_buffer[CCTRL_TOKEN_BUFFER_SIZE];

TokenRingBuffer *tokenRingBufferStaticNew(void) {
    TokenRingBuffer *ring_buffer = (TokenRingBuffer *)malloc(sizeof(TokenRingBuffer));
    ring_buffer->tail = 0;
    ring_buffer->head = 0;
    ring_buffer->size = 0;
    ring_buffer->got_eof = 0;
    ring_buffer->pos = 0;
    ring_buffer->entries = token_ring_buffer;
    ring_buffer->capacity = CCTRL_TOKEN_BUFFER_SIZE;
    for (s64 i = 0; i < CCTRL_TOKEN_BUFFER_SIZE; ++i) {
        ring_buffer->entries[i] = NULL;
    }
    return ring_buffer;
}

TokenRingBuffer *tokenRingBufferNew(void) {
    TokenRingBuffer *ring_buffer = (TokenRingBuffer *)malloc(sizeof(TokenRingBuffer));
    ring_buffer->tail = 0;
    ring_buffer->head = 0;
    ring_buffer->size = 0;
    ring_buffer->got_eof = 0;
    ring_buffer->pos = 0;
    return ring_buffer;
}

/* This always moves forward by one so is more of a get next */
s64 tokenRingBufferGetIdx(s64 idx, u64 capacity) {
    return (idx + 1) & (capacity - 1);
}

void tokenBufferPrint(TokenRingBuffer *ring_buffer) {
    for (int i = 0, idx = ring_buffer->tail; i < ring_buffer->size;
            i++, idx = tokenRingBufferGetIdx(idx, ring_buffer->capacity)) {
        printf(">> %s\n", lexemeToString(ring_buffer->entries[idx]));
    }
    printf("\n");
}

int tokenRingBufferEmpty(TokenRingBuffer *ring_buffer) {
    return ring_buffer->size == 0;
}

/* Add a token to the ring buffer and remove the oldest element */
void tokenRingBufferPush(TokenRingBuffer *ring_buffer, Lexeme *token) {
    ring_buffer->entries[ring_buffer->head] = token;
    ring_buffer->head = tokenRingBufferGetIdx(ring_buffer->head,
                                              ring_buffer->capacity);
    if (ring_buffer->size == ring_buffer->capacity) {
        ring_buffer->tail = tokenRingBufferGetIdx(ring_buffer->tail,
                                                  ring_buffer->capacity);
    } else {
        ring_buffer->size++;
    }
}

/* Take one token from the ring buffer */
Lexeme *tokenRingBufferPop(TokenRingBuffer *ring_buffer) {
    if (tokenRingBufferEmpty(ring_buffer)) {
        return NULL;
    }
    Lexeme *token = ring_buffer->entries[ring_buffer->tail];
    ring_buffer->tail = tokenRingBufferGetIdx(ring_buffer->tail,
                                              ring_buffer->capacity);
    ring_buffer->size--;
    return token;
}

Lexeme *tokenRingBufferPeekBy(TokenRingBuffer *ring_buffer, s64 offset) {
    /* we are out of tokens */
    if (tokenRingBufferEmpty(ring_buffer)) {
        return NULL;
    }
    s64 idx = tokenRingBufferGetIdx(ring_buffer->tail + offset,
                                        ring_buffer->capacity);
    return ring_buffer->entries[idx];
}

Lexeme *tokenRingBufferPeek(TokenRingBuffer *ring_buffer) {
    /* we are out of tokens */
    if (tokenRingBufferEmpty(ring_buffer)) {
        return NULL;
    }
    return ring_buffer->entries[ring_buffer->tail];
}

int tokenRingBufferRewind(TokenRingBuffer *ring_buffer) {
    //if (tokenRingBufferEmpty(ring_buffer)) { //|| ring_buffer->tail == ring_buffer->head) {
    //    return 0;
    //}

    Lexeme *token = ring_buffer->entries[ring_buffer->tail];
    u64 capacity = ring_buffer->capacity;
    s64 offset = 1;
    do {
        ring_buffer->tail = ((ring_buffer->tail - offset) + capacity) & (capacity-1);
        token = ring_buffer->entries[ring_buffer->tail];
        offset++;
    } while (token && token->tk_type == TK_COMMENT);
    ring_buffer->size++;
    return 1;
}

static Lexeme *cctrlMaybeExpandToken(Cctrl *cc, Lexeme *token);

void cctrLoadNextTokens(Cctrl *cc, s64 token_count) {
    for (s64 i = 0; i < token_count; ++i) {
        Lexeme *token = lexToken(cc->macro_defs,cc->lexer_);
        if (!token) break;
        /* Expand here, not when the parser takes the token: the lexer
         * runs ahead of the parser and has already applied any later
         * #undef or #define by then. */
        tokenRingBufferPush(cc->token_buffer, cctrlMaybeExpandToken(cc, token));
    }
}

void cctrlInitParse(Cctrl *cc, Lexer *lexer_) {
    cc->lexer_ = lexer_;
    /* Wire the back-pointer so lex-time errors can route through
     * this Cctrl's diagnostic accumulator and recovery longjmp. */
    if (lexer_) lexer_->cc = cc;
    if (cc->token_buffer == NULL) {
        cc->token_buffer = tokenRingBufferStaticNew();
    }
    cc->token_buffer->got_eof = 0;
    /* New tokens: forget the last parse's macro uses */
    memset(cc->macro_uses, 0, sizeof(cc->macro_uses));
    cc->macro_use_next = 0;
    cctrLoadNextTokens(cc, cc->token_buffer->capacity);
}

void cctrlInitMacroProcessor(Cctrl *cc) {
    cc->lexer_ = NULL;
    /* The caller will tack on the entries and size */
    TokenRingBuffer *ring_buffer = (TokenRingBuffer *)malloc(sizeof(TokenRingBuffer));
    ring_buffer->tail = 0;
    ring_buffer->head = 0;
    ring_buffer->size = 0;
    ring_buffer->got_eof = 0;
    cc->token_buffer = ring_buffer;
    memset(cc->macro_uses, 0, sizeof(cc->macro_uses));
    cc->macro_use_next = 0;
    cc->ast_list = NULL;
    cc->tmp_locals = NULL;
    cc->tmp_func = NULL;
}

/* A macro use becomes the macro's value as it is defined at this point
 * of the source; any other token is handed back as is. */
static Lexeme *cctrlMaybeExpandToken(Cctrl *cc, Lexeme *token) {
    if (token->tk_type != TK_IDENT) {
        return token;
    }

    Lexeme *maybe_define = mapGetLen(cc->macro_defs, token->start, token->len);
    if (maybe_define) {
        /* The macro's value, at the line and column the macro is used:
         * the stored lexeme carries the #define's position, which put
         * an error on the use on the #define line. */
        CctrlMacroUse *mu = &cc->macro_uses[cc->macro_use_next];
        cc->macro_use_next = (cc->macro_use_next + 1) % CCTRL_MACRO_USE_CACHE_SIZE;
        mu->use = token;
        mu->macro = maybe_define;
        mu->expansion = lexemeCopy(maybe_define);
        mu->expansion->line = token->line;
        mu->expansion->col = token->col;
        mu->expansion->file = token->file;
        mu->expansion->module_id = token->module_id;
        mu->expansion->mod_pass = token->mod_pass;
        mu->expansion->in_std = token->in_std;
        mu->expansion->is_prefix = 0;
        return mu->expansion;
    }
    return token; 
}

/* The macro use `tok` was expanded from, or NULL if it is not an
 * expansion. */
static CctrlMacroUse *cctrlMacroUseOf(Cctrl *cc, Lexeme *tok) {
    if (!tok) return NULL;
    for (int i = 0; i < CCTRL_MACRO_USE_CACHE_SIZE; ++i) {
        if (cc->macro_uses[i].expansion == tok) {
            return &cc->macro_uses[i];
        }
    }
    return NULL;
}

/* Width of `tok` in the source: the macro name for an expansion, whose
 * own `len` is the length of the value. A string or character constant's
 * `len` is what is between its quotes; it starts at the opening one. */
static s64 cctrlTokenSourceLen(Cctrl *cc, Lexeme *tok) {
    CctrlMacroUse *mu = cctrlMacroUseOf(cc, tok);
    if (mu) return mu->use->len;
    if (tok->tk_type == TK_STR || tok->tk_type == TK_CHAR_CONST) {
        return tok->len + 2;
    }
    /* `http.nope` is the token `net.http.nope` (a package's is longer
     * still): the source has the prefix as written, a dot and the name */
    if (tok->qual_prefix && tok->len > 0) {
        const char *dot = NULL;
        for (s64 i = tok->len - 1; i >= 0 && !dot; --i) {
            if (tok->start[i] == '.') dot = tok->start + i;
        }
        if (dot) return tok->qual_prefix_len + 1 + (tok->start + tok->len - dot - 1);
    }
    return tok->len;
}

Lexeme *cctrlTokenPeekBy(Cctrl *cc, int cnt) {
    assert(cnt > 0);
    /* The -1 is bizzare, however as an argument peeking by `1` you'd
     * expect to see the next token, which is infact `0` */
    return tokenRingBufferPeekBy(cc->token_buffer, cnt-1);
}

Lexeme *cctrlTokenPeek(Cctrl *cc) {
    Lexeme *token = tokenRingBufferPeek(cc->token_buffer);
    s64 offset = 0;
    while (token) {
        if (token->tk_type == TK_COMMENT) {
            token = tokenRingBufferPeekBy(cc->token_buffer, offset);
            offset++;
            continue;
        }
        cc->lineno = token->line;
        return token;
    }
    return NULL;
}

void cctrlTokenRewind(Cctrl *cc) {
    TokenRingBuffer *ring_buffer = cc->token_buffer;
    /* Undoing a get that hit the end of input: nothing was consumed.
     * Stepping back here would hand the previous token out twice, and
     * the usual `get; if (no match) rewind;` loops then never end. */
    if (ring_buffer->got_eof) {
        ring_buffer->got_eof = 0;
        return;
    }
    if (!tokenRingBufferRewind(ring_buffer)) {
        return;
    }
    ring_buffer->pos--;
}

/* Register `filename` in cc->file_map, returning its 1-based id -
 * or the existing id if this file was seen before (linear scan;
 * registration is once per file push, and files are few). */
u32 cctrlRegisterFile(Cctrl *cc, AoStr *filename) {
    if (cc == NULL || filename == NULL) return 0;
    MapIter mi;
    mapIterInit(cc->file_map, &mi);
    while (mapIterNext(&mi)) {
        if (aoStrEq((AoStr *)mi.node->value, filename))
            return (u32)(u64)mi.node->key;
    }
    u32 id = (u32)cc->file_map->size + 1;
    mapAddIntOrErr(cc->file_map, id, aoStrDup(filename));
    return id;
}

/* Record `start`..`len` as a name the builtin header declares, if the
 * token is in the header's source */
void cctrlNoteHeaderName(Cctrl *cc, char *start, int len) {
    if (!cc || !cc->header_src || !start || len <= 0) return;
    if (start < cc->header_src || start >= cc->header_src + cc->header_len) {
        return;
    }
    if (setHasLen(cc->header_names, start, len)) return;
    setAdd(cc->header_names, mprintf("%.*s", len, start));
}

/* Library symbols std.ktmh doesn't declare that are used from outside
 * libktm all the same */
static const char *cctrl_libktm_extra_exports[] = {
    "argc", "argv",   /* provided by the C startup glue */
    NULL
};

/* Whether a function or global named `name` gets a global symbol. Every
 * one does in a program or a user library. libktm is one translation
 * unit, so in it only what std.ktmh declares is exported (and argc/argv);
 * everything else is local and a program can't replace it. */
/* The declaration a direct call to the symbol `name` calls, for the
 * backends' calling convention (variadic or not): a function by its name,
 * or a module's extern "c" by its C name */
Ast *cctrlCalleeDecl(Cctrl *cc, char *name, int len) {
    Ast *decl = mapGetLen(cc->global_env, name, len);
    if (!decl && cc->c_externs) decl = mapGetLen(cc->c_externs, name, len);
    return decl;
}

int cctrlIsExported(Cctrl *cc, AoStr *name) {
    if (!cc->is_libktm || !name) return 1;
    /* libktm built from the stdlib's modules, without a header: module
     * symbols are mangled apart and the asm labels are what `_extern`
     * bindings call, so all but `static` (local already) are exported */
    if (!cc->header_src) return 1;
    if (setHasLen(cc->header_names, name->data, name->len)) return 1;
    for (int i = 0; cctrl_libktm_extra_exports[i]; ++i) {
        if (!strcmp(name->data, cctrl_libktm_extra_exports[i])) return 1;
    }
    return 0;
}

AoStr *cctrlLookUpFile(Cctrl *cc, u32 file_id) {
    if (cc == NULL || file_id == 0) return NULL;
    return (AoStr *)mapGetInt(cc->file_map, file_id);
}

Lexeme *cctrlTokenGet(Cctrl *cc) {
    /* the member of `local.member` the parser split from a qualified
     * token (a local hides an import prefix, parseShadowedPrefix) */
    if (cc->pending_token) {
        Lexeme *member = cc->pending_token;
        cc->pending_token = NULL;
        return member;
    }
    Lexeme *token = tokenRingBufferPeek(cc->token_buffer);
    cc->token_buffer->got_eof = 0;
    while (token) {
        tokenRingBufferPop(cc->token_buffer);
        if (token->tk_type == TK_COMMENT) {
            /* XXX: Not really sure what to do with the comments or where they
             * should go ??*/
            token = tokenRingBufferPeek(cc->token_buffer);
            continue;
        }
        if (cc->token_buffer->size < 3 && cc->lexer_ && !cc->replaying &&
            !cc->generic_replay) {
            cctrLoadNextTokens(cc,5);
        }
        cc->token_buffer->pos++;
        cc->lineno = token->line;
        ast_line_hint = token->line;
        ast_col_hint = token->col;
        /* Register the token's file lazily; the id rides on every
         * astNew until a token from another file is taken. */
        if (cc->lexer_ && (token->file || cc->lexer_->cur_file)) {
            LexFile *f = token->file ? token->file : cc->lexer_->cur_file;
            if (f->file_id == 0)
                f->file_id = cctrlRegisterFile(cc, f->filename);
            ast_file_hint = f->file_id;
            cc->tok_file = f;
        }
        return token;
    }
    cc->token_buffer->got_eof = 1;
    return NULL;
}

/* cctrlTokenGet where the input cannot end: the end of input is
 * reported instead of handed back as NULL. */
Lexeme *cctrlTokenGetRequired(Cctrl *cc) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (!tok) {
        cctrlRaiseException(cc, "Unexpected end of input");
    }
    return tok;
}

/* Should this be at the lexer level? */   
Lexeme *cctrlAsmTokenGet(Cctrl *cc) {
    /* Tokens flow through to prsasm.c verbatim - the libtasm assembler
     * consumes register spellings directly, so the old
     * AT&T `RAX` -> `%rax` rewrite is gone. */
    return cctrlTokenGet(cc);
}

AoStr *cctrlSeverityMessage(int severity) {
    AoStr *buf = aoStrNew();
    switch (severity) {
        case CCTRL_ERROR:
            aoStrCatColoured(buf, ESC_BOLD_RED, "error: ");
            break;
        case CCTRL_WARN:
            aoStrCatColoured(buf, ESC_BOLD_YELLOW, "warning: ");
            break;
        case CCTRL_INFO:
            aoStrCatColoured(buf, ESC_BOLD_CYAN, "info: ");
            break;
        case CCTRL_ICE:
            aoStrCatColoured(buf, ESC_BOLD_RED, "INTERNAL COMPILER ERROR");
            aoStrCatPrintf(buf, " - ktm %s: ", cctrlGetVersion());
            break;
    }
    return buf;
}

void cctrlFileAndLine(Cctrl *cc, AoStr *buf, s64 lineno, s64 char_pos, char *msg, int severity) {
    AoStr *severity_msg = cctrlSeverityMessage(severity);

    aoStrCatFmt(buf,"%S%s", severity_msg, msg);
    if (buf->data[buf->len - 1] != '\n') {
        aoStrPutChar(buf,'\n');
    }

    if (cc->lexer_) {
        LexFile *file = cctrlCurFile(cc);
        char *file_name = file->filename->data;
        aoStrPutChar(buf, ' ');
        aoStrCatColoured(buf, ESC_CYAN, "-->");
        aoStrCatPrintf(buf, " %s:%ld", file_name, (long)lineno);
        if (char_pos > -1) {
            aoStrCatFmt(buf,":%I",char_pos+1);
        }
        aoStrPutChar(buf,'\n');
    } else {
        aoStrCatFmt(buf, "Parsing macro:%I ", lineno);
    }
    aoStrRelease(severity_msg);
}

/* Colour `text`, the token's source text as written: a number keeps its
 * spelling (`0x1F`, `2.5`, `1e3`), a string or char const its quotes and
 * escapes. Building it from the token's value instead printed `31`,
 * `2.500000` and `a`, and moved the text out from under the `^`. */
char *lexemeToColor(Cctrl *cc, Lexeme *tok, char *text, int len, int is_err) {
    /* Error token wins: render in bold red, no syntax colouring.
     * Otherwise pick a per-kind colour, with TK_IDENT looking up
     * the keyword table to catch user-defined-but-actually-builtin
     * names (e.g. type aliases). */
    const char *rst = clr(ESC_RESET);
    const char *color = NULL;
    if (is_err) {
        color = clr(ESC_BOLD_RED);
    } else {
        switch (tok->tk_type) {
            case TK_KEYWORD:
                color = clr(ESC_BLUE);
                break;
            case TK_STR:
                color = clr(ESC_GREEN);
                break;
            case TK_I64:
            case TK_F64:
                color = clr(ESC_PURPLE);
                break;
            case TK_IDENT:
                if (cctrlTokIsKeyword(cc, tok)) {
                    color = clr(ESC_BLUE);
                }
                break;
            default:
                break;
        }
    }
    if (!color) {
        return mprintf("%.*s", len, text);
    }
    return mprintf("%s%.*s%s", color, len, text, rst);
}

s64 cctrlGetErrorIdx(Cctrl *cc, s64 line, char ch,
                         const char *line_buffer)
{
    (void)cc;
    (void)line;
    char *ptr = (char *)line_buffer;
    while (*ptr) {
        if (*ptr == ch) {
            break;
        }
        ptr++;
    }
    return ptr-line_buffer;
}

void cctrlCreateColoredLine(Cctrl *cc,
                            AoStr *buf,
                            s64 lineno,
                            int should_color_err,
                            char *suggestion,
                            s64 char_pos,
                            s64 *_offset,
                            s64 *_tok_len,
                            const char *line_buffer)
{
    (void)suggestion;
    aoStrCatColoured(buf, ESC_CYAN, "     |\n");
    Lexeme *cur_tok = cctrlTokenPeek(cc);

    AoStr *colored_buffer = aoStrNew();
    s64 offset = -1;
    s64 tok_len = -1;
    Lexeme tok;
    Lexer l;
    /* XXX: I don't think this is the best of ideas re-lexing the line, even
     * if it does mean we have pretty colours. Preserving the current lexer's
     * flags at least means we parse the line in the same way. However this
     * will panic presently as `l.cc` has not been set to `cc`.
     *
     * Accept whitespace AND comments so we preserve every source character.
     * Without this, comments get silently swallowed during re-lexing and the
     * `^` underline (positioned by source column) lands past the wrong text.
     * Also flip on CCF_PERMISSIVE so malformed escapes (e.g. `"\q"` in the
     * source) don't make the renderer call lexRaise. We're only trying to
     * syntax-colour the line, not to validate it. */

    int safer_lexer_flags = CCF_ACCEPT_WHITESPACE|CCF_ACCEPT_COMMENTS|CCF_PERMISSIVE;
    int current_lexer_flags = cc->lexer_ ? cc->lexer_->flags : 0;
    lexInit(&l, (char *)line_buffer, safer_lexer_flags|current_lexer_flags);

    s64 current_offset = 0;
    /* Each token is echoed as the source text it was lexed from, which
     * also keeps any character the lexer skips (`?`) */
    char *text = l.ptr;
    char *line_end = (char *)line_buffer + strlen(line_buffer);

    /* This assumes we want the last match of an error as opposed to the first */
    while (lex(&l,&tok)) {
        char *colored_lexeme = NULL;
        int is_err = 0;
        if (should_color_err) {
            if (cur_tok->tk_type != TK_STR && tok.tk_type == TK_STR) current_offset++;
            if (lexemeEq(cur_tok, &tok)) {
                if (current_offset == char_pos) {
                    tok_len = tok.len;
                    offset = current_offset;
                    is_err = 1;
                    if (tok.tk_type == TK_STR) tok_len++;
                }
            }
        }

        char *text_end = l.ptr < line_end ? l.ptr : line_end;
        colored_lexeme = lexemeToColor(cc, &tok, text, (int)(text_end - text),
                                       is_err && should_color_err);
        aoStrCat(colored_buffer, colored_lexeme);
        current_offset += tok.len;
        text = text_end;
    }
    /* Characters skipped at the end of the line */
    if (text < line_end) {
        aoStrCatLen(colored_buffer, text, line_end - text);
    }

    aoStrCatColouredFmt(buf, ESC_CYAN, "%4ld |", (long)lineno);
    aoStrCatPrintf(buf, "    %s\n", colored_buffer->data);
    if (_offset) *_offset = offset;
    if (_tok_len) *_tok_len = tok_len;
    aoStrRelease(colored_buffer);
}

/* Render a diagnostic positioned at the current peek token. */
AoStr *cctrlCreateErrorLine(Cctrl *cc, s64 lineno, char *msg,
                            int severity, char *suggestion)
{
    Lexeme *peek = cctrlTokenPeek(cc);
    s64 line = peek ? peek->line : lineno;
    s64 col  = peek ? peek->col  : 0;
    s64 len  = peek ? cctrlTokenSourceLen(cc, peek) : 0;
    /* At a macro use the token shown is the macro's value: say so */
    CctrlMacroUse *mu = cctrlMacroUseOf(cc, peek);
    if (mu && !suggestion) {
        char *note = mprintf("expanded from macro `%.*s`",
                             mu->use->len, mu->use->start);
        return cctrlCreateErrorLineAt(cc, line, col, len, msg, severity, note);
    }
    return cctrlCreateErrorLineAt(cc, line, col, len, msg, severity, suggestion);
}

/* A tab in an excerpt is expanded to spaces, up to the next multiple of
 * CCTRL_TAB_WIDTH, in the echoed line and in the `^` line alike. A tab is
 * one character of the column but several on screen, so counting it as
 * one space left the `^` short of the text after it. */
#define CCTRL_TAB_WIDTH 8

/* How many columns a terminal gives the UTF-8 character starting at
 * `s` (at most `n` bytes): 2 for the wide East Asian and emoji ranges,
 * 1 otherwise */
static s64 cctrlUtf8Width(const unsigned char *s, s64 n) {
    u32 cp;
    if (s[0] >= 0xF0 && n >= 4) {
        cp = ((u32)(s[0] & 0x07) << 18) | ((u32)(s[1] & 0x3F) << 12) |
             ((u32)(s[2] & 0x3F) << 6) | (u32)(s[3] & 0x3F);
    } else if (s[0] >= 0xE0 && n >= 3) {
        cp = ((u32)(s[0] & 0x0F) << 12) | ((u32)(s[1] & 0x3F) << 6) |
             (u32)(s[2] & 0x3F);
    } else {
        return 1;
    }
    if ((cp >= 0x1100 && cp <= 0x115F) || (cp >= 0x2E80 && cp <= 0xA4CF) ||
        (cp >= 0xAC00 && cp <= 0xD7A3) || (cp >= 0xF900 && cp <= 0xFAFF) ||
        (cp >= 0xFE30 && cp <= 0xFE4F) || (cp >= 0xFF00 && cp <= 0xFF60) ||
        (cp >= 0xFFE0 && cp <= 0xFFE6) || (cp >= 0x1F300 && cp <= 0x1F64F) ||
        (cp >= 0x1F900 && cp <= 0x1F9FF) || (cp >= 0x20000 && cp <= 0x3FFFD)) {
        return 2;
    }
    return 1;
}

/* The on-screen column of character `idx` of `line` once its tabs are
 * expanded; a position past the end of the line counts 1 per character.
 * Columns count bytes, so a UTF-8 character takes its width at its first
 * byte and none at the rest (`é` is 2 bytes, 1 column). */
static s64 cctrlScreenCol(const char *line, s64 line_len, s64 idx) {
    s64 screen_col = 0;
    for (s64 i = 0; i < idx; ++i) {
        unsigned char ch = i < line_len ? (unsigned char)line[i] : ' ';
        if (ch == '\t') {
            screen_col += CCTRL_TAB_WIDTH - screen_col % CCTRL_TAB_WIDTH;
        } else if ((ch & 0xC0) == 0x80) {
            /* a UTF-8 continuation byte */
        } else if (ch >= 0xC0) {
            screen_col += cctrlUtf8Width((const unsigned char *)line + i,
                                         line_len - i);
        } else {
            screen_col++;
        }
    }
    return screen_col;
}

/* Render a diagnostic at an *explicit* source position. Unlike
 * cctrlCreateErrorLine this never calls cctrlTokenPeek, so it works correctly
 * when the caller knows where the error is but the token buffer doesn't.
 * 0 for a column means "we don't have a column", the line is rendered without
 * an underline. `len` is the width of the `^` underline
 * (clamped to 1 if 0 is given). */
AoStr *cctrlCreateErrorLineAt(Cctrl *cc, s64 lineno, s64 col, s64 len,
                              char *msg, int severity, char *suggestion)
{
    char *color = severity == CCTRL_ERROR || CCTRL_ICE
            ? ESC_BOLD_RED
            : CCTRL_WARN ? ESC_BOLD_YELLOW : ESC_CYAN;
    AoStr *buf = aoStrNew();
    if (!cc->lexer_) {
        cctrlFileAndLine(cc, buf, lineno, -1, msg, severity);
        aoStrCatColoured(buf, ESC_CYAN, "     |\n");
        return buf;
    }

    s64 char_pos = col > 0 ? col - 1 : -1;
    s64 tok_len  = len > 0 ? len : 1;
    cctrlFileAndLine(cc, buf, lineno, char_pos, msg, severity);

    const char *line_buffer = lexerReportLineIn(cctrlCurFile(cc), lineno);
    s64 line_len = strlen(line_buffer);
    AoStr *expanded = aoStrNew();
    for (s64 i = 0; i < line_len; ++i) {
        if (line_buffer[i] == '\t') {
            s64 pad = cctrlScreenCol(line_buffer, line_len, i + 1) -
                      cctrlScreenCol(line_buffer, line_len, i);
            while (pad-- > 0) aoStrPutChar(expanded, ' ');
        } else {
            aoStrPutChar(expanded, line_buffer[i]);
        }
    }
    s64 unused_off = -1, unused_len = -1;
    cctrlCreateColoredLine(cc, buf, lineno, 0, NULL, -1,
                           &unused_off, &unused_len, expanded->data);
    aoStrRelease(expanded);

    if (char_pos != -1) {
        /* The underline's start and width on screen */
        s64 screen_pos = cctrlScreenCol(line_buffer, line_len, char_pos);
        s64 screen_len = cctrlScreenCol(line_buffer, line_len,
                                        char_pos + tok_len) - screen_pos;
        aoStrCatColoured(buf, ESC_CYAN, "     |    ");
        for (s64 i = 0; i < screen_pos; ++i) aoStrPutChar(buf, ' ');
        for (s64 i = 0; i < screen_len; ++i) {
            aoStrCatColoured(buf, color, "^");
        }
        if (suggestion) {
            aoStrCatColouredFmt(buf, color, " %s", suggestion);
        }
    } else {
        aoStrCatColoured(buf, ESC_CYAN, "     |");
    }
    return buf;
}

AoStr *cctrlMessagVnsPrintF(Cctrl *cc, char *fmt, va_list ap, int severity) {
    char *msg = mprintVa(fmt, ap, NULL);
    AoStr *bold_msg = aoStrNew();
    aoStrCatColoured(bold_msg, ESC_BOLD, msg);
    Lexeme *cur_tok = cctrlTokenPeek(cc);
    /* At EOF the peek returns NULL; use the last known line so the
     * error message still has useful location info instead of
     * crashing on the field-dereference below. */
    s64 line = cur_tok ? cur_tok->line : cc->lineno;
    AoStr *buf = cctrlCreateErrorLine(cc,line,bold_msg->data,severity,NULL);
    aoStrRelease(bold_msg);
    return buf;
}

AoStr *cctrlMessagVnsPrintFWithSuggestion(Cctrl *cc, char *fmt, va_list ap,
                                          int severity, char *suggestion)
{
    char *msg = mprintVa(fmt, ap, NULL);
    AoStr *bold_msg = aoStrNew();
    aoStrCatColoured(bold_msg, ESC_BOLD, msg);
    Lexeme *cur_tok = cctrlTokenPeek(cc);
    s64 line = cur_tok ? cur_tok->line : cc->lineno;
    AoStr *buf = cctrlCreateErrorLine(cc,line,bold_msg->data,severity,suggestion);
    aoStrRelease(bold_msg);
    return buf;
}

AoStr *cctrlMessagePrintF(Cctrl *cc, int severity, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlMessagVnsPrintF(cc,fmt,ap,severity);
    va_end(ap);
    return buf;
}

void cctrlInfo(Cctrl *cc, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlMessagVnsPrintF(cc,fmt,ap,CCTRL_INFO);
    va_end(ap);
    /* Route through the diagnostic accumulator so info/warning
     * lines interleave with errors in source order at flush time
     * instead of bleeding out mid-parse. */
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_INFO, buf, NULL);
    cctrlDiagPush(cc, d);
}

void cctrlWarning(Cctrl *cc, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    /* If we are in a repl we are super conservative and treat warnings
     * as errors. This is so the reepl is _less_ likey to crash.
     * A bit like -Werror in C */
    int is_werror = cc->flags & (CCTRL_WERROR);
    int severity = is_werror ? CCTRL_ERROR : CCTRL_WARN;
    AoStr *buf = cctrlMessagVnsPrintF(cc,fmt,ap,severity);
    va_end(ap);
    CctrlDiagnostic *d = cctrlMakeDiag(cc, severity, buf, NULL);
    cctrlDiagPush(cc, d);
    if (severity == CCTRL_ERROR) {
        cctrlTerminate(cc);
    }
}

static void cctrlDiagAtVaRaise(Cctrl *cc, int severity, s64 lineno, s64 col,
                               s64 len, int raise, char *fmt, va_list ap)
{
    char *msg = mprintVa(fmt, ap, NULL);
    AoStr *bold_msg = aoStrNew();
    aoStrCatColoured(bold_msg, ESC_BOLD, msg);
    AoStr *rendered = cctrlCreateErrorLineAt(cc, lineno, col, len,
                                             bold_msg->data, severity, NULL);
    aoStrRelease(bold_msg);

    CctrlDiagnostic *d = (CctrlDiagnostic *)calloc(1, sizeof(CctrlDiagnostic));
    d->severity = severity;
    d->message = rendered;
    d->suggestion = NULL;
    d->file = cc->lexer_ ? cctrlCurFile(cc) : NULL;
    d->line = (int)lineno;
    d->col = (int)col;
    d->end_line = (int)lineno;
    d->end_col = (int)(col + (len > 0 ? len : 1));
    cctrlDiagPush(cc, d);
    if (severity == CCTRL_ERROR && raise) {
        cctrlTerminate(cc);
    }
}

static void cctrlDiagAtVa(Cctrl *cc, int severity, s64 lineno, s64 col,
                          s64 len, char *fmt, va_list ap)
{
    cctrlDiagAtVaRaise(cc, severity, lineno, col, len, 1, fmt, ap);
}

/* An error at (line, col) the parser can step over: reported, and
 * parsing goes on where it is (no unwinding to the recovery point) */
void cctrlErrorAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    cctrlDiagAtVaRaise(cc, CCTRL_ERROR, lineno, col, len, 0, fmt, ap);
    va_end(ap);
}

/* Like cctrlWarning, but anchored at an explicit (line, col) instead of
 * the current token. Needed when the thing being warned about was parsed
 * earlier and the cursor has since moved on - e.g. a missing return is
 * only known after the whole function body is consumed, by which point
 * the next declaration is the "current" token. */
void cctrlWarningAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    int is_werror = cc->flags & (CCTRL_WERROR);
    int severity = is_werror ? CCTRL_ERROR : CCTRL_WARN;
    cctrlDiagAtVa(cc, severity, lineno, col, len, fmt, ap);
    va_end(ap);
}

/* A note anchored at an explicit (line, col), see cctrlWarningAt */
void cctrlInfoAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    cctrlDiagAtVa(cc, CCTRL_INFO, lineno, col, len, fmt, ap);
    va_end(ap);
}

/* An error anchored at an explicit (line, col), see cctrlWarningAt. */
void cctrlRaiseExceptionAt(Cctrl *cc, s64 lineno, s64 col, s64 len, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    cctrlDiagAtVa(cc, CCTRL_ERROR, lineno, col, len, fmt, ap);
    va_end(ap);
}

CctrlDiagnostic *cctrlMakeDiag(Cctrl *cc,
                               int severity,
                               AoStr *rendered,
                               AoStr *suggestion)
{
    CctrlDiagnostic *d = (CctrlDiagnostic *)calloc(1, sizeof(CctrlDiagnostic));
    d->severity = severity;
    d->message = rendered;
    d->suggestion = suggestion;
    d->file = cc->lexer_ ? cctrlCurFile(cc) : NULL;

    Lexeme *cur = cctrlTokenPeek(cc);
    if (cur) {
        d->line = cur->line;
        d->col = cur->col;
        d->end_line = cur->line;
        s64 len = cctrlTokenSourceLen(cc, cur);
        d->end_col = cur->col + (len > 0 ? len : 1);
    } else {
        d->line = (int)cc->lineno;
        d->col = 0;
        d->end_line = d->line;
        d->end_col = 0;
    }
    return d;
}

/* A package's module path inside the compiler (github_com.x.ktm_pg.pg)
 * in a message reads as its name (pg), as the program writes it */
static void cctrlDiagPackageNames(Cctrl *cc, CctrlDiagnostic *d) {
    Lexer *l = cc ? cc->lexer_ : NULL;
    if (!l || !d || !d->message) return;
    for (int i = 0; i < l->modules_len; ++i) {
        LexModule *m = l->modules[i];
        if (!m->package) continue;
        char *path = m->path->data;
        char *name = strrchr(path, '.');
        name = name ? name + 1 : path;
        size_t plen = strlen(path);
        char *at = strstr(d->message->data, path);
        if (!at) continue;
        AoStr *out = aoStrNew();
        char *p = d->message->data;
        while (at) {
            aoStrCatLen(out, p, at - p);
            aoStrCat(out, name);
            p = at + plen;
            at = strstr(p, path);
        }
        aoStrCat(out, p);
        aoStrRelease(d->message);
        d->message = out;
    }
}

/* A warning or a note in another project's package (a dependency): the
 * user can't change that code, so it isn't shown; its errors are */
static int cctrlDiagInDependency(Cctrl *cc, CctrlDiagnostic *d) {
    Lexer *l = cc ? cc->lexer_ : NULL;
    if (!l || !d || d->severity == CCTRL_ERROR || !d->file || !d->file->module_id) return 0;
    if ((int)d->file->module_id > l->modules_len) return 0;
    return l->modules[d->file->module_id - 1]->foreign;
}

void cctrlDiagPush(Cctrl *cc, CctrlDiagnostic *d) {
    if (cctrlDiagInDependency(cc, d)) {
        if (d->message) aoStrRelease(d->message);
        if (d->suggestion) aoStrRelease(d->suggestion);
        free(d);
        return;
    }
    cctrlDiagPackageNames(cc, d);
    if (!cc || !cc->diagnostics) {
        /* No accumulation storage (e.g. macro-processor stub).
         * Print immediately so the message isn't lost. */
        if (d) {
            if (d->message) fprintf(stderr, "%s\n", d->message->data);
            if (d->message) aoStrRelease(d->message);
            if (d->suggestion) aoStrRelease(d->suggestion);
            free(d);
        }
        return;
    }
    /* A module type declaration parsed from its recorded tokens: the
     * declarations pass reports what still fails (see Cctrl.replaying) */
    if (cc->replaying) {
        if (d->severity == CCTRL_ERROR) cc->quiet_errors++;
        if (d->message) aoStrRelease(d->message);
        if (d->suggestion) aoStrRelease(d->suggestion);
        free(d);
        return;
    }
    /* The same diagnostic again (a module file is read in more than one
     * pass, and a declaration can be checked in both): reported once */
    for (u64 i = 0; d->message && i < cc->diagnostics->size; ++i) {
        CctrlDiagnostic *e = cc->diagnostics->entries[i];
        if (e && e->message && e->severity == d->severity &&
            e->message->len == d->message->len &&
            !memcmp(e->message->data, d->message->data, d->message->len)) {
            aoStrRelease(d->message);
            if (d->suggestion) aoStrRelease(d->suggestion);
            free(d);
            return;
        }
    }
    vecPush(cc->diagnostics, d);
    if (d->severity == CCTRL_ERROR) cc->n_errors++;
}

int cctrlDiagFlush(Cctrl *cc) {
    if (!cc || !cc->diagnostics) return 0;
    for (u64 i = 0; i < cc->diagnostics->size; i++) {
        CctrlDiagnostic *d = (CctrlDiagnostic *)cc->diagnostics->entries[i];
        if (d && d->message) {
            fprintf(stderr, "%s\n", d->message->data);
        }
    }
    return cc->n_errors;
}

/* Drop all queued diagnostics and reset the error count. The REPL
 * calls this between inputs so one bad line doesn't poison (or
 * re-print with) the next. */
void cctrlDiagClear(Cctrl *cc) {
    if (!cc) return;
    if (cc->diagnostics) vecClear(cc->diagnostics);
    cc->n_errors = 0;
}

/* Empty the token ring buffer. After an errored parse the buffer can
 * still hold tokens from the abandoned input; a fresh REPL parse must
 * not see them. The freed slots are filled with a sentinel rather
 * than NULL: parsePrimary rewinds one token to inspect what preceded
 * an expression, and when the expression opens the input that step
 * walks into the ring's history - which must therefore always hold
 * valid lexemes. */
void cctrlResetTokenBuffer(Cctrl *cc) {
    TokenRingBuffer *ring = cc->token_buffer;
    if (!ring) return;
    static Lexeme *filler = NULL;
    if (filler == NULL) filler = lexemeSentinal();
    ring->tail = 0;
    ring->head = 0;
    ring->size = 0;
    for (s64 i = 0; i < ring->capacity; ++i) {
        ring->entries[i] = filler;
    }
}

__noreturn void cctrlTerminate(Cctrl *cc) {
    /* Optionally we can not have a jump buffer */
    if (cc->current_recovery) {
        /* Hand control to the nearest active recovery point. The
         * jumper resyncs tokens and resumes; the message is
         * printed by `cctrlDiagFlush` at the end of compilation. */
        longjmp(*cc->current_recovery, 1);
    }
    /* No recovery active: keep the legacy fail-fast behaviour. */
    cctrlDiagFlush(cc);
    exit(EXIT_FAILURE);
}

/* Walks the ring buffer back to the token just before `tok` and, if
 * `tok` starts its line and that token ends an earlier one, renders an
 * INFO message positioned at it, then re-advances so the buffer head
 * ends up where the caller left it. A `tok` in the middle of a line
 * (`I64 x = 2 3;`) gets no hint: the earlier line is not what's wrong,
 * and neither is one ending in `;` or `{`. The walk is capped at 16
 * rewinds and bails on a same-peek-after-rewind (ring floor). Returns
 * NULL when there is no hint to give. */
CctrlDiagnostic *cctrlInfoAtPreviousLine(Cctrl *cc, Lexeme *tok,
                                         const char *fmt, ...) {
    int rewinds = 0, passed_tok = 0;
    Lexeme *prev = cctrlTokenPeek(cc);
    while (prev && !passed_tok && rewinds < 16) {
        if (prev == tok) passed_tok = 1;
        cctrlTokenRewind(cc);
        Lexeme *new_prev = cctrlTokenPeek(cc);
        if (new_prev == prev) {
            passed_tok = 0;
            break;
        }
        prev = new_prev;
        rewinds++;
    }

    /* Built while rewound so its position is the previous line's last
     * token, not tok's. */
    CctrlDiagnostic *info = NULL;
    if (passed_tok && prev && prev->line < tok->line &&
        !tokenPunctIs(prev, ';') && !tokenPunctIs(prev, '{')) {
        va_list ap;
        va_start(ap, fmt);
        AoStr *info_msg = cctrlMessagVnsPrintF(cc, (char *)fmt, ap, CCTRL_INFO);
        va_end(ap);
        info = cctrlMakeDiag(cc, CCTRL_INFO, info_msg, NULL);
    }

    /* Restore buffer head so the recovery code sees the same
     * state the caller had before this helper ran. */
    while (rewinds-- > 0) {
        if (!cctrlTokenGet(cc)) break;
    }
    return info;
}

void cctrlRaiseException(Cctrl *cc, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlMessagVnsPrintF(cc,fmt,ap,CCTRL_ERROR);
    va_end(ap);

    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

void cctrlRaiseSuggestion(Cctrl *cc, char *suggestion, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlMessagVnsPrintFWithSuggestion(cc,fmt,ap,CCTRL_ERROR,suggestion);
    va_end(ap);

    AoStr *sug = suggestion ? aoStrPrintf("%s", suggestion) : NULL;
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, sug);
    cctrlDiagPush(cc, d);
    cctrlTerminate(cc);
}

void cctrlIce(Cctrl *cc, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlMessagVnsPrintF(cc,fmt,ap,CCTRL_ICE);
    va_end(ap);
    /* An embedder (REPL/LSP) with a recovery point armed wants the ICE
     * as a recoverable diagnostic, not a process exit. */
    if (cc && cc->current_recovery) {
        CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, NULL);
        cctrlDiagPush(cc, d);
        cctrlTerminate(cc);
    }
    fprintf(stderr,"%s\n",buf->data);
    aoStrRelease(buf);
    exit(EXIT_FAILURE);
}

/* Walk forwards through the token stream, balancing `{`/`}`, until
 * we land on a recovery point. Returns once `peek()` is positioned
 * at the first token the caller's loop should resume from. */
static void cctrlSyncCommon(Cctrl *cc, int stop_at_toplevel_kw) {
    int depth = 0;
    while (1) {
        Lexeme *peek = cctrlTokenPeek(cc);
        if (!peek) return;

        if (tokenPunctIs(peek, '{')) {
            depth++;
            cctrlTokenGet(cc);
            continue;
        }
        if (tokenPunctIs(peek, '}')) {
            if (depth == 0 && !stop_at_toplevel_kw) {
                /* Hit the closing brace of our containing block;
                 * leave it in place so the parent parser can consume
                 * it and exit cleanly. */
                return;
            }
            if (depth == 0) {
                /* Nothing encloses the top level: a `}` there is
                 * stray (the rest of a block the error cut short).
                 * Left in place, the next declaration would start
                 * with it. */
                cctrlTokenGet(cc);
                continue;
            }
            depth--;
            cctrlTokenGet(cc);
            continue;
        }
        if (depth == 0 && tokenPunctIs(peek, ';')) {
            /* Consume the terminator so the next iteration starts
             * fresh on the following statement. */
            cctrlTokenGet(cc);
            return;
        }
        if (depth == 0 && stop_at_toplevel_kw && peek->tk_type == TK_KEYWORD) {
            /* Likely the start of a new top-level decl; leave it
             * for parseToplevelDef. */
            return;
        }
        cctrlTokenGet(cc);
    }
}

void cctrlSyncToplevel(Cctrl *cc) {
    cctrlSyncCommon(cc, 1);
}

void cctrlSyncStatement(Cctrl *cc) {
    cctrlSyncCommon(cc, 0);
}

/* Rewind the token buffer until there is a match. NULL `peek`
 * (EOF in the ring buffer) and a NULL-source `ch` are tolerated -
 * callers in the parser sometimes hit this when the lexer has run
 * dry mid-construct. */
void cctrlRewindUntilPunctMatch(Cctrl *cc, s64 ch, int *_count) {
    int count = 0;
    Lexeme *peek = cctrlTokenPeek(cc);
    while (peek && !tokenPunctIs(peek, ch)) {
        cctrlTokenRewind(cc);
        Lexeme *new_peek = cctrlTokenPeek(cc);
        /* Rewind can't go below the start of the ring; if peek
         * doesn't change, we've hit the floor. Bail. */
        if (new_peek == peek) break;
        peek = new_peek;
        count++;
        if (count > 5) break; // has to be some limit
    }
    if (_count) *_count = count;
}

void cctrlRewindUntilStrMatch(Cctrl *cc, char *str, int len, int *_count) {
    int count = 0;
    Lexeme *peek = cctrlTokenPeek(cc);
    /* `peek` is NULL when the ring is fully drained. A single line
     * input will hit this when the error fires at the end of a statement.
     * */
    while (peek == NULL ||
           !(peek->len == len && memcmp(peek->start,str,len) == 0)) {
        cctrlTokenRewind(cc);
        Lexeme *new_peek = cctrlTokenPeek(cc);
        if (new_peek == peek) break;
        peek = new_peek;
        count++;
        if (count > 5) break; // has to be some limit
    }
    if (_count) *_count = count;
}


/* assert the token we are currently pointing at is a TK_PUNCT and the 'i64'
 * matches 'expected'. Then consume this token else throw an error */
void cctrlTokenExpect(Cctrl *cc, s64 expected) {
    Lexeme *tok = cctrlTokenGet(cc);
    if (tokenPunctIs(tok, expected)) return;

    if (!tok) {
        /* End of input while we still wanted `expected`. This is
         * almost always an unbalanced bracket / missing terminator
         * - point at the line we were last consuming so the user
         * sees the actual region the parse fell off at. */
        const char *hint = (expected == '}')
            ? " (unterminated `{ ... }` block?)"
          : (expected == ')')
            ? " (unterminated `( ... )`?)"
          : (expected == ']')
            ? " (unterminated `[ ... ]`?)"
          : (expected == ';')
            ? " (missing terminator?)"
          : "";
        cctrlRaiseException(cc,
            "Unexpected end of input, expected `%c`%s",
            (char)expected, hint);
        return; /* unreachable; cctrlRaiseException is noreturn */
    }

    /* tok was consumed by the get above so peek now points past it. Rewind
     * once so the diagnostic renderer captures tok's actual line/col. */
    cctrlTokenRewind(cc);
    AoStr *err_msg = cctrlMessagePrintF(cc, CCTRL_ERROR,
            "Syntax error, got an unexpected %s `%s`, perhaps you meant `%c`?",
            lexemeTypeToString(tok->tk_type),
            lexemeAsWritten(tok),
            (char)expected);
    /* Positioned on tok too, while still rewound. */
    CctrlDiagnostic *err_d = cctrlMakeDiag(cc, CCTRL_ERROR, err_msg, NULL);
    /* Re-consume so the buffer is back to where the caller left us before we
     * go hunting for an explanatory hint. */
    cctrlTokenGet(cc);

    CctrlDiagnostic *info_d = cctrlInfoAtPreviousLine(cc, tok,
            "previous statement starts here");

    cctrlDiagPush(cc, err_d);
    if (info_d) cctrlDiagPush(cc, info_d);
    cctrlTerminate(cc);
}

/* Render a diagnostic whose underline spans the chars `from`..`to`
 * within the offending source line (e.g. `{` ... `}` for an
 * unbalanced block). Position-derivation is the only thing that
 * differs from the regular path - actual rendering goes through
 * cctrlCreateErrorLineAt. */
AoStr *cctrlRaiseFromTo(Cctrl *cc, int severity, char *suggestion, char from,
                        char to, char *fmt, va_list ap)
{
    char *msg = mprintVa(fmt, ap, NULL);
    AoStr *bold_msg = aoStrNew();
    aoStrCatColoured(bold_msg, ESC_BOLD, msg);

    Lexeme *cur_tok = cctrlTokenPeek(cc);
    s64 line = cur_tok ? cur_tok->line : cc->lineno;

    s64 col = 0, len = 0;
    if (cur_tok && cc->lexer_) {
        const char *line_buffer = lexerReportLineIn(cctrlCurFile(cc), line);
        s64 from_idx = cctrlGetErrorIdx(cc, line, from, line_buffer);
        s64 to_idx   = cctrlGetErrorIdx(cc, line, to,   line_buffer);
        if (from_idx != -1 && to_idx != -1) {
            col = from_idx + 1;
            len = (to_idx + 1) - from_idx;
        }
    }

    AoStr *buf = cctrlCreateErrorLineAt(cc, line, col, len,
                                        bold_msg->data, severity, suggestion);
    aoStrRelease(bold_msg);
    return buf;
}

void cctrlRaiseExceptionFromTo(Cctrl *cc, char *suggestion, char from, char to, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    AoStr *buf = cctrlRaiseFromTo(cc,CCTRL_ERROR,suggestion,from,to,fmt,ap);
    va_end(ap);

    AoStr *sug = suggestion ? aoStrPrintf("%s", suggestion) : NULL;
    CctrlDiagnostic *d = cctrlMakeDiag(cc, CCTRL_ERROR, buf, sug);
    cctrlDiagPush(cc, d);

    cctrlTerminate(cc);
}

void cctrlWarningFromTo(Cctrl *cc, char *suggestion, char from, char to, char *fmt, ...) {
    va_list ap;
    va_start(ap,fmt);
    int is_werror = cc->flags & (CCTRL_WERROR);
    int severity = is_werror ? CCTRL_ERROR : CCTRL_WARN;
    AoStr *buf = cctrlRaiseFromTo(cc,severity,suggestion,from,to,fmt,ap);
    va_end(ap);

    /* Warnings accumulate but never longjmp as the parser keeps going.
     * `cctrlDiagFlush(...)` prints them alongside the errors at the end of
     * compilation. */
    AoStr *sug = suggestion ? aoStrPrintf("%s", suggestion) : NULL;
    CctrlDiagnostic *d = cctrlMakeDiag(cc, severity, buf, sug);
    cctrlDiagPush(cc, d);
    if (severity == CCTRL_ERROR) {
        cctrlTerminate(cc);
    }
}

/* Get variable either from the local or global scope */
/* The path ("a.b") of module `module_id`, NULL for the main program */
AoStr *cctrlModulePath(Cctrl *cc, u32 module_id) {
    if (!module_id || !cc->lexer_ || (int)module_id > cc->lexer_->modules_len) {
        return NULL;
    }
    return cc->lexer_->modules[module_id - 1]->path;
}

/* `name` as declared in the current module ("a.b.name"), NULL in the
 * main program or for a name that is already qualified */
char *cctrlQualifyName(Cctrl *cc, char *name, int len) {
    AoStr *path = cctrlModulePath(cc, cc->cur_module);
    /* a generic instance's type arguments may have dots of their own:
     * `decode<m.In>` isn't qualified yet */
    if (!path || astLastTopDot(name, len) >= 0) return NULL;
    return mprintf("%s.%.*s", path->data, len, name);
}

/* Is the current module the prelude, whose pub names every file sees
 * without a prefix or an import? */
int cctrlInPrelude(Cctrl *cc) {
    AoStr *path = cctrlModulePath(cc, cc->cur_module);
    return path && path->len == 7 && !memcmp(path->data, "prelude", 7);
}

/* Is the current module `libc`, the C library's declarations? */
int cctrlInCModule(Cctrl *cc) {
    AoStr *path = cctrlModulePath(cc, cc->cur_module);
    return path && path->len == 4 && !memcmp(path->data, "libc", 4);
}

/* The name a declaration is made under: as cctrlQualifyName, except
 * that the prelude's pub names are flat (`Error`, not `prelude.Error`)
 * so every file finds them as it finds the program's own */
char *cctrlDeclName(Cctrl *cc, char *name, int len) {
    if (cc->decl_pub && cctrlInPrelude(cc)) return NULL;
    return cctrlQualifyName(cc, name, len);
}

/* The file diagnostics are about: the last taken token's */
LexFile *cctrlCurFile(Cctrl *cc) {
    if (cc->tok_file) return cc->tok_file;
    return cc->lexer_ ? cc->lexer_->cur_file : NULL;
}

/* Inside a module, a flat name the main program declared: the module
 * can't depend on the program that uses it */
int cctrlModuleHides(Cctrl *cc, char *name, int len) {
    if (!cc->cur_module || astLastTopDot(name, len) >= 0) return 0;
    char *flat = mprintf("%.*s", len, name);
    return setHas(cc->main_names, flat);
}

/* `ktm test`: the runner it writes (the main program) calls the tested
 * module's test functions, which aren't pub. Is module `name` (mod_len
 * bytes) that module, seen from the main program? */
int cctrlTestSees(Cctrl *cc, char *name, int mod_len) {
    Lexer *l = cc->lexer_;
    if (!l || !l->test_module_id || cc->cur_module) return 0;
    AoStr *path = cctrlModulePath(cc, l->test_module_id);
    return path && (int)path->len == mod_len && !memcmp(path->data, name, mod_len);
}

/* `a.b.name` found: an error unless it is pub or used inside a.b */
void cctrlCheckModuleAccess(Cctrl *cc, char *name, int len) {
    int at = astLastTopDot(name, len);
    char *dot = at >= 0 ? name + at : NULL;
    if (!dot) return;
    AoStr *here = cctrlModulePath(cc, cc->cur_module);
    int mod_len = dot - name;
    if (here && (int)here->len == mod_len && !memcmp(here->data, name, mod_len)) {
        return;
    }
    char *full = mprintf("%.*s", len, name);
    if (setHas(cc->pub_names, full)) return;
    if (cctrlTestSees(cc, name, mod_len)) return;
    cctrlRaiseException(cc, "`%.*s` is private to module %.*s",
                        len - mod_len - 1, dot + 1, mod_len, name);
}

/* A declaration named like one of its file's import prefixes */
void cctrlCheckPrefixHidden(Cctrl *cc, Lexeme *name) {
    if (name && name->tk_type == TK_IDENT && name->is_prefix) {
        cctrlRaiseException(cc, "`%.*s` hides the module imported as %.*s",
                            name->len, name->start, name->len, name->start);
    }
}

Ast *cctrlGetVar(Cctrl *cc, char *varname, int len) {
    Ast *ast_var;
    Lexeme *tok;

    if (cc->localenv && (ast_var = mapGetLen(cc->localenv, varname, len)) != NULL) {
        return ast_var;
    }

    /* In a module, its own names first */
    char *qual = cctrlQualifyName(cc, varname, len);
    if (qual && (ast_var = (Ast *)mapGet(cc->global_env, qual)) != NULL) {
        return ast_var;
    }

    if (!cctrlModuleHides(cc, varname, len) &&
        (ast_var = (Ast *)mapGetLen(cc->global_env, varname, len)) != NULL) {
        cctrlCheckModuleAccess(cc, varname, len);
        return ast_var;
    }

    if ((ast_var = mapGetLen(cc->asm_funcs, varname, len)) != NULL) {
        cctrlCheckModuleAccess(cc, varname, len);
        return ast_var;
    }
    /* a module's own asm binding (`_extern _x I32 stat(...)`) */
    if (qual && (ast_var = mapGet(cc->asm_funcs, qual)) != NULL) {
        return ast_var;
    }

    /* Expand a macro definition */
    if ((tok = mapGetLen(cc->macro_defs, varname, len)) != NULL) {
        switch (tok->tk_type) {
        case TK_I64:
            ast_var = astI64Type(tok->i64);
            if (tok->isu64) ast_var->type = astTypeCopy(ast_uint_type);
            return ast_var;
        case TK_F64:
            return astF64Type(tok->f64);
        case TK_STR: {
            return cctrlGetOrSetString(cc, tok->start, tok->len, tok->i64);
        }
        default:
            return NULL;
        }
    }
    return NULL;
}

AstType *cctrlGetKeyWord(Cctrl *cc, char *name, int len) {
    AstType *type;
    assert(name != NULL);
    /* a type parameter of the generic instance being parsed */
    if (cc->type_bindings &&
        (type = mapGetLen(cc->type_bindings, name, len)) != NULL) {
        return type;
    }
    if ((type = mapGetLen(cc->symbol_table, name, len)) != NULL) {
        return type;
    }
    /* In a module, its own types first */
    char *qual = cctrlQualifyName(cc, name, len);
    if (qual) {
        if ((type = mapGet(cc->clsdefs, qual)) != NULL) return type;
        if ((type = mapGet(cc->uniondefs, qual)) != NULL) return type;
        TypeAlias *alias = mapGet(cc->type_aliases, qual);
        if (alias) return alias->type;
    }
    if (cctrlModuleHides(cc, name, len)) return NULL;
    if (memchr(name, '.', len) &&
        (mapGetLen(cc->clsdefs, name, len) || mapGetLen(cc->uniondefs, name, len) ||
         mapGetLen(cc->type_aliases, name, len)))
    {
        cctrlCheckModuleAccess(cc, name, len);
    }
    /* Classes are types */
    if ((type = mapGetLen(cc->clsdefs,name,len)) != NULL) {
        return type;
    }
    if ((type = mapGetLen(cc->uniondefs,name,len)) != NULL) {
        return type;
    }
    TypeAlias *alias = mapGetLen(cc->type_aliases, name, len);
    if (alias) return alias->type;
    return NULL;
}

int cctrlIsKeyword(Cctrl *cc, char *name, int len) {
    return cctrlGetKeyWord(cc,name,len) != NULL;
}

/* Is the token a keyword or a type name: a string, character or number
 * literal never is, whatever its text (`F("private")`) */
int cctrlTokIsKeyword(Cctrl *cc, Lexeme *tok) {
    if (tok->tk_type == TK_STR || tok->tk_type == TK_CHAR_CONST ||
        tok->tk_type == TK_I64 || tok->tk_type == TK_F64) {
        return 0;
    }
    return cctrlIsKeyword(cc, tok->start, tok->len);
}

void cctrlSetCommandLineDefines(Cctrl *cc, List *defines_list) {
    listForEach(defines_list) {
        mapAdd(cc->macro_defs,(char*)it->value,lexemeSentinal());
    }
}

/* De-duplicates strings by checking their existance */
Ast *cctrlGetOrSetString(Cctrl *cc, char *str, int len, s64 real_len) {
    Ast *ast_str = NULL;
    if (cc->strs) {
        ast_str = mapGetLen(cc->strs, str, len);
        if (!ast_str) {
            ast_str = astString(str,len,real_len);
            mapAddLen(cc->strs, ast_str->sval->data, len, ast_str);
        }
    } else {
        ast_str = astString(str,len,real_len);
    }
    return ast_str;
}
