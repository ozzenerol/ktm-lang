#ifndef IR_H__
#define IR_H__

#include "cctrl.h"
#include "ir-types.h"

/* Stack-clash probing stride: when a function's frame is larger than this,
 * the prologue reserves it one step at a time and touches each step, so a
 * frame bigger than the stack guard page faults on overflow instead of
 * jumping over the guard. 4096 is <= the guard on every target we support
 * (Linux 4 KiB, macOS arm64 16 KiB), so no page is ever skipped. */
#define KTM_STACK_PROBE_STRIDE 4096u

void irMemoryInit(void);
void irMemoryRelease(void);
void irMemoryStats(void);
void irDump(Cctrl *cc);
IrValue *irExpr(IrCtx *ctx, Ast *ast);
IrCtx *irLowerProgram(Cctrl *cc);
IrFunction *irLowerFunction(IrCtx *ctx, Ast *ast_func);

void irFunctionPrepForCodeGen(IrCgCtx *ctx, IrFunction *fn, Ast *ast_fn);

#endif
