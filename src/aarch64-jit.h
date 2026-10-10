#ifndef AARCH64_JIT_H__
#define AARCH64_JIT_H__

/* Optional in-process JIT for AArch64 hosts. Lowers IR straight to
 * native instruction bytes via the libtasm AArch64 encoders; all the
 * arch-independent machinery (state, globals, symbol resolution, the
 * runner) lives in jit-common.h.
 *
 * Build-gated: this header is only meaningful when KTM is built on an
 * AArch64 host with KTM_ENABLE_JIT defined. */

#include "cctrl.h"
#include "jit-common.h"

/* Run the front-end -> IR -> aarch64 bytes -> RX mapping pipeline for
 * every function in `cc`. Returns NULL on error (errors are reported
 * via the same channels as the AOT path). Use ktmJitRunMain /
 * ktmJitLookup / ktmJitFree on the result. */
KtmJit *aarch64JitCompile(Cctrl *cc);

/* The backend vtable, for incremental compilation via ktmJitNew /
 * ktmJitCompileChunk (the REPL). NULL on non-aarch64 hosts. */
const KtmJitBackend *aarch64JitBackend(void);

#endif
