#ifndef JIT_COMMON_H__
#define JIT_COMMON_H__

/* Arch-independent machinery shared by the aarch64 and x86_64 JIT
 * backends: JIT state, label-number bookkeeping, fixup helpers, symbol
 * resolution, globals/string-literal layout, phi-materialise ordering,
 * and the compile-pipeline driver. The per-arch files own only the
 * instruction selection and emission.
 *
 * Build-gated: only compiled when KTM_ENABLE_JIT is defined. */

#include <stdint.h>

#include "ast.h"
#include "cctrl.h"
#include "containers.h"
#include "ir.h"
#include "asm/asm_enc.h"
#include "asm/asm_jit.h"

/* A mapping holding string literals, read-only after they are written */
typedef struct JitStrings {
    uint8_t *ptr;
    size_t len;
} JitStrings;

typedef struct KtmJit {
    Cctrl *cc;

    /* The arch backend driving compile_function for every chunk. */
    const struct KtmJitBackend *backend;

    /* Per-chunk accumulating encoder. All function bytes of the chunk
     * being built land here in emission order; public labels carry the
     * function name. Reset at the start of every ktmJitCompileChunk. */
    AsmEnc enc;

    /* name -> entry address inside an RX mapping. Accumulates across
     * chunks; redefinitions overwrite so lookups see the newest one. */
    Map *symbols;
    /* User-defined host symbols (printf etc.) - checked before dlsym.
     * Finalized functions and globals/string arenas are registered
     * here too so later chunks can address them. */
    Map *host_symbols;

    /* Function names defined in the chunk CURRENTLY being emitted.
     * These route through label fixups; everything else resolves via
     * host_symbols/dlsym. Reset per chunk. */
    Map *chunk_fns;

    /* Finalized RX mappings (AsmJitCode *), one per chunk. Old chunks
     * stay mapped forever - earlier code may hold pointers into them. */
    List *chunks;

    /* RW arenas for globals, one per chunk (string literals are in
     * string_arenas); the
     * absolute addresses are registered in host_symbols. */
    List *globals_arenas;
    /* String literals, read-only once written as AOT's are (a write
     * through one faults): each a JitStrings */
    List *string_arenas;
    /* Global pointers initialised with an address (`U8 *p = &g`, `&Fn`),
     * patched once the target has one (jitResolvePtrFixups) */
    List *ptr_fixups;

    /* Incremental cursors: last node of cc->ast_list / cc->asm_blocks
     * already consumed by a chunk. A chunk compiles (cursor, sentinel]. */
    List *ast_cursor;
    List *asm_cursor;

    /* Per-IrBlock -> AsmEnc local_num.  Key = (fn_uuid<<32) | block_id;
     * value = small int.  Local nums are pulled from `next_local`. */
    Map *block_local;
    /* Per-function epilogue label local_num. Key = fn_uuid. */
    Map *epi_local;
    int  next_local;

    /* pc -> source line. Backends note (enc offset, line) as they emit
     * (pending_lines); ktmJitCompileChunk rebases the offsets onto the
     * finalized chunk's address and appends to `lines`. Consulted only
     * when reporting (faults, memsafe), so a linear scan is fine. */
    /* function symbol -> the source file it was written in (char *);
     * for reports, like `lines` */
    Map *sym_files;
    struct KtmJitLine *lines;
    u32 n_lines, cap_lines;
    struct KtmJitLine *pending_lines;
    u32 n_pending, cap_pending;
    /* Every compiled function's code range, rebased like `lines`, for
     * the crash report (ktmJitRunMain); the backend sets fn_omit_frame
     * for the function it is emitting. */
    struct KtmJitFn *fns;
    u32 n_fns, cap_fns, n_pending_fns;
    int fn_omit_frame;
    u32 fn_file_id;  /* the function being compiled's file */
} KtmJit;

typedef struct KtmJitLine {
    uintptr_t addr; /* absolute pc in `lines`; enc offset in pending */
    int line;
    int own_line;   /* the line in the function's own file: an inlined
                     * call from another file's line instead of its own */
    struct IrInlineSite *site;  /* the inlined call it is in, or NULL */
} KtmJitLine;

typedef struct KtmJitFn {
    uintptr_t addr;  /* absolute once its chunk is final; enc offset before */
    size_t size;
    char *name;      /* as a report shows it */
    char *file;
    int omit_frame;
    int32_t *lines;  /* count pairs: offset, line | site << 20 (see asm.c) */
    int count;
    struct KtmJitSite *sites;
    int n_sites;
} KtmJitFn;

typedef struct KtmJitSite {
    char *name;
    char *file;                 /* NULL: the function's */
    int32_t line, outer;
} KtmJitSite;

/* What a per-arch backend plugs into the shared compile pipeline. */
typedef struct KtmJitBackend {
    /* For diagnostics ("aarch64", "x86_64"). */
    const char *name;
    /* Is this Cctrl target compatible with the backend? */
    int (*target_ok)(enum CliTarget target);
    /* Install the backend's IrRegPool (idempotent). */
    void (*init_reg_pool)(void);
    /* Lower + emit one function into jit->enc. Non-zero on error. */
    int (*compile_function)(KtmJit *jit, Ast *ast, IrCtx *ir_ctx);
} KtmJitBackend;

