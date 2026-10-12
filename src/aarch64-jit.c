/* AArch64 in-process JIT backend.
 *
 * Lowers IR straight to native instruction bytes via the libtasm AArch64
 * encoder, then maps the result RX and exposes public function entries
 * by name. Mirrors src/aarch64.c case-for-case: same register roles
 * (x0/x1/x2 scratch, d0/d1 FP scratch, x29 fp, x30 lr, x9 large-offset
 * pivot, x16 indirect-call scratch), same omit_frame rule, same frame
 * layout, same addressing-mode fusion, same AAPCS64 + ktm variadic
 * convention. The only material difference is global addressing: at JIT
 * time we know absolute addresses, so a global reference materialises via
 * mov_imm64 instead of adrp+@PAGEOFF (saves a fixup pair).
 *
 *   IR -> enc_arm64 encoders -> AsmEnc.bytes + AsmFixup list
 *   AsmEnc -> asm_jit_finalize -> RX mapping, branches patched
 *   public labels exposed as `name -> entry pointer` via aarch64JitLookup
 *
 * Inter-function calls and external symbols share one mechanism: emit a
 * BL placeholder + AF_SYMBOL fixup. asm_jit_finalize walks the fixup
 * list and resolves each via jitResolveSymbol (internal -> host override
 * -> dlsym). Out-of-range branches get a movz/movk/br trampoline. */

#include "aarch64-jit.h"

#if !defined(__aarch64__)

KtmJit *aarch64JitCompile(Cctrl *cc) { (void)cc; return NULL; }
const KtmJitBackend *aarch64JitBackend(void) { return NULL; }

#else /* __aarch64__ && KTM_ENABLE_JIT */

#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

#include "aostr.h"
#include "ast.h"
#include "asm.h"
#include "asm/asm_enc.h"
#include "asm/asm_jit.h"
#include "asm/enc_arm64.h"
#include "aarch64-emit.h"
#include "cli.h"
#include "containers.h"
#include "ir.h"
#include "ir-optimise.h"
#include "ir-regalloc.h"
#include "list.h"
#include "util.h"

/* Per-function emission context. Same shape as IrCgCtx in spirit but
 * carries JIT-specific bits (the encoder, the local-label counter). */
typedef struct {
    KtmJit *jit;
    IrFunction *fn;
    Ast *ast;
    IrBlock *cur_block;
    IrBlock *next_block;
    int omit_frame;
    uint32_t aligned_frame; /* what the prologue subtracted from sp */
} JitFnCtx;

/* ---------------- forward decls ---------------- */

static void jitEmitInstr(JitFnCtx *ctx, IrInstr *instr);
static void jitLoadToReg(JitFnCtx *ctx, IrValue *val, A64Reg reg);
static void jitLoadToFpr(JitFnCtx *ctx, IrValue *val, A64Reg dreg);
static void jitStoreReg(JitFnCtx *ctx, IrValue *dst, A64Reg reg);
static void jitStoreFpr(JitFnCtx *ctx, IrValue *dst, A64Reg dreg);
static void jitPhiMaterialise(JitFnCtx *ctx, IrBlock *from, IrBlock *to);

/* ---------------- value-kind helpers ---------------- */

static int jitIsAddImm(s64 v) {
    if (v < 0) return 0;
    if (v <= 0xFFF) return 1;
    if ((v & 0xFFF) == 0 && (v >> 12) <= 0xFFF) return 1;
    return 0;
}

/* Can `[base, #disp]` encode for a `size`-byte access? Unscaled
 * (ldur/stur) covers signed -256..255; the scaled unsigned form
 * covers size-aligned 0..4095*size. */
static int jitIsMemDisp(s32 disp, int size) {
    if (disp >= -256 && disp <= 255) return 1;
    if (disp >= 0 && (disp % size) == 0 && disp <= 4095 * size) return 1;
    return 0;
}

/* sp -=/+= n for any n up to 16MB, via the imm12 (+ lsl #12) split.
 * The register form is a trap here: reg 31 in ADD/SUB
 * (shifted-register) decodes as XZR, not SP, so `sub sp, sp, x9`
 * assembles to a silent no-op. The immediate form treats 31 as SP. */
static void jitSpAdjust(AsmEnc *enc, int is_sub, u64 n) {
    /* The add/sub immediate form reaches a 12-bit value optionally shifted
     * left 12, so at most 0xFFF << 12 in one instruction. Apply that many
     * bytes at a time for frames beyond the two-immediate reach (this lifts
     * the old 16 MB panic), then the 4096-aligned hi and the lo remainder.
     * The immediate form is SP-safe; the register form treats reg 31 as
     * XZR, so it must not be used with sp. */
    const u64 CHUNK = 0xFFFULL << 12;
    while (n >= CHUNK) {
        if (is_sub) aarch64_enc_sub_imm(enc, 1, A_SP, A_SP, (uint32_t)CHUNK);
        else        aarch64_enc_add_imm(enc, 1, A_SP, A_SP, (uint32_t)CHUNK);
        n -= CHUNK;
    }
    u64 hi = n & ~0xFFFULL; /* 4096-aligned: encoder picks the sh=1 form */
    u64 lo = n & 0xFFF;
    if (hi) {
        if (is_sub) aarch64_enc_sub_imm(enc, 1, A_SP, A_SP, (uint32_t)hi);
        else        aarch64_enc_add_imm(enc, 1, A_SP, A_SP, (uint32_t)hi);
    }
    if (lo) {
        if (is_sub) aarch64_enc_sub_imm(enc, 1, A_SP, A_SP, (uint32_t)lo);
        else        aarch64_enc_add_imm(enc, 1, A_SP, A_SP, (uint32_t)lo);
    }
}

/* Parse a register name like "x9", "w0", "d0", "s3" into an A64Reg
 * (which carries just the numeric index 0..31).  Used to translate
 * the pinned-reg / IR_LOC_REG `AoStr *reg` fields into encoder regs. */
static A64Reg jitRegFromName(const char *name) {
    if (!name || !*name) return A_X0;
    if (name[0] == 'x' || name[0] == 'X' ||
        name[0] == 'w' || name[0] == 'W' ||
        name[0] == 'd' || name[0] == 'D' ||
        name[0] == 's' || name[0] == 'S')
    {
        int n = atoi(name + 1);
        if (n < 0 || n > 31) return A_X0;
        return (A64Reg)n;
    }
    if (!strcasecmp(name, "sp")) return A_SP;
    if (!strcasecmp(name, "lr")) return A_LR;
    if (!strcasecmp(name, "fp")) return A_FP;
    if (!strcasecmp(name, "xzr") || !strcasecmp(name, "wzr")) return A_XZR;
    return A_X0;
}

static int jitIsFpRegName(const char *name) {
    return name && (name[0] == 'd' || name[0] == 's' ||
                    name[0] == 'D' || name[0] == 'S');
}

/* ---------------- immediate materialise ---------------- */

/* Emit `reg = imm` using movz / movk / movn sequences.  Mirrors
 * aarch64EmitMovImm in aarch64.c. */
static void jitEmitMovImm(AsmEnc *enc, A64Reg reg, s64 imm) {
    u64 u = (u64)imm;
    if (u <= 0xFFFFULL) {
        aarch64_enc_mov_imm64(enc, reg, u);
        return;
    }
    if (imm < 0 && imm >= -65536) {
        u64 n = (u64)(~imm) & 0xFFFFULL;
        aarch64_enc_movn(enc, reg, 1, (uint16_t)n, 0);
        return;
    }
    aarch64_enc_mov_imm64(enc, reg, u);
}

/* Base register for a `[base, idx]` access with a displacement:
 * x10 = base + disp, or base itself when disp is 0. Mirrors
 * aarch64IdxBase: base must not be bumped in place (it may be a live
 * parameter register, and RMW reuses it for the store), and x9 may
 * hold a store value, so a large disp goes via x10 too. */
static A64Reg jitIdxBase(AsmEnc *enc, A64Reg base, s64 disp) {
    if (disp == 0) return base;
    u64 mag = (u64)(disp < 0 ? -disp : disp);
    if (mag <= 0xFFF) {
        if (disp < 0) aarch64_enc_sub_imm(enc, 1, A_X10, base, (uint32_t)mag);
        else          aarch64_enc_add_imm(enc, 1, A_X10, base, (uint32_t)mag);
    } else {
        jitEmitMovImm(enc, A_X10, disp);
        aarch64_enc_add_reg(enc, 1, A_X10, base, A_X10);
    }
    return A_X10;
}

/* ---------------- frame load/store ---------------- */

/* Decide which form (unscaled imm9, scaled imm12, or register offset)
 * fits `loff` for an access of `size` bytes. Sets `*use_x9` to 1 when
 * the caller must use the register-offset variant; returns the (signed)
 * displacement to plug into the encoder. */
typedef enum {
    JFRAME_UNSCALED,
    JFRAME_SCALED,
    JFRAME_REGOFF
} JFrameForm;

static JFrameForm jitFrameForm(AsmEnc *enc, int loff, int size) {
    if (loff >= -256 && loff <= 255) return JFRAME_UNSCALED;
    if (loff >= 0) {
        int step = (size == 1) ? 1 : (size == 2) ? 2 : (size == 4) ? 4 : 8;
        int max  = step * 4095;
        if ((loff % step) == 0 && loff <= max) return JFRAME_SCALED;
    }
    jitEmitMovImm(enc, A_X9, (s64)loff);
    return JFRAME_REGOFF;
}

/* Width of a GPR memory access for a `size`-byte value. A slot wider
 * than a register holds its value in the low 8 bytes. ldur/stur and the scaled form
 * already treat such widths as 8, but the register-offset encoder
 * rejects them and emits nothing, so normalise up front. Mirrors the
 * default case of aarch64.c's frame and deref accesses. */
static int jitGprAccessSize(int size) {
    return (size == 1 || size == 2 || size == 4) ? size : 8;
}

/* Integer frame load with size-aware sign/zero extension (matches
 * x86 movslq for 4 bytes, movzbq/movzwq for sub-word). */
static void jitFrameLoad(AsmEnc *enc, A64Reg reg, int size, int loff) {
    size = jitGprAccessSize(size);
    JFrameForm form = jitFrameForm(enc, loff, size);
    if (form == JFRAME_REGOFF) {
        aarch64_enc_ldst_regoff(enc, 0 /*gpr*/, 1 /*load*/, size,
                                0, reg, A_FP, A_X9);
        /* The register-offset `ldr w` zero-extends; sign-extend like
         * the ldursw/ldrsw forms below. */
        if (size == 4) aarch64_enc_sxtw(enc, reg, reg);
        return;
    }
    if (form == JFRAME_UNSCALED) {
        if (size == 4) {
            /* ldursw Xt, [Xn, #simm9] -- not in our encoder; use the
             * unscaled form and trust the size to sign-extend via
             * ldursw bit pattern.  Falls back to ldur + sxtw below for
             * 4-byte negative offsets to keep things simple. */
            aarch64_enc_ldur(enc, 4, reg, A_FP, (int32_t)loff);
            aarch64_enc_sxtw(enc, reg, reg);
            return;
        }
        aarch64_enc_ldur(enc, size, reg, A_FP, (int32_t)loff);
        if (size == 1) aarch64_enc_uxtb(enc, 0, reg, reg);
        else if (size == 2) aarch64_enc_uxth(enc, reg, reg);
        return;
    }
    /* Scaled imm12 form. Encoders take byte offsets and scale internally. */
    uint32_t bytes = (uint32_t)loff;
    switch (size) {
        case 1: aarch64_enc_ldrb_imm (enc, reg, A_FP, bytes); break;
        case 2: aarch64_enc_ldrh_imm (enc, reg, A_FP, bytes); break;
        case 4: aarch64_enc_ldrsw_imm(enc, reg, A_FP, bytes); break;
        default: aarch64_enc_ldr_imm (enc, reg, A_FP, bytes); break;
    }
}

