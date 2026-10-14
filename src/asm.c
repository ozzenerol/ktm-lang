#include <strings.h>
#include <math.h>
#include <stdlib.h>
#include <string.h>

#include "aarch64.h"
#include "asm.h"
#include "asm/asm.h"
#include "asm/asm_enc.h"
#include "cctrl.h"
#include "cli.h"
#include "config.h"
#include "prsasm.h"
#include "x86_64.h"
#include "types.h"
#include "util.h"
#include "version.h"

/* Encode `64` as a `u64`. Bit-cast through a union (like ieee754_32) so the
 * exact IEEE-754 representation is produced. The previous manual bit-by-bit
 * reconstruction zeroed the mantissa for negative values (the fraction loop
 * compared a negative number against positive thresholds), so e.g. -1.5 was
 * encoded as -1.0. */
uint64_t ieee754_64(double _f64) {
    union F64Conv { double f; uint64_t bits; };
    union F64Conv c;
    c.f = _f64;
    return c.bits;
}

uint32_t ieee754_32(f32 _f32) {
    union F32Conv { f32 f; uint32_t bits; };
    union F32Conv c;
    c.f = _f32;
    return c.bits;
}

/* Mach-O treats any symbol beginning with an uppercase `L` as a local
 * (assembler-temporary) label, so `.globl LIGHTGRAY` is rejected with
 * "non-local symbol required". Data globals are otherwise emitted with their
 * raw name (the JIT keys its arena on that name and never goes through the
 * assembler, so it is unaffected). On macOS, escape the colliding names with
 * a leading `_` - matching the function-name convention; the SAME transform
 * must be applied at the symbol's definition and at every reference so they
 * agree. Returns the original AoStr when no change is needed. */
AoStr *asmNormaliseGlobalLabel(Cctrl *cc, AoStr *name) {
    int is_macos = cc->target == TARGET_AARCH64_APPLE_DARWIN ||
                   cc->target == TARGET_X86_64_APPLE_DARWIN;
    if (is_macos && name && name->len > 0 && name->data[0] == 'L') {
        AoStr *out = aoStrNew();
        aoStrPutChar(out, '_');
        aoStrCatAoStr(out, name);
        return out;
    }
    return name;
}

/* This is a hacky, but seemingly functional way of me being able
 * to run this on Macos and Linux */
/* `.globl` for a function's label. In a library (`-lib`, how libktm is
 * built) it is also a weak definition: a program's own (strong)
 * definition of the same name then wins at link time instead of
 * "multiple definition", matching the JIT, where the program's
 * function shadows the library's. With the static libktm.a every call
 * to it, the library's own included, goes to the program's version. */
void asmEmitFunctionGlobal(Cctrl *cc, AoStr *buf, char *label) {
    aoStrCatPrintf(buf, ".globl %s\n", label);
    if (!cc->is_library) return;
    switch (cc->target) {
        case TARGET_AARCH64_APPLE_DARWIN:
        case TARGET_X86_64_APPLE_DARWIN:
            aoStrCatPrintf(buf, ".weak_definition %s\n", label);
            break;
        case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
        case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            aoStrCatPrintf(buf, ".weak %s\n", label);
            break;
    }
}

/* Does generic definition d name the function whose (mangled) symbol is
 * `sym`: a name in its tokens that, qualified with d's module, mangles
 * to sym? */
static int asmGenericNames(GenericDef *d, char *sym) {
    AoStr *path = d->module_path;
    if (!path || !d->toks) return 0;
    for (u64 j = 0; j < d->toks->size; ++j) {
        Lexeme *t = d->toks->entries[j];
        if (t->tk_type != TK_IDENT) continue;
        char *qual = mprintf("%s.%.*s", path->data, t->len, t->start);
        char *mangled = astModuleMangle(qual, (int)strlen(qual));
        int same = mangled && !strcmp(mangled, sym);
        if (same) return 1;
    }
    return 0;
}

