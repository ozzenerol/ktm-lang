/* x86_64 IR-based codegen.
 *
 * SysV AMD64 ABI for both Linux and macOS (only the symbol naming and
 * PIC syntax differ - same call convention, same register set, same
 * struct passing). */
#include <assert.h>
#include <math.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "aostr.h"
#include "ast.h"
#include "asm.h"
#include "cctrl.h"
#include "cli.h"
#include "config.h"
#include "containers.h"
#include "ir.h"
#include "ir-debug.h"
#include "ir-optimise.h"
#include "ir-regalloc.h"
#include "list.h"
#include "util.h"
#include "version.h"
#include "x86_64.h"

/* SysV first-six integer arg registers, ordered as arg 0..5. */
static const char *kIntRegs64[] = {
    "rdi", "rsi", "rdx", "rcx", "r8", "r9"
};
/* SysV first-eight FP arg registers. */
static const char *kXmmRegs[] = {
    "xmm0", "xmm1", "xmm2", "xmm3",
    "xmm4", "xmm5", "xmm6", "xmm7"
};

/* Build AoStr-wrapped backend pool once, lazily, and hand it to the
 * IR layer via `irRegPoolSet`. The AoStr instances live for the
 * compiler's lifetime; the Vec owns the pointer array. */
static Vec *x86_64MakeAoStrVec(const char *const *src, int n) {
    Vec *v = vecNew(&vec_aostr_type);
    for (int i = 0; i < n; ++i) {
        vecPush(v, aoStrDupRaw((char *)src[i], strlen(src[i])));
    }
    return v;
}

/* Which of rax/rcx/rdx/xmm0/xmm1 does emitting `I` actually write, by
 * the point `at`? r1 is materialised into rax/xmm0, r2 into rcx, and
 * the result spills out of rax/xmm0.
 *
 * Conservative by default - only the audited cases answer "not
 * clobbered", so an unconsidered opcode keeps the old behaviour. */
static int x86_64OpClobbers(IrInstr *I, AoStr *reg, IrClobberPoint at) {
    const char *r = reg->data;
    int is_scratch0 = !strcmp(r, "rax") || !strcmp(r, "xmm0");
    /* Division sign-extends into rdx (cqto) *before* the divisor is
     * loaded, so rdx is unsafe for r2 - and rdx is SysV arg #3. */
    int is_divrem = I->op == IR_IDIV || I->op == IR_UDIV ||
                    I->op == IR_IREM || I->op == IR_UREM;

    switch (at) {
        case IR_CLOBBER_BEFORE_R1:
            if (I->op == IR_STORE_DEREF || I->op == IR_RMW_DEREF) return 1;
            return 0;

        case IR_CLOBBER_BEFORE_R2:
            if (I->op == IR_STORE_DEREF || I->op == IR_RMW_DEREF) return 1;
            if (is_divrem) return 1;
            /* r1 has landed in rax/xmm0; rcx and rdx are untouched. */
            return is_scratch0;

        case IR_CLOBBER_AFTER:
        default:
            /* The IR_STORE path stores a reg-resident r1 straight out
             * of its own register instead of bouncing through
             * rax/xmm0, so nothing is clobbered - which is what lets
             * consecutive param spills all forward. A pinned
             * destination names its own register and does clobber. */
            if (I->op == IR_STORE) {
                if (I->dst && I->dst->pinned_reg) return 1;
                int src_in_reg = I->r1 && I->r1->loc.kind == IR_LOC_REG &&
                                 I->r1->loc.as.reg;
                return src_in_reg ? 0 : is_scratch0;
            }
            return 1;
    }
}

static void x86_64InitRegPool(void) {
    static int initialised = 0;
    static IrRegPool pool;
    if (initialised) return;

    static const char *kScratchRegs[] = { "rax", "rcx", "rdx", "xmm0", "xmm1" };
    int n_int     = (int)(sizeof(kIntRegs64)   / sizeof(kIntRegs64[0]));
    int n_float   = (int)(sizeof(kXmmRegs)     / sizeof(kXmmRegs[0]));
    int n_scratch = (int)(sizeof(kScratchRegs) / sizeof(kScratchRegs[0]));
    pool.int_arg_regs     = x86_64MakeAoStrVec(kIntRegs64,   n_int);
    pool.float_arg_regs   = x86_64MakeAoStrVec(kXmmRegs,     n_float);
    pool.int_return_reg   = aoStrDupRaw((char *)"rax", 3);
    pool.float_return_reg = aoStrDupRaw((char *)"xmm0", 4);
    pool.sret_reg         = NULL; /* SysV: sret ptr is the first int arg */
    pool.scratch_regs     = x86_64MakeAoStrVec(kScratchRegs, n_scratch);
    pool.op_clobbers      = x86_64OpClobbers;

    irRegPoolSet(&pool);
    initialised = 1;
}

/* Map a 64-bit GP register name to its narrower variant. The
 * heterogeneous register-naming scheme on x86 makes this a lookup
 * table rather than a name-mangling rule. */
static void x86_64RegForWidth(const char *reg64,
                              int size,
                              char *out,
                              u64 out_sz)
{
    static const struct { const char *q, *d, *w, *b; } tab[] = {
        {"rax", "eax",  "ax",  "al"},
        {"rbx", "ebx",  "bx",  "bl"},
        {"rcx", "ecx",  "cx",  "cl"},
        {"rdx", "edx",  "dx",  "dl"},
        {"rsi", "esi",  "si",  "sil"},
        {"rdi", "edi",  "di",  "dil"},
        {"rbp", "ebp",  "bp",  "bpl"},
        {"rsp", "esp",  "sp",  "spl"},
        {"r8",  "r8d",  "r8w", "r8b"},
        {"r9",  "r9d",  "r9w", "r9b"},
        {"r10", "r10d", "r10w","r10b"},
        {"r11", "r11d", "r11w","r11b"},
        {"r12", "r12d", "r12w","r12b"},
        {"r13", "r13d", "r13w","r13b"},
        {"r14", "r14d", "r14w","r14b"},
        {"r15", "r15d", "r15w","r15b"},
    };
    for (u64 i = 0; i < sizeof(tab)/sizeof(tab[0]); ++i) {
        if (!strcmp(reg64, tab[i].q)) {
            const char *pick;
            if (size == 1)      pick = tab[i].b;
            else if (size == 2) pick = tab[i].w;
            else if (size == 4) pick = tab[i].d;
            else                pick = tab[i].q;
            snprintf(out, out_sz, "%s", pick);
            return;
        }
    }
    loggerPanic("ir-cg-x86_64: unrecognised 64-bit reg name '%s'\n", reg64);
}

/* leaq <sym>(%rip), %<reg64>. Caller has already normalised the
 * symbol name */
static void x86_64GlobalAddr(IrCgCtx *ctx, const char *sym, const char *reg) {
    aoStrCatFmt(ctx->buf, "leaq    %s(%%rip), %%%s\n\t", sym, reg);
}

/* leaq <loff>(%rbp), %<reg64>. AT&T allows signed displacements
 * natively. */
static void x86_64FrameAddr(AoStr *buf, int loff, const char *reg) {
    aoStrCatFmt(buf, "leaq    %i(%%rbp), %%%s\n\t", loff, reg);
}

static void x86_64Lea(IrCgCtx *ctx, IrInstr *lea, const char *reg) {
    if (lea->r1 && lea->r1->kind == IR_VAL_GLOBAL) {
        const char *name = asmNormaliseGlobalLabel(ctx->cc,
                lea->r1->as.global.name)->data;
        if (lea->r1->flags & IR_VAL_FLAG_FUNC) {
            name = asmNormaliseFunctionName(ctx->cc,
                    lea->r1->as.global.name);
        }
        if (lea->r1->flags & IR_VAL_FLAG_TLS) {
            /* A threadlocal: initial-exec, the thread pointer plus the
             * variable's offset from the GOT. ld relaxes the GOT load to
             * `movq $x@tpoff` in a program (local-exec); libktm.so keeps
             * it, its TLS is in the static block (see rt/except.ktm). */
            if (ctx->cc->target == TARGET_X86_64_APPLE_DARWIN) {
                loggerPanic("threadlocal globals are not supported on "
                            "x86_64 macOS\n");
            }
            aoStrCatFmt(ctx->buf, "movq    %s@gottpoff(%%rip), %%%s\n\t"
                        "addq    %%fs:0, %%%s\n\t", name, reg, reg);
        } else if (lea->r1->flags & IR_VAL_FLAG_EXTERN) {
            /* Possibly in a shared library: through the GOT (ELF and
             * Mach-O spell it the same; the linker relaxes it back to
             * a lea when the symbol turns out to be local) */
            aoStrCatFmt(ctx->buf, "movq    %s@GOTPCREL(%%rip), %%%s\n\t",
                        name, reg);
        } else {
            x86_64GlobalAddr(ctx, name, reg);
        }
    } else {
        int loff = irCgGetLoff(&ctx->fn->ra, lea->r1);
        x86_64FrameAddr(ctx->buf, loff, reg);
    }
}

/* Width-aware load from <loff>(%rbp) into <reg64>.
 *
 * Width policy mirrors aarch64's choice: sub-word loads (1/2) zero-
 * extend to the full register, 4-byte loads sign-extend.
 * 8-byte loads are plain movq. */
static void x86_64Load(AoStr *buf, const char *reg, u32 size, int loff) {
    switch (size) {
        case 1:  aoStrCatFmt(buf, "movzbq  %i(%%rbp), %%%s\n\t", loff, reg); break;
        case 2:  aoStrCatFmt(buf, "movzwq  %i(%%rbp), %%%s\n\t", loff, reg); break;
        case 4:  aoStrCatFmt(buf, "movslq  %i(%%rbp), %%%s\n\t", loff, reg); break;
        default: aoStrCatFmt(buf, "movq    %i(%%rbp), %%%s\n\t", loff, reg); break;
    }
}

/* Build the `[disp](%base[,%idx,scale])` operand text. disp==0 ->
 * no displacement; idx_reg==NULL -> no SIB component. The scale
 * must be one of 1/2/4/8 when idx_reg is provided. */
static void x86_64FmtMemOperand(char *out, u64 out_sz,
                                s32 disp,
                                const char *base_reg,
                                const char *idx_reg,
                                u8 scale)
{
    if (idx_reg) {
        if (disp == 0) {
            snprintf(out, out_sz, "(%%%s,%%%s,%d)",
                     base_reg, idx_reg, (int)scale);
        } else {
            snprintf(out, out_sz, "%d(%%%s,%%%s,%d)",
                     (int)disp, base_reg, idx_reg, (int)scale);
        }
    } else if (disp == 0) {
        snprintf(out, out_sz, "(%%%s)", base_reg);
    } else {
        snprintf(out, out_sz, "%d(%%%s)", (int)disp, base_reg);
    }
}

/* Width-aware load from `disp(<base_reg>[, <idx_reg>, scale])` into
 * <reg64>. Same extension policy as x86_64Load. idx_reg==NULL drops
 * the SIB component. */
static void x86_64DerefLoadWidth(AoStr *buf,
                                 u32 size,
                                 const char *reg,
                                 const char *base_reg,
                                 const char *idx_reg,
                                 u8 scale,
                                 s32 disp)
{
    char mem[48];
    x86_64FmtMemOperand(mem, sizeof(mem), disp, base_reg, idx_reg, scale);
    switch (size) {
        case 1:  aoStrCatFmt(buf, "movzbq  %s, %%%s\n\t", mem, reg); break;
        case 2:  aoStrCatFmt(buf, "movzwq  %s, %%%s\n\t", mem, reg); break;
        case 4:  aoStrCatFmt(buf, "movslq  %s, %%%s\n\t", mem, reg); break;
        default: aoStrCatFmt(buf, "movq    %s, %%%s\n\t", mem, reg); break;
    }
}


const char *x86_64GetSizedMov(u32 size) {
    switch (size) {
        case 1:  return "movb";
        case 2:  return "movw";
        case 4:  return "movl";
        default: return "movq";
    }
}


static void x86_64GenericStoreWidth(AoStr *buf, u32 size, const char *reg, char *fmt) {
    switch (size) {
        case 1:
        case 2:
        case 4: {
            char nreg[8];
            const char *sized_mov = x86_64GetSizedMov(size);
            x86_64RegForWidth(reg, size, nreg, sizeof(nreg));
            aoStrCatFmt(buf, "%s    %%%s, %s\n\t", sized_mov, nreg, fmt);
            break;
        }
        default: aoStrCatFmt(buf, "movq    %%%s, %s\n\t", reg, fmt); break;
    }
}

/* Width-aware store of <reg64>'s low bits into <loff>(%rbp). The
 * narrower stores use the matching subregister name (e.g. al for
 * size=1) so the assembler picks the right encoding. */
static void x86_64FrameStoreWidth(AoStr *buf,
                                  int loff,
                                  u32 size,
                                  const char *reg)
{
    char fmt[32];
    snprintf(fmt, sizeof(fmt), "%d(%%rbp)", loff);
    x86_64GenericStoreWidth(buf, size, reg, fmt);

}

/* Width-aware store of <reg64> into `disp(<base_reg>[, <idx_reg>,
 * scale])`. idx_reg==NULL drops the SIB component. */
static void x86_64DerefStoreWidth(AoStr *buf,
                                  u32 size,
                                  const char *reg,
                                  const char *base_reg,
                                  const char *idx_reg,
                                  u8 scale,
                                  s32 disp)
{
    char fmt[48];
    x86_64FmtMemOperand(fmt, sizeof(fmt), disp, base_reg, idx_reg, scale);
    x86_64GenericStoreWidth(buf, size, reg, fmt);
}

static void x86_64DerefStoreGlobal(AoStr *buf, u32 size, const char *reg, const char *sym) {
    /* sized by the name: a module's global's symbol is long
     * (_K3net5https16gClientTransport), and a fixed buffer cut it short
     * into `sym(`, which the assembler refused */
    x86_64GenericStoreWidth(buf, size, reg, mprintf("%s(%%rip)", sym));
}

/* x86 mnemonic suffix for an N-byte operation. */
static const char *x86_64SizedSuffix(u32 size) {
    switch (size) {
        case 1:  return "b";
        case 2:  return "w";
        case 4:  return "l";
        default: return "q";
    }
}

/* Mnemonic base for an RMW IrOp; suffix added by caller. */
static const char *x86_64RmwMnem(IrOp op) {
    switch (op) {
        case IR_IADD: return "add";
        case IR_ISUB: return "sub";
        case IR_AND:  return "and";
        case IR_OR:   return "or";
        case IR_XOR:  return "xor";
        default:      return NULL;
    }
}

/* Lay an immediate down through a register-held address (optionally
 * displaced and SIB-scaled). Used by the DST_IN_REG path so we
 * don't bounce through %rax for the value. */
static void x86_64DerefStoreImm(AoStr *buf,
                                u32 size,
                                s64 imm,
                                const char *base_reg,
                                const char *idx_reg,
                                u8 scale,
                                s32 disp)
{
    const char *mnem = x86_64GetSizedMov(size);
    char mem[48];
    x86_64FmtMemOperand(mem, sizeof(mem), disp, base_reg, idx_reg, scale);
    aoStrCatFmt(buf, "%s    $%I, %s\n\t", mnem, (s64)imm, mem);
}