/* Integer frame store: size-aware width (strb / strh / str). */
static void jitFrameStore(AsmEnc *enc, A64Reg reg, int size, int loff) {
    size = jitGprAccessSize(size);
    JFrameForm form = jitFrameForm(enc, loff, size);
    if (form == JFRAME_REGOFF) {
        aarch64_enc_ldst_regoff(enc, /*is_simd=*/0 , /*is_load=*/0, size,
                                /*scaled=*/0, reg, A_FP, A_X9);
        return;
    }
    if (form == JFRAME_UNSCALED) {
        aarch64_enc_stur(enc, size, reg, A_FP, (int32_t)loff);
        return;
    }
    uint32_t bytes = (uint32_t)loff;
    switch (size) {
        case 1: aarch64_enc_strb_imm     (enc, reg, A_FP, bytes); break;
        case 2: aarch64_enc_strh_imm     (enc, reg, A_FP, bytes); break;
        case 4: aarch64_enc_str32_imm_gpr(enc, reg, A_FP, bytes); break;
        default: aarch64_enc_str_imm    (enc, reg, A_FP, bytes); break;
    }
}

/* FP frame load/store via the FP load/store encoder family.
 *
 * AArch64's scaled `LDR Dt, [Xn, #imm]` only accepts non-negative
 * size-aligned offsets, but local slots typically live at negative loff
 * from x29. The encoder doesn't expose an FP-LDUR helper, so for both
 * negative and out-of-range cases we route through `[Xn, Xm]` (regoff)
 * with the offset materialised into x9. `jitFrameForm` emits the movz
 * only on the REGOFF branch; we re-issue it on the UNSCALED branch
 * here so the regoff load below has the offset in x9. */
static void jitFpFrameLoad(AsmEnc *enc, A64Reg dreg, int size, int loff) {
    JFrameForm form = jitFrameForm(enc, loff, size);
    if (form == JFRAME_REGOFF) {
        aarch64_enc_ldst_regoff(enc, 1 /*simd*/, 1, size, 0, dreg, A_FP, A_X9);
        return;
    }
    if (form == JFRAME_UNSCALED) {
        jitEmitMovImm(enc, A_X9, (s64)loff);
        aarch64_enc_ldst_regoff(enc, 1, 1, size, 0, dreg, A_FP, A_X9);
        return;
    }
    /* Scaled imm12 form; encoder takes byte offset. */
    aarch64_enc_fp_ldst_imm(enc, 1, size, dreg, A_FP, (uint32_t)loff);
}

static void jitFpFrameStore(AsmEnc *enc, A64Reg dreg, int size, int loff) {
    JFrameForm form = jitFrameForm(enc, loff, size);
    if (form == JFRAME_REGOFF) {
        aarch64_enc_ldst_regoff(enc, 1 /*simd*/, 0, size, 0, dreg, A_FP, A_X9);
        return;
    }
    if (form == JFRAME_UNSCALED) {
        jitEmitMovImm(enc, A_X9, (s64)loff);
        aarch64_enc_ldst_regoff(enc, 1, 0, size, 0, dreg, A_FP, A_X9);
        return;
    }
    aarch64_enc_fp_ldst_imm(enc, 0, size, dreg, A_FP, (uint32_t)loff);
}

/* Width-aware load through an address held in `base_reg`. Mirrors
 * aarch64DerefLoad: handles disp + idx + scale fusion. */
static void jitDerefLoad(AsmEnc *enc, int size, A64Reg dst, A64Reg base,
                         int has_idx, A64Reg idx, int scale, s32 disp)
{
    size = jitGprAccessSize(size);
    if (has_idx) {
        int sh = (scale == 8) ? 3 : (scale == 4) ? 2 : (scale == 2) ? 1 : 0;
        base = jitIdxBase(enc, base, (s64)disp);
        /* Register-offset form with optional LSL scale. */
        int scaled = (sh != 0); /* encoder S=1 picks "scale by access width" */
        if (size == 4) {
            /* ldrsw via regoff: encoder doesn't expose the sign-extending
             * variant; load plain word then sxtw. */
            aarch64_enc_ldst_regoff(enc, 0, 1, 4, scaled, dst, base, idx);
            aarch64_enc_sxtw(enc, dst, dst);
        } else {
            aarch64_enc_ldst_regoff(enc, 0, 1, size, scaled, dst, base, idx);
            if (size == 1) aarch64_enc_uxtb(enc, 0, dst, dst);
            else if (size == 2) aarch64_enc_uxth(enc, dst, dst);
        }
        return;
    }
    if (disp != 0) {
        int ok_scaled = disp >= 0 && (disp % size) == 0 &&
                        disp <= 4095 * size;
        if (!ok_scaled && !jitIsMemDisp(disp, size)) {
            /* Out of every immediate form's range: register-offset
             * via x10. base may be a live allocated register (must
             * not be mutated) and x9 can hold a store value. */
            jitEmitMovImm(enc, A_X10, (s64)disp);
            if (size == 4) {
                aarch64_enc_ldst_regoff(enc, 0, 1, 4, 0, dst, base, A_X10);
                aarch64_enc_sxtw(enc, dst, dst);
            } else {
                aarch64_enc_ldst_regoff(enc, 0, 1, size, 0, dst, base, A_X10);
                if (size == 1) aarch64_enc_uxtb(enc, 0, dst, dst);
                else if (size == 2) aarch64_enc_uxth(enc, dst, dst);
            }
            return;
        }
        if (!ok_scaled) {
            /* Small negative or misaligned: ldur (imm9 unscaled). */
            aarch64_enc_ldur(enc, size == 4 ? 4 : size, dst, base, disp);
            if (size == 4) aarch64_enc_sxtw(enc, dst, dst);
            else if (size == 1) aarch64_enc_uxtb(enc, 0, dst, dst);
            else if (size == 2) aarch64_enc_uxth(enc, dst, dst);
            return;
        }
        switch (size) {
            case 1: aarch64_enc_ldrb_imm (enc, dst, base, (uint32_t)disp); break;
            case 2: aarch64_enc_ldrh_imm (enc, dst, base, (uint32_t)disp); break;
            case 4: aarch64_enc_ldrsw_imm(enc, dst, base, (uint32_t)disp); break;
            default: aarch64_enc_ldr_imm (enc, dst, base, (uint32_t)disp); break;
        }
        return;
    }
    /* No disp, no idx: straight indirect load. */
    switch (size) {
        case 1: aarch64_enc_ldrb_imm (enc, dst, base, 0); break;
        case 2: aarch64_enc_ldrh_imm (enc, dst, base, 0); break;
        case 4: aarch64_enc_ldrsw_imm(enc, dst, base, 0); break;
        default: aarch64_enc_ldr_imm (enc, dst, base, 0); break;
    }
}

static void jitDerefStore(AsmEnc *enc, int size, A64Reg val, A64Reg base,
                          int has_idx, A64Reg idx, int scale, s32 disp)
{
    size = jitGprAccessSize(size);
    if (has_idx) {
        int sh = (scale == 8) ? 3 : (scale == 4) ? 2 : (scale == 2) ? 1 : 0;
        base = jitIdxBase(enc, base, (s64)disp);
        int scaled = (sh != 0);
        aarch64_enc_ldst_regoff(enc, 0, 0, size, scaled, val, base, idx);
        return;
    }
    if (disp != 0) {
        int ok_scaled = disp >= 0 && (disp % size) == 0 &&
                        disp <= 4095 * size;
        if (!ok_scaled && !jitIsMemDisp(disp, size)) {
            /* See jitDerefLoad: x10, never mutate base, x9 may be
             * the value. */
            jitEmitMovImm(enc, A_X10, (s64)disp);
            aarch64_enc_ldst_regoff(enc, 0, 0, size, 0, val, base, A_X10);
            return;
        }
        if (!ok_scaled) {
            aarch64_enc_stur(enc, size, val, base, disp);
            return;
        }
        switch (size) {
            case 1: aarch64_enc_strb_imm     (enc, val, base, (uint32_t)disp); break;
            case 2: aarch64_enc_strh_imm     (enc, val, base, (uint32_t)disp); break;
            case 4: aarch64_enc_str32_imm_gpr(enc, val, base, (uint32_t)disp); break;
            default: aarch64_enc_str_imm    (enc, val, base, (uint32_t)disp); break;
        }
        return;
    }
    switch (size) {
        case 1: aarch64_enc_strb_imm     (enc, val, base, 0); break;
        case 2: aarch64_enc_strh_imm     (enc, val, base, 0); break;
        case 4: aarch64_enc_str32_imm_gpr(enc, val, base, 0); break;
        default: aarch64_enc_str_imm    (enc, val, base, 0); break;
    }
}

/* ---------------- global addressing ----------------
 *
 * Three kinds of symbol need different treatment:
 *
 *  - Internal functions (defined in this TU): their address is the JIT
 *    mapping base + the label's byte_offset, but we don't know the base
 *    at emit time. Emit ADRP + ADD with PAGE21/PAGEOFF12 fixups; the
 *    finalize step looks up the label in enc->labels by name and
 *    patches the 21-bit page delta + 12-bit page offset (same scheme
 *    the AOT path uses for @PAGE/@PAGEOFF on Darwin).
 *
 *  - Internal globals/strings (registered in host_symbols by
 *    jitAllocateGlobals): we know the absolute address NOW because
 *    they live in a host malloc'd arena. Emit mov_imm64 with the
 *    materialised address.
 *
 *  - External functions (printf/MAlloc/etc., dlsym-discoverable):
 *    materialise via mov_imm64 with the dlsym address. Don't use
 *    ADRP+ADD here -- libc is typically beyond ADRP's +/-4GB reach
 *    on macOS. */

static void jitGlobalAddr(KtmJit *jit, A64Reg reg, const char *sym) {
    /* Internal function -> ADRP + ADD with PAGE21/PAGEOFF12 fixups. */
    if (ktmJitIsInternalFunc(jit, sym)) {
        size_t adrp_off = aarch64_enc_adrp(&jit->enc, reg, 0);
        AsmFixup f1 = {
            .kind = AF_SYMBOL,
            .reloc = AFR_AARCH64_PAGE21,
            .patch_offset = adrp_off,
            .width = 4, .pcrel = 1,
            .sym = strdup(sym),
        };
        asm_add_fixup(&jit->enc, f1);
        size_t add_off = aarch64_enc_add_imm(&jit->enc, 1, reg, reg, 0);
        AsmFixup f2 = {
            .kind = AF_SYMBOL,
            .reloc = AFR_AARCH64_PAGEOFF12,
            .patch_offset = add_off,
            .width = 4, .pcrel = 0,
            .sym = strdup(sym),
        };
        asm_add_fixup(&jit->enc, f2);
        return;
    }
    /* Internal global / string / external function: address is known
     * now (host arena malloc, or dlsym for libc). Materialise it. */
    void *addr = mapGet(jit->host_symbols, (void *)sym);
    if (!addr) addr = asm_jit_dlsym_resolver(NULL, sym);
    if (!addr) {
        /* nothing defines it: the chunk fails (it was a null address) */
        asm_jit_unresolved(&jit->enc, sym);
        aarch64_enc_mov_imm64(&jit->enc, reg, 0);
        return;
    }
    aarch64_enc_mov_imm64(&jit->enc, reg, (uint64_t)(uintptr_t)addr);
}

/* ---------------- load/store IR values ---------------- */

static void jitLoadToReg(JitFnCtx *ctx, IrValue *val, A64Reg reg) {
    AsmEnc *enc = &ctx->jit->enc;
    switch (val->kind) {
        case IR_VAL_CONST_INT:
            if (val->as._i64 == 0) {
                aarch64_enc_mov_reg(enc, reg, A_XZR);
            } else {
                jitEmitMovImm(enc, reg, val->as._i64);
            }
            break;
        case IR_VAL_CONST_FLOAT: {
            /* Rare bit-cast: materialise the bit pattern. */
            jitEmitMovImm(enc, reg, (s64)(u64)ieee754_64(val->as._f64));
            break;
        }
        case IR_VAL_CONST_STR:
            jitGlobalAddr(ctx->jit, reg, val->as.str.label->data);
            break;
        case IR_VAL_TMP:
        case IR_VAL_LOCAL:
        case IR_VAL_PARAM: {
            if (val->pinned_reg) {
                A64Reg src = jitRegFromName(val->pinned_reg->data);
                if (src != reg) aarch64_enc_mov_reg(enc, reg, src);
                break;
            }
            if (val->loc.kind == IR_LOC_REG && val->loc.as.reg) {
                A64Reg src = jitRegFromName(val->loc.as.reg->data);
                if (src != reg) aarch64_enc_mov_reg(enc, reg, src);
                break;
            }
            int size = (int)irValueByteSize(val);
            int loff = irCgGetLoff(&ctx->fn->ra, val);
            jitFrameLoad(enc, reg, size, loff);
            break;
        }
        case IR_VAL_GLOBAL:
            jitGlobalAddr(ctx->jit, reg, val->as.global.name->data);
            break;
        default:
            loggerPanic("jit-aarch64: cannot load int value of kind %d\n", val->kind);
    }
}