/* A `static` function of a module, in a library build, that one of the
 * module's generics calls: the generic's instances are made in the
 * programs that use it, which then call the function by its symbol, so
 * it can't stay local to the library (the link failed with "undefined
 * reference to net.http.arenaAlloc"). */
static int asmStaticUsedByGeneric(Cctrl *cc, AoStr *fname) {
    if (!cc->is_library || !fname) return 0;
    MapIter it;
    if (cc->generic_funcs) {
        mapIterInit(cc->generic_funcs, &it);
        while (mapIterNext(&it)) {
            if (asmGenericNames(it.node->value, fname->data)) return 1;
        }
    }
    if (cc->generic_structs) {
        mapIterInit(cc->generic_structs, &it);
        while (mapIterNext(&it)) {
            if (asmGenericNames(it.node->value, fname->data)) return 1;
        }
    }
    for (u64 i = 0; cc->generic_methods && i < cc->generic_methods->size; ++i) {
        if (asmGenericNames(cc->generic_methods->entries[i], fname->data)) return 1;
    }
    return 0;
}

/* The symbol of a function about to be emitted: a `static` one stays
 * local to the object (no .globl / .weak), anything else is global. */
void asmEmitFunctionSymbol(Cctrl *cc, AoStr *buf, char *label, Ast *func) {
    if (func && (func->flags & AST_FLAG_STATIC) &&
        !asmStaticUsedByGeneric(cc, func->fname)) {
        return;
    }
    /* A library function the header doesn't declare is internal */
    if (func && !cctrlIsExported(cc, func->fname)) {
        return;
    }
    asmEmitFunctionGlobal(cc, buf, label);
}

/* The bytes of string literal `str` (its NUL included) as assembler
 * data: a string with escapes for bytes outside printable text as a
 * `.byte` list, which every assembler reads the same way. */
/* Does string literal `str` hold a NUL byte before its terminating one? */
int asmStringHasNul(Ast *str) {
    char *bytes = malloc(str->sval->len + 1);
    int n = astStringDecode(str, bytes);
    int has = memchr(bytes, 0, (size_t)n) != NULL;
    free(bytes);
    return has;
}

void asmEmitStringBytes(AoStr *buf, Ast *str) {
    AoStr *sval = str->sval;
    int has_octal = 0;
    for (u64 i = 0; i + 1 < sval->len; ++i) {
        if (sval->data[i] == '\\' && sval->data[i + 1] >= '0' && sval->data[i + 1] <= '7') {
            has_octal = 1;
            break;
        }
        if (sval->data[i] == '\\') ++i;
    }
    if (!has_octal) {
        aoStrCatFmt(buf, ".asciz \"%S\"\n", sval);
        return;
    }
    char *bytes = malloc(sval->len + 1);
    int n = astStringDecode(str, bytes);
    aoStrCatFmt(buf, ".byte ");
    for (int i = 0; i <= n; ++i) {
        aoStrCatFmt(buf, i ? ",%i" : "%i", (int)(unsigned char)bytes[i]);
    }
    aoStrPutChar(buf, '\n');
    free(bytes);
}