/* Load any IrValue into the given 64-bit GP register.
 *
 * - Constants are materialised directly (no slot needed).
 * - String labels become PC-relative LEA.
 * - Tmp/local/param values either come from their pinned register
 *   (when one is set) or are loaded from their frame slot via
 *   x86_64Load. */
static void x86_64LoadToReg(IrCgCtx *ctx, IrValue *val, const char *reg) {
    switch (val->kind) {
        case IR_VAL_CONST_INT:
            /* Zero load: `xorl r32, r32` is 2 bytes and the implicit
             * zero-extend gives us a clean 64-bit zero. */
            if (val->as._i64 == 0) {
                char reg32[8];
                x86_64RegForWidth(reg, 4, reg32, sizeof(reg32));
                aoStrCatFmt(ctx->buf, "xorl    %%%s, %%%s\n\t",
                            reg32, reg32);
                break;
            }
            /* GNU as picks the 7-byte mov-imm32 or 10-byte movabs
             * encoding depending on whether the value fits in a
             * 32-bit sign-extended immediate. */
            aoStrCatFmt(ctx->buf, "movq    $%I, %%%s\n\t",
                        val->as._i64, reg);
            break;

        case IR_VAL_CONST_FLOAT:
            /* Load the raw bit pattern as an integer. Rarely needed
             * - float constants normally go through LoadToFpr but might
             *   be needed in some cases for bitcasts. */
            aoStrCatPrintf(ctx->buf, "movabsq $0x%lX, %%%s\n\t",
                           (u64)ieee754_64(val->as._f64), reg);
            break;

        case IR_VAL_CONST_STR:
            x86_64GlobalAddr(ctx, val->as.str.label->data, reg);
            break;

        case IR_VAL_TMP:
        case IR_VAL_LOCAL:
        case IR_VAL_PARAM: {
            if (val->pinned_reg) {
                aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t",
                            val->pinned_reg->data, reg);
                break;
            }
            /* Regalloc parked the value in a physical reg, just a
             * reg-to-reg move (or nothing if it's already there). */
            if (val->loc.kind == IR_LOC_REG && val->loc.as.reg) {
                const char *src = val->loc.as.reg->data;
                if (strcmp(src, reg) != 0) {
                    aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t", src, reg);
                }
                break;
            }
            u32 size = irValueByteSize(val);
            int loff = irCgGetLoff(&ctx->fn->ra, val);
            x86_64Load(ctx->buf, reg, size, loff);
            break;
        }
        default:
            loggerPanic("ir-cg-x86_64: cannot load value of kind %s\n",
                        irValueKindToString(val->kind));
    }
}

/* Store a 64-bit GP register into the destination IrValue's home.
 * If the destination is pinned, this is just a register-to-register
 * move; otherwise it's a width-aware store to the frame slot. */
static void x86_64StoreReg(IrCgCtx *ctx, IrValue *dst, const char *reg) {
    if (dst->pinned_reg) {
        aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t",
                    reg, dst->pinned_reg->data);
        return;
    }
    /* Regalloc-assigned register: reg-to-reg move (or nothing). */
    if (dst->loc.kind == IR_LOC_REG && dst->loc.as.reg) {
        const char *home = dst->loc.as.reg->data;
        if (strcmp(home, reg) != 0) {
            aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t",
                        reg, home);
        }
        return;
    }
    u32 size = irValueByteSize(dst);
    int loff = irCgGetLoff(&ctx->fn->ra, dst);
    x86_64FrameStoreWidth(ctx->buf, loff, size, reg);
}

/* Spill instr->dst into its home. When dst.loc is a register (set by
 * the peephole or the linear-scan), StoreReg's same-reg short-circuit
 * makes this a no-op move; when dst is a slot, it's a real store. */
static void x86_64SpillDst(IrCgCtx *ctx, IrInstr *instr, const char *reg) {
    x86_64StoreReg(ctx, instr->dst, reg);
}

/* The SSE scalar move mnemonic for a float of `size` bytes:
 * movss for single (F32), movsd for double (F64). */
static const char *x86_64FpMov(int size) {
    return size == 4 ? "movss" : "movsd";
}

/* Materialise a float constant in .data and load it via PC-relative
 * addressing. `size` selects single (.long / movss) vs double
 * (.quad / movsd). Bit-pattern dedup: identical constants share a
 * single .LIRFn slot across the whole translation unit (4- and 8-byte
 * pools are kept separate so widths never alias). */
static void x86_64EmitFloatLiteralData(IrCgCtx *ctx,
                                       const char *xmm_reg,
                                       f64 f,
                                       int size)
{
    static int float_seq = 0;
    static Map *bits_to_label8 = NULL;
    static Map *bits_to_label4 = NULL;
    if (!bits_to_label8)
        bits_to_label8 = mapNew(16, &map_uint_to_uint_type);
    if (!bits_to_label4)
        bits_to_label4 = mapNew(16, &map_uint_to_uint_type);

    int is_single = (size == 4);
    Map *bits_to_label = is_single ? bits_to_label4 : bits_to_label8;
    u64 bits = is_single ? (u64)(u32)ieee754_32((f32)f) : (u64)ieee754_64(f);
    const char *mov = x86_64FpMov(size);

    AoStr *label = (AoStr *)mapGetInt(bits_to_label, bits);
    if (label) {
        /* Already in the data section, just reference it. */
        aoStrCatFmt(ctx->buf, "%s   %S(%%rip), %%%s\n\t",
                    mov, label, xmm_reg);
        return;
    }
    char buf[64];
    int n = snprintf(buf, sizeof(buf), ".LIRF%d", float_seq++);
    label = aoStrDupRaw(buf, (u64)n);
    mapAddIntOrErr(bits_to_label, bits, label);

    aoStrRemovePreviousChar(ctx->buf, '\t');
    if (is_single) {
        aoStrCatPrintf(ctx->buf,
                       ".data\n\t.p2align 2\n%s:\n\t"
                       ".long 0x%lX # %.9f\n"
                       ".text\n\t"
                       "%s   %s(%%rip), %%%s\n\t",
                       label->data,
                       (unsigned long)bits, f,
                       mov, label->data, xmm_reg);
    } else {
        aoStrCatPrintf(ctx->buf,
                       ".data\n\t.p2align 3\n%s:\n\t"
                       ".quad 0x%lX # %.9f\n"
                       ".text\n\t"
                       "%s   %s(%%rip), %%%s\n\t",
                       label->data,
                       (unsigned long)bits, f,
                       mov, label->data, xmm_reg);
    }
}

/* Load any IrValue into an xmm register. Integer constants are
 * coerced to double (matching aarch64). Float-typed locals/params
 * come from their frame slot via movsd. Pinned-reg variants are not
 * yet supported on the FP side. */
static void x86_64LoadToFpr(IrCgCtx *ctx, IrValue *val, const char *xmm_reg) {
    switch (val->kind) {
        case IR_VAL_CONST_FLOAT:
        case IR_VAL_CONST_INT: {
            double _f64 = val->kind == IR_VAL_CONST_INT ?
                                       (double)val->as._i64 :
                                       val->as._f64;
            int size = (val->kind == IR_VAL_CONST_FLOAT)
                     ? (int)irValueByteSize(val) : 8;
            /* +0.0 (bit pattern 0): xorps/xorpd is shorter than a rodata
             * load and leaves no .LIRF entry behind. -0.0 has the
             * sign bit set, so it must keep the rodata path. */
            if (ieee754_64(_f64) == 0) {
                aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t",
                            size == 4 ? "xorps" : "xorpd", xmm_reg, xmm_reg);
                break;
            }
            x86_64EmitFloatLiteralData(ctx, xmm_reg, _f64, size);
            break;
        }

        case IR_VAL_TMP:
        case IR_VAL_LOCAL:
        case IR_VAL_PARAM: {
            int size = (int)irValueByteSize(val);
            const char *mov = x86_64FpMov(size);
            /* gpr<->xmm bit move: movd for 32-bit, movq for 64-bit. */
            const char *gprmov = size == 4 ? "movd" : "movq";
            /* Pinned FP local: read it out of the named register.
             * movss/movsd for an xmm home, movd/movq to pull bits from a
             * GPR home. The xmm test is case-insensitive (`XMM5`/`xmm5`). */
            if (val->pinned_reg) {
                const char *src = val->pinned_reg->data;
                if (strcmp(src, xmm_reg) != 0) {
                    const char *mnem =
                        (src[0] == 'x' || src[0] == 'X') ? mov : gprmov;
                    aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t",
                                mnem, src, xmm_reg);
                }
                break;
            }
            if (val->loc.kind == IR_LOC_REG && val->loc.as.reg) {
                const char *src = val->loc.as.reg->data;
                if (strcmp(src, xmm_reg) == 0) break;
                /* Cross-file move (int reg -> xmm) uses movd/movq; same
                 * register file uses movss/movsd. xmm reg names start
                 * with 'x'; everything else is a GP reg. */
                const char *mnem = (src[0] == 'x') ? mov : gprmov;
                aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t",
                            mnem, src, xmm_reg);
                break;
            }
            int loff = irCgGetLoff(&ctx->fn->ra, val);
            aoStrCatFmt(ctx->buf, "%s   %i(%%rbp), %%%s\n\t",
                        mov, loff, xmm_reg);
            break;
        }
        default:
            loggerPanic("ir-cg-x86_64: cannot load float of kind %s\n",
                        irValueKindToString(val->kind));
    }
}

/* `val_size` (0 = derive from dst) is the width of the value stored. When
 * dst is a gep/pointer tmp aliasing a narrow field, sizing the store from
 * dst (8 bytes) would clobber the neighbouring field; pass the value width
 * so a F32 field gets a 4-byte `movss` not an 8-byte `movsd`. */
static void x86_64StoreFprSized(IrCgCtx *ctx, IrValue *dst,
                                const char *xmm_reg, int val_size) {
    int size = val_size > 0 ? val_size : (int)irValueByteSize(dst);
    const char *mov = x86_64FpMov(size);
    const char *gprmov = size == 4 ? "movd" : "movq";
    if (dst->pinned_reg) {
        const char *home = dst->pinned_reg->data;
        if (strcmp(home, xmm_reg) != 0) {
            const char *mnem =
                (home[0] == 'x' || home[0] == 'X') ? mov : gprmov;
            aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t", mnem, xmm_reg, home);
        }
        return;
    }
    if (dst->loc.kind == IR_LOC_REG && dst->loc.as.reg) {
        const char *home = dst->loc.as.reg->data;
        if (strcmp(home, xmm_reg) == 0) return;
        const char *mnem = (home[0] == 'x') ? mov : gprmov;
        aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t", mnem, xmm_reg, home);
        return;
    }
    int loff = irCgGetLoff(&ctx->fn->ra, dst);
    aoStrCatFmt(ctx->buf, "%s   %%%s, %i(%%rbp)\n\t", mov, xmm_reg, loff);
}

static void x86_64StoreFpr(IrCgCtx *ctx, IrValue *dst, const char *xmm_reg) {
    int size = (int)irValueByteSize(dst);
    const char *mov = x86_64FpMov(size);
    const char *gprmov = size == 4 ? "movd" : "movq";
    /* Pinned FP local: write the value into the named register.
     * movss/movsd for an xmm home, movd/movq to push bits into a GPR. */
    if (dst->pinned_reg) {
        const char *home = dst->pinned_reg->data;
        if (strcmp(home, xmm_reg) != 0) {
            const char *mnem =
                (home[0] == 'x' || home[0] == 'X') ? mov : gprmov;
            aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t",
                        mnem, xmm_reg, home);
        }
        return;
    }
    if (dst->loc.kind == IR_LOC_REG && dst->loc.as.reg) {
        const char *home = dst->loc.as.reg->data;
        if (strcmp(home, xmm_reg) == 0) return;
        const char *mnem = (home[0] == 'x') ? mov : gprmov;
        aoStrCatFmt(ctx->buf, "%s   %%%s, %%%s\n\t",
                    mnem, xmm_reg, home);
        return;
    }
    int loff = irCgGetLoff(&ctx->fn->ra, dst);
    aoStrCatFmt(ctx->buf, "%s   %%%s, %i(%%rbp)\n\t", mov, xmm_reg, loff);
}

/* Does this value fit in the 32-bit sign-extended immediate form that
 * most x86 integer ops accept (add/sub/and/or/xor/cmp etc.)? */
static int x86_64IsImm32(IrValue *val, s64 *out) {
    if (!val || !irIsConstInt(val)) return 0;
    s64 v = val->as._i64;
    if (v < INT32_MIN || v > INT32_MAX) return 0;
    *out = v;
    return 1;
}

/* Load src into %rax. When src.loc is already %rax (peephole-fused
 * producer), LoadToReg's same-reg check makes this a no-op. */
static void x86_64LoadFirstSrc(IrCgCtx *ctx, IrInstr *instr, IrValue *src) {
    (void)instr;
    x86_64LoadToReg(ctx, src, "rax");
}

/* Float counterpart. */
static void x86_64LoadFirstSrcFpr(IrCgCtx *ctx, IrInstr *instr, IrValue *src) {
    (void)instr;
    x86_64LoadToFpr(ctx, src, "xmm0");
}

static void x86_64SpillDstFpr(IrCgCtx *ctx, IrInstr *instr, const char *xmm_reg) {
    x86_64StoreFpr(ctx, instr->dst, xmm_reg);
}

/* Register `v` already lives in (a param still in its arg reg, a
 * fused tmp), or NULL if it has to be loaded. */
static const char *x86_64HomeReg(IrValue *v) {
    if (v && v->loc.kind == IR_LOC_REG && v->loc.as.reg)
        return v->loc.as.reg->data;
    return NULL;
}

/* Resolve a mem op's address `addr` and its SIB idx (NULL when there
 * is none) to registers. A register-resident operand is used in
 * place; one that has to be loaded goes in %rcx or %rdx, whichever
 * the OTHER operand is not sitting in, so loading one never
 * overwrites the other - `I64 F(I64 a, I64 b, I64 *p, I64 i)
 * { return p[i]; }` reloaded p into %rcx over i
 * (`movq (%rcx,%rcx,8), %rax`). */
static void x86_64AddrRegs(IrCgCtx *ctx, IrInstr *instr, IrValue *addr,
                           const char **base_out, const char **idx_out)
{
    int has_idx = instr->idx && instr->scale;
    const char *base = x86_64HomeReg(addr);
    const char *idx = has_idx ? x86_64HomeReg(instr->idx) : NULL;
    if (!base) {
        base = (idx && !strcmp(idx, "rcx")) ? "rdx" : "rcx";
        x86_64LoadToReg(ctx, addr, base);
    }
    if (has_idx && !idx) {
        idx = !strcmp(base, "rdx") ? "rcx" : "rdx";
        x86_64LoadToReg(ctx, instr->idx, idx);
    }
    *base_out = base;
    *idx_out = idx;
}

/* First of `pref`, %rax, %rcx, %rdx that is neither the base nor the
 * idx register of the address it will be stored through. */
static const char *x86_64ValReg(const char *pref, const char *base_reg,
                                const char *idx_reg)
{
    const char *cands[] = { pref, "rax", "rcx", "rdx" };
    for (int i = 0; i < 4; ++i) {
        if (strcmp(cands[i], base_reg) &&
            (!idx_reg || strcmp(cands[i], idx_reg))) return cands[i];
    }
    loggerPanic("ir-cg-x86_64: no free value register\n");
}