/* 1 for a double-width (F64) float value, 0 for single (F32). */
static int jitFpDbl(IrValue *v) { return (int)irValueByteSize(v) == 8; }

static void jitLoadToFpr(JitFnCtx *ctx, IrValue *val, A64Reg dreg) {
    AsmEnc *enc = &ctx->jit->enc;
    switch (val->kind) {
        case IR_VAL_CONST_FLOAT:
        case IR_VAL_CONST_INT: {
            double f = val->kind == IR_VAL_CONST_INT
                       ? (double)val->as._i64
                       : val->as._f64;
            int is_dbl = (val->kind == IR_VAL_CONST_FLOAT) ? jitFpDbl(val) : 1;
            u64 bits = is_dbl ? (u64)ieee754_64(f)
                              : (u64)(u32)ieee754_32((f32)f);
            if (bits == 0) {
                aarch64_enc_fmov_gpr(enc, is_dbl, 0, dreg, A_XZR);
                break;
            }
            /* Materialise via x9 = bit pattern, then fmov to dreg. */
            jitEmitMovImm(enc, A_X9, (s64)bits);
            aarch64_enc_fmov_gpr(enc, is_dbl, 0, dreg, A_X9);
            break;
        }
        case IR_VAL_TMP:
        case IR_VAL_LOCAL:
        case IR_VAL_PARAM: {
            int is_dbl = jitFpDbl(val);
            /* register-pinned FP local: the value lives in the named
             * register (d/s for FP, x for a GPR holding the bits). */
            if (val->pinned_reg) {
                A64Reg src = jitRegFromName(val->pinned_reg->data);
                if (jitIsFpRegName(val->pinned_reg->data)) {
                    if (src != dreg) aarch64_enc_fmov_reg(enc, is_dbl, dreg, src);
                } else {
                    aarch64_enc_fmov_gpr(enc, is_dbl, 0, dreg, src);
                }
                break;
            }
            if (val->loc.kind == IR_LOC_REG && val->loc.as.reg) {
                A64Reg src = jitRegFromName(val->loc.as.reg->data);
                if (jitIsFpRegName(val->loc.as.reg->data)) {
                    if (src != dreg) aarch64_enc_fmov_reg(enc, is_dbl, dreg, src);
                } else {
                    aarch64_enc_fmov_gpr(enc, is_dbl, 0, dreg, src);
                }
                break;
            }
            int size = (int)irValueByteSize(val);
            int loff = irCgGetLoff(&ctx->fn->ra, val);
            jitFpFrameLoad(enc, dreg, size, loff);
            break;
        }
        default:
            loggerPanic("jit-aarch64: cannot load fp value of kind %d\n", val->kind);
    }
}

static void jitStoreReg(JitFnCtx *ctx, IrValue *dst, A64Reg reg) {
    AsmEnc *enc = &ctx->jit->enc;
    if (dst->pinned_reg) {
        A64Reg home = jitRegFromName(dst->pinned_reg->data);
        if (home != reg) aarch64_enc_mov_reg(enc, home, reg);
        return;
    }
    if (dst->loc.kind == IR_LOC_REG && dst->loc.as.reg) {
        A64Reg home = jitRegFromName(dst->loc.as.reg->data);
        if (home != reg) aarch64_enc_mov_reg(enc, home, reg);
        return;
    }
    int size = (int)irValueByteSize(dst);
    int loff = irCgGetLoff(&ctx->fn->ra, dst);
    jitFrameStore(enc, reg, size, loff);
}

/* `val_size` (0 = derive from dst) is the width of the value being stored.
 * When dst is a pointer/gep tmp aliasing a narrow field, sizing the store
 * from dst (8 bytes) would clobber the neighbouring field; pass the value
 * width instead. Mirrors aarch64.c's aarch64StoreFprSized. */
static void jitStoreFprSized(JitFnCtx *ctx, IrValue *dst, A64Reg dreg,
                             int val_size) {
    AsmEnc *enc = &ctx->jit->enc;
    int size = val_size > 0 ? val_size : (int)irValueByteSize(dst);
    int is_dbl = (size == 8);
    /* Pinned FP local: write the value into the named register. */
    if (dst->pinned_reg) {
        A64Reg home = jitRegFromName(dst->pinned_reg->data);
        if (jitIsFpRegName(dst->pinned_reg->data)) {
            if (home != dreg) aarch64_enc_fmov_reg(enc, is_dbl, home, dreg);
        } else {
            aarch64_enc_fmov_gpr(enc, is_dbl, 1, home, dreg);
        }
        return;
    }
    if (dst->loc.kind == IR_LOC_REG && dst->loc.as.reg) {
        A64Reg home = jitRegFromName(dst->loc.as.reg->data);
        if (jitIsFpRegName(dst->loc.as.reg->data)) {
            if (home != dreg) aarch64_enc_fmov_reg(enc, is_dbl, home, dreg);
        } else {
            aarch64_enc_fmov_gpr(enc, is_dbl, 1, home, dreg);
        }
        return;
    }
    int loff = irCgGetLoff(&ctx->fn->ra, dst);
    jitFpFrameStore(enc, dreg, size, loff);
}

static void jitStoreFpr(JitFnCtx *ctx, IrValue *dst, A64Reg dreg) {
    jitStoreFprSized(ctx, dst, dreg, 0);
}

static void jitSpillDst(JitFnCtx *ctx, IrInstr *instr, A64Reg reg) {
    jitStoreReg(ctx, instr->dst, reg);
}

static void jitSpillDstFpr(JitFnCtx *ctx, IrInstr *instr, A64Reg dreg) {
    jitStoreFpr(ctx, instr->dst, dreg);
}

static void jitLoadFirstSrc(JitFnCtx *ctx, IrValue *src) {
    jitLoadToReg(ctx, src, A_X0);
}

static void jitLoadFirstSrcFpr(JitFnCtx *ctx, IrValue *src) {
    jitLoadToFpr(ctx, src, A_X0); /* d0 (same numeric index) */
}

/* Register `v` already lives in, or -1 if it has to be loaded. */
static int jitHomeReg(IrValue *v) {
    if (v && v->loc.kind == IR_LOC_REG && v->loc.as.reg)
        return (int)jitRegFromName(v->loc.as.reg->data);
    return -1;
}

/* Mirror of aarch64AddrRegs: resolve a mem op's address and SIB idx
 * to registers, loading whichever is not register-resident into the
 * one of x1/x2 the other operand is not sitting in, so neither load
 * overwrites the other. `x0_busy` copies an operand out of x0 first
 * (RMW loads the old value into x0 and still needs the address).
 * Returns 1 if there is an idx component; `*idx_out` is always
 * written so callers can forward it unconditionally. */
static int jitAddrRegs(JitFnCtx *ctx, IrInstr *instr, IrValue *addr,
                       int x0_busy, A64Reg *base_out, A64Reg *idx_out)
{
    int has_idx = instr->idx && instr->scale;
    int base = jitHomeReg(addr);
    int idx = has_idx ? jitHomeReg(instr->idx) : -1;
    if (x0_busy && base == A_X0) base = -1;
    if (x0_busy && idx == A_X0) idx = -1;
    if (base < 0) {
        base = (idx == A_X1) ? A_X2 : A_X1;
        if (addr->kind == IR_VAL_GLOBAL)
            jitGlobalAddr(ctx->jit, (A64Reg)base, addr->as.global.name->data);
        else
            jitLoadToReg(ctx, addr, (A64Reg)base);
    }
    if (has_idx && idx < 0) {
        idx = (base == A_X2) ? A_X1 : A_X2;
        jitLoadToReg(ctx, instr->idx, (A64Reg)idx);
    }
    *base_out = (A64Reg)base;
    *idx_out = has_idx ? (A64Reg)idx : A_X2;
    return has_idx;
}

/* ---------------- condition codes ---------------- */

static A64Cond jitCcFor(IrCmpKind cmp, int is_float) {
    if (is_float) {
        switch (cmp) {
            case IR_CMP_EQ: return A_EQ;
            case IR_CMP_NE: return A_NE;
            case IR_CMP_LT: return A_MI;
            case IR_CMP_LE: return A_LS;
            case IR_CMP_GT: return A_GT;
            case IR_CMP_GE: return A_GE;
            default: loggerPanic("jit-aarch64: bad float cmp %d\n", cmp);
        }
    }
    switch (cmp) {
        case IR_CMP_EQ:  return A_EQ;
        case IR_CMP_NE:  return A_NE;
        case IR_CMP_LT:  return A_LT;
        case IR_CMP_LE:  return A_LE;
        case IR_CMP_GT:  return A_GT;
        case IR_CMP_GE:  return A_GE;
        case IR_CMP_ULT: return A_CC;  /* lo */
        case IR_CMP_ULE: return A_LS;
        case IR_CMP_UGT: return A_HI;
        case IR_CMP_UGE: return A_CS;  /* hs */
        default: loggerPanic("jit-aarch64: bad int cmp %d\n", cmp);
    }
}

static A64Cond jitCcInvFor(IrCmpKind cmp, int is_float) {
    if (is_float) {
        switch (cmp) {
            case IR_CMP_EQ: return A_NE;
            case IR_CMP_NE: return A_EQ;
            case IR_CMP_LT: return A_PL;
            case IR_CMP_LE: return A_HI;
            case IR_CMP_GT: return A_LE;
            case IR_CMP_GE: return A_LT;
            default: loggerPanic("jit-aarch64: bad float cmp %d\n", cmp);
        }
    }
    switch (cmp) {
        case IR_CMP_EQ:  return A_NE;
        case IR_CMP_NE:  return A_EQ;
        case IR_CMP_LT:  return A_GE;
        case IR_CMP_LE:  return A_GT;
        case IR_CMP_GT:  return A_LE;
        case IR_CMP_GE:  return A_LT;
        case IR_CMP_ULT: return A_CS;
        case IR_CMP_ULE: return A_HI;
        case IR_CMP_UGT: return A_LS;
        case IR_CMP_UGE: return A_CC;
        default: loggerPanic("jit-aarch64: bad int cmp %d\n", cmp);
    }
}

static void jitEmitCSet(AsmEnc *enc, IrCmpKind cmp, int is_float) {
    aarch64_enc_cset(enc, A_X0, jitCcFor(cmp, is_float));
}

/* ---------------- phi materialisation ---------------- */

static void jitEmitOnePhi(JitFnCtx *ctx, IrInstr *phi, IrPair *match) {
    IrValue *v = match ? match->ir_value : NULL;
    int v_dangling = irIsTmp(v) &&
                     v->loc.kind != IR_LOC_REG &&
                     !mapHasInt(ctx->fn->ra.id_to_loff, irVarId(v));
    if (irIsFloat(phi->dst->type)) {
        if (v_dangling) {
            aarch64_enc_fmov_gpr(&ctx->jit->enc, jitFpDbl(phi->dst), 0,
                                 A_X0 /*d0*/, A_XZR);
        } else {
            jitLoadToFpr(ctx, v, A_X0 /*d0*/);
        }
        jitStoreFpr(ctx, phi->dst, A_X0 /*d0*/);
        return;
    }
    if (v_dangling) {
        aarch64_enc_mov_reg(&ctx->jit->enc, A_X0, A_XZR);
    } else {
        jitLoadToReg(ctx, v, A_X0);
    }
    jitStoreReg(ctx, phi->dst, A_X0);
}

static void jitEmitOnePhiCb(void *ud, IrInstr *phi, IrPair *match) {
    jitEmitOnePhi((JitFnCtx *)ud, phi, match);
}

static void jitPhiMaterialise(JitFnCtx *ctx, IrBlock *from, IrBlock *to) {
    ktmJitPhiMaterialise(from, to, jitEmitOnePhiCb, ctx);
}