char *asmNormaliseFunctionName(Cctrl *cc, AoStr *fname) {
    AoStr *newfn = aoStrNew();
    /* A main-program function's symbol is its own (astProgramSymbol), so
     * it can't replace a C or libktm function of the same name */
    Ast *decl = mapGetLen(cc->global_env, fname->data, fname->len);
    /* a call compiled while the function was the only one of its name:
     * the name still leads to it, renamed since (parseOverloadDecl) */
    if (decl && (decl->kind == AST_FUNC || decl->kind == AST_FUN_PROTO) &&
        (decl->flags & AST_FLAG_OVERLOAD) && decl->fname != fname &&
        !aoStrCmp(decl->fname, fname)) {
        fname = decl->fname;
    }
    AoStr prog_sym;
    if (decl && (decl->kind == AST_FUNC || decl->kind == AST_FUN_PROTO) &&
        (decl->flags & AST_FLAG_PROGRAM)) {
        char *sym = astProgramSymbol(fname->data, fname->len);
        prog_sym = (AoStr){ .data = sym, .len = strlen(sym), .capacity = 0 };
        fname = &prog_sym;
    }
    switch(cc->target) {
        case TARGET_AARCH64_APPLE_DARWIN:
        case TARGET_X86_64_APPLE_DARWIN: {
            if (fname->data[0] != '_') {
                aoStrPutChar(newfn, '_');
            }
            break;
        }
        case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
        case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            break;
    }

    aoStrCatAoStr(newfn, fname);

    /* User-defined `Main` (capital M) is the conventional ktm entry.
     *  - With initialisers: rename to `MainFn`. The synthetic init-driver
     *    function (named `main` lowercase below) is the real `_main`.
     *  - Without initialisers: lowercase to `main` so it links as the
     *    process entry point.
     * Compare case-sensitively so the synthetic `main` (which we feed in
     * lowercase) flows straight through as `_main`. */
    if (fname->len == 4 && !strncmp(fname->data, str_lit("Main"))) {
         if (cc->flags & CCTRL_ASM_HAS_INITIALISERS) {
            aoStrCatPrintf(newfn, "Fn");
        } else {
            aoStrToLowerCase(newfn);
        }
    }
    return aoStrMove(newfn);
}

/* Build the synthetic `main` that runs file-scope initialiser
 * statements (cc->initalisers) as the process entry point, shared by
 * every backend (aarch64 / x86_64 AOT and the JIT). Returns NULL when
 * there are no initialisers - in that case the user's `Main` becomes
 * the entry directly (asmNormaliseFunctionName lowercases it).
 *
 * When initialisers DO exist the user's `Main` is renamed to `MainFn`
 * and this driver becomes `_main`, so the driver must call `Main`
 * itself - otherwise the program runs the initialisers and exits
 * without ever entering Main (the auto-entry convenience would be
 * silently lost the moment a file-scope initialiser appears). */
Ast *asmBuildInitialiserMain(Cctrl *cc) {
    if (listEmpty(cc->initalisers)) return NULL;
    cc->flags |= CCTRL_ASM_HAS_INITIALISERS;

    /* ktm "scripting" files drive execution themselves with a
     * trailing `Main;` at file scope. If the initialisers already call
     * Main, don't auto-call it again (that would run Main twice). The
     * auto-call only restores the convenience for the "globals +
     * Main, no explicit call" case. */
    int calls_main = 0;
    listForEach(cc->initalisers) {
        Ast *a = (Ast *)it->value;
        if (a && a->kind == AST_FUNCALL && a->fname &&
            a->fname->len == 4 && !strncmp(a->fname->data, str_lit("Main")))
        {
            calls_main = 1;
            break;
        }
    }

    /* The process's argc/argv arrive here: pass them on to Main, or the
     * initialisers above would run over the registers Main reads them
     * from (x0/x1 on arm64). */
    Vec *params = astVecNew();
    Ast *argc_p = astLVar(ast_i32_type, str_lit("__main_argc"));
    Ast *argv_p = astLVar(astMakePointerType(astMakePointerType(ast_u8_type)),
                          str_lit("__main_argv"));
    vecPush(params, argc_p);
    vecPush(params, argv_p);
    /* Ids are per function: take ones the file-scope locals don't use */
    s64 max_id = 0;
    if (cc->initaliser_locals) {
        listForEach(cc->initaliser_locals) {
            Ast *l = (Ast *)it->value;
            if (l && l->kind == AST_LVAR && l->lvar_id > max_id) {
                max_id = l->lvar_id;
            }
        }
    }
    argc_p->lvar_id = max_id + 1;
    argv_p->lvar_id = max_id + 2;

    Ast *user_main = (Ast *)mapGetLen(cc->global_env, str_lit("Main"));
    if (!calls_main && user_main && user_main->kind == AST_FUNC) {
        AstType *rt = user_main->type ? user_main->type->rettype : NULL;
        Vec *margs = astVecNew();
        Vec *mp = user_main->params;
        Ast *p0 = mp && mp->size >= 1 ? (Ast *)mp->entries[0] : NULL;
        Ast *p1 = mp && mp->size >= 2 ? (Ast *)mp->entries[1] : NULL;
        if (p0 && astIsIntType(p0->type)) {
            vecPush(margs, p0->type->size == 4
                    ? argc_p : astCast(argc_p, p0->type));
            if (p1 && p1->type && p1->type->kind == AST_TYPE_POINTER) {
                vecPush(margs, argv_p);
            }
        }
        Ast *call = astFunctionCall(rt ? rt : ast_void_type,
                                    str_lit("Main"), margs);
        if (rt && rt->kind != AST_TYPE_VOID) {
            /* Propagate Main's value as the exit code, matching the
             * no-initialiser case where Main *is* the entry point. */
            listAppend(cc->initalisers, astReturn(call, ast_i32_type));
        } else {
            listAppend(cc->initalisers, call);
            listAppend(cc->initalisers,
                       astReturn(astI64Type(0), ast_i32_type));
        }
    } else {
        listAppend(cc->initalisers,
                   astReturn(astI64Type(0), ast_i32_type));
    }

    Ast *body = astCompountStatement(cc->initalisers);
    AstType *fn_type = astMakeFunctionType(ast_i32_type, params);
    return astFunction(fn_type, str_lit("main"), params, body,
                       cc->initaliser_locals, 0);
}