/* The float branch uses ucomisd, whose flags follow unsigned-int
 * semantics. NaN (unordered) sets CF=ZF=PF=1, so to get C's ordered
 * semantics x86_64EmitFCmp turns LT/LE into a swapped GT/GE (a/ae
 * are false when CF=1) and EQ/NE also consult PF. */
static const char *x86_64CcFor(IrCmpKind cmp, int is_float) {
    if (is_float) {
        switch (cmp) {
            case IR_CMP_EQ: return "e";
            case IR_CMP_NE: return "ne";
            case IR_CMP_GT: return "a";
            case IR_CMP_GE: return "ae";
            default:        return NULL;
        }
    }
    switch (cmp) {
        case IR_CMP_EQ:  return "e";
        case IR_CMP_NE:  return "ne";
        case IR_CMP_LT:  return "l";
        case IR_CMP_LE:  return "le";
        case IR_CMP_GT:  return "g";
        case IR_CMP_GE:  return "ge";
        case IR_CMP_ULT: return "b";
        case IR_CMP_ULE: return "be";
        case IR_CMP_UGT: return "a";
        case IR_CMP_UGE: return "ae";
        default:         return NULL;
    }
}

/* Inverted condition for jumping when the cmp is FALSE. */
static const char *x86_64CcInvFor(IrCmpKind cmp, int is_float) {
    if (is_float) {
        switch (cmp) {
            case IR_CMP_EQ: return "ne";
            case IR_CMP_NE: return "e";
            case IR_CMP_GT: return "be";
            case IR_CMP_GE: return "b";
            default:        return NULL;
        }
    }
    switch (cmp) {
        case IR_CMP_EQ:  return "ne";
        case IR_CMP_NE:  return "e";
        case IR_CMP_LT:  return "ge";
        case IR_CMP_LE:  return "g";
        case IR_CMP_GT:  return "le";
        case IR_CMP_GE:  return "l";
        case IR_CMP_ULT: return "ae";
        case IR_CMP_ULE: return "a";
        case IR_CMP_UGT: return "be";
        case IR_CMP_UGE: return "b";
        default:         return NULL;
    }
}

/* Materialise the EFLAGS-encoded comparison result as 0/1 in %rax.
 * setcc only writes the low byte, so we follow with a zero-extension
 * into the full 64-bit register. */
static void x86_64EmitSetCC(IrCgCtx *ctx, IrCmpKind cmp, int is_float) {
    const char *cc = x86_64CcFor(cmp, is_float);
    if (!cc) {
        loggerPanic("ir-cg-x86_64: unsupported cmp kind %s\n",
                    irCmpKindToString(cmp));
    }
    if (is_float && (cmp == IR_CMP_EQ || cmp == IR_CMP_NE)) {
        /* Unordered sets ZF too: EQ also needs PF=0, NE accepts PF=1. */
        int eq = cmp == IR_CMP_EQ;
        aoStrCatFmt(ctx->buf,
                    "set%s    %%al\n\t"
                    "set%s   %%cl\n\t"
                    "%s    %%cl, %%al\n\t"
                    "movzbq  %%al, %%rax\n\t",
                    cc, eq ? "np" : "p ", eq ? "andb" : "orb ");
        return;
    }
    aoStrCatFmt(ctx->buf,
                "set%s    %%al\n\t"
                "movzbq  %%al, %%rax\n\t",
                cc);
}

/* ucomiss/ucomisd of instr->r1 against instr->r2. LT/LE are emitted
 * as the swapped GT/GE so an unordered (NaN) compare is false; returns
 * the cmp kind the flags now encode. */
static IrCmpKind x86_64EmitFCmp(IrCgCtx *ctx, IrInstr *instr, IrCmpKind cmp) {
    const char *op = (int)irValueByteSize(instr->r1) == 4 ? "ucomiss"
                                                          : "ucomisd";
    x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
    x86_64LoadToFpr(ctx, instr->r2, "xmm1");
    if (cmp == IR_CMP_LT || cmp == IR_CMP_LE) {
        /* flags from (xmm1 cmp xmm0): a < b  <=>  b > a */
        aoStrCatFmt(ctx->buf, "%s %%xmm0, %%xmm1\n\t", op);
        return cmp == IR_CMP_LT ? IR_CMP_GT : IR_CMP_GE;
    }
    /* flags from (xmm0 cmp xmm1) at the operand precision. */
    aoStrCatFmt(ctx->buf, "%s %%xmm1, %%xmm0\n\t", op);
    return cmp;
}

/* XMM register names start with x/X; GPR names never do. */
static int x86_64IsXmmName(const char *r) {
    return r && (r[0] == 'x' || r[0] == 'X');
}

static u32 x86_64PinnedSaveBytes(Vec *pinned) {
    return (u32)((pinned->size * 8 + 15) & ~(u64)15);
}

/* Preserve every register a local/param is pinned to. XMM regs can't be
 * pushed, so instead of push/pop we reserve one 8-byte slot per pinned
 * reg (rounded to 16 for SysV alignment) and spill each with movq (GPR)
 * or movsd (XMM). rsp stays at the save-area base for the body; the
 * restore mirror reads the slots back and frees the area. */
static void x86_64SavePinnedRegs(AoStr *buf, Vec *pinned) {
    if (!pinned || pinned->size == 0) return;
    aoStrCatFmt(buf, "subq    $%u, %%rsp\n\t", x86_64PinnedSaveBytes(pinned));
    for (u64 i = 0; i < pinned->size; ++i) {
        AoStr *r = (AoStr *)pinned->entries[i];
        const char *mnem = x86_64IsXmmName(r->data) ? "movsd" : "movq";
        aoStrCatFmt(buf, "%s    %%%s, %i(%%rsp)\n\t",
                    mnem, r->data, (int)(i * 8));
    }
}

static void x86_64RestorePinnedRegs(AoStr *buf, Vec *pinned) {
    if (!pinned || pinned->size == 0) return;
    for (u64 i = 0; i < pinned->size; ++i) {
        AoStr *r = (AoStr *)pinned->entries[i];
        const char *mnem = x86_64IsXmmName(r->data) ? "movsd" : "movq";
        aoStrCatFmt(buf, "%s    %i(%%rsp), %%%s\n\t",
                    mnem, (int)(i * 8), r->data);
    }
    aoStrCatFmt(buf, "addq    $%u, %%rsp\n\t", x86_64PinnedSaveBytes(pinned));
}

/* leaveq is `movq %rbp, %rsp; popq %rbp` in one instruction. Saves a
 * byte over the explicit pair, no other difference. When the matching
 * prologue elided its rbp dance, emit just retq. */
static void x86_64Epilogue(IrCgCtx *ctx) {
    if (ctx->omit_frame) {
        aoStrCatFmt(ctx->buf, "retq\n");
    } else {
        aoStrCatFmt(ctx->buf,
                    "leaveq\n\t"
                    "retq\n");
    }
}

/* Did the last emit already place an epilogue at the tail of buf?
 * Used so we don't double-emit when the function ends with an
 * explicit return. */
static int x86_64HasRet(IrCgCtx *ctx) {
    static const char *with_frame    = "leaveq\n\tretq\n";
    static const char *without_frame = "retq\n";
    const char *needle = ctx->omit_frame ? without_frame : with_frame;
    int nlen = (int)strlen(needle);
    AoStr *buf = ctx->buf;
    if ((int)buf->len < nlen) return 0;
    return memcmp(buf->data + buf->len - nlen, needle, nlen) == 0;
}

static void x86_64BlockLabel(IrCgCtx *ctx, IrBlock *block, char *out, int n) {
    snprintf(out, n, ".LIRBB%d_%u", ctx->fn->uuid, block->id);
}

/* Move a single phi's incoming value into the phi's destination.
 * Dangling values (tmp with no slot) only arise from unreachable
 * incoming edges, zero out for safety. Phis here always materialise
 * via the slot: register-resident phi classification was removed
 * alongside the FUSE_TO_NEXT machinery. */
static void x86_64EmitOnePhi(IrCgCtx *ctx, IrInstr *phi, IrPair *match) {
    IrValue *v = match->ir_value;
    /* A tmp is "dangling" when it has neither a slot nor an
     * allocator-assigned register, which happens for short-circuit
     * shims that compute their value via control flow rather than a
     * real producer. Source it as zero. */
    int v_dangling = irIsTmp(v) &&
                     v->loc.kind != IR_LOC_REG &&
                     !mapHasInt(ctx->fn->ra.id_to_loff, irVarId(v));

    if (irIsFloat(phi->dst->type)) {
        if (v_dangling) {
            aoStrCatFmt(ctx->buf, "xorpd   %%xmm0, %%xmm0\n\t");
        } else {
            x86_64LoadToFpr(ctx, v, "xmm0");
        }
        x86_64StoreFpr(ctx, phi->dst, "xmm0");
        return;
    }
    if (v_dangling) {
        aoStrCatFmt(ctx->buf, "xorq    %%rax, %%rax\n\t");
    } else {
        x86_64LoadToReg(ctx, v, "rax");
    }
    x86_64StoreReg(ctx, phi->dst, "rax");
}

/* Materialise all phis at block `to` from predecessor `from`, ordered
 * so a phi never overwrites a value still needed by another pending
 * phi in the same group (parallel-copy semantics). Cycles among
 * phis would need a temporary swap; we panic loudly if one appears,
 * matching aarch64's behaviour. This is target-agnostic */
#define kMaxPhisX86 32
static void x86_64PhiMaterialise(IrCgCtx *ctx, IrBlock *from, IrBlock *to) {
    if (!to || !from) return;

    IrInstr *phis[kMaxPhisX86];
    IrPair  *pairs[kMaxPhisX86];
    int     done[kMaxPhisX86];
    int n = 0;

    listForEach(to->instructions) {
        IrInstr *I = (IrInstr *)it->value;
        if (I->op == IR_NOP) continue;
        if (I->op != IR_PHI) break;
        if (n >= kMaxPhisX86) {
            loggerPanic("ir-cg-x86_64: too many phis at one block "
                        "(>%d)\n", kMaxPhisX86);
        }
        IrPair *match = NULL;
        if (I->extra.phi_pairs) {
            for (u64 i = 0; i < I->extra.phi_pairs->size; ++i) {
                IrPair *p = vecGet(IrPair *, I->extra.phi_pairs, i);
                if (p->ir_block == from) { match = p; break; }
            }
        }
        if (!match || !match->ir_value) continue;
        phis[n] = I;
        pairs[n] = match;
        done[n] = 0;
        n++;
    }
    if (n == 0) return;

    int emitted = 0;
    while (emitted < n) {
        int progress = 0;
        for (int i = 0; i < n; ++i) {
            if (done[i]) continue;
            int read_by_pending = 0;
            for (int j = 0; j < n; ++j) {
                if (i == j || done[j]) continue;
                IrValue *v = pairs[j]->ir_value;
                if (irIsTmp(v) && irIsTmp(phis[i]->dst) &&
                    irVarId(v) == irDstVarId(phis[i])) {
                    read_by_pending = 1;
                    break;
                }
            }
            if (!read_by_pending) {
                x86_64EmitOnePhi(ctx, phis[i], pairs[i]);
                done[i] = 1;
                emitted++;
                progress = 1;
            }
        }
        if (!progress) {
            loggerPanic("ir-cg-x86_64: phi materialisation cycle "
                        "unimplemented\n");
        }
    }
}

/* SysV AMD64 has 6 int arg regs (rdi/rsi/rdx/rcx/r8/r9) and 8 FP
 * (xmm0-7); anything past that goes on the stack right-to-left
 * (we lay them out left-to-right at [rsp + i*8] which produces the
 * same memory image once the call instruction's return-address
 * push lands on top).
 *
 * ktm's variadic convention sends every arg past var_arg_start
 * through the stack regardless of register availability (unlike
 * C-style variadic, which uses normal regs until they run out).
 *
 * Unlike aarch64, x86_64 has no separate indirect-result-location
 * register: when the callee returns by hidden pointer, args[0] is
 * that pointer and lives in %rdi like any first int arg. So we
 * don't special-case it here. */
static s32 x86_64PartitionCallArgs(u8 *is_stack, u64 n, Vec *args,
                                   int ktm_variadic,
                                   int var_arg_start)
{
    s32 n_stack_total = 0;
    if (ktm_variadic && (s64)n > var_arg_start) {
        for (u64 i = (u64)var_arg_start; i < n; ++i) {
            is_stack[i] = 1;
            n_stack_total++;
        }
    }
    int int_idx = 0, float_idx = 0;
    for (u64 i = 0; i < n; ++i) {
        if (is_stack[i]) continue;
        IrValue *a = vecGet(IrValue *, args, i);
        if (irIsFloat(a->type)) {
            if (float_idx >= 8) {
                is_stack[i] = 1;
                n_stack_total++;
            } else {
                float_idx++;
            }
        } else {
            if (int_idx >= 6) {
                is_stack[i] = 1;
                n_stack_total++;
            } else {
                int_idx++;
            }
        }
    }
    return n_stack_total;
}

/* ---------------- System V struct-by-value passing ----------------
 *
 * Mirrors the aarch64 AAPCS path for the x86_64 SysV ABI. Each by-value
 * aggregate is classified into eightbytes (INTEGER -> GP reg, SSE -> XMM
 * reg); >16-byte aggregates are MEMORY class and passed on the stack by
 * value. Struct *returns* keep using the legacy hidden-out-pointer (which
 * the IR passes as args[0] -> %rdi) + memcpy. Register-only overflow and
 * scalar stack spilling beyond 6 GP / 8 SSE panic, matching the support
 * window of the other backends' struct paths. */

static const char *const kSysvGpArg[6] = {
    "rdi", "rsi", "rdx", "rcx", "r8", "r9"
};

static int x86_64IsByvalStruct(AstType *t) {
    return t && (t->kind == AST_TYPE_CLASS || t->kind == AST_TYPE_UNION) &&
           !t->is_intrinsic;
}

/* SysV classification of a by-value aggregate with the per-class eightbyte
 * counts pre-summed, so the caller (sizing + emit) and the callee prologue
 * share one classification and one reg/stack decision. */
typedef struct {
    SysvClass cls[2];  /* per-eightbyte class (valid when !memory) */
    int neb;           /* number of eightbytes */
    int gp;            /* INTEGER eightbytes -> GP regs  (0 if memory) */
    int sse;           /* SSE eightbytes -> XMM regs     (0 if memory) */
    int memory;        /* 1 -> >16B / otherwise passed on the stack */
} X86SysvAgg;

static void x86_64ClassifyAgg(AstType *t, X86SysvAgg *a) {
    a->gp = a->sse = a->neb = 0;
    a->memory = astSysvClassify(t, a->cls, &a->neb);
    if (!a->memory) {
        for (int e = 0; e < a->neb; ++e) {
            if (a->cls[e] == SYSV_INTEGER) a->gp++;
            else a->sse++;
        }
    }
}

/* True when the whole aggregate still fits in the remaining argument
 * registers; otherwise it is passed on the stack by value. */
static int x86_64AggInRegs(const X86SysvAgg *a, int ngp, int nsse) {
    return !a->memory && ngp + a->gp <= 6 && nsse + a->sse <= 8;
}