/* ---------------- call args partition ---------------- */

static s32 jitPartitionCallArgs(u8 *is_stack, u64 n, Vec *args,
                                int is_variadic, int named_count,
                                int force_va_stack)
{
    int int_used = 0, float_used = 0, stack_count = 0;
    for (u64 i = 0; i < n; ++i) {
        IrValue *a = vecGet(IrValue *, args, i);
        int is_float = irIsFloat(a->type);
        int force_stack = 0;
        if (is_variadic && force_va_stack && (int)i >= named_count) force_stack = 1;
        if (force_stack) { is_stack[i] = 1; stack_count++; }
        else if (is_float) {
            if (float_used < 8) { is_stack[i] = 0; float_used++; }
            else { is_stack[i] = 1; stack_count++; }
        } else {
            if (int_used < 8) { is_stack[i] = 0; int_used++; }
            else { is_stack[i] = 1; stack_count++; }
        }
    }
    return (s32)stack_count;
}

/* ---------------- branches / IR_RET ---------------- */

/* Emit `b <local>` placeholder + AF_LOCAL fixup. */
static void jitEmitBranchLocal(JitFnCtx *ctx, int local_num) {
    size_t off = aarch64_jit_emit_b_placeholder(&ctx->jit->enc);
    ktmJitAddLocalBranchFixup(&ctx->jit->enc, off, local_num, AFR_AARCH64_JUMP26);
}

/* Emit `b.cc <local>` placeholder + AF_LOCAL fixup (BCOND19). */
static void jitEmitBcondLocal(JitFnCtx *ctx, A64Cond cc, int local_num) {
    size_t off = aarch64_jit_emit_bcond_placeholder(&ctx->jit->enc, cc);
    ktmJitAddLocalBranchFixup(&ctx->jit->enc, off, local_num, AFR_AARCH64_BCOND19);
}

/* ---------------- AAPCS struct-by-value passing ----------------
 *
 * Mirrors aarch64.c's aarch64EmitAapcsCall / aarch64EmitStructParamPrologue
 * for the JIT (machine-code) path. Same classification (HFA -> v-regs,
 * INTEGER <=16B -> GP regs, INDIRECT >16B -> caller copy + pointer) and
 * the same register-only support window (stack spill panics, matching the
 * text backend). */

static int jitIsByvalStruct(AstType *t) {
    return t && (t->kind == AST_TYPE_CLASS || t->kind == AST_TYPE_UNION) &&
           !t->is_intrinsic;
}

/* The widest power-of-two access, at most 8 bytes, that fits `n`. */
static int jitChunkSize(int n) {
    return n >= 8 ? 8 : n >= 4 ? 4 : n >= 2 ? 2 : 1;
}

/* Load the `n` (1..8) bytes at [base, #off] (off >= 0 and a multiple of
 * 8) into `reg`, zero-extended, as 4/2/1-byte pieces ORed together
 * through x10 when `n` is not a power of two: an aggregate of 3, 5, 6, 7
 * bytes (or the tail of one of 9..15) travels in an x register, and
 * reading past it could fault. Mirrors aarch64LoadBytes. */
static void jitLoadBytes(AsmEnc *enc, A64Reg reg, A64Reg base, int off, int n) {
    if (n >= 8) {
        aarch64_enc_ldr_imm(enc, reg, base, (uint32_t)off);
        return;
    }
    int o = 0;
    while (o < n) {
        int c = jitChunkSize(n - o);
        A64Reg r = o == 0 ? reg : A_X10;
        uint32_t at = (uint32_t)(off + o);
        if (c == 4)      aarch64_enc_ldr32_imm_gpr(enc, r, base, at);
        else if (c == 2) aarch64_enc_ldrh_imm(enc, r, base, at);
        else             aarch64_enc_ldrb_imm(enc, r, base, at);
        if (o != 0) {
            aarch64_enc_lsl_imm(enc, 1, A_X10, A_X10, (uint32_t)(o * 8));
            aarch64_enc_orr_reg(enc, 1, reg, reg, A_X10);
        }
        o += c;
    }
}

/* Store the low `n` (1..8) bytes of `reg` to [base, #off] (off as for
 * jitLoadBytes), as 4/2/1-byte pieces shifted down through x10 when `n`
 * is not a power of two; `reg` is left intact. */
static void jitStoreBytes(AsmEnc *enc, A64Reg reg, A64Reg base, int off, int n) {
    if (n >= 8) {
        aarch64_enc_str_imm(enc, reg, base, (uint32_t)off);
        return;
    }
    int o = 0;
    while (o < n) {
        int c = jitChunkSize(n - o);
        A64Reg r = reg;
        uint32_t at = (uint32_t)(off + o);
        if (o != 0) {
            aarch64_enc_lsr_imm(enc, 1, A_X10, reg, (uint32_t)(o * 8));
            r = A_X10;
        }
        if (c == 4)      aarch64_enc_str32_imm_gpr(enc, r, base, at);
        else if (c == 2) aarch64_enc_strh_imm(enc, r, base, at);
        else             aarch64_enc_strb_imm(enc, r, base, at);
        o += c;
    }
}

/* reg = base + off for any signed off (the add/sub encoders split a
 * large immediate into several instructions). */
static void jitAddrOf(AsmEnc *enc, A64Reg reg, A64Reg base, int off) {
    if (off >= 0) aarch64_enc_add_imm(enc, 1, reg, base, (uint32_t)off);
    else          aarch64_enc_sub_imm(enc, 1, reg, base, (uint32_t)(-off));
}

/* x11 = x29 + loff, so the odd-sized frame accesses above can use a
 * non-negative offset. */
static void jitFrameAddrX11(AsmEnc *enc, int loff) {
    jitAddrOf(enc, A_X11, A_FP, loff);
}

/* Copy `size` bytes from [x11] to [x12] by a loop over 8-byte words (x13
 * counts, x10 carries) and then the 4/2/1-byte tail. Mirrors
 * aarch64CopyLoop: for aggregates of A64_COPY_LOOP_MIN bytes and up. */
static void jitCopyLoop(AsmEnc *enc, int size) {
    jitEmitMovImm(enc, A_X13, (s64)(size / 8));
    size_t top = enc->len;
    aarch64_enc_ldr_imm(enc, A_X10, A_X11, 0);
    aarch64_enc_str_imm(enc, A_X10, A_X12, 0);
    aarch64_enc_add_imm(enc, 1, A_X11, A_X11, 8);
    aarch64_enc_add_imm(enc, 1, A_X12, A_X12, 8);
    aarch64_enc_subs_imm(enc, 1, A_X13, A_X13, 1);
    int32_t rel_words = (int32_t)(((int64_t)top - (int64_t)enc->len) / 4);
    aarch64_enc_b_cond(enc, A_NE, rel_words);
    int o = 0, tail = size % 8;
    while (o < tail) {
        int c = jitChunkSize(tail - o);
        jitLoadBytes(enc, A_X10, A_X11, o, c);
        jitStoreBytes(enc, A_X10, A_X12, o, c);
        o += c;
    }
}

/* A power-of-two size the plain frame/base accesses handle. */
static int jitIsPow2Access(int n) {
    return n == 1 || n == 2 || n == 4 || n >= 8;
}

/* Copy `size` bytes from [base, #src] to the frame slot at #dst via x10. */
static void jitCopyToSlot(AsmEnc *enc, A64Reg base, int size, int src, int dst) {
    if (size >= A64_COPY_LOOP_MIN) {
        jitAddrOf(enc, A_X11, base, src);
        jitAddrOf(enc, A_X12, A_FP, dst);
        jitCopyLoop(enc, size);
        return;
    }
    int o = 0;
    while (size - o >= 8) {
        aarch64_enc_ldr_imm(enc, A_X10, base, (uint32_t)(src + o));
        jitFrameStore(enc, A_X10, 8, dst + o);
        o += 8;
    }
    /* The tail of an odd-sized aggregate: 4/2/1-byte pieces. */
    while (o < size) {
        int c = jitChunkSize(size - o);
        jitLoadBytes(enc, A_X10, base, src + o, c);
        jitFrameStore(enc, A_X10, c, dst + o);
        o += c;
    }
}

/* Copy `size` bytes from [src, #0..] to [sp, #dst] via x10. */
static void jitCopyToSp(AsmEnc *enc, A64Reg src, int size, int dst) {
    if (size >= A64_COPY_LOOP_MIN) {
        aarch64_enc_mov_reg(enc, A_X11, src);
        aarch64_enc_add_imm(enc, 1, A_X12, A_SP, (uint32_t)dst);
        jitCopyLoop(enc, size);
        return;
    }
    int o = 0;
    while (size - o >= 8) {
        aarch64_enc_ldr_imm(enc, A_X10, src, (uint32_t)o);
        aarch64_enc_str_imm(enc, A_X10, A_SP, (uint32_t)(dst + o));
        o += 8;
    }
    /* The tail of an odd-sized aggregate: 4/2/1-byte pieces. */
    while (o < size) {
        int c = jitChunkSize(size - o);
        jitLoadBytes(enc, A_X10, src, o, c);
        jitStoreBytes(enc, A_X10, A_SP, dst + o, c);
        o += c;
    }
}

/* ---------------- the main IR emitter ---------------- */

/* JIT (machine-code) leaf ops for the shared a64Emit* lowering. The helper
 * resolves r1 to its live register, or loads it into the scratch. */
static A64Reg a64JitSrcReg(JitFnCtx *ctx, IrInstr *i, int is_fpr) {
    if (i->r1->loc.kind == IR_LOC_REG && i->r1->loc.as.reg)
        return jitRegFromName(i->r1->loc.as.reg->data);
    if (is_fpr) jitLoadFirstSrcFpr(ctx, i->r1);
    else        jitLoadFirstSrc(ctx, i->r1);
    return A_X0;
}

static void a64JitStoreFpr(void *be, IrInstr *i, int val_size) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    jitStoreFprSized(ctx, i->dst, a64JitSrcReg(ctx, i, 1), val_size);
}

static void a64JitStoreInt(void *be, IrInstr *i, int size) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    A64Reg src = a64JitSrcReg(ctx, i, 0);
    jitFrameStore(&ctx->jit->enc, src, size,
                  irCgGetLoff(&ctx->fn->ra, i->dst));
}

static void a64JitStoreIntPinned(void *be, IrInstr *i) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    A64Reg src = a64JitSrcReg(ctx, i, 0);
    aarch64_enc_mov_reg(&ctx->jit->enc,
                        jitRegFromName(i->dst->pinned_reg->data), src);
}

static void a64JitEmitInit(A64Emitter *e, JitFnCtx *ctx) {
    e->be = ctx;
    e->store_fpr        = a64JitStoreFpr;
    e->store_int        = a64JitStoreInt;
    e->store_int_pinned = a64JitStoreIntPinned;
}

/* JIT (machine-code) leaf ops for the shared a64EmitCall lowering. */
static void a64JitSubSp(void *be, int n) {
    aarch64_enc_sub_imm(&((JitFnCtx *)be)->jit->enc, 1, A_SP, A_SP, (uint32_t)n);
}

static void a64JitAddSp(void *be, int n) {
    aarch64_enc_add_imm(&((JitFnCtx *)be)->jit->enc, 1, A_SP, A_SP, (uint32_t)n);
}

static void a64JitLoadStructAddr(void *be, IrValue *a) {
    jitLoadToReg((JitFnCtx *)be, a, A_X9);
}

static void a64JitCopyX9ToSp(void *be, int size, int dst_off) {
    jitCopyToSp(&((JitFnCtx *)be)->jit->enc, A_X9, size, dst_off);
}

static void a64JitAggLoad(void *be, int is_fp, int reg, int src_off, int size) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (is_fp)
        aarch64_enc_fp_ldst_imm(enc, 1, size, (A64Reg)reg, A_X9, (uint32_t)src_off);
    else
        jitLoadBytes(enc, (A64Reg)reg, A_X9, src_off, size);
}

static void a64JitPtrInReg(void *be, int reg, int sp_off) {
    aarch64_enc_add_imm(&((JitFnCtx *)be)->jit->enc, 1, (A64Reg)reg, A_SP,
                        (uint32_t)sp_off);
}