AoStr *asmGenerate(Cctrl *cc) {
    switch (cc->target) {
        case TARGET_AARCH64_APPLE_DARWIN:
        case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
            return aarch64AsmGenerate(cc);
        case TARGET_X86_64_APPLE_DARWIN:
        case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            return x86_64AsmGenerate(cc);
    }
    loggerPanic("asm: unknown target %d\n", (int)cc->target);
}

/* ---- AOT byte emission for `asm {}` chunks ---- */

/* Order fixups by patch offset so the byte walk below can splice
 * relocation expressions in stream order. */
static int asmFixupOffsetCmp(const void *a, const void *b) {
    const AsmFixup *fa = a, *fb = b;
    if (fa->patch_offset < fb->patch_offset) return -1;
    if (fa->patch_offset > fb->patch_offset) return 1;
    return 0;
}

static void asmEmitByteRun(AoStr *buf, const uint8_t *bytes,
                           size_t from, size_t to)
{
    for (size_t i = from; i < to; ++i) {
        if (((i - from) & 15) == 0) {
            if (i != from) aoStrPutChar(buf, '\n');
            aoStrCatLen(buf, "\t.byte ", 7);
        } else {
            aoStrCatLen(buf, ", ", 2);
        }
        aoStrCatFmt(buf, "%I", (s64)bytes[i]);
    }
    if (to > from) aoStrPutChar(buf, '\n');
}