/* The widest power-of-two access, at most 8 bytes, that fits `n`. */
static u32 x86_64ChunkSize(int n) {
    return n >= 8 ? 8 : n >= 4 ? 4 : n >= 2 ? 2 : 1;
}

/* Load the `n` (1..8) bytes at `disp(%base)` into %reg. An eightbyte of
 * 3, 5, 6 or 7 bytes (a class of that size, or the tail of one of
 * 11, 13..15) is read as 4/2/1-byte pieces ORed together through
 * %scratch: reading the whole eightbyte could run past the object. */
static void x86_64LoadBytes(AoStr *buf, const char *reg, const char *base,
                            s32 disp, int n, const char *scratch)
{
    if (x86_64ChunkSize(n) == (u32)n) {
        x86_64DerefLoadWidth(buf, (u32)n, reg, base, NULL, 0, disp);
        return;
    }
    char r32[8];
    char mem[48];
    x86_64RegForWidth(reg, 4, r32, sizeof(r32));
    /* The first piece is the 4- or 2-byte one, zero-extended. */
    int o = 0;
    while (o < n) {
        u32 c = x86_64ChunkSize(n - o);
        x86_64FmtMemOperand(mem, sizeof(mem), disp + o, base, NULL, 0);
        if (o == 0) {
            if (c == 4) aoStrCatFmt(buf, "movl    %s, %%%s\n\t", mem, r32);
            else x86_64DerefLoadWidth(buf, c, reg, base, NULL, 0, disp);
        } else {
            x86_64DerefLoadWidth(buf, c, scratch, base, NULL, 0, disp + o);
            aoStrCatFmt(buf, "shlq    $%i, %%%s\n\t", o * 8, scratch);
            aoStrCatFmt(buf, "orq     %%%s, %%%s\n\t", scratch, reg);
        }
        o += (int)c;
    }
}

/* Store the low `n` (1..8) bytes of %reg to `disp(%base)`, as 4/2/1-byte
 * pieces shifted down through %scratch when `n` is not a power of two;
 * %reg is left intact. */
static void x86_64StoreBytes(AoStr *buf, const char *reg, const char *base,
                             s32 disp, int n, const char *scratch)
{
    int o = 0;
    while (o < n) {
        u32 c = x86_64ChunkSize(n - o);
        const char *r = reg;
        if (o != 0) {
            aoStrCatFmt(buf, "movq    %%%s, %%%s\n\t", reg, scratch);
            aoStrCatFmt(buf, "shrq    $%i, %%%s\n\t", o * 8, scratch);
            r = scratch;
        }
        x86_64DerefStoreWidth(buf, c, r, base, NULL, 0, disp + o);
        o += (int)c;
    }
}

/* Copy `n` bytes from `src(%src_base)` to `dst(%dst_base)` through %rax,
 * in 8/4/2/1-byte pieces. */
static void x86_64CopyBytes(AoStr *buf, const char *src_base, s32 src,
                            const char *dst_base, s32 dst, int n)
{
    int o = 0;
    while (o < n) {
        u32 c = x86_64ChunkSize(n - o);
        x86_64DerefLoadWidth(buf, c, "rax", src_base, NULL, 0, src + o);
        x86_64DerefStoreWidth(buf, c, "rax", dst_base, NULL, 0, dst + o);
        o += (int)c;
    }
}

static void x86_64EmitSysvCall(IrCgCtx *ctx, IrInstr *instr, Vec *args,
                               AoStr *fname, int indirect)
{
    AoStr *buf = ctx->buf;
    u64 n = args ? args->size : 0;

    /* A <=16-byte aggregate return comes back in registers, so its
     * destination buffer (args[0]) is NOT an ABI argument: skip it and copy
     * the result registers into it after the call. A >16-byte (MEMORY)
     * return keeps args[0] as the hidden out-pointer in rdi. */
    AstType *ret_st = (instr->flags & IRCG_CALL_AGG_RETURN) && instr->dst
                      ? instr->dst->byval_struct_type : NULL;
    int reg_ret = ret_st && ret_st->size <= 16;
    u64 a0 = reg_ret ? 1 : 0;

    /* Pre-pass: size the stack-argument area. An aggregate is stack-passed
     * (MEMORY) when it is >16B or doesn't fit in the remaining registers;
     * a scalar spills when its register class is exhausted. */
    int nsaa_total = 0;
    {
        int g = 0;
        int s = 0;
        for (u64 i = a0; i < n; ++i) {
            IrValue *a = vecGet(IrValue *, args, i);
            if (a->byval_struct_type) {
                X86SysvAgg ag; x86_64ClassifyAgg(a->byval_struct_type, &ag);
                if (x86_64AggInRegs(&ag, g, s)) {
                    g += ag.gp;
                    s += ag.sse;
                } else {
                    nsaa_total += (a->byval_struct_type->size + 7) & ~7;
                }
            } else if (irIsFloat(a->type)) {
                if (s < 8) s++; else nsaa_total += 8;
            } else {
                if (g < 6) g++; else nsaa_total += 8;
            }
        }
    }

    /* Reserve 8 bytes above the stack-arg area to save the register-return
     * destination pointer across the call (caller-saved regs are clobbered). */
    int dest_save_off = nsaa_total;
    int stack_bytes = (nsaa_total + (reg_ret ? 16 : 0) + 15) & ~15;
    if (stack_bytes > 0)
        aoStrCatFmt(buf, "subq    $%i, %%rsp\n\t", stack_bytes);
    int stack_off = 0;

    if (reg_ret) {
        /* Compute the destination buffer address into r10 (before the arg
         * loop clobbers r10) and stash it across the call. */
        x86_64LoadToReg(ctx, vecGet(IrValue *, args, 0), "r10");
        aoStrCatFmt(buf, "movq    %%r10, %i(%%rsp)\n\t", dest_save_off);
    }

    int ngp = 0, nsse = 0;
    for (u64 i = a0; i < n; ++i) {
        IrValue *a = vecGet(IrValue *, args, i);
        if (a->byval_struct_type) {
            AstType *t = a->byval_struct_type;
            X86SysvAgg ag; x86_64ClassifyAgg(t, &ag);
            /* The arg value is the struct's address - park it in %r10
             * (not an arg reg, not %r11 = indirect call target). */
            x86_64LoadToReg(ctx, a, "r10");
            if (!x86_64AggInRegs(&ag, ngp, nsse)) {
                /* On the stack by value. */
                x86_64CopyBytes(buf, "r10", 0, "rsp", stack_off, t->size);
                stack_off += (t->size + 7) & ~7;
                continue;
            }
            for (int e = 0; e < ag.neb; ++e) {
                int off = e * 8;
                int rem = t->size - off;
                u32 sz = rem >= 8 ? 8 : (u32)rem;
                if (ag.cls[e] == SYSV_SSE) {
                    char mem_s[48];
                    x86_64FmtMemOperand(mem_s, sizeof(mem_s), off, "r10",
                                        NULL, 0);
                    aoStrCatFmt(buf, "%s   %s, %%xmm%i\n\t",
                                x86_64FpMov(sz == 4 ? 4 : 8), mem_s, nsse);
                    nsse++;
                } else {
                    /* %rax is free: %r10 holds the struct, %r11 the
                     * indirect call target. */
                    x86_64LoadBytes(buf, kSysvGpArg[ngp], "r10", off,
                                    (int)sz, "rax");
                    ngp++;
                }
            }
            continue;
        }
        if (irIsFloat(a->type)) {
            if (nsse < 8) {
                char xmm[16];
                snprintf(xmm, sizeof(xmm), "xmm%d", nsse++);
                x86_64LoadToFpr(ctx, a, xmm);
            } else {
                x86_64LoadToFpr(ctx, a, "xmm0");
                aoStrCatFmt(buf, "%s   %%xmm0, %i(%%rsp)\n\t",
                            x86_64FpMov((int)irValueByteSize(a)), stack_off);
                stack_off += 8;
            }
        } else {
            if (ngp < 6) {
                x86_64LoadToReg(ctx, a, kSysvGpArg[ngp++]);
            } else {
                x86_64LoadToReg(ctx, a, "rax");
                aoStrCatFmt(buf, "movq    %%rax, %i(%%rsp)\n\t", stack_off);
                stack_off += 8;
            }
        }
    }

    if (indirect) {
        aoStrCatFmt(buf, "callq   *%%r11\n\t");
    } else {
        aoStrCatFmt(buf, "callq   %s\n\t",
                    asmNormaliseFunctionName(ctx->cc, fname));
    }

    if (reg_ret) {
        /* Reload the destination buffer and copy the SysV result registers
         * (INTEGER eightbytes -> rax,rdx; SSE -> xmm0,xmm1) into it. */
        X86SysvAgg ag; x86_64ClassifyAgg(ret_st, &ag);
        const char *gpr[2] = { "rax", "rdx" };
        int gpi = 0, ssei = 0;
        aoStrCatFmt(buf, "movq    %i(%%rsp), %%r10\n\t", dest_save_off);
        for (int e = 0; e < ag.neb; ++e) {
            int off = e * 8, rem = ret_st->size - off;
            u32 sz = rem >= 8 ? 8 : (u32)rem;
            if (ag.cls[e] == SYSV_SSE) {
                char mem[48];
                x86_64FmtMemOperand(mem, sizeof(mem), off, "r10", NULL, 0);
                aoStrCatFmt(buf, "%s   %%xmm%i, %s\n\t",
                            x86_64FpMov(sz == 4 ? 4 : 8), ssei, mem);
                ssei++;
            } else {
                x86_64StoreBytes(buf, gpr[gpi], "r10", off, (int)sz, "r11");
                gpi++;
            }
        }
    }

    if (stack_bytes > 0)
        aoStrCatFmt(buf, "addq    $%i, %%rsp\n\t", stack_bytes);

    if (reg_ret) {
        /* dst = destination buffer address (in r10). */
        if (instr->dst) x86_64SpillDst(ctx, instr, "r10");
    } else if (instr->dst && instr->dst->type != IR_TYPE_VOID &&
        (instr->dst->kind == IR_VAL_TMP ||
         instr->dst->kind == IR_VAL_LOCAL ||
         instr->dst->kind == IR_VAL_PARAM))
    {
        if (irIsFloat(instr->dst->type)) x86_64SpillDstFpr(ctx, instr, "xmm0");
        else                             x86_64SpillDst(ctx, instr, "rax");
    }
}

/* Callee entry: unpack by-value struct params (arrived in GP/XMM regs, or
 * on the stack for MEMORY class) into their frame slots. */
static void x86_64EmitSysvParamPrologue(IrCgCtx *ctx, Ast *ast) {
    if (!ast->params) return;
    AoStr *buf = ctx->buf;
    int ngp = 0, nsse = 0;

    AstType *rt = ast->type ? ast->type->rettype : NULL;
    /* Only an INDIRECT (>16-byte) struct return uses a hidden out-pointer
     * in rdi; a <=16-byte aggregate comes back in registers. */
    if (x86_64IsByvalStruct(rt) && rt->size > 16) ngp = 1; /* hidden ret ptr */
    int incoming_off = 0; /* byte cursor into the incoming stack-arg area */

    for (u64 i = 0; i < ast->params->size; ++i) {
        Ast *p = vecGet(Ast *, ast->params, i);
        if (p->kind == AST_VAR_ARGS) break;
        AstType *t = p->type;

        /* loff is only valid when the param is used; unreferenced params
         * get no slot. Still advance the ABI counters/cursor, skip stores. */
        IrValue *slot = irFnGetVar(ctx->fn, irGetParamId(p));
        int has_slot = slot &&
            mapHasInt(ctx->fn->ra.id_to_loff, irVarId(slot));
        int loff = has_slot ? irCgGetLoff(&ctx->fn->ra, slot) : 0;

        if (!x86_64IsByvalStruct(t)) {
            int is_float = (t->kind == AST_TYPE_FLOAT);
            if (is_float ? (nsse < 8) : (ngp < 6)) {
                if (is_float) nsse++; else ngp++; /* reg: arrive+store */
            } else {
                if (has_slot) {
                    u32 sz = (u32)t->size;
                    x86_64DerefLoadWidth(buf, sz, "rax", "rbp", NULL, 0,
                                         16 + incoming_off);
                    x86_64FrameStoreWidth(buf, loff, sz, "rax");
                }
                incoming_off += 8;
            }
            continue;
        }

        X86SysvAgg ag; x86_64ClassifyAgg(t, &ag);
        if (!x86_64AggInRegs(&ag, ngp, nsse)) {
            /* MEMORY / register overflow: arrived at [rbp+16+incoming_off]. */
            if (has_slot) {
                x86_64CopyBytes(buf, "rbp", 16 + incoming_off, "rbp", loff,
                                t->size);
            }
            incoming_off += (t->size + 7) & ~7;
            continue;
        }

        for (int e = 0; e < ag.neb; ++e) {
            int off = e * 8;
            int rem = t->size - off;
            u32 sz = rem >= 8 ? 8 : (u32)rem;
            if (ag.cls[e] == SYSV_SSE) {
                if (has_slot) {
                    char mem_s[48];
                    x86_64FmtMemOperand(mem_s, sizeof(mem_s), loff + off,
                                        "rbp", NULL, 0);
                    aoStrCatFmt(buf, "%s   %%xmm%i, %s\n\t",
                                x86_64FpMov(sz == 4 ? 4 : 8), nsse, mem_s);
                }
                nsse++;
            } else {
                if (has_slot)
                    x86_64StoreBytes(buf, kSysvGpArg[ngp], "rbp", loff + off,
                                     (int)sz, "r11");
                ngp++;
            }
        }
    }
}