static void a64JitPtrToStack(void *be, int sp_off, int nsaa) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    aarch64_enc_add_imm(enc, 1, A_X10, A_SP, (uint32_t)sp_off);
    aarch64_enc_str_imm(enc, A_X10, A_SP, (uint32_t)nsaa);
}

static void a64JitScalarToReg(void *be, IrValue *a, int is_fp, int reg) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    if (is_fp) jitLoadToFpr(ctx, a, reg);
    else       jitLoadToReg(ctx, a, (A64Reg)reg);
}

static void a64JitScalarToStack(void *be, IrValue *a, int is_fp, int nsaa) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    AsmEnc *enc = &ctx->jit->enc;
    if (is_fp) {
        jitLoadToFpr(ctx, a, 0 /*v0*/);
        aarch64_enc_fp_ldst_imm(enc, 0, (int)irValueByteSize(a), 0, A_SP,
                                (uint32_t)nsaa);
    } else {
        jitLoadToReg(ctx, a, A_X9);
        aarch64_enc_str_imm(enc, A_X9, A_SP, (uint32_t)nsaa);
    }
}

static void a64JitCall(void *be, AoStr *fname, int indirect) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    AsmEnc *enc = &ctx->jit->enc;
    if (indirect) {
        aarch64_enc_blr(enc, A_X16);
    } else {
        char *norm = asmNormaliseFunctionName(ctx->jit->cc, fname);
        size_t off = aarch64_jit_emit_bl_placeholder(enc);
        ktmJitAddCallFixup(enc, off, norm, AFR_AARCH64_CALL26);
    }
}

static void a64JitSpillRet(void *be, IrInstr *instr, int is_float) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    if (is_float) jitSpillDstFpr(ctx, instr, A_X0);
    else          jitSpillDst(ctx, instr, A_X0);
}
/* Store a result-register chunk of a register-returned struct into the
 * destination buffer parked in x9 (mirror of a64JitAggLoad). */
static void a64JitRetChunkStore(void *be, int is_fp, int reg, int off, int size) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (is_fp)
        aarch64_enc_fp_ldst_imm(enc, 0 /*store*/, size, (A64Reg)reg, A_X9,
                                (uint32_t)off);
    else
        jitStoreBytes(enc, (A64Reg)reg, A_X9, off, size);
}

/* Spill from x0, not x9: jitFrameStore uses x9 as the offset scratch for
 * slots beyond stur range, which would clobber the buffer address. */
static void a64JitSpillStructDst(void *be, IrInstr *instr) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    aarch64_enc_mov_reg(&ctx->jit->enc, A_X0, A_X9);
    jitSpillDst(ctx, instr, A_X0);
}

/* The save slot sits above the call's copies of by-value structs, so its
 * offset grows with them: past the scaled range, go through x10. */
static void a64JitSpSlot(AsmEnc *enc, int is_load, int sp_off) {
    A64Reg base = A_SP;
    if (sp_off > 32760) {
        aarch64_enc_add_imm(enc, 1, A_X10, A_SP, (uint32_t)sp_off);
        base = A_X10;
        sp_off = 0;
    }
    if (is_load) aarch64_enc_ldr_imm(enc, A_X9, base, (uint32_t)sp_off);
    else         aarch64_enc_str_imm(enc, A_X9, base, (uint32_t)sp_off);
}

static void a64JitStashDest(void *be, int sp_off) {
    a64JitSpSlot(&((JitFnCtx *)be)->jit->enc, 0, sp_off);
}

static void a64JitUnstashDest(void *be, int sp_off) {
    a64JitSpSlot(&((JitFnCtx *)be)->jit->enc, 1, sp_off);
}

static void a64JitCallInit(A64CallEmitter *e, JitFnCtx *ctx) {
    e->be = ctx;
    e->sub_sp           = a64JitSubSp;
    e->add_sp           = a64JitAddSp;
    e->load_struct_addr = a64JitLoadStructAddr;
    e->copy_x9_to_sp    = a64JitCopyX9ToSp;
    e->agg_load         = a64JitAggLoad;
    e->ptr_in_reg       = a64JitPtrInReg;
    e->ptr_to_stack     = a64JitPtrToStack;
    e->scalar_to_reg    = a64JitScalarToReg;
    e->scalar_to_stack  = a64JitScalarToStack;
    e->call             = a64JitCall;
    e->spill_ret        = a64JitSpillRet;
    e->ret_chunk_store  = a64JitRetChunkStore;
    e->spill_struct_dst = a64JitSpillStructDst;
    e->stash_dest       = a64JitStashDest;
    e->unstash_dest     = a64JitUnstashDest;
}

/* JIT (machine-code) leaf ops for the shared a64EmitParamPrologue. */
static void a64JitAggStore(void *be, int is_fp, int reg, int loff, int size) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (is_fp) {
        jitFpFrameStore(enc, (A64Reg)reg, size, loff);
    } else if (jitIsPow2Access(size)) {
        jitFrameStore(enc, (A64Reg)reg, size, loff);
    } else {
        /* x11 is free: incoming args live in x0-x7/d0-d7. */
        jitFrameAddrX11(enc, loff);
        jitStoreBytes(enc, (A64Reg)reg, A_X11, 0, size);
    }
}

static void a64JitCopyStackToSlot(void *be, int size, int incoming_off,
                                  int loff) {
    jitCopyToSlot(&((JitFnCtx *)be)->jit->enc, A_FP, size, incoming_off, loff);
}

static void a64JitIndirectToSlot(void *be, int from_stack, int reg,
                                 int incoming_off, int size, int loff) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    A64Reg base;
    if (from_stack) {
        aarch64_enc_ldr_imm(enc, A_X9, A_FP, (uint32_t)incoming_off);
        base = A_X9;
    } else {
        base = (A64Reg)reg;
    }
    jitCopyToSlot(enc, base, size, 0, loff);
}

static void a64JitParamInit(A64ParamEmitter *e, JitFnCtx *ctx) {
    e->be = ctx;
    e->agg_store          = a64JitAggStore;
    e->copy_stack_to_slot = a64JitCopyStackToSlot;
    e->indirect_to_slot   = a64JitIndirectToSlot;
}

/* JIT (machine-code) leaf ops for the shared a64EmitConvert lowering. */
static void a64JitCvtLoadGp(void *be, IrInstr *i) {
    jitLoadFirstSrc((JitFnCtx *)be, i->r1);
}
static void a64JitCvtLoadFpr(void *be, IrInstr *i) {
    jitLoadFirstSrcFpr((JitFnCtx *)be, i->r1);
}
static void a64JitCvtSpillGp(void *be, IrInstr *i) {
    jitSpillDst((JitFnCtx *)be, i, A_X0);
}
static void a64JitCvtSpillFpr(void *be, IrInstr *i) {
    jitSpillDstFpr((JitFnCtx *)be, i, A_X0);
}
static void a64JitConvert(void *be, A64CvtKind k, int width) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    switch (k) {
    case A64_CVT_UXTB: aarch64_enc_uxtb(enc, 0, A_X0, A_X0); break;
    case A64_CVT_UXTH: aarch64_enc_uxth(enc, A_X0, A_X0); break;
    case A64_CVT_MOVW: aarch64_enc_mov_reg_w(enc, A_X0, A_X0); break;
    case A64_CVT_SXTB: aarch64_enc_sxtb(enc, A_X0, A_X0); break;
    case A64_CVT_SXTH: aarch64_enc_sxth(enc, A_X0, A_X0); break;
    case A64_CVT_SXTW: aarch64_enc_sxtw(enc, A_X0, A_X0); break;
    case A64_CVT_FCVT_NARROW: aarch64_enc_fcvt_narrow(enc, A_X0, A_X0); break;
    case A64_CVT_FCVT_WIDEN:  aarch64_enc_fcvt_widen(enc, A_X0, A_X0); break;
    case A64_CVT_FCVTZS: aarch64_enc_fcvtzs(enc, 1, width == 8, A_X0, A_X0); break;
    case A64_CVT_FCVTZU: aarch64_enc_fcvtzu(enc, 1, width == 8, A_X0, A_X0); break;
    case A64_CVT_SCVTF:  aarch64_enc_scvtf(enc, 1, width == 8, A_X0, A_X0); break;
    case A64_CVT_UCVTF:  aarch64_enc_ucvtf(enc, 1, width == 8, A_X0, A_X0); break;
    case A64_CVT_FMOV_GP2FP: aarch64_enc_fmov_gpr(enc, width == 8, 0, A_X0, A_X0); break;
    case A64_CVT_FMOV_FP2GP: aarch64_enc_fmov_gpr(enc, width == 8, 1, A_X0, A_X0); break;
    }
}
static void a64JitConvInit(A64ConvEmitter *e, JitFnCtx *ctx) {
    e->be = ctx;
    e->load_gp   = a64JitCvtLoadGp;
    e->load_fpr  = a64JitCvtLoadFpr;
    e->spill_gp  = a64JitCvtSpillGp;
    e->spill_fpr = a64JitCvtSpillFpr;
    e->convert   = a64JitConvert;
}