int asmEmitBlockBytes(Cctrl *cc, AoStr *buf, AoStr *text, int src_line) {
    if (!text || text->len == 0) return 0;
    const char *src_file = "<asm>";
    AsmBlock *blk = asm_parse_with_handler(text->data, (int)text->len,
                                           src_file, src_line,
                                           prsAsmTasmArch(cc),
                                           cc,
                                           prsAsmDiagSink);
    if (blk->errors) {
        asm_block_free(blk);
        return -1;
    }
    AsmEnc enc;
    asm_enc_init(&enc);
    asm_enc_set_error_handler(&enc, cc, prsAsmDiagSink);
    asm_encode(blk, NULL, &enc);
    asm_block_free(blk);
    if (enc.errors) {
        asm_enc_free(&enc);
        return -1;
    }

    /* Local-label fixups were resolved by asm_encode; what remains are
     * symbol references. Sort by offset and splice each back into the
     * byte stream as a relocation expression. */
    if (enc.n_fixups > 1) {
        qsort(enc.fixups, (size_t)enc.n_fixups, sizeof(AsmFixup),
              asmFixupOffsetCmp);
    }
    int rc = 0;
    size_t pos = 0;
    for (int i = 0; i < enc.n_fixups && rc == 0; ++i) {
        AsmFixup *f = &enc.fixups[i];
        if (f->kind != AF_SYMBOL) continue;
        /* C symbols get the platform prefix; internal `_NAME` asm
         * labels already carry their underscore so this is a no-op
         * for them on Darwin and everywhere on Linux. */
        AoStr *raw = aoStrDupRaw(f->sym, strlen(f->sym));
        char *sym = asmNormaliseFunctionName(cc, raw);
        /* Branch fixups go back out as textual instructions: Mach-O
         * has no subtraction reloc for undefined symbols, and
         * `call sym` assembles to the exact bytes libtasm produced
         * (opcode + rel32) with a proper branch relocation. The
         * opcode byte(s) sit just before the displacement. */
        static const char *const kCcNames[16] = {
            "o","no","b","ae","e","ne","be","a",
            "s","ns","p","np","l","ge","le","g"
        };
        switch (f->reloc) {
            case AFR_X86_64_CALL32:
            case AFR_X86_64_JMP32: {
                if (f->patch_offset < 1) { rc = -1; break; }
                asmEmitByteRun(buf, enc.bytes, pos, f->patch_offset - 1);
                int is_call = (f->reloc == AFR_X86_64_CALL32);
                aoStrCatFmt(buf, "\t%s %s\n", is_call ? "call" : "jmp", sym);
                pos = f->patch_offset + 4;
                break;
            }
            case AFR_X86_64_JCC32: {
                if (f->patch_offset < 2) { rc = -1; break; }
                asmEmitByteRun(buf, enc.bytes, pos, f->patch_offset - 2);
                int cc4 = enc.bytes[f->patch_offset - 1] & 0x0F;
                aoStrCatFmt(buf, "\tj%s %s\n", kCcNames[cc4], sym);
                pos = f->patch_offset + 4;
                break;
            }
            case AFR_X86_64_SIGNED:
                /* RIP-relative data ref; subtraction works for symbols
                 * defined in this translation unit. */
                asmEmitByteRun(buf, enc.bytes, pos, f->patch_offset);
                aoStrCatFmt(buf, "\t.long %s - . - 4\n", sym);
                pos = f->patch_offset + 4;
                break;
            case AFR_AARCH64_CALL26:
            case AFR_AARCH64_JUMP26: {
                /* aarch64 branch to an external symbol: the relocation
                 * lands inside the 4-byte instruction word, which a
                 * .byte run can't carry. Re-emit the branch as a textual
                 * instruction so the system assembler produces a proper
                 * ARM64_RELOC_BRANCH26 against `sym`. */
                asmEmitByteRun(buf, enc.bytes, pos, f->patch_offset);
                int is_bl = (f->reloc == AFR_AARCH64_CALL26);
                aoStrCatFmt(buf, "\t%s %s\n", is_bl ? "bl" : "b", sym);
                pos = f->patch_offset + 4;
                break;
            }
            default:
                if (!f->pcrel && f->width == 8) {
                    asmEmitByteRun(buf, enc.bytes, pos, f->patch_offset);
                    aoStrCatFmt(buf, "\t.quad %s\n", sym);
                    pos = f->patch_offset + 8;
                    break;
                }
                cctrlDiagPush(cc, cctrlMakeDiag(cc, CCTRL_ERROR,
                        aoStrPrintf("%s:%d: asm: reference to `%s` cannot be "
                                    "expressed on the AOT path for this "
                                    "target (reloc %d); use -jit",
                                    src_file, src_line, f->sym,
                                    (int)f->reloc),
                        NULL));
                rc = -1;
                break;
        }
    }
    if (rc == 0) asmEmitByteRun(buf, enc.bytes, pos, enc.len);
    asm_enc_free(&enc);
    return rc;
}