static void x86_64EmitInstr(IrCgCtx *ctx, IrInstr *instr) {
    switch (instr->op) {
        /* No-ops at codegen: ALLOCA/GEP/PHI/LABEL are all resolved
         * elsewhere (alloca slots are assigned during layout, GEPs
         * fold into addressing modes, PHIs materialise at predecessor
         * edges, LABEL is dropped during IR resolution). */
        case IR_NOP:
        case IR_ALLOCA:
        case IR_PHI:
        case IR_GEP:
        case IR_LABEL:
            break;

        case IR_LOAD: {
            if (instr->r1 && instr->r1->pinned_reg) {
                aoStrCatFmt(ctx->buf, "movq    %%%s, %%rax\n\t",
                            instr->r1->pinned_reg->data);
                x86_64SpillDst(ctx, instr, "rax");
                break;
            }

            /* Float-typed loads go straight to xmm0 with movsd so a
             * fused FP consumer reads from the result reg directly. */
            if (instr->dst && irIsFloat(instr->dst->type)) {
                int loff = irCgGetLoff(&ctx->fn->ra, instr->r1);
                aoStrCatFmt(ctx->buf,
                            "%s   %i(%%rbp), %%xmm0\n\t",
                            x86_64FpMov((int)irValueByteSize(instr->dst)), loff);
                x86_64SpillDstFpr(ctx, instr, "xmm0");
                break;
            }

            int loff = irCgGetLoff(&ctx->fn->ra, instr->r1);
            u32 size = instr->dst ? instr->dst->as.var.size : 8;
            x86_64Load(ctx->buf, "rax", size, loff);
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_STORE: {
            /* If r1 already lives in a register (e.g. a param's ABI
             * arg reg at function entry), store from there directly
             * instead of bouncing through %rax / %xmm0. */
            const char *src_reg = NULL;
            if (instr->r1 && instr->r1->loc.kind == IR_LOC_REG &&
                instr->r1->loc.as.reg)
            {
                src_reg = instr->r1->loc.as.reg->data;
            }

            /* Float r1 takes the FP path so a fused producer can hand
             * the value over in xmm0 (movsd xmm0->slot, no rax bounce). */
            if (instr->r1 && irIsFloat(instr->r1->type))
            {
                if (!src_reg) {
                    x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
                    src_reg = "xmm0";
                }
                x86_64StoreFprSized(ctx, instr->dst, src_reg,
                                    (int)irValueByteSize(instr->r1));
                break;
            }

            if (!src_reg) {
                x86_64LoadFirstSrc(ctx, instr, instr->r1);
                src_reg = "rax";
            }
            /* Pinned destination lives in a register, not a slot - so
             * check it before asking for a (nonexistent) loff. */
            if (instr->dst && instr->dst->pinned_reg) {
                aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t",
                            src_reg, instr->dst->pinned_reg->data);
                break;
            }
            /* Width comes from the value's size, not the destination's:
             * when dst is a GEP'd pointer to a sub-word slot (e.g.
             * `I8 a[3]`), forcing an 8-byte store would overrun the
             * slot and clobber adjacent stack data. */
            int loff = irCgGetLoff(&ctx->fn->ra, instr->dst);
            u32 size = irValueByteSize(instr->r1);
            x86_64FrameStoreWidth(ctx->buf, loff, size, src_reg);
            break;
        }

        case IR_LOAD_DEREF: {
            /* Global address folded in by irFoldGlobalDeref: read the
             * symbol RIP-relative in a single instruction. Disp on a
             * GLOBAL operand has no current use (the IADD fusion only
             * runs when r1 is a TMP), so we assert it's zero. */
            if (instr->r1 && instr->r1->kind == IR_VAL_GLOBAL) {
                const char *sym = asmNormaliseGlobalLabel(ctx->cc,
                        instr->r1->as.global.name)->data;
                if (instr->dst && irIsFloat(instr->dst->type)) {
                    aoStrCatFmt(ctx->buf, "%s   %s(%%rip), %%xmm0\n\t",
                                x86_64FpMov((int)irValueByteSize(instr->dst)),
                                sym);
                    x86_64SpillDstFpr(ctx, instr, "xmm0");
                } else {
                    aoStrCatFmt(ctx->buf, "movq    %s(%%rip), %%rax\n\t",
                                sym);
                    x86_64SpillDst(ctx, instr, "rax");
                }
                break;
            }
            /* Address may already live in %rax from a fused producer.
             * Read r1's loc to skip the slot reload in that case. */
            const char *base_reg, *idx_reg;
            x86_64AddrRegs(ctx, instr, instr->r1, &base_reg, &idx_reg);
            if (instr->dst && irIsFloat(instr->dst->type)) {
                char mem[48];
                x86_64FmtMemOperand(mem, sizeof(mem), instr->disp,
                                    base_reg, idx_reg, instr->scale);
                aoStrCatFmt(ctx->buf, "%s   %s, %%xmm0\n\t",
                            x86_64FpMov((int)irValueByteSize(instr->dst)), mem);
                x86_64SpillDstFpr(ctx, instr, "xmm0");
                break;
            }
            u32 size = instr->dst ? instr->dst->as.var.size : 8;
            x86_64DerefLoadWidth(ctx->buf, size, "rax", base_reg,
                                 idx_reg, instr->scale, instr->disp);
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_STORE_DEREF: {
            /* Global address folded in by irFoldGlobalDeref: write
             * directly to sym(%rip). */
            if (instr->dst && instr->dst->kind == IR_VAL_GLOBAL) {
                const char *sym = asmNormaliseGlobalLabel(ctx->cc,
                        instr->dst->as.global.name)->data;
                u32 sz = irValueByteSize(instr->r1);
                if (instr->r1 && irIsFloat(instr->r1->type)) {
                    x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
                    aoStrCatFmt(ctx->buf, "%s   %%xmm0, %s(%%rip)\n\t",
                                x86_64FpMov((int)irValueByteSize(instr->r1)), sym);
                    break;
                }
                s64 imm;
                if (x86_64IsImm32(instr->r1, &imm)) {
                    const char *mnem = x86_64GetSizedMov(sz);
                    aoStrCatFmt(ctx->buf, "%s    $%I, %s(%%rip)\n\t",
                                mnem, (s64)imm, sym);
                    break;
                }
                x86_64LoadFirstSrc(ctx, instr, instr->r1);
                x86_64DerefStoreGlobal(ctx->buf, sz, "rax", sym);
                break;
            }

            /* Address may already live in a register thanks to the
             * peephole's loc-pinning. Pull it out of there directly
             * instead of bouncing through a slot reload. */
            int addr_in_reg = x86_64HomeReg(instr->dst) != NULL;
            const char *base_reg, *idx_reg;
            x86_64AddrRegs(ctx, instr, instr->dst, &base_reg, &idx_reg);
            /* Float r1: value through xmm0. */
            if (instr->r1 && irIsFloat(instr->r1->type)) {
                x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
                char mem[48];
                x86_64FmtMemOperand(mem, sizeof(mem), instr->disp,
                                    base_reg, idx_reg, instr->scale);
                aoStrCatFmt(ctx->buf, "%s   %%xmm0, %s\n\t",
                            x86_64FpMov((int)irValueByteSize(instr->r1)), mem);
                break;
            }
            u32 sz = irValueByteSize(instr->r1);
            /* Immediate value: lay the constant down directly through
             * the address register (skip the rax bounce). */
            s64 imm;
            int has_imm = x86_64IsImm32(instr->r1, &imm);
            if (has_imm) {
                x86_64DerefStoreImm(ctx->buf, sz, imm, base_reg,
                                    idx_reg, instr->scale, instr->disp);
                break;
            }
            /* General path: pick a val_reg that conflicts with
             * neither base_reg nor idx_reg. Defaults are rcx for the
             * "addr already in a reg" case, otherwise rax; bump to
             * rdx when an SIB-pinned idx has stolen our chosen slot
             * (e.g. idx fused from an immediate producer landed in
             * %rax). */
            const char *val_reg = x86_64ValReg(addr_in_reg ? "rcx" : "rax",
                                               base_reg, idx_reg);
            x86_64LoadToReg(ctx, instr->r1, val_reg);
            x86_64DerefStoreWidth(ctx->buf, sz, val_reg, base_reg,
                                  idx_reg, instr->scale, instr->disp);
            break;
        }

        case IR_RMW_DEREF: {
            /* `*addr <op>= r1`. Build the memory-operand string for
             * the addressing mode, then emit through one shared
             * emitter that picks inc/dec / `op $imm, mem` / `op
             * %reg, mem` based on r1. */
            u32 sz = irValueByteSize(instr->r1);
            IrOp rop = instr->extra.rmw_op;
            char mem_buf[64];
            char *mem = mem_buf;
            const char *val_reg = "rax";

            if (instr->dst && instr->dst->kind == IR_VAL_GLOBAL) {
                /* any length of name (see x86_64DerefStoreGlobal) */
                mem = mprintf("%s(%%rip)",
                              asmNormaliseGlobalLabel(ctx->cc,
                                      instr->dst->as.global.name)->data);
            } else {
                int addr_in_reg = x86_64HomeReg(instr->dst) != NULL;
                const char *base_reg, *idx_reg;
                x86_64AddrRegs(ctx, instr, instr->dst, &base_reg, &idx_reg);
                x86_64FmtMemOperand(mem_buf, sizeof(mem_buf), instr->disp,
                                    base_reg, idx_reg, instr->scale);
                /* Pick a val_reg not aliased with base/idx. */
                val_reg = x86_64ValReg(addr_in_reg ? "rcx" : "rax",
                                       base_reg, idx_reg);
            }

            const char *sfx = x86_64SizedSuffix(sz);
            s64 imm;
            if (x86_64IsImm32(instr->r1, &imm)) {
                int inc = (rop == IR_IADD && imm ==  1) ||
                          (rop == IR_ISUB && imm == -1);
                int dec = (rop == IR_IADD && imm == -1) ||
                          (rop == IR_ISUB && imm ==  1);
                if (inc || dec) {
                    aoStrCatFmt(ctx->buf, "%s%s    %s\n\t",
                                inc ? "inc" : "dec", sfx, mem);
                } else {
                    aoStrCatFmt(ctx->buf, "%s%s    $%I, %s\n\t",
                                x86_64RmwMnem(rop), sfx, (s64)imm, mem);
                }
                break;
            }
            x86_64LoadToReg(ctx, instr->r1, val_reg);
            char sub[8];
            x86_64RegForWidth(val_reg, (int)sz, sub, sizeof(sub));
            aoStrCatFmt(ctx->buf, "%s%s    %%%s, %s\n\t",
                        x86_64RmwMnem(rop), sfx, sub, mem);
            break;
        }

        case IR_LEA: {
            x86_64Lea(ctx, instr, "rax");
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_IADD:
        case IR_ISUB:
        case IR_AND:
        case IR_OR:
        case IR_XOR: {
            const char *op;
            switch (instr->op) {
                case IR_IADD: op = "addq"; break;
                case IR_ISUB: op = "subq"; break;
                case IR_AND:  op = "andq"; break;
                case IR_OR:   op = "orq";  break;
                case IR_XOR:  op = "xorq"; break;
                default: loggerPanic("Invalid op: %s\n",
                                     irOpcodeToString(instr));
            }
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            s64 imm;
            if (x86_64IsImm32(instr->r2, &imm)) {
                /* +/-1 with IADD/ISUB folds to incq/decq (3 bytes vs
                 * 4 for `addq $1, %rax`). Logical ops have no such
                 * single-operand form, so they stay on `op $imm`. */
                if ((instr->op == IR_IADD && imm == 1) ||
                    (instr->op == IR_ISUB && imm == -1))
                {
                    aoStrCatFmt(ctx->buf, "incq    %%rax\n\t");
                } else if ((instr->op == IR_IADD && imm == -1) ||
                           (instr->op == IR_ISUB && imm == 1))
                {
                    aoStrCatFmt(ctx->buf, "decq    %%rax\n\t");
                } else {
                    aoStrCatFmt(ctx->buf, "%s    $%I, %%rax\n\t",
                                op, (s64)imm);
                }
            } else if (instr->r2->loc.kind == IR_LOC_REG &&
                       instr->r2->loc.as.reg)
            {
                /* r2 already lives in a register (typically an ABI
                 * arg reg from a forwarded param store). Use it as
                 * the source operand directly, skips the rcx bounce. */
                aoStrCatFmt(ctx->buf, "%s    %%%s, %%rax\n\t",
                            op, instr->r2->loc.as.reg->data);
            } else {
                x86_64LoadToReg(ctx, instr->r2, "rcx");
                aoStrCatFmt(ctx->buf, "%s    %%rcx, %%rax\n\t", op);
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_IMUL: {
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            s64 imm;
            if (x86_64IsImm32(instr->r2, &imm)) {
                /* imulq's 3-operand form with a 32-bit immediate:
                 * dst = src * imm. */
                aoStrCatFmt(ctx->buf, "imulq   $%I, %%rax, %%rax\n\t",
                            (s64)imm);
            } else if (instr->r2->loc.kind == IR_LOC_REG &&
                       instr->r2->loc.as.reg)
            {
                aoStrCatFmt(ctx->buf, "imulq   %%%s, %%rax\n\t",
                            instr->r2->loc.as.reg->data);
            } else {
                x86_64LoadToReg(ctx, instr->r2, "rcx");
                aoStrCatFmt(ctx->buf, "imulq   %%rcx, %%rax\n\t");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_IDIV:
        case IR_UDIV:
        case IR_IREM:
        case IR_UREM: {
            /* idiv/div divide rdx:rax by the source operand.
             * Sign-extend rax into rdx with cqo for signed, or zero
             * rdx for unsigned. Quotient lands in rax, remainder in
             * rdx; we move rdx into rax for the REM ops so SpillDst
             * always writes from rax. */
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            x86_64LoadToReg(ctx, instr->r2, "rcx");
            if (instr->op == IR_IDIV || instr->op == IR_IREM) {
                aoStrCatFmt(ctx->buf,
                            "cqo\n\t"
                            "idivq   %%rcx\n\t");
            } else {
                aoStrCatFmt(ctx->buf,
                            "xorq    %%rdx, %%rdx\n\t"
                            "divq    %%rcx\n\t");
            }
            if (instr->op == IR_IREM || instr->op == IR_UREM) {
                aoStrCatFmt(ctx->buf, "movq    %%rdx, %%rax\n\t");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_SHL:
        case IR_SHR:
        case IR_SAR: {
            const char *op = (instr->op == IR_SHL) ? "shlq"
                           : (instr->op == IR_SAR) ? "sarq"
                                                   : "shrq";
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            if (irIsConstInt(instr->r2) &&
                instr->r2->as._i64 >= 0 &&
                instr->r2->as._i64 <= 63)
            {
                aoStrCatFmt(ctx->buf, "%s    $%I, %%rax\n\t",
                            op, (s64)instr->r2->as._i64);
            } else {
                /* Variable shift count must live in %cl. */
                x86_64LoadToReg(ctx, instr->r2, "rcx");
                aoStrCatFmt(ctx->buf, "%s    %%cl, %%rax\n\t", op);
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_INEG:
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "negq    %%rax\n\t");
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_NOT:
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "notq    %%rax\n\t");
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_ROTL:
        case IR_ROTR: {
            int w32 = instr->flags & IRCG_BITOP_32;
            const char *op = instr->op == IR_ROTL ? (w32 ? "roll" : "rolq")
                                                  : (w32 ? "rorl" : "rorq");
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            if (irIsConstInt(instr->r2)) {
                aoStrCatFmt(ctx->buf, "%s    $%I, %%%s\n\t", op,
                            (s64)(instr->r2->as._i64 & (w32 ? 31 : 63)),
                            w32 ? "eax" : "rax");
            } else {
                x86_64LoadToReg(ctx, instr->r2, "rcx");
                aoStrCatFmt(ctx->buf, "%s    %%cl, %%%s\n\t", op, w32 ? "eax" : "rax");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_BSWAP:
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            if (instr->flags & IRCG_BITOP_16) {
                aoStrCatFmt(ctx->buf, "rolw    $8, %%ax\n\tmovzwl  %%ax, %%eax\n\t");
            } else if (instr->flags & IRCG_BITOP_32) {
                aoStrCatFmt(ctx->buf, "bswapl  %%eax\n\t");
            } else {
                aoStrCatFmt(ctx->buf, "bswapq  %%rax\n\t");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_CLZ:
            /* bsr gives the top set bit's index (and sets ZF for 0, its
             * result then undefined): width-1 - index, or the width */
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            if (instr->flags & IRCG_BITOP_32) {
                aoStrCatFmt(ctx->buf, "movl    $63, %%ecx\n\tbsrl    %%eax, %%eax\n\t"
                                      "cmovzl  %%ecx, %%eax\n\txorl    $31, %%eax\n\t");
            } else {
                aoStrCatFmt(ctx->buf, "movl    $127, %%ecx\n\tbsrq    %%rax, %%rax\n\t"
                                      "cmovzq  %%rcx, %%rax\n\txorq    $63, %%rax\n\t");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_CTZ:
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            if (instr->flags & IRCG_BITOP_32) {
                aoStrCatFmt(ctx->buf, "movl    $32, %%ecx\n\tbsfl    %%eax, %%eax\n\t"
                                      "cmovzl  %%ecx, %%eax\n\t");
            } else {
                aoStrCatFmt(ctx->buf, "movl    $64, %%ecx\n\tbsfq    %%rax, %%rax\n\t"
                                      "cmovzq  %%rcx, %%rax\n\t");
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_FADD:
        case IR_FSUB:
        case IR_FMUL:
        case IR_FDIV: {
            int single = (int)irValueByteSize(instr->dst) == 4;
            const char *op = (instr->op == IR_FADD) ? (single ? "addss" : "addsd")
                           : (instr->op == IR_FSUB) ? (single ? "subss" : "subsd")
                           : (instr->op == IR_FMUL) ? (single ? "mulss" : "mulsd")
                           : (single ? "divss" : "divsd");
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            x86_64LoadToFpr(ctx, instr->r2, "xmm1");
            aoStrCatFmt(ctx->buf, "%s   %%xmm1, %%xmm0\n\t", op);
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;
        }

        case IR_FNEG: {
            /* No fneg on SSE - flip the sign bit. For F64 XOR with
             * the shared 0x8000000000000000 `sign_bit` global; for F32
             * flip bit 31 via a gpr round-trip (self-contained, no
             * extra 32-bit mask global needed). */
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            if ((int)irValueByteSize(instr->dst) == 4) {
                aoStrCatFmt(ctx->buf,
                            "movd    %%xmm0, %%eax\n\t"
                            "xorl    $0x80000000, %%eax\n\t"
                            "movd    %%eax, %%xmm0\n\t");
            } else {
                aoStrCatFmt(ctx->buf,
                            "xorpd   sign_bit(%%rip), %%xmm0\n\t");
            }
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;
        }

        case IR_ICMP: {
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            s64 imm;
            if (x86_64IsImm32(instr->r2, &imm)) {
                /* AT&T cmpq A, B computes B - A; cc names mirror the
                 * natural ordering (e.g. cmpq $5, %rax + setg = rax>5). */
                aoStrCatFmt(ctx->buf, "cmpq    $%I, %%rax\n\t", (s64)imm);
            } else {
                x86_64LoadToReg(ctx, instr->r2, "rcx");
                aoStrCatFmt(ctx->buf, "cmpq    %%rcx, %%rax\n\t");
            }
            x86_64EmitSetCC(ctx, instr->extra.cmp_kind, 0);
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_FCMP: {
            IrCmpKind kind = x86_64EmitFCmp(ctx, instr, instr->extra.cmp_kind);
            x86_64EmitSetCC(ctx, kind, 1);
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        /* IR_TRUNC, SITOFP, UITOFP, FPTOSI, FPTOUI, BITCAST are
         * implemented. The remaining ZEXT/SEXT/FPTRUNC/FPEXT/
         * PTRTOINT/INTTOPTR aren't currently emitted by the IR
         * builder for ktm programs. */
        case IR_TRUNC: {
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            u32 sz = instr->dst ? instr->dst->as.var.size : 8;
            switch (sz) {
                case 1: aoStrCatFmt(ctx->buf, "movzbq  %%al, %%rax\n\t"); break;
                case 2: aoStrCatFmt(ctx->buf, "movzwq  %%ax, %%rax\n\t"); break;
                case 4: /* movl into a 32-bit subreg zero-extends to 64 */
                        aoStrCatFmt(ctx->buf, "movl    %%eax, %%eax\n\t"); break;
                default: break;
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_SITOFP:
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "%s %%rax, %%xmm0\n\t",
                        (int)irValueByteSize(instr->dst) == 4 ? "cvtsi2ssq"
                                                              : "cvtsi2sdq");
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;

        case IR_UITOFP: {
            /* x86 has no unsigned 64-bit int-to-float. Branch-free form
             * of what gcc/clang emit: when the top bit is set, convert
             * (u >> 1) | (u & 1) (the low bit keeps the rounding right)
             * and double the result; otherwise convert u as signed. The
             * doubling multiplies by 1.0 or 2.0 built from the sign. */
            int is_f32 = (int)irValueByteSize(instr->dst) == 4;
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf,
                        "movq    %%rax, %%rcx\n\t"
                        "shrq    $1, %%rcx\n\t"
                        "movq    %%rax, %%rdx\n\t"
                        "andq    $1, %%rdx\n\t"
                        "orq     %%rdx, %%rcx\n\t"
                        "movq    %%rax, %%rdx\n\t"
                        "sarq    $63, %%rdx\n\t"
                        "xorq    %%rax, %%rcx\n\t"
                        "andq    %%rdx, %%rcx\n\t"
                        "xorq    %%rax, %%rcx\n\t"
                        "%s %%rcx, %%xmm0\n\t"
                        "shrq    $63, %%rdx\n\t"
                        "shlq    $%i, %%rdx\n\t"
                        "movabsq $%s, %%rax\n\t"
                        "addq    %%rdx, %%rax\n\t"
                        "movq    %%rax, %%xmm1\n\t"
                        "%s   %%xmm1, %%xmm0\n\t",
                        is_f32 ? "cvtsi2ssq" : "cvtsi2sdq",
                        is_f32 ? 23 : 52,
                        is_f32 ? "0x3F800000" : "0x3FF0000000000000",
                        is_f32 ? "mulss" : "mulsd");
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;
        }

        case IR_FPTOSI:
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "%s %%xmm0, %%rax\n\t",
                        (int)irValueByteSize(instr->r1) == 4 ? "cvttss2siq"
                                                             : "cvttsd2siq");
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_FPTOUI:
            /* Same simplification as UITOFP - signed truncation. */
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "%s %%xmm0, %%rax\n\t",
                        (int)irValueByteSize(instr->r1) == 4 ? "cvttss2siq"
                                                             : "cvttsd2siq");
            x86_64SpillDst(ctx, instr, "rax");
            break;

        case IR_FPTRUNC:
            /* F64 -> F32. */
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "cvtsd2ss %%xmm0, %%xmm0\n\t");
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;

        case IR_FPEXT:
            /* F32 -> F64. */
            x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
            aoStrCatFmt(ctx->buf, "cvtss2sd %%xmm0, %%xmm0\n\t");
            x86_64SpillDstFpr(ctx, instr, "xmm0");
            break;

        case IR_BITCAST:
            /* Reinterpret bits between int and fp. movd for 4-byte
             * (F32/I32), movq for 8-byte (F64/I64). */
            if (instr->dst && irIsFloat(instr->dst->type)) {
                int single = (int)irValueByteSize(instr->dst) == 4;
                x86_64LoadFirstSrc(ctx, instr, instr->r1);
                aoStrCatFmt(ctx->buf, "%s    %%%s, %%xmm0\n\t",
                            single ? "movd" : "movq", single ? "eax" : "rax");
                x86_64SpillDstFpr(ctx, instr, "xmm0");
            } else {
                int single = (int)irValueByteSize(instr->r1) == 4;
                x86_64LoadFirstSrcFpr(ctx, instr, instr->r1);
                aoStrCatFmt(ctx->buf, "%s    %%xmm0, %%%s\n\t",
                            single ? "movd" : "movq", single ? "eax" : "rax");
                x86_64SpillDst(ctx, instr, "rax");
            }
            break;

        case IR_ZEXT:
        case IR_SEXT: {
            /* Width-extending int-to-int. Source width comes from r1's
             * tmp size; we pick the matching movz/movs encoding. */
            x86_64LoadFirstSrc(ctx, instr, instr->r1);
            u32 src_sz = instr->r1 ? irValueByteSize(instr->r1) : 8;
            int signed_ext = (instr->op == IR_SEXT);
            switch (src_sz) {
                case 1:
                    aoStrCatFmt(ctx->buf,
                                signed_ext ? "movsbq  %%al, %%rax\n\t"
                                           : "movzbq  %%al, %%rax\n\t");
                    break;
                case 2:
                    aoStrCatFmt(ctx->buf,
                                signed_ext ? "movswq  %%ax, %%rax\n\t"
                                           : "movzwq  %%ax, %%rax\n\t");
                    break;
                case 4:
                    aoStrCatFmt(ctx->buf,
                                signed_ext ? "movslq  %%eax, %%rax\n\t"
                                           /* movl r32, r32 zero-extends */
                                           : "movl    %%eax, %%eax\n\t");
                    break;
                default:
                    /* src already 8 bytes: nothing to extend. */
                    break;
            }
            x86_64SpillDst(ctx, instr, "rax");
            break;
        }

        case IR_PTRTOINT: case IR_INTTOPTR:
            loggerPanic("ir-cg-x86_64: conversion op not yet implemented "
                        "(IR op %d) - not currently emitted by IR\n",
                        instr->op);

        case IR_RET:
            if (instr->dst && instr->dst->byval_struct_type) {
                /* <=16-byte struct returned in registers: load its bytes from
                 * the return slot into rax,rdx (INTEGER) / xmm0,xmm1 (SSE). */
                AstType *t = instr->dst->byval_struct_type;
                int loff = irCgGetLoff(&ctx->fn->ra, instr->dst);
                X86SysvAgg ag; x86_64ClassifyAgg(t, &ag);
                const char *gpr[2] = { "rax", "rdx" };
                int gpi = 0, ssei = 0;
                for (int e = 0; e < ag.neb; ++e) {
                    int off = e * 8, rem = t->size - off;
                    u32 sz = rem >= 8 ? 8 : (u32)rem;
                    if (ag.cls[e] == SYSV_SSE) {
                        char mem[48];
                        x86_64FmtMemOperand(mem, sizeof(mem), loff + off,
                                            "rbp", NULL, 0);
                        aoStrCatFmt(ctx->buf, "%s   %s, %%xmm%i\n\t",
                                    x86_64FpMov(sz == 4 ? 4 : 8), mem, ssei);
                        ssei++;
                    } else {
                        x86_64LoadBytes(ctx->buf, gpr[gpi], "rbp", loff + off,
                                        (int)sz, "r11");
                        gpi++;
                    }
                }
            } else if (instr->dst) {
                if (irIsFloat(instr->dst->type)) {
                    x86_64LoadFirstSrcFpr(ctx, instr, instr->dst);
                } else {
                    /* Canonically XOR EAX for returning 0 */
                    if (irIsConstInt(instr->dst) && instr->dst->as._i64 == 0) {
                        aoStrCatFmt(ctx->buf, "xorl    %%eax, %%eax\n\t");
                    } else {
                        x86_64LoadFirstSrc(ctx, instr, instr->dst);
                    }
                }
            }
            x86_64RestorePinnedRegs(ctx->buf, ctx->pinned_regs);
            x86_64Epilogue(ctx);
            break;

        case IR_JMP: {
            IrBlock *target = instr->extra.blocks.target_block;
            x86_64PhiMaterialise(ctx, ctx->cur_block, target);
            if (target != ctx->next_block) {
                char tlbl[64];
                x86_64BlockLabel(ctx, target, tlbl, sizeof(tlbl));
                aoStrCatFmt(ctx->buf, "jmp     %s\n\t", tlbl);
            }
            break;
        }

        case IR_BR: {
            IrBlock *t = instr->extra.blocks.target_block;
            IrBlock *f = instr->extra.blocks.fallthrough_block;
            char tlbl[64], flbl[64];

            x86_64BlockLabel(ctx, t, tlbl, sizeof(tlbl));
            x86_64BlockLabel(ctx, f, flbl, sizeof(flbl));

            x86_64LoadFirstSrc(ctx, instr, instr->dst);

            int t_phi = irBlockHasPhi(t);
            int f_phi = irBlockHasPhi(f);
            if (!t_phi && !f_phi) {
                /* dst holds a 0/1 in %rax. testq sets ZF without
                 * clobbering rax; jz/jnz selects the path. */
                aoStrCatFmt(ctx->buf, "testq   %%rax, %%rax\n\t");
                if (ctx->next_block == t) {
                    aoStrCatFmt(ctx->buf, "jz      %s\n\t", flbl);
                } else if (ctx->next_block == f) {
                    aoStrCatFmt(ctx->buf, "jnz     %s\n\t", tlbl);
                } else {
                    aoStrCatFmt(ctx->buf,
                                "jz      %s\n\t"
                                "jmp     %s\n\t",
                                flbl, tlbl);
                }
            } else {
                /* One arm has phis: land at a dedicated shim that
                 * materialises them before the real jump. */
                static int br_seq = 0;
                char else_lbl[64];
                int br_id = br_seq++;
                snprintf(else_lbl, sizeof(else_lbl),
                         ".LIRBR%d_E%d", ctx->fn->uuid, br_id);
                aoStrCatFmt(ctx->buf,
                            "testq   %%rax, %%rax\n\t"
                            "jz      %s\n\t", else_lbl);
                x86_64PhiMaterialise(ctx, ctx->cur_block, t);
                aoStrCatFmt(ctx->buf, "jmp     %s\n", tlbl);
                aoStrRemovePreviousChar(ctx->buf, '\t');
                aoStrCatFmt(ctx->buf, "%s:\n\t", else_lbl);
                x86_64PhiMaterialise(ctx, ctx->cur_block, f);
                if (ctx->next_block != f) {
                    aoStrCatFmt(ctx->buf, "jmp     %s\n\t", flbl);
                }
            }
            break;
        }

        case IR_CMP_BR: {
            IrBlock *t = instr->extra.cmp_br.target_block;
            IrBlock *f = instr->extra.cmp_br.fallthrough_block;
            IrCmpKind kind = instr->extra.cmp_br.cmp_kind;
            int is_float = instr->r1 && irIsFloat(instr->r1->type);
            char tlbl[64], flbl[64];
            x86_64BlockLabel(ctx, t, tlbl, sizeof(tlbl));
            x86_64BlockLabel(ctx, f, flbl, sizeof(flbl));

            /* Emit the compare. Mirror IR_ICMP / IR_FCMP but skip the
             * setcc + spill since the result lives in EFLAGS for one
             * instruction. */
            if (is_float) {
                kind = x86_64EmitFCmp(ctx, instr, kind);
                if (kind == IR_CMP_EQ || kind == IR_CMP_NE) {
                    /* EQ/NE need two flags (ZF and PF): materialise
                     * the 0/1 and branch on that instead. */
                    x86_64EmitSetCC(ctx, kind, 1);
                    aoStrCatFmt(ctx->buf, "testq   %%rax, %%rax\n\t");
                    kind = IR_CMP_NE;
                    is_float = 0;
                }
            } else {
                x86_64LoadFirstSrc(ctx, instr, instr->r1);
                s64 imm;
                if (x86_64IsImm32(instr->r2, &imm)) {
                    aoStrCatFmt(ctx->buf, "cmpq    $%I, %%rax\n\t",
                                (s64)imm);
                } else {
                    x86_64LoadToReg(ctx, instr->r2, "rcx");
                    aoStrCatFmt(ctx->buf, "cmpq    %%rcx, %%rax\n\t");
                }
            }

            const char *cc_t = x86_64CcFor(kind, is_float);
            const char *cc_f = x86_64CcInvFor(kind, is_float);
            int t_phi = irBlockHasPhi(t);
            int f_phi = irBlockHasPhi(f);
            if (!t_phi && !f_phi) {
                if (ctx->next_block == t) {
                    aoStrCatFmt(ctx->buf, "j%s     %s\n\t", cc_f, flbl);
                } else if (ctx->next_block == f) {
                    aoStrCatFmt(ctx->buf, "j%s     %s\n\t", cc_t, tlbl);
                } else {
                    aoStrCatFmt(ctx->buf,
                                "j%s     %s\n\t"
                                "jmp     %s\n\t",
                                cc_t, tlbl, flbl);
                }
            } else {
                static int cb_seq = 0;
                char else_lbl[64];
                int br_id = cb_seq++;
                snprintf(else_lbl, sizeof(else_lbl),
                         ".LIRCB%d_E%d", ctx->fn->uuid, br_id);
                aoStrCatFmt(ctx->buf, "j%s     %s\n\t", cc_f, else_lbl);
                x86_64PhiMaterialise(ctx, ctx->cur_block, t);
                aoStrCatFmt(ctx->buf, "jmp     %s\n", tlbl);
                aoStrRemovePreviousChar(ctx->buf, '\t');
                aoStrCatFmt(ctx->buf, "%s:\n\t", else_lbl);
                x86_64PhiMaterialise(ctx, ctx->cur_block, f);
                if (ctx->next_block != f) {
                    aoStrCatFmt(ctx->buf, "jmp     %s\n\t", flbl);
                }
            }
            break;
        }

        case IR_SWITCH:
            loggerPanic("ir-cg-x86_64: IR_SWITCH not currently emitted "
                        "by the IR builder\n");

        case IR_CALL: {
            /* Calls clobber all caller-saved regs. Any tmp the
             * tracker thinks is in %rax / %xmm0 must be considered
             * gone after the callq itself. The arg-loading sequence
             * below, though, may legitimately consume a still-live
             * fused tmp from the prior producer. We don't clear the
             * tracker yet. We zero it after the callq emit.
             * For an inndirect call: wrap->as.array.label is NULL and the
             * function-pointer source lives in instr->r2. Load it
             * into %r11 (scratch, outside the arg-reg range) BEFORE
             * the arg loads so subsequent arg evaluation can't
             * clobber the target. callq *%r11 is the indirect form. */
            IrValue *wrap = instr->r1;
            Vec *args = wrap ? wrap->as.array.values : NULL;
            AoStr *fname = wrap ? wrap->as.array.label : NULL;
            int indirect = (fname == NULL);
            if (indirect) {
                if (!instr->r2) {
                    loggerPanic("ir-cg-x86_64: indirect IR_CALL "
                                "without target\n");
                }
                x86_64LoadToReg(ctx, instr->r2, "r11");
            }

            /* Route through the uniform SysV path when a struct is passed by
             * value OR returned by value (register-return / hidden-out-ptr). */
            {
                int use_sysv = (instr->flags & IRCG_CALL_AGG_RETURN) != 0;
                for (u64 ai = 0; args && ai < args->size; ++ai) {
                    if (vecGet(IrValue *, args, ai)->byval_struct_type) {
                        use_sysv = 1;
                        break;
                    }
                }
                if (use_sysv) {
                    x86_64EmitSysvCall(ctx, instr, args, fname, indirect);
                    break;
                }
            }

            Ast *callee = NULL;
            if (!indirect) {
                callee = (Ast *)cctrlCalleeDecl(ctx->cc,
                                          fname->data, fname->len);
            }
            int callee_va = 0;
            if (callee) {
                if ((callee->type && callee->type->has_var_args) ||
                    callee->has_var_args)
                {
                    callee_va = 1;
                } else if (callee->params && callee->params->size > 0) {
                    Ast *last = vecGet(Ast *, callee->params,
                                       callee->params->size - 1);
                    if (last && last->kind == AST_VAR_ARGS)
                        callee_va = 1;
                }
            }
            int ktm_variadic = callee_va && callee &&
                                 callee->kind != AST_EXTERN_FUNC;
            int extern_variadic = callee_va && callee &&
                                  callee->kind == AST_EXTERN_FUNC;

            int var_arg_start = -1;
            if (ktm_variadic && callee->params) {
                for (u64 i = 0; i < callee->params->size; ++i) {
                    Ast *p = vecGet(Ast *, callee->params, i);
                    var_arg_start++;
                    if (p && p->kind == AST_VAR_ARGS)
                        break;
                }
                var_arg_start += 1;
            }
            /* Through a pointer to a ktm `...` function. */
            if (instr->flags & IRCG_CALL_KTM_VA) {
                ktm_variadic = 1;
                var_arg_start = instr->extra.va_named_count + 1;
            }

            u64 n = args ? args->size : 0;
            u8 *is_stack = (n > 0) ? (u8 *)malloc(sizeof(u8) * n) : NULL;
            if (is_stack) memset(is_stack, 0, n);
            s32 n_stack_total = x86_64PartitionCallArgs(
                    is_stack, n, args, ktm_variadic, var_arg_start);

            /* Stack args sit in 8-byte lanes; SysV requires the
             * stack to be 16-byte aligned at the call instruction
             * (and the call pushes 8 bytes for the return address),
             * so the region we sub off has to be a multiple of 16. */
            s32 stack_bytes = n_stack_total * 8;
            if (stack_bytes & 15) stack_bytes += 8;
            if (stack_bytes > 0) {
                aoStrCatFmt(ctx->buf, "subq    $%i, %%rsp\n\t", stack_bytes);
            }

            /* If arg[0]'s producer left it in a register (its
             * IrValue->loc is REG), route it into the first ABI
             * int-arg register *before* the stack-arg loop, that
             * loop uses %rax as scratch and would clobber an
             * %rax-resident value. Only fires for an int arg[0] in
             * a reg slot. */
            int arg0_in_reg = 0;
            if (n > 0 && !is_stack[0]) {
                IrValue *a0 = vecGet(IrValue *, args, 0);
                if (a0 && !irIsFloat(a0->type) &&
                    a0->loc.kind == IR_LOC_REG && a0->loc.as.reg)
                {
                    IrRegPool *pool = irRegPoolGet();
                    const char *src = a0->loc.as.reg->data;
                    const char *dst_reg =
                        vecGet(AoStr *, pool->int_arg_regs, 0)->data;
                    if (strcmp(src, dst_reg) != 0) {
                        aoStrCatFmt(ctx->buf, "movq    %%%s, %%%s\n\t",
                                    src, dst_reg);
                    }
                    arg0_in_reg = 1;
                }
            }

            /* Place stack args at [rsp + i*8] in source order. */
            s32 stack_idx = 0;
            for (u64 i = 0; i < n; ++i) {
                if (!is_stack[i]) continue;
                IrValue *a = vecGet(IrValue *, args, i);
                s32 slot_off = stack_idx * 8;
                if (irIsFloat(a->type)) {
                    x86_64LoadToFpr(ctx, a, "xmm0");
                    aoStrCatFmt(ctx->buf, "%s   %%xmm0, %i(%%rsp)\n\t",
                                x86_64FpMov((int)irValueByteSize(a)), slot_off);
                } else {
                    x86_64LoadToReg(ctx, a, "rax");
                    aoStrCatFmt(ctx->buf, "movq    %%rax, %i(%%rsp)\n\t", slot_off);
                }
                stack_idx++;
            }

            /* Reg args. Hidden struct-return pointer (when present)
             * is args[0] and naturally lands in %rdi. No special
             * handling needed (unlike aarch64's x8). The backend's
             * IrRegPool descriptor supplies the ABI mapping so this
             * loop stays target-agnostic. */
            IrRegPool *pool = irRegPoolGet();
            int int_idx = arg0_in_reg ? 1 : 0;
            int float_idx = 0;
            int xmm_used = 0;
            for (u64 i = 0; i < n; ++i) {
                if (is_stack[i]) continue;
                if (i == 0 && arg0_in_reg) continue;
                IrValue *a = vecGet(IrValue *, args, i);
                if (irIsFloat(a->type)) {
                    if ((u64)float_idx >= pool->float_arg_regs->size) {
                        loggerPanic("ir-cg-x86_64: too many float args\n");
                    }
                    AoStr *r = vecGet(AoStr *, pool->float_arg_regs,
                                      float_idx++);
                    x86_64LoadToFpr(ctx, a, r->data);
                    xmm_used++;
                } else {
                    if ((u64)int_idx >= pool->int_arg_regs->size) {
                        loggerPanic("ir-cg-x86_64: too many int args\n");
                    }
                    AoStr *r = vecGet(AoStr *, pool->int_arg_regs, int_idx++);
                    x86_64LoadToReg(ctx, a, r->data);
                }
            }

            /* SysV variadic ABI: %al = number of XMM regs used.
             * Required by C-variadic callees so their va_start can
             * spill the right registers; cheap to set unconditionally
             * for any variadic call. */
            if (extern_variadic || ktm_variadic) {
                aoStrCatFmt(ctx->buf, "movb    $%i, %%al\n\t", xmm_used);
            }

            if (indirect) {
                aoStrCatFmt(ctx->buf, "callq   *%%r11\n\t");
            } else {
                aoStrCatFmt(ctx->buf, "callq   %s\n\t",
                            asmNormaliseFunctionName(ctx->cc, fname));
            }
            /* The call clobbered all caller-saved regs; any prior
             * FUSE_TO_NEXT producer's value is gone. */
            if (stack_bytes > 0) {
                aoStrCatFmt(ctx->buf, "addq    $%i, %%rsp\n\t", stack_bytes);
            }

            if (instr->dst && instr->dst->type != IR_TYPE_VOID &&
                (instr->dst->kind == IR_VAL_TMP ||
                 instr->dst->kind == IR_VAL_LOCAL ||
                 instr->dst->kind == IR_VAL_PARAM))
            {
                if (irIsFloat(instr->dst->type)) {
                    x86_64SpillDstFpr(ctx, instr, "xmm0");
                } else {
                    x86_64SpillDst(ctx, instr, "rax");
                }
            }
            if (is_stack) free(is_stack);
            break;
        }

        /* Inline `asm { ... }` block. When fragments are present
         * (the parser saw `&var` references in the asm body) walk
         * them and substitute `<loff>(%rbp)` for each LVAR_REF using
         * the lvar's now-known stack offset. Otherwise paste the
         * plain text. Mirrors aarch64's IR_ASM case modulo the
         * frame-relative syntax. */
        case IR_ASM: {
            /* Inline `asm { ... }` statement: assemble with libtasm
             * and emit the bytes in place. */
            if (instr->extra.asm_fragments) {
                loggerPanic("ir-cg-x86_64: `&var` asm fragments are no "
                            "longer supported; address locals directly\n");
            }
            aoStrRemovePreviousChar(ctx->buf, '\t');
            if (instr->r1 && instr->r1->as.str.str) {
                asmEmitBlockBytes(ctx->cc, ctx->buf,
                                  instr->r1->as.str.str, 0);
            }
            aoStrPutChar(ctx->buf, '\t');
            break;
        }

        /* IR_SELECT and IR_VA_* aren't currently emitted by the IR
         * builder for ktm programs. Panic if they ever start appearing so
         * we notice. Select could be interesting to avoid branching */
        case IR_SELECT:
        case IR_VA_ARG: case IR_VA_START: case IR_VA_END:
            loggerPanic("ir-cg-x86_64: IR op %d not yet implemented "
                        "(not currently emitted by IR builder; "
                        "matches aarch64)\n", instr->op);
    }

}

void x86_64PasteDataSection(Cctrl *cc, AoStr *buf) {
    /* sign_bit / one_dbl are shared FP scratch globals - referenced
     * by FNEG and convert-float ops. */
    aoStrCatFmt(buf, "sign_bit:\n\t.quad 0x8000000000000000\n");
    aoStrCatFmt(buf, "one_dbl:\n\t.double 1.0\n");

    MapIter it;
    mapIterInit(cc->strs, &it);
    while (mapIterNext(&it)) {
        MapNode *n = it.node;
        Ast *ast = (Ast *)n->value;
        assert(ast->kind == AST_STRING);
        aoStrCatFmt(buf, "%S:\n\t", ast->slabel);
        asmEmitStringBytes(buf, ast);
    }
    aoStrPutChar(buf, '\t');
}

static void x86_64EmitFunctionPrologue(Cctrl *cc,
                                       AoStr *buf,
                                       Ast *func,
                                       u32 total_stack,
                                       Vec *pinned,
                                       int omit_frame)
{
    char *fname = asmNormaliseFunctionName(cc, func->fname);
    /* .p2align 4 = 16-byte aligned function entries (x86_64
     * convention for the I-cache fetch boundary). */
    aoStrCatFmt(buf,
                ".text\n\t"
                ".p2align 4\n\t");
    asmEmitFunctionSymbol(cc, buf, fname, func);
    aoStrCatFmt(buf, "%s:\n\t", fname);
    /* Leaf function with empty frame: skip the rbp dance entirely
     * (saves 3 instructions per call). Caller-saved rbp survives
     * untouched, no slot accesses to anchor, no callee-saved regs
     * to preserve. The matching epilogue is just `retq`. */
    if (omit_frame) return;

    aoStrCatFmt(buf,
                "pushq   %%rbp\n\t"
                "movq    %%rsp, %%rbp\n\t");
    if (total_stack > 0) {
        /* Round up to 16 so rsp stays aligned for inner calls. The
         * call instruction pushes 8 bytes, our pushq %rbp pushes
         * another 8 - so the frame base is already aligned; the
         * subq has to preserve that. */
        u32 aligned = ((u32)total_stack + 15u) & ~15u;
        if (aligned > KTM_STACK_PROBE_STRIDE) {
            /* A frame bigger than the guard page could leap over it, so
             * first ask the runtime whether rsp - aligned is still on
             * the stack (rt.__ktmStackCheck reports a stack overflow
             * otherwise). Nothing is touched: a big buffer costs only
             * the pages it uses. The argument registers are still live
             * here, so they are kept around the call (128 bytes, rsp
             * stays 16-aligned). */
            AoStr sym = { .data = astRuntimeSymbol("__ktmStackCheck"), .len = 0, .capacity = 0 };
            sym.len = strlen(sym.data);
            aoStrCatFmt(buf,
                        "leaq    -%u(%%rsp), %%r11\n\t"
                        "subq    $128, %%rsp\n\t"
                        "movq    %%rdi, 0(%%rsp)\n\t"
                        "movq    %%rsi, 8(%%rsp)\n\t"
                        "movq    %%rdx, 16(%%rsp)\n\t"
                        "movq    %%rcx, 24(%%rsp)\n\t"
                        "movq    %%r8, 32(%%rsp)\n\t"
                        "movq    %%r9, 40(%%rsp)\n\t"
                        "movq    %%rax, 48(%%rsp)\n\t"
                        "movq    %%r10, 56(%%rsp)\n\t"
                        "movsd   %%xmm0, 64(%%rsp)\n\t"
                        "movsd   %%xmm1, 72(%%rsp)\n\t"
                        "movsd   %%xmm2, 80(%%rsp)\n\t"
                        "movsd   %%xmm3, 88(%%rsp)\n\t"
                        "movsd   %%xmm4, 96(%%rsp)\n\t"
                        "movsd   %%xmm5, 104(%%rsp)\n\t"
                        "movsd   %%xmm6, 112(%%rsp)\n\t"
                        "movsd   %%xmm7, 120(%%rsp)\n\t"
                        "movq    %%r11, %%rdi\n\t"
                        "callq   %s\n\t"
                        "movq    0(%%rsp), %%rdi\n\t"
                        "movq    8(%%rsp), %%rsi\n\t"
                        "movq    16(%%rsp), %%rdx\n\t"
                        "movq    24(%%rsp), %%rcx\n\t"
                        "movq    32(%%rsp), %%r8\n\t"
                        "movq    40(%%rsp), %%r9\n\t"
                        "movq    48(%%rsp), %%rax\n\t"
                        "movq    56(%%rsp), %%r10\n\t"
                        "movsd   64(%%rsp), %%xmm0\n\t"
                        "movsd   72(%%rsp), %%xmm1\n\t"
                        "movsd   80(%%rsp), %%xmm2\n\t"
                        "movsd   88(%%rsp), %%xmm3\n\t"
                        "movsd   96(%%rsp), %%xmm4\n\t"
                        "movsd   104(%%rsp), %%xmm5\n\t"
                        "movsd   112(%%rsp), %%xmm6\n\t"
                        "movsd   120(%%rsp), %%xmm7\n\t"
                        "addq    $128, %%rsp\n\t"
                        "subq    $%u, %%rsp\n\t",
                        aligned, asmNormaliseFunctionName(cc, &sym), aligned);
        } else {
            aoStrCatFmt(buf, "subq    $%u, %%rsp\n\t", aligned);
        }
    }
    /* Pinned saves AFTER reserving locals so rbp-relative offsets
     * for slots stay valid throughout the function body. */
    x86_64SavePinnedRegs(buf, pinned);
}

void x86_64GenerateFunction(IrCgCtx *ctx, Ast *ast) {
    AoStr *buf = ctx->buf;
    IrFunction *fn = ctx->fn;

    /* Collect pinned-register set used by this function's locals
     * AND params (same sweep aarch64 does). The prologue saves;
     * each ret restores. */
    ctx->pinned_regs = NULL;
    if (ast->locals) {
        listForEach(ast->locals) {
            Ast *l = (Ast *)it->value;
            if (!l || l->kind != AST_LVAR) continue;
            if (l->pinned_kind != LVAR_REG) continue;
            if (!l->pinned_reg) continue;
            if (!ctx->pinned_regs) ctx->pinned_regs = irValueVecNew();
            vecPush(ctx->pinned_regs, l->pinned_reg);
        }
    }
    if (ast->params) {
        for (u64 i = 0; i < ast->params->size; ++i) {
            Ast *p = vecGet(Ast *, ast->params, i);
            if (!p || p->kind != AST_LVAR) continue;
            if (p->pinned_kind != LVAR_REG) continue;
            if (!p->pinned_reg) continue;
            int seen = 0;
            if (ctx->pinned_regs) {
                for (u64 k = 0; k < ctx->pinned_regs->size; ++k) {
                    AoStr *r = (AoStr *)ctx->pinned_regs->entries[k];
                    if (r == p->pinned_reg) { seen = 1; break; }
                }
            }
            if (seen) continue;
            if (!ctx->pinned_regs) ctx->pinned_regs = irValueVecNew();
            vecPush(ctx->pinned_regs, p->pinned_reg);
        }
    }

    /* A by-value struct param is unpacked into an rbp-relative slot by
     * x86_64EmitSysvParamPrologue, so the frame must exist. */
    int has_struct_param = 0;
    if (ast->params) {
        for (u64 i = 0; i < ast->params->size; ++i) {
            Ast *p = vecGet(Ast *, ast->params, i);
            if (x86_64IsByvalStruct(p->type)) { has_struct_param = 1; break; }
        }
    }

    /* Leaf function with zero frame: no stack slots, no pinned regs
     * to save, no calls (so we don't need rsp realignment) and no
     * VARGS area. Skip the rbp prologue/epilogue. */
    ctx->omit_frame = (fn->stack_space == 0)
                      && (ctx->pinned_regs == NULL
                          || ctx->pinned_regs->size == 0)
                      && !irFnHasCalls(fn)
                      && (ast->loff == 0)
                      && !has_struct_param;

    asmLinesBegin(ctx->cc, ast, ctx->omit_frame);
    x86_64EmitFunctionPrologue(ctx->cc, buf, ast,
                               fn->stack_space,
                               ctx->pinned_regs,
                               ctx->omit_frame);
    x86_64EmitSysvParamPrologue(ctx, ast);

    Set *referenced = irCgComputeReferencedBlocks(fn);

    listForEach(fn->blocks) {
        IrBlock *block = (IrBlock *)it->value;
        ctx->cur_block = block;
        ctx->next_block = (it->next != fn->blocks)
                         ? (IrBlock *)it->next->value
                         : NULL;
        if (setHas(referenced, (void *)(u64)block->id)) {
            char lbl[64];
            x86_64BlockLabel(ctx, block, lbl, sizeof(lbl));
            aoStrRemovePreviousChar(buf, '\t');
            aoStrCatPrintf(buf, "%s:\n\t", lbl);
        }
        listForEach(block->instructions) {
            IrInstr *instr = (IrInstr *)it->value;
            asmLinesNote(buf, instr->line, instr->site);
            x86_64EmitInstr(ctx, instr);
        }
    }
    setRelease(referenced);

    /* If the function body didn't end with an explicit ret, emit
     * the epilogue ourselves so we don't fall through. */
    if (!x86_64HasRet(ctx)) {
        x86_64RestorePinnedRegs(buf, ctx->pinned_regs);
        x86_64Epilogue(ctx);
    }
    asmLinesEnd(buf);
}

void x86_64InitialiseEmptyGlobal(Cctrl *cc,
                                 AoStr *buf,
                                 Ast *global,
                                 int zerofill)
{
    AoStr *label = (global->glabel ? global->glabel : global->gname);
    label = asmNormaliseGlobalLabel(cc, label);
    int size = global->type->size;

    if (zerofill && (cc->target == TARGET_AARCH64_APPLE_DARWIN ||
                     cc->target == TARGET_X86_64_APPLE_DARWIN))
    {
        aoStrCatFmt(buf, ".globl %S\n\t", label);
        aoStrCatFmt(buf, ".zerofill __DATA,__common,%S,%i,%i\n\t",
                    label, size, (int)log2((double)size));
    } else {
        aoStrCatFmt(buf, ".globl %S\n\t.comm %S, %i, %u\n\t",
                    label, label, size,
                    roundUpToNextPowerOf2((unsigned long)size));
    }
}

static void x86_64DataInternal(AoStr *buf, Ast *data) {
    if (data->type->kind == AST_TYPE_FLOAT) {
        if (data->type->size == 4) {
            aoStrCatPrintf(buf, ".long 0x%lX #%.9f\n\t",
                           (unsigned long)(u32)ieee754_32((f32)data->f64),
                           data->f64);
        } else {
            aoStrCatPrintf(buf, ".quad 0x%lX #%.9f\n\t",
                           ieee754_64(data->f64), data->f64);
        }
        return;
    }
    switch (data->type->size) {
        /* %lld: the value is 64 bits (`I64 g = -3000000000`) */
        case 1: aoStrCatPrintf(buf, ".byte %lld\n\t", (long long)data->i64); break;
        case 2: aoStrCatPrintf(buf, ".short %lld\n\t", (long long)data->i64); break;
        case 4: aoStrCatPrintf(buf, ".long %lld\n\t", (long long)data->i64); break;
        case 8: aoStrCatPrintf(buf, ".quad %lld\n\t", (long long)data->i64); break;
        default:
            loggerPanic("Cannot create size information for: %s\n",
                        astToString(data));
    }
}

void x86_64GlobalVar(Cctrl *cc,
                     Set *seen_globals,
                     AoStr *buf,
                     Ast *ast)
{
    Ast *declvar = ast->declvar;
    Ast *declinit = ast->declinit;
    AoStr *varname = declvar->gname;
    AoStr *label = (declvar->glabel ? declvar->glabel : declvar->gname);
    label = asmNormaliseGlobalLabel(cc, label);
    /* A local symbol: `static`, or a library global the header
     * doesn't declare */
    int local = declvar->is_static || !cctrlIsExported(cc, declvar->gname);

    if (ast->flags & AST_FLAG_EXTERN) return;
    if (declvar->flags & AST_FLAG_EXTERN) return;
    if (setHasLen(seen_globals, varname->data, varname->len)) return;

    aoStrRemovePreviousChar(buf, '\t');
    setAdd(seen_globals, varname->data);
    if (asmThreadLocalGlobal(cc, buf, declvar, declinit, label, local)) return;

    /* `U8 *p = &g;`, `&arr[2]`, `&Fn`: a relocated pointer */
    AoStr *addr_sym;
    s64 addr_off;
    if (declinit && asmAddrConst(cc, declinit, &addr_sym, &addr_off, 0)) {
        if (!local) {
            aoStrCatFmt(buf, ".globl %S\n", label);
        }
        aoStrCatFmt(buf, ".data\n\t.p2align 3\n%S:\n\t", label);
        asmEmitAddrConst(buf, addr_sym, addr_off);
        return;
    }

    if (declinit &&
        (declinit->kind == AST_ARRAY_INIT ||
         declinit->kind == AST_LITERAL ||
         declinit->kind == AST_STRING))
    {
        if (declinit->kind == AST_STRING) {
            /* `U8 *p = "..."`: the global is a *pointer* to the string
             * literal (emitted separately in the cstring section), not
             * inline storage. `U8 buf[] = "..."` keeps the bytes inline. */
            if (declvar->type && declvar->type->kind == AST_TYPE_POINTER) {
                if (!local) {
                    aoStrCatFmt(buf, ".globl %S\n", label);
                }
                aoStrCatFmt(buf, ".data\n\t.p2align 3\n%S:\n\t.quad %S\n\t",
                            label, declinit->slabel);
            } else {
                /* Writable storage: without a section directive it went
                 * wherever the previous global left off, after the
                 * string literals in a read-only section. */
                if (!local) {
                    aoStrCatFmt(buf, ".globl %S\n", label);
                }
                aoStrCatFmt(buf, ".data\n%S:\n\t", label);
                asmEmitStringBytes(buf, declinit);
                aoStrPutChar(buf, '\t');
                /* `U8 buf[8] = "abc"`: zero-fill past the string. */
                int pad = declvar->type->size - (int)declinit->real_len;
                if (pad > 0) {
                    aoStrCatFmt(buf, ".zero %i\n\t", pad);
                }
            }
            return;
        }
        if (!local) {
            aoStrCatFmt(buf, ".globl %S\n", label);
        }
        aoStrCatPrintf(buf, ".data\n");
        int align_log2 = asmDataAlignLog2(declvar->type);
        if (align_log2 > 0) {
            aoStrCatPrintf(buf, ".p2align %d\n", align_log2);
        }
        aoStrCatFmt(buf, "%S:\n\t", label);
        if (declinit->kind == AST_ARRAY_INIT) {
            /* Fields at their offsets, padded to the full object. */
            asmEmitInitData(buf, declinit, declvar->type);
            return;
        }
        x86_64DataInternal(buf, declinit);
    } else {
        if (local) {
            /* Mach-O's .lcomm takes no default alignment; ELF's
             * aligns by size. */
            if (cc->target == TARGET_X86_64_APPLE_DARWIN) {
                aoStrCatFmt(buf, ".lcomm %S, %i, %i\n\t", label,
                            declvar->type->size,
                            asmDataAlignLog2(declvar->type));
            } else {
                aoStrCatFmt(buf, ".lcomm %S, %i\n\t", label,
                            declvar->type->size);
            }
        } else {
            /* argc/argv are linked from the startup file - use .comm
             * not .zerofill so the linker doesn't double-allocate. */
            if (label->len == 4 &&
                (!strncmp(label->data, str_lit("argc")) ||
                 !strncmp(label->data, str_lit("argv"))))
            {
                x86_64InitialiseEmptyGlobal(cc, buf, declvar, 0);
            } else {
                x86_64InitialiseEmptyGlobal(cc, buf, declvar, 1);
            }
        }
    }
}

/* `asm {}` function blocks: assemble each function chunk with libtasm
 * and emit the encoded bytes under the function's label. Per-function
 * encoding scopes `@@N` local labels per function. */
static void x86_64PasteAsmBlocks(AoStr *buf, Cctrl *cc) {
    if (!cc->asm_blocks) return;
    for (List *bl = cc->asm_blocks->next; bl != cc->asm_blocks;
         bl = bl->next)
    {
        Ast *asm_block = (Ast *)bl->value;
        if (!asm_block->funcs) continue;
        for (List *fl = asm_block->funcs->next; fl != asm_block->funcs;
             fl = fl->next)
        {
            Ast *asm_func = (Ast *)fl->value;
            aoStrCatPrintf(buf, ".text\n");
            /* An asm function the header doesn't bind is internal to the
             * library */
            if (cctrlIsExported(cc, asm_func->asmfname)) {
                asmEmitFunctionGlobal(cc, buf, asm_func->asmfname->data);
            }
            aoStrCatPrintf(buf, "%s:\n", asm_func->asmfname->data);
            asmEmitBlockBytes(cc, buf, asm_func->body->asm_stmt, 0);
        }
    }
}

AoStr *x86_64AsmGenerate(Cctrl *cc) {
    AoStr *buf = aoStrAlloc(2048);
    x86_64InitRegPool();
    asmLinesReset();
    x86_64PasteDataSection(cc, buf);
    x86_64PasteAsmBlocks(buf, cc);

    /* Top-level statements parsed into cc->initalisers become the
     * body of a synthetic `main`. CCTRL_ASM_HAS_INITIALISERS makes
     * asmNormaliseFunctionName rename the user's `Main` to `MainFn`
     * so the symbols don't collide. */
    Ast *synth_main = asmBuildInitialiserMain(cc);

    IrCtx *ir_ctx = irCtxNew(cc);
    IrCgCtx ctx;
    ctx.buf = buf;
    ctx.cc = cc;

    Set *seen_globals = setNew(32, &set_cstring_type);

    listForEach(cc->ast_list) {
        Ast *ast = it->value;
        if (ast->kind == AST_FUNC) {
            IrFunction *fn = irLowerFunction(ir_ctx, ast);
            irBasicFunctionOptimisations(fn);
            irFunctionPrepForCodeGen(&ctx, fn, ast);
            x86_64GenerateFunction(&ctx, ast);
        } else if (ast->kind == AST_DECL || ast->kind == AST_GVAR) {
            x86_64GlobalVar(cc, seen_globals, buf, ast);
        }
    }

    if (synth_main) {
        IrFunction *fn = irLowerFunction(ir_ctx, synth_main);
        irBasicFunctionOptimisations(fn);
        irFunctionPrepForCodeGen(&ctx, fn, synth_main);
        x86_64GenerateFunction(&ctx, synth_main);
    }

    Ast *user_main = (Ast *)mapGetLen(cc->global_env, str_lit("main"));
    asmLinesEmit(cc, buf, cc->crash_report &&
                 (synth_main != NULL || (user_main && user_main->kind == AST_FUNC)));
    asmEmitAsmInfo(cc, buf);
    setRelease(seen_globals);
    return buf;
}