/* JIT (machine-code) leaf ops for the shared a64EmitArith lowering. */
static int a64JitAluImm(void *be, IrOp op, s64 imm) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (op != IR_IADD && op != IR_ISUB) return 0; /* JIT folds add/sub only */
    int as_add = (op == IR_IADD);
    if (jitIsAddImm(imm)) { /* keep */ }
    else if (imm < 0 && jitIsAddImm(-imm)) { as_add = !as_add; imm = -imm; }
    else return 0;
    if (as_add) aarch64_enc_add_imm(enc, 1, A_X0, A_X0, (uint32_t)imm);
    else        aarch64_enc_sub_imm(enc, 1, A_X0, A_X0, (uint32_t)imm);
    return 1;
}
static void a64JitAluReg(void *be, IrOp op, IrInstr *i) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    AsmEnc *enc = &ctx->jit->enc;
    A64Reg rhs = A_X1;
    if (i->r2->loc.kind == IR_LOC_REG && i->r2->loc.as.reg)
        rhs = jitRegFromName(i->r2->loc.as.reg->data);
    else jitLoadToReg(ctx, i->r2, A_X1);
    switch (op) {
    case IR_IADD: aarch64_enc_add_reg(enc, 1, A_X0, A_X0, rhs); break;
    case IR_ISUB: aarch64_enc_sub_reg(enc, 1, A_X0, A_X0, rhs); break;
    case IR_AND:  aarch64_enc_and_reg(enc, 1, A_X0, A_X0, rhs); break;
    case IR_OR:   aarch64_enc_orr_reg(enc, 1, A_X0, A_X0, rhs); break;
    case IR_XOR:  aarch64_enc_eor_reg(enc, 1, A_X0, A_X0, rhs); break;
    default: break;
    }
}
static void a64JitMul(void *be) {
    aarch64_enc_mul(&((JitFnCtx *)be)->jit->enc, 1, A_X0, A_X0, A_X1);
}
static void a64JitDivRem(void *be, IrOp op) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    int is_signed = (op == IR_IDIV || op == IR_IREM);
    int is_rem = (op == IR_IREM || op == IR_UREM);
    if (!is_rem) {
        if (is_signed) aarch64_enc_sdiv(enc, 1, A_X0, A_X0, A_X1);
        else           aarch64_enc_udiv(enc, 1, A_X0, A_X0, A_X1);
    } else {
        if (is_signed) aarch64_enc_sdiv(enc, 1, A_X2, A_X0, A_X1);
        else           aarch64_enc_udiv(enc, 1, A_X2, A_X0, A_X1);
        aarch64_enc_msub(enc, 1, A_X0, A_X2, A_X1, A_X0);
    }
}
static void a64JitUnary(void *be, IrOp op) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (op == IR_INEG) aarch64_enc_neg_reg(enc, 1, A_X0, A_X0);
    else               aarch64_enc_mvn_reg(enc, 1, A_X0, A_X0);
}
static void a64JitShiftImm(void *be, IrOp op, int sh) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    if (op == IR_SHL)      aarch64_enc_lsl_imm(enc, 1, A_X0, A_X0, (uint32_t)sh);
    else if (op == IR_SHR) aarch64_enc_lsr_imm(enc, 1, A_X0, A_X0, (uint32_t)sh);
    else                   aarch64_enc_asr_imm(enc, 1, A_X0, A_X0, (uint32_t)sh);
}
static void a64JitShiftReg(void *be, IrOp op, IrInstr *i) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    AsmEnc *enc = &ctx->jit->enc;
    jitLoadToReg(ctx, i->r2, A_X1);
    if (op == IR_SHL)      aarch64_enc_lslv(enc, 1, A_X0, A_X0, A_X1);
    else if (op == IR_SHR) aarch64_enc_lsrv(enc, 1, A_X0, A_X0, A_X1);
    else                   aarch64_enc_asrv(enc, 1, A_X0, A_X0, A_X1);
}
static void a64JitLoadX1(void *be, IrInstr *i) {
    jitLoadToReg((JitFnCtx *)be, i->r2, A_X1);
}
static void a64JitFbinop(void *be, IrOp op, int width) {
    AsmEnc *enc = &((JitFnCtx *)be)->jit->enc;
    int opcode4 = (op == IR_FMUL) ? 0 : (op == IR_FDIV) ? 1 :
                  (op == IR_FADD) ? 2 : 3 /* FSUB */;
    aarch64_enc_fp_2src_scalar(enc, width == 8, opcode4, A_X0, A_X0, A_X1);
}
static void a64JitLoadD1(void *be, IrInstr *i) {
    jitLoadToFpr((JitFnCtx *)be, i->r2, A_X1 /*d1*/);
}
static void a64JitFneg(void *be, int width) {
    aarch64_enc_fneg_scalar(&((JitFnCtx *)be)->jit->enc, width == 8, A_X0, A_X0);
}
static void a64JitBitop(void *be, IrOp op, int width, IrInstr *i) {
    JitFnCtx *ctx = (JitFnCtx *)be;
    AsmEnc *enc = &ctx->jit->enc;
    int is64 = width == 64;
    uint32_t sf = is64 ? 0x80000000u : 0u;
    switch (op) {
        case IR_ROTL:
        case IR_ROTR:
            if (i->r2 && i->r2->kind == IR_VAL_CONST_INT) {
                uint32_t k = (uint32_t)(i->r2->as._i64 & (width - 1));
                if (op == IR_ROTL) k = (uint32_t)((width - k) & (width - 1));
                /* ror #k = extr x0, x0, x0, #k */
                put_word(enc, (is64 ? 0x93C00000u : 0x13800000u) | (k << 10));
            } else {
                jitLoadToReg(ctx, i->r2, A_X1);
                if (op == IR_ROTL) aarch64_enc_neg_reg(enc, is64, A_X1, A_X1);
                put_word(enc, sf | 0x1AC02C00u | (1u << 16));       /* rorv x0, x0, x1 */
            }
            break;
        case IR_BSWAP:
            if (width == 16) {
                put_word(enc, 0x5AC00400u);                         /* rev16 w0, w0 */
                aarch64_enc_uxth(enc, A_X0, A_X0);
            } else {
                put_word(enc, is64 ? 0xDAC00C00u : 0x5AC00800u);    /* rev */
            }
            break;
        case IR_CLZ:
            put_word(enc, sf | 0x5AC01000u);                        /* clz */
            break;
        case IR_CTZ:
            put_word(enc, sf | 0x5AC00000u);                        /* rbit */
            put_word(enc, sf | 0x5AC01000u);                        /* clz */
            break;
        default:
            break;
    }
}
static void a64JitArithInit(A64ArithEmitter *e, JitFnCtx *ctx) {
    e->be        = ctx;
    e->load_gp   = a64JitCvtLoadGp;   e->load_fpr  = a64JitCvtLoadFpr;
    e->spill_gp  = a64JitCvtSpillGp;  e->spill_fpr = a64JitCvtSpillFpr;
    e->alu_imm   = a64JitAluImm;      e->alu_reg   = a64JitAluReg;
    e->mul       = a64JitMul;         e->divrem    = a64JitDivRem;
    e->unary     = a64JitUnary;       e->shift_imm = a64JitShiftImm;
    e->shift_reg = a64JitShiftReg;    e->load_x1   = a64JitLoadX1;
    e->fbinop    = a64JitFbinop;      e->load_d1   = a64JitLoadD1;
    e->fneg      = a64JitFneg;
    e->bitop     = a64JitBitop;
}