void asmEmitAsmInfo(Cctrl *cc, AoStr *buf) {
    /* Depends on the target, not the host, so cross compiling works:
     * ELF wants the non-executable stack note, Mach-O has no such
     * section and its assembler rejects it. */
    switch (cc->target) {
        case TARGET_AARCH64_UNKNOWN_LINUX_GNU:
        case TARGET_X86_64_UNKNOWN_LINUX_GNU:
            aoStrCatFmt(buf,
                    ".section    .note.GNU-stack,\"\",@progbits\n");
            break;
        case TARGET_AARCH64_APPLE_DARWIN:
        case TARGET_X86_64_APPLE_DARWIN:
            break;
    }
    aoStrCatFmt(buf,
               ".ident      \"ktm: %s %s %s hash: %s\"\n",
                OS_STR,
                cliTargetToString(cc->target),
                cctrlGetVersion(),
                KTM_GIT_HASH);
}

/* ---- Global initialisers ---- */

/* Write `init` as a `type` object at `off` into the image. Returns the
 * offset just past it, where a following item goes when there is no
 * type to place it by. */
static int asmInitImageAt(Ast *init, AstType *type, u8 *bytes, Ast **items,
                          int off, int size)
{
    if (!init) return off;

    if (init->kind == AST_ARRAY_INIT) {
        int is_class = type && !astIsIntrinsicClass(type) &&
                       (type->kind == AST_TYPE_CLASS ||
                        type->kind == AST_TYPE_UNION);
        AstType *elem_ty = (type && type->kind == AST_TYPE_ARRAY)
                           ? type->ptr : NULL;
        int next = off;
        int idx = 0;
        listForEach(init->arrayinit) {
            Ast *item = (Ast *)it->value;
            AstType *item_ty = NULL;
            int item_off = next;
            if (is_class) {
                item_ty = astClassFieldAt(type, idx);
                if (item_ty) item_off = off + item_ty->offset;
            } else if (elem_ty) {
                item_ty = elem_ty;
                item_off = off + idx * elem_ty->size;
            }
            next = asmInitImageAt(item, item_ty, bytes, items, item_off, size);
            idx++;
        }
        return (type && type->size > 0) ? off + type->size : next;
    }

    if (init->kind == AST_STRING) {
        /* A string in a char-array slot (`U8 s[2][8] = {"ab",..}`, a
         * `U8 name[8]` field) is its bytes, zero-filled to the slot;
         * the parser has checked that it fits. Anywhere else it is a
         * pointer to the literal. */
        if (type && type->kind == AST_TYPE_ARRAY && type->size > 0) {
            char *str = (char *)malloc(init->sval->len + 1);
            int n = astStringDecode(init, str) + 1;
            if (n > type->size) n = type->size;
            if (off >= 0 && off + n <= size) memcpy(bytes + off, str, n);
            free(str);
            return off + type->size;
        }
        if (off >= 0 && off + 8 <= size) items[off] = init;
        return off + 8;
    }

    if (init->kind == AST_LITERAL) {
        int w = asmInitItemWidth(init);
        u64 bits;
        if (init->type && init->type->kind == AST_TYPE_FLOAT) {
            bits = w == 4 ? (u64)ieee754_32((f32)init->f64)
                          : ieee754_64(init->f64);
        } else {
            bits = (u64)init->i64;
        }
        if (off >= 0 && off + w <= size) {
            items[off] = init;
            for (int i = 0; i < w; ++i) {
                bytes[off + i] = (u8)(bits >> (i * 8));
            }
        }
        return off + w;
    }

    /* Not a constant: nothing to store, the slot stays zero. */
    return off + ((type && type->size > 0) ? type->size : 8);
}

/* A global's initialiser that parseConstGlobalInit folded to an address,
 * `&target` or `(U8 *)&target + off` with target a global or a function:
 * the symbol to write (`.quad sym+off`) and the offset. `for_jit` gives
 * the name the JIT registers a global under (no assembler escaping). */