/* Run the full front-end -> IR -> native bytes -> RX mapping pipeline
 * for every function in `cc` through `backend`. NULL on error. */
KtmJit *ktmJitCompile(Cctrl *cc, const KtmJitBackend *backend);

/* Create an empty jit (no code compiled yet) for incremental use.
 * Checks target compatibility, installs the register pool and loads
 * libktm. NULL on error. */
KtmJit *ktmJitNew(Cctrl *cc, const KtmJitBackend *backend);

/* Compile everything appended to cc->ast_list / cc->asm_blocks since
 * the previous chunk - plus `extra_fn` if non-NULL (a synthetic
 * function that is NOT in ast_list, e.g. the REPL's per-input
 * wrapper) - into a fresh RX mapping. New globals and string literals
 * get slots in a new RW arena. Finalized function addresses are
 * registered so later chunks (and ktmJitLookup) can reach them;
 * redefinitions rebind the name for future chunks while old code
 * keeps calling the address it was linked against.
 *
 * The cursors advance even on failure so a broken input isn't
 * recompiled by the next call. Non-zero on error. */
int ktmJitCompileChunk(KtmJit *jit, Ast *extra_fn);

/* Look up a public function by name (tries the underscore-prefixed
 * macOS mangling too). The returned pointer is owned by the jit and
 * remains valid until ktmJitFree. */
void *ktmJitLookup(KtmJit *jit, const char *name);

/* Reverse-map a pc: the chunk whose live executable bytes (code +
 * veneers) contain it, or NULL if it isn't JIT'd code. */
AsmJitCode *ktmJitFindChunk(KtmJit *jit, void *pc);

/* Name of the JIT function containing `pc`: the nearest symbol at or
 * below it within its chunk's code (veneer pcs return NULL - use
 * ktmJitFindChunk to tell "veneer" from "not ours"). *off_out gets
 * pc - symbol. */
const char *ktmJitFindSymbolForAddr(KtmJit *jit, void *pc, size_t *off_out);

/* Record "code emitted from enc offset jit->enc.len onward is source
 * line `line`" - called by the backends per IR instruction; consecutive
 * duplicates collapse. line 0 (synthetic) is ignored. */
void ktmJitNoteLine(KtmJit *jit, int line, struct IrInlineSite *site);

/* Source line for a pc inside JIT code, in its function's own file (the
 * call's line in code inlined from another file), 0 when unknown. */
int ktmJitFindLineForAddr(KtmJit *jit, void *pc);
/* The source file of the function `sym` (from ktmJitFindSymbolForAddr),
 * NULL if unknown. */
const char *ktmJitFindFileForSymbol(KtmJit *jit, const char *sym);

/* Register a host-provided symbol (e.g. printf, malloc, a libktm hook)
 * so JITted code can call it. Overrides dlsym for that name. */
void ktmJitDefineSymbol(KtmJit *jit, const char *name, void *addr);
void *ktmJitSymbolAddr(KtmJit *jit, const char *sym);

/* Convenience: look up `main` and invoke it as `int main(int, char **)`,
 * returning its exit code. */
int ktmJitRunMain(KtmJit *jit, int argc, char **argv);

void ktmJitFree(KtmJit *jit);

/* ---- helpers for the per-arch emitters ---- */

/* Stable AsmEnc local label numbers for (fn, block) / fn epilogue, and
 * fresh anonymous ones for shim labels. */
int ktmJitBlockLocalNum(KtmJit *jit, IrFunction *fn, IrBlock *block);
int ktmJitEpilogueLocalNum(KtmJit *jit, IrFunction *fn);
int ktmJitFreshLocalNum(KtmJit *jit);

/* Was `sym` pre-registered as a function defined in this TU? Internal
 * functions route through label fixups; externals through dlsym. */
int ktmJitIsInternalFunc(KtmJit *jit, const char *sym);

/* AF_SYMBOL call fixup / AF_LOCAL branch fixup with the arch reloc. */
void ktmJitAddCallFixup(AsmEnc *enc, size_t patch_off, const char *sym,
                        AsmFixupReloc reloc);
void ktmJitAddLocalBranchFixup(AsmEnc *enc, size_t patch_off, int local_num,
                               AsmFixupReloc reloc);

/* Phi-materialise driver: collects `to`'s phis matching predecessor
 * `from`, orders them so no pending phi's source is overwritten, and
 * calls `emit` for each. `ud` is the backend's emission context. */
typedef void (*KtmJitEmitPhiFn)(void *ud, IrInstr *phi, IrPair *match);
void ktmJitPhiMaterialise(IrBlock *from, IrBlock *to,
                          KtmJitEmitPhiFn emit, void *ud);

/* Assemble a asm chunk (captured by prsasm.c) with
 * libtasm and splice the encoded bytes into the jit's encoder at the
 * current position. Used for `asm {}` function bodies and inline
 * IR_ASM statements. Non-zero on error (diagnostics already pushed). */
int ktmJitAssembleText(KtmJit *jit, AoStr *text, int src_line);

#endif