static void jitEmitInstr(JitFnCtx *ctx, IrInstr *instr) {
    KtmJit *jit = ctx->jit;
    AsmEnc *enc = &jit->enc;

    switch (instr->op) {
        case IR_NOP:
        case IR_LABEL:
        case IR_ALLOCA:
        case IR_GEP:
            break;

        case IR_LOAD: {
            if (instr->r1 && instr->r1->pinned_reg) {
                A64Reg src = jitRegFromName(instr->r1->pinned_reg->data);
                aarch64_enc_mov_reg(enc, A_X0, src);
                jitSpillDst(ctx, instr, A_X0);
                break;
            }
            if (instr->dst && irIsFloat(instr->dst->type)) {
                int size = (int)instr->dst->as.var.size;
                int loff = irCgGetLoff(&ctx->fn->ra, instr->r1);
                jitFpFrameLoad(enc, A_X0 /*d0*/, size, loff);
                jitSpillDstFpr(ctx, instr, A_X0 /*d0*/);
                break;
            }
            int loff = irCgGetLoff(&ctx->fn->ra, instr->r1);
            int size = instr->dst ? (int)instr->dst->as.var.size : 8;
            jitFrameLoad(enc, A_X0, size, loff);
            jitSpillDst(ctx, instr, A_X0);
            break;
        }

        case IR_STORE: {
            /* Unified lowering shared with the AOT backend (aarch64-emit.h). */
            A64Emitter e;
            a64JitEmitInit(&e, ctx);
            a64EmitStore(&e, instr);
            break;
        }

        case IR_LOAD_DEREF: {
            /* Global base: materialise address into x1, then load. */
            if (instr->r1 && instr->r1->kind == IR_VAL_GLOBAL) {
                jitGlobalAddr(jit, A_X1, instr->r1->as.global.name->data);
                int size = instr->dst ? (int)instr->dst->as.var.size : 8;
                if (instr->dst && irIsFloat(instr->dst->type)) {
                    aarch64_enc_fp_ldst_imm(enc, 1, (int)irValueByteSize(instr->dst),
                                            A_X0 /*d0*/, A_X1, 0);
                    jitSpillDstFpr(ctx, instr, A_X0);
                } else {
                    jitDerefLoad(enc, size, A_X0, A_X1, 0, A_X0, 0, 0);
                    jitSpillDst(ctx, instr, A_X0);
                }
                break;
            }
            A64Reg base, idx;
            int has_idx = jitAddrRegs(ctx, instr, instr->r1, 0, &base, &idx);
            if (instr->dst && irIsFloat(instr->dst->type)) {
                int fsz = (int)irValueByteSize(instr->dst);
                if (has_idx) {
                    int sh = (instr->scale == 8) ? 3 :
                             (instr->scale == 4) ? 2 :
                             (instr->scale == 2) ? 1 : 0;
                    base = jitIdxBase(enc, base, (s64)instr->disp);
                    aarch64_enc_ldst_regoff(enc, 1, 1, fsz, sh ? 1 : 0,
                                            A_X0 /*d0*/, base, idx);
                } else if (instr->disp != 0) {
                    if (instr->disp >= 0 && (instr->disp % fsz) == 0 &&
                        instr->disp <= 4095 * fsz) {
                        aarch64_enc_fp_ldst_imm(enc, 1, fsz, A_X0 /*d0*/,
                                                base, (uint32_t)instr->disp);
                    } else {
                        jitEmitMovImm(enc, A_X10, (s64)instr->disp);
                        aarch64_enc_ldst_regoff(enc, 1, 1, fsz, 0,
                                                A_X0 /*d0*/, base, A_X10);
                    }
                } else {
                    aarch64_enc_fp_ldst_imm(enc, 1, fsz, A_X0 /*d0*/, base, 0);
                }
                jitSpillDstFpr(ctx, instr, A_X0);
                break;
            }
            int size = instr->dst ? (int)instr->dst->as.var.size : 8;
            jitDerefLoad(enc, size, A_X0, base, has_idx, idx,
                         instr->scale, instr->disp);
            jitSpillDst(ctx, instr, A_X0);
            break;
        }

        case IR_STORE_DEREF: {
            if (instr->dst && instr->dst->kind == IR_VAL_GLOBAL) {
                jitGlobalAddr(jit, A_X1, instr->dst->as.global.name->data);
                int sz = (int)irValueByteSize(instr->r1);
                if (instr->r1 && irIsFloat(instr->r1->type)) {
                    jitLoadFirstSrcFpr(ctx, instr->r1);
                    aarch64_enc_fp_ldst_imm(enc, 0, (int)irValueByteSize(instr->r1),
                                            A_X0 /*d0*/, A_X1, 0);
                } else {
                    jitLoadFirstSrc(ctx, instr->r1);
                    jitDerefStore(enc, sz, A_X0, A_X1, 0, A_X0, 0, 0);
                }
                break;
            }
            A64Reg base, idx;
            int has_idx = jitAddrRegs(ctx, instr, instr->dst, 0, &base, &idx);
            if (instr->r1 && irIsFloat(instr->r1->type)) {
                int fsz = (int)irValueByteSize(instr->r1);
                jitLoadFirstSrcFpr(ctx, instr->r1);
                if (has_idx) {
                    int sh = (instr->scale == 8) ? 3 :
                             (instr->scale == 4) ? 2 :
                             (instr->scale == 2) ? 1 : 0;
                    base = jitIdxBase(enc, base, (s64)instr->disp);
                    aarch64_enc_ldst_regoff(enc, 1, 0, fsz, sh ? 1 : 0,
                                            A_X0 /*d0*/, base, idx);
                } else if (instr->disp != 0) {
                    if (instr->disp >= 0 && (instr->disp % fsz) == 0 &&
                        instr->disp <= 4095 * fsz) {
                        aarch64_enc_fp_ldst_imm(enc, 0, fsz, A_X0 /*d0*/,
                                                base, (uint32_t)instr->disp);
                    } else {
                        jitEmitMovImm(enc, A_X10, (s64)instr->disp);
                        aarch64_enc_ldst_regoff(enc, 1, 0, fsz, 0,
                                                A_X0 /*d0*/, base, A_X10);
                    }
                } else {
                    aarch64_enc_fp_ldst_imm(enc, 0, fsz, A_X0 /*d0*/, base, 0);
                }
                break;
            }
            int sz = (int)irValueByteSize(instr->r1);
            A64Reg val = A_X0;
            if (has_idx && val == idx) val = A_X9;
            if (val == base) val = A_X9;
            jitLoadToReg(ctx, instr->r1, val);
            jitDerefStore(enc, sz, val, base, has_idx, idx, instr->scale, instr->disp);
            break;
        }

        case IR_RMW_DEREF: {
            A64Reg base, idx;
            int has_idx = jitAddrRegs(ctx, instr, instr->dst, 1, &base, &idx);
            int sz = (int)irValueByteSize(instr->r1);
            jitDerefLoad(enc, sz, A_X0, base, has_idx, idx, instr->scale, instr->disp);
            IrOp rop = instr->extra.rmw_op;
            /* Fold add/sub immediates like the AOT backend. The JIT has no
             * logical-immediate encoder, so and/or/xor stay on the reg path. */
            if (instr->r1 && instr->r1->kind == IR_VAL_CONST_INT &&
                (rop == IR_IADD || rop == IR_ISUB) &&
                jitIsAddImm(instr->r1->as._i64))
            {
                u32 imm = (u32)instr->r1->as._i64;
                if (rop == IR_IADD) aarch64_enc_add_imm(enc, 1, A_X0, A_X0, imm);
                else                aarch64_enc_sub_imm(enc, 1, A_X0, A_X0, imm);
            } else {
                jitLoadToReg(ctx, instr->r1, A_X9);
                switch (rop) {
                    case IR_IADD: aarch64_enc_add_reg(enc, 1, A_X0, A_X0, A_X9); break;
                    case IR_ISUB: aarch64_enc_sub_reg(enc, 1, A_X0, A_X0, A_X9); break;
                    case IR_AND:  aarch64_enc_and_reg(enc, 1, A_X0, A_X0, A_X9); break;
                    case IR_OR:   aarch64_enc_orr_reg(enc, 1, A_X0, A_X0, A_X9); break;
                    case IR_XOR:  aarch64_enc_eor_reg(enc, 1, A_X0, A_X0, A_X9); break;
                    default: loggerPanic("jit-aarch64: bad RMW op %d\n", rop);
                }
            }
            jitDerefStore(enc, sz, A_X0, base, has_idx, idx, instr->scale, instr->disp);
            break;
        }

        case IR_LEA: {
            if (instr->r1 && instr->r1->kind == IR_VAL_GLOBAL) {
                const char *name = instr->r1->as.global.name->data;
                if (instr->r1->flags & IR_VAL_FLAG_FUNC) {
                    name = asmNormaliseFunctionName(jit->cc,
                            instr->r1->as.global.name);
                }
                jitGlobalAddr(jit, A_X0, name);
            } else if (instr->r1) {
                int loff = irCgGetLoff(&ctx->fn->ra, instr->r1);
                if (loff >= 0 && jitIsAddImm(loff)) {
                    aarch64_enc_add_imm(enc, 1, A_X0, A_FP, (uint32_t)loff);
                } else if (loff < 0 && jitIsAddImm(-loff)) {
                    aarch64_enc_sub_imm(enc, 1, A_X0, A_FP, (uint32_t)(-loff));
                } else {
                    jitEmitMovImm(enc, A_X9, (s64)loff);
                    aarch64_enc_add_reg(enc, 1, A_X0, A_FP, A_X9);
                }
            }
            jitSpillDst(ctx, instr, A_X0);
            break;
        }

        case IR_IADD: case IR_ISUB: case IR_AND: case IR_OR: case IR_XOR:
        case IR_IMUL: case IR_IDIV: case IR_UDIV: case IR_IREM: case IR_UREM:
        case IR_INEG: case IR_NOT: case IR_SHL: case IR_SHR: case IR_SAR:
        case IR_ROTL: case IR_ROTR: case IR_BSWAP: case IR_CLZ: case IR_CTZ:
        case IR_FADD: case IR_FSUB: case IR_FMUL: case IR_FDIV: case IR_FNEG: {
            /* Unified arithmetic lowering shared with the AOT (aarch64-emit.h). */
            A64ArithEmitter ae;
            a64JitArithInit(&ae, ctx);
            a64EmitArith(&ae, instr);
            break;
        }

        case IR_ICMP: {
            jitLoadFirstSrc(ctx, instr->r1);
            if (instr->r2 && instr->r2->kind == IR_VAL_CONST_INT &&
                jitIsAddImm(instr->r2->as._i64))
            {
                aarch64_enc_cmp_imm(enc, 1, A_X0, (uint32_t)instr->r2->as._i64);
            } else {
                jitLoadToReg(ctx, instr->r2, A_X1);
                aarch64_enc_cmp_reg(enc, 1, A_X0, A_X1);
            }
            jitEmitCSet(enc, instr->extra.cmp_kind, 0);
            jitSpillDst(ctx, instr, A_X0);
            break;
        }

        case IR_FCMP: {
            int is_dbl = jitFpDbl(instr->r1);
            jitLoadFirstSrcFpr(ctx, instr->r1);
            if (instr->r2 && instr->r2->kind == IR_VAL_CONST_FLOAT &&
                ieee754_64(instr->r2->as._f64) == 0)
            {
                aarch64_enc_fcmp_zero(enc, is_dbl, A_X0);
            } else {
                jitLoadToFpr(ctx, instr->r2, A_X1 /*d1*/);
                aarch64_enc_fcmp_reg2(enc, is_dbl, A_X0, A_X1);
            }
            jitEmitCSet(enc, instr->extra.cmp_kind, 1);
            jitSpillDst(ctx, instr, A_X0);
            break;
        }

        case IR_CMP_BR: {
            IrCmpKind kind = instr->extra.cmp_br.cmp_kind;
            IrBlock *t = instr->extra.cmp_br.target_block;
            IrBlock *f = instr->extra.cmp_br.fallthrough_block;
            int is_float = instr->r1 && irIsFloat(instr->r1->type);
            if (is_float) {
                int is_dbl = jitFpDbl(instr->r1);
                jitLoadFirstSrcFpr(ctx, instr->r1);
                if (instr->r2 && instr->r2->kind == IR_VAL_CONST_FLOAT &&
                    ieee754_64(instr->r2->as._f64) == 0)
                {
                    aarch64_enc_fcmp_zero(enc, is_dbl, A_X0);
                } else {
                    jitLoadToFpr(ctx, instr->r2, A_X1);
                    aarch64_enc_fcmp_reg2(enc, is_dbl, A_X0, A_X1);
                }
            } else {
                jitLoadFirstSrc(ctx, instr->r1);
                if (instr->r2 && instr->r2->kind == IR_VAL_CONST_INT &&
                    jitIsAddImm(instr->r2->as._i64))
                {
                    aarch64_enc_cmp_imm(enc, 1, A_X0, (uint32_t)instr->r2->as._i64);
                } else {
                    jitLoadToReg(ctx, instr->r2, A_X1);
                    aarch64_enc_cmp_reg(enc, 1, A_X0, A_X1);
                }
            }
            A64Cond cc_t = jitCcFor(kind, is_float);
            A64Cond cc_f = jitCcInvFor(kind, is_float);
            int t_ln = ktmJitBlockLocalNum(jit, ctx->fn, t);
            int f_ln = ktmJitBlockLocalNum(jit, ctx->fn, f);
            int t_phi = irBlockHasPhi(t);
            int f_phi = irBlockHasPhi(f);
            if (!t_phi && !f_phi) {
                if (ctx->next_block == t) {
                    jitEmitBcondLocal(ctx, cc_f, f_ln);
                } else if (ctx->next_block == f) {
                    jitEmitBcondLocal(ctx, cc_t, t_ln);
                } else {
                    jitEmitBcondLocal(ctx, cc_t, t_ln);
                    jitEmitBranchLocal(ctx, f_ln);
                }
            } else {
                int else_ln = ktmJitFreshLocalNum(jit);
                jitEmitBcondLocal(ctx, cc_f, else_ln);
                jitPhiMaterialise(ctx, ctx->cur_block, t);
                jitEmitBranchLocal(ctx, t_ln);
                asm_define_label(enc, else_ln, NULL);
                jitPhiMaterialise(ctx, ctx->cur_block, f);
                if (ctx->next_block != f) jitEmitBranchLocal(ctx, f_ln);
            }
            break;
        }

        case IR_BR: {
            IrBlock *t = instr->extra.blocks.target_block;
            IrBlock *f = instr->extra.blocks.fallthrough_block;
            jitLoadFirstSrc(ctx, instr->dst);
            aarch64_enc_cmp_imm(enc, 1, A_X0, 0);
            int t_ln = ktmJitBlockLocalNum(jit, ctx->fn, t);
            int f_ln = ktmJitBlockLocalNum(jit, ctx->fn, f);
            int t_phi = irBlockHasPhi(t);
            int f_phi = irBlockHasPhi(f);
            if (!t_phi && !f_phi) {
                if (ctx->next_block == t) {
                    jitEmitBcondLocal(ctx, A_EQ, f_ln);
                } else if (ctx->next_block == f) {
                    jitEmitBcondLocal(ctx, A_NE, t_ln);
                } else {
                    jitEmitBcondLocal(ctx, A_NE, t_ln);
                    jitEmitBranchLocal(ctx, f_ln);
                }
            } else {
                int else_ln = ktmJitFreshLocalNum(jit);
                jitEmitBcondLocal(ctx, A_EQ, else_ln);
                jitPhiMaterialise(ctx, ctx->cur_block, t);
                jitEmitBranchLocal(ctx, t_ln);
                asm_define_label(enc, else_ln, NULL);
                jitPhiMaterialise(ctx, ctx->cur_block, f);
                if (ctx->next_block != f) jitEmitBranchLocal(ctx, f_ln);
            }
            break;
        }

        case IR_JMP: {
            IrBlock *target = instr->extra.blocks.target_block;
            jitPhiMaterialise(ctx, ctx->cur_block, target);
            if (target != ctx->next_block) {
                int ln = ktmJitBlockLocalNum(jit, ctx->fn, target);
                jitEmitBranchLocal(ctx, ln);
            }
            break;
        }

        case IR_RET:
            if (instr->dst && instr->dst->byval_struct_type) {
                /* <=16-byte struct returned in registers: load its bytes from
                 * the return slot into v0.. (HFA) or x0,x1 (INTEGER). */
                AstType *t = instr->dst->byval_struct_type;
                int loff = irCgGetLoff(&ctx->fn->ra, instr->dst);
                int re = 0, rc = 0;
                AapcsClass rcls = astAapcsClassify(t, &re, &rc);
                if (rcls == AAPCS_HFA) {
                    for (int k = 0; k < rc; ++k)
                        jitFpFrameLoad(enc, (A64Reg)k, re, loff + k * re);
                } else { /* INTEGER, <=16 bytes -> x0,(x1) */
                    int ngp = (t->size + 7) / 8;
                    for (int k = 0; k < ngp; ++k) {
                        int off = k * 8, rem = t->size - off;
                        if (jitIsPow2Access(rem)) {
                            jitFrameLoad(enc, (A64Reg)k, rem >= 8 ? 8 : rem,
                                         loff + off);
                        } else {
                            /* x10/x11 are dead at the return. */
                            jitFrameAddrX11(enc, loff + off);
                            jitLoadBytes(enc, (A64Reg)k, A_X11, 0, rem);
                        }
                    }
                }
            } else if (instr->dst) {
                if (irIsFloat(instr->dst->type)) jitLoadFirstSrcFpr(ctx, instr->dst);
                else                              jitLoadFirstSrc(ctx, instr->dst);
            }
            /* The epilogue label is defined straight after the last block,
             * so a ret ending that block falls through to it. */
            if (ctx->next_block != NULL ||
                ctx->cur_block->instructions->prev->value != (void *)instr)
            {
                jitEmitBranchLocal(ctx, ktmJitEpilogueLocalNum(jit, ctx->fn));
            }
            break;

        case IR_TRUNC: case IR_ZEXT: case IR_SEXT:
        case IR_FPTRUNC: case IR_FPEXT: case IR_FPTOSI: case IR_FPTOUI:
        case IR_SITOFP: case IR_UITOFP:
        case IR_PTRTOINT: case IR_INTTOPTR: case IR_BITCAST: {
            /* Unified conversion lowering shared with the AOT (aarch64-emit.h). */
            A64ConvEmitter ce;
            a64JitConvInit(&ce, ctx);
            a64EmitConvert(&ce, instr);
            break;
        }

        case IR_PHI:
            /* Materialised at predecessor's terminator. */
            break;

        case IR_CALL: {
            IrValue *wrap = instr->r1;
            Vec *args = wrap ? wrap->as.array.values : NULL;
            AoStr *fname = wrap ? wrap->as.array.label : NULL;
            int indirect = (fname == NULL);
            if (indirect) {
                if (!instr->r2) loggerPanic("jit-aarch64: indirect call without target\n");
                jitLoadToReg(ctx, instr->r2, A_X16);
            }
            /* By-value struct args: use the uniform AAPCS path. */
            int use_aapcs = (instr->flags & IRCG_CALL_AGG_RETURN) != 0;
            if (args) {
                for (u64 ai = 0; ai < args->size; ++ai) {
                    if (vecGet(IrValue *, args, ai)->byval_struct_type) {
                        use_aapcs = 1;
                        break;
                    }
                }
            }
            if (use_aapcs) {
                A64CallEmitter ce;
                a64JitCallInit(&ce, ctx);
                a64EmitCall(&ce, instr, args, fname, indirect);
                break;
            }
            Ast *callee = NULL;
            if (!indirect) {
                callee = (Ast *)cctrlCalleeDecl(jit->cc,
                                          fname->data, fname->len);
            }
            int callee_va = 0, named_count = 0;
            if (callee) {
                if ((callee->type && callee->type->has_var_args) || callee->has_var_args)
                    callee_va = 1;
                else if (callee->params && callee->params->size > 0) {
                    Ast *last = vecGet(Ast *, callee->params, callee->params->size - 1);
                    if (last && last->kind == AST_VAR_ARGS) callee_va = 1;
                }
                if (callee->params) {
                    named_count = (int)callee->params->size;
                    if (callee_va && named_count > 0) named_count--;
                }
            }
            /* Through a pointer to a ktm `...` function. */
            if (instr->flags & IRCG_CALL_KTM_VA) {
                callee_va = 1;
                named_count = instr->extra.va_named_count;
            }
            int apple_target = jit->cc->target == TARGET_AARCH64_APPLE_DARWIN;
            int ktm_variadic = callee_va && (!callee || callee->kind != AST_EXTERN_FUNC);
            int extern_variadic = callee_va && callee && callee->kind == AST_EXTERN_FUNC;
            int force_va_stack = ktm_variadic || (extern_variadic && apple_target);

            u64 n = args ? args->size : 0;
            u8 *is_stack = n ? calloc(n, 1) : NULL;
            s32 n_stack = jitPartitionCallArgs(is_stack, n, args, callee_va,
                                               named_count, force_va_stack);
            int stack_bytes = (n_stack * 8 + 15) & ~15;
            jitSpAdjust(enc, 1, (u64)stack_bytes);
            int stack_idx = 0;
            for (u64 i = 0; i < n; ++i) {
                if (!is_stack[i]) continue;
                IrValue *a = vecGet(IrValue *, args, i);
                uint32_t off = (uint32_t)(stack_idx * 8);
                if (irIsFloat(a->type)) {
                    jitLoadToFpr(ctx, a, A_X0 /*d0*/);
                    aarch64_enc_fp_ldst_imm(enc, 0, (int)irValueByteSize(a),
                                            A_X0, A_SP, off);
                } else {
                    jitLoadToReg(ctx, a, A_X9);
                    aarch64_enc_str_imm(enc, A_X9, A_SP, off);
                }
                stack_idx++;
            }
            int int_idx = 0, float_idx = 0;
            for (u64 i = 0; i < n; ++i) {
                if (is_stack[i]) continue;
                IrValue *a = vecGet(IrValue *, args, i);
                if (irIsFloat(a->type)) {
                    jitLoadToFpr(ctx, a, (A64Reg)float_idx++);
                } else {
                    jitLoadToReg(ctx, a, (A64Reg)int_idx++);
                }
            }
            if (indirect) {
                aarch64_enc_blr(enc, A_X16);
            } else {
                char *normalised = asmNormaliseFunctionName(jit->cc, fname);
                size_t off = aarch64_jit_emit_bl_placeholder(enc);
                ktmJitAddCallFixup(enc, off, normalised, AFR_AARCH64_CALL26);
            }
            jitSpAdjust(enc, 0, (u64)stack_bytes);
            if (instr->dst && instr->dst->type != IR_TYPE_VOID) {
                if (irIsFloat(instr->dst->type)) jitSpillDstFpr(ctx, instr, A_X0);
                else                              jitSpillDst   (ctx, instr, A_X0);
            }
            free(is_stack);
            break;
        }

        case IR_ASM:
            /* Inline `asm {...}` statement: libtasm-encode and splice
             * the bytes at the current position. */
            if (instr->extra.asm_fragments) {
                loggerPanic("jit-aarch64: `&var` asm fragments are not "
                            "supported; address locals directly\n");
            }
            if (instr->r1 && instr->r1->as.str.str &&
                ktmJitAssembleText(jit, instr->r1->as.str.str, 0) != 0)
            {
                loggerPanic("jit-aarch64: failed to assemble inline asm\n");
            }
            break;

        case IR_SELECT:
        case IR_SWITCH:
        case IR_VA_ARG:
        case IR_VA_START:
        case IR_VA_END:
            loggerPanic("jit-aarch64: op %d not yet implemented\n", instr->op);
        default:
            loggerPanic("jit-aarch64: unknown op %d\n", instr->op);
    }
}