int asmAddrConst(Cctrl *cc, Ast *init, AoStr **_sym, s64 *_off,
                 int for_jit)
{
    s64 off = 0;
    if (!init) return 0;
    if (init->kind == AST_BINOP && init->binop == AST_BIN_OP_ADD &&
        init->right && init->right->kind == AST_LITERAL)
    {
        off = init->right->i64;
        init = init->left;
    }
    if (init->kind != AST_UNOP || init->unop != AST_UN_OP_ADDR_OF ||
        !init->operand)
    {
        return 0;
    }
    Ast *t = init->operand;
    switch (t->kind) {
        case AST_GVAR: {
            AoStr *label = (t->glabel ? t->glabel : t->gname);
            *_sym = for_jit ? label : asmNormaliseGlobalLabel(cc, label);
            break;
        }
        case AST_FUNC:
        case AST_FUN_PROTO:
        case AST_EXTERN_FUNC: {
            char *name = asmNormaliseFunctionName(cc, t->fname);
            *_sym = aoStrDupRaw(name, strlen(name));
            break;
        }
        default:
            return 0;
    }
    *_off = off;
    return 1;
}

/* `.quad sym+off` */
void asmEmitAddrConst(AoStr *buf, AoStr *sym, s64 off) {
    if (off) {
        aoStrCatPrintf(buf, ".quad %s%+lld\n\t", sym->data, (long long)off);
    } else {
        aoStrCatPrintf(buf, ".quad %s\n\t", sym->data);
    }
}

int asmDataAlignLog2(AstType *type) {
    int align = astTypeAlign(type);
    int log2 = 0;
    while ((1 << log2) < align) log2++;
    return log2;
}

int asmInitItemWidth(Ast *item) {
    if (item->kind == AST_STRING || !item->type) return 8;
    int w = item->type->size;
    return (w == 1 || w == 2 || w == 4) ? w : 8;
}

void asmInitImage(Ast *init, AstType *type, u8 *bytes, Ast **items) {
    asmInitImageAt(init, type, bytes, items, 0, type->size);
}

void asmEmitInitData(AoStr *buf, Ast *init, AstType *type) {
    int size = type->size;
    if (size <= 0) return;
    u8 *bytes = (u8 *)calloc((size_t)size, 1);
    Ast **items = (Ast **)calloc((size_t)size, sizeof(Ast *));
    asmInitImage(init, type, bytes, items);

    /* Each item at its natural width, the gaps between them as `.zero`. */
    int zeros = 0;
    int pos = 0;
    while (pos < size) {
        Ast *item = items[pos];
        if (!item && bytes[pos] == 0) {
            zeros++;
            pos++;
            continue;
        }
        if (zeros) aoStrCatPrintf(buf, ".zero %d\n\t", zeros);
        zeros = 0;
        if (item && item->kind == AST_STRING) {
            aoStrCatFmt(buf, ".quad %S\n\t", item->slabel);
            pos += 8;
            continue;
        }
        /* An item, or a byte a later overlapping item left behind. */
        int w = item ? asmInitItemWidth(item) : 1;
        u64 v = 0;
        for (int i = 0; i < w; ++i) {
            v |= (u64)bytes[pos + i] << (i * 8);
        }
        switch (w) {
            case 1: aoStrCatPrintf(buf, ".byte %u\n\t", (unsigned)v); break;
            case 2: aoStrCatPrintf(buf, ".short %u\n\t", (unsigned)v); break;
            case 4: aoStrCatPrintf(buf, ".long %u\n\t", (unsigned)v); break;
            default:
                aoStrCatPrintf(buf, ".quad %llu\n\t", (unsigned long long)v);
                break;
        }
        pos += w;
    }
    if (zeros) aoStrCatPrintf(buf, ".zero %d\n\t", zeros);
    free(bytes);
    free(items);
}