/* ---------------- prologue / epilogue ---------------- */

/* Reserve `aligned` bytes of frame after asking rt.__ktmStackCheck
 * whether fp - aligned is still on the stack (matches the AOT prologue),
 * keeping x0-x8 and d0-d7 around the call. Nothing is touched. */
static void jitStackCheck(JitFnCtx *ctx, AsmEnc *enc, uint32_t aligned) {
    jitSpAdjust(enc, 1, 144);
    for (int i = 0; i < 9; ++i) aarch64_enc_str_imm(enc, (A64Reg)i, A_SP, (uint32_t)(i * 8));
    for (int i = 0; i < 8; ++i) aarch64_enc_fp_ldst_imm(enc, 0, 8, (A64Reg)i, A_SP, (uint32_t)(80 + i * 8));
    aarch64_enc_mov_imm64(enc, A_X9, aligned);
    aarch64_enc_sub_reg(enc, 1, A_X0, A_FP, A_X9);
    AoStr sym = { .data = astRuntimeSymbol("__ktmStackCheck"), .len = 0, .capacity = 0 };
    sym.len = strlen(sym.data);
    size_t off = aarch64_jit_emit_bl_placeholder(enc);
    ktmJitAddCallFixup(enc, off, asmNormaliseFunctionName(ctx->jit->cc, &sym), AFR_AARCH64_CALL26);
    for (int i = 0; i < 9; ++i) aarch64_enc_ldr_imm(enc, (A64Reg)i, A_SP, (uint32_t)(i * 8));
    for (int i = 0; i < 8; ++i) aarch64_enc_fp_ldst_imm(enc, 1, 8, (A64Reg)i, A_SP, (uint32_t)(80 + i * 8));
    jitSpAdjust(enc, 0, 144);
    jitSpAdjust(enc, 1, aligned);
}

static void jitEmitPrologue(JitFnCtx *ctx) {
    AsmEnc *enc = &ctx->jit->enc;
    if (ctx->omit_frame) return;
    aarch64_jit_emit_stp_pre(enc, A_FP, A_LR, A_SP, -16);
    /* `mov x29, sp` -- can't use the ORR alias here because Xm=31
     * decodes as XZR in logical-shifted-register form. Use the ADD-imm
     * alias instead, which treats Rn=31 as SP. */
    aarch64_enc_add_imm(enc, 1, A_FP, A_SP, 0);
    uint32_t aligned = ((uint32_t)ctx->fn->stack_space + 15u) & ~15u;
    ctx->aligned_frame = aligned;
    if (aligned > KTM_STACK_PROBE_STRIDE) jitStackCheck(ctx, enc, aligned);
    else                                  jitSpAdjust(enc, 1, aligned);
}

static void jitEmitEpilogue(JitFnCtx *ctx) {
    AsmEnc *enc = &ctx->jit->enc;
    if (ctx->omit_frame) {
        aarch64_jit_emit_ret(enc);
        return;
    }
    jitSpAdjust(enc, 0, ctx->aligned_frame);
    aarch64_jit_emit_ldp_post(enc, A_FP, A_LR, A_SP, 16);
    aarch64_jit_emit_ret(enc);
}

/* ---------------- per-function compilation ---------------- */

static int jitCompileFunction(KtmJit *jit, Ast *ast, IrCtx *ir_ctx) {
    IrFunction *fn = irLowerFunction(ir_ctx, ast);
    irBasicFunctionOptimisations(fn);

    /* Layout passes (slot offsets, IR-CG ctx). The text path goes
     * through irFunctionPrepForCodeGen; same here so the regalloc loff
     * map is populated before we ask for offsets. */
    IrCgCtx dummy = {0};
    dummy.cc = jit->cc;
    dummy.fn = fn;
    irFunctionPrepForCodeGen(&dummy, fn, ast);

    int is_variadic = (ast->type && ast->type->has_var_args) || ast->has_var_args;
    /* A by-value struct param is unpacked into a frame slot relative to
     * x29 by a64EmitParamPrologue, so the frame must be set up even
     * if the body looks trivial. */
    int has_struct_param = 0;
    if (ast->params) {
        for (u64 pi = 0; pi < ast->params->size; ++pi) {
            Ast *p = vecGet(Ast *, ast->params, pi);
            if (jitIsByvalStruct(p->type)) { has_struct_param = 1; break; }
        }
    }
    int omit_frame = (fn->stack_space == 0) && !irFnHasCalls(fn) &&
                     (ast->loff == 0) && !is_variadic && !has_struct_param;

    JitFnCtx ctx = {0};
    ctx.jit = jit;
    ctx.fn = fn;
    ctx.ast = ast;
    ctx.omit_frame = omit_frame;

    /* Publish the function entry as a public label. asmNormaliseFunctionName
     * strips/adds underscores per platform; the resolver uses the same name. */
    char *fname = asmNormaliseFunctionName(jit->cc, ast->fname);
    asm_define_label(&jit->enc, -1, fname);

    jitEmitPrologue(&ctx);
    {
        A64ParamEmitter pe;
        a64JitParamInit(&pe, &ctx);
        a64EmitParamPrologue(&pe, ctx.ast, ctx.fn);
    }

    Set *referenced = irCgComputeReferencedBlocks(fn);

    listForEach(fn->blocks) {
        IrBlock *block = (IrBlock *)it->value;
        ctx.cur_block = block;
        ctx.next_block = (it->next != fn->blocks)
                       ? (IrBlock *)it->next->value : NULL;
        if (setHas(referenced, (void *)(u64)block->id)) {
            int ln = ktmJitBlockLocalNum(jit, fn, block);
            asm_define_label(&jit->enc, ln, NULL);
        }
        listForEach(block->instructions) {
            IrInstr *in = (IrInstr *)it->value;
            ktmJitNoteLine(jit, in->line);
            jitEmitInstr(&ctx, in);
        }
    }
    setRelease(referenced);

    /* Epilogue label + bytes. */
    asm_define_label(&jit->enc, ktmJitEpilogueLocalNum(jit, fn), NULL);
    jitEmitEpilogue(&ctx);

    return 0;
}

/* ---------------- reg pool setup ---------------- */

/* Self-contained pool init: aarch64.c's aarch64InitRegPool is static,
 * so we replicate the (small) setup here. The text path may not have
 * run yet, and even if it has the pool is idempotent (irRegPoolSet
 * just overwrites the slot). */
static void jitInitRegPool(void) {
    static int initialised = 0;
    static IrRegPool pool;
    if (initialised) return;

    static const char *const kInt[]   = { "x0","x1","x2","x3","x4","x5","x6","x7" };
    static const char *const kFloat[] = { "d0","d1","d2","d3","d4","d5","d6","d7" };
    static const char *const kScratch[] = { "x0","x1","x2","d0","d1" };

    Vec *iv = vecNew(&vec_aostr_type);
    Vec *fv = vecNew(&vec_aostr_type);
    Vec *sv = vecNew(&vec_aostr_type);
    for (int i = 0; i < 8; i++) vecPush(iv, aoStrDupRaw((char *)kInt[i], 2));
    for (int i = 0; i < 8; i++) vecPush(fv, aoStrDupRaw((char *)kFloat[i], 2));
    for (int i = 0; i < 5; i++) vecPush(sv, aoStrDupRaw((char *)kScratch[i], 2));

    pool.int_arg_regs     = iv;
    pool.float_arg_regs   = fv;
    pool.int_return_reg   = aoStrDupRaw((char *)"x0", 2);
    pool.float_return_reg = aoStrDupRaw((char *)"d0", 2);
    pool.sret_reg         = aoStrDupRaw((char *)"x8", 2);
    pool.scratch_regs     = sv;
    pool.variadic_on_stack = 1;
    irRegPoolSet(&pool);
    initialised = 1;
}

/* ---------------- public API ---------------- */

static int jitTargetOk(enum CliTarget target) {
    return target == TARGET_AARCH64_APPLE_DARWIN ||
           target == TARGET_AARCH64_UNKNOWN_LINUX_GNU;
}

static const KtmJitBackend aarch64_jit_backend = {
    .name = "aarch64",
    .target_ok = jitTargetOk,
    .init_reg_pool = jitInitRegPool,
    .compile_function = jitCompileFunction,
};

KtmJit *aarch64JitCompile(Cctrl *cc) {
    return ktmJitCompile(cc, &aarch64_jit_backend);
}

const KtmJitBackend *aarch64JitBackend(void) {
    return &aarch64_jit_backend;
}

#endif /* __aarch64__ && KTM_ENABLE_JIT */
