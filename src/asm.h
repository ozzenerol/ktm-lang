#ifndef ASM_H__
#define ASM_H__

#include "aostr.h"
#include "cctrl.h"
#include "types.h"

char *asmNormaliseFunctionName(Cctrl *cc, AoStr *fname);
void asmEmitStringBytes(AoStr *buf, Ast *str);
int asmStringHasNul(Ast *str);
void asmEmitFunctionGlobal(Cctrl *cc, AoStr *buf, char *label);
void asmEmitFunctionSymbol(Cctrl *cc, AoStr *buf, char *label, Ast *func);
AoStr *asmNormaliseGlobalLabel(Cctrl *cc, AoStr *name);
AoStr *asmGenerate(Cctrl *cc);
/* Synthetic file-scope-initialiser `main` (calls the user's Main).
 * NULL when there are no initialisers. Shared by all backends. */
Ast *asmBuildInitialiserMain(Cctrl *cc);
uint64_t ieee754_64(double _f64);
uint32_t ieee754_32(f32 _f32);

/* Assemble a asm chunk (captured by prsasm.c) with
 * libtasm for cc->target and append the encoded bytes to `buf` as
 * `.byte` runs for the system assembler. Symbol references that
 * libtasm leaves as fixups are re-expressed as relocation expressions
 * (`.long sym - . - 4` for rel32) with platform name mangling applied.
 * Returns 0 on success; on failure pushes cctrl error diagnostics and
 * returns -1. */
int asmEmitBlockBytes(Cctrl *cc, AoStr *buf, AoStr *text, int src_line);

void asmEmitAsmInfo(Cctrl *cc, AoStr *buf);

/* The line table a crash report reads (rt.__ktmCrashInstall): each
 * function gets a record in the ktm_lines section with its address,
 * size, name, file and the code offset where each source line starts,
 * with the calls the inliner merged into it (IrInlineSite).
 * asmLinesReset before a module, asmLinesBegin after a function's
 * label, asmLinesNote before each instruction, asmLinesEnd after the
 * function; asmLinesEmit writes the tables, and in a program with a
 * main the constructor that installs the crash handler. */
void asmLinesReset(void);
void asmLinesBegin(Cctrl *cc, Ast *func, int omit_frame);
struct IrInlineSite;
void asmLinesNote(AoStr *buf, int line, struct IrInlineSite *site);
void asmLinesEnd(AoStr *buf);
void asmLinesEmit(Cctrl *cc, AoStr *buf, int has_main);
/* A function's name and a file as the report shows them (the JIT
 * builds the same records) */
char *asmLinesName(Cctrl *cc, AoStr *fname);
const char *asmLinesPath(Cctrl *cc, const char *path);

/* Lay out the global initialiser `init` as the `type` object it sits in
 * memory as: class fields at their offsets, array elements at the
 * element stride and zeros everywhere else. `bytes` (type->size long)
 * receives the scalar values and `items` (type->size entries) the
 * literal or string that starts at each offset; a string stands for its
 * 8-byte address. Both must be zeroed by the caller. Shared by the AOT
 * data emitters and the JIT. */
void asmInitImage(Ast *init, AstType *type, u8 *bytes, Ast **items);
/* Bytes asmInitImage gives a literal or string item. */
int asmInitItemWidth(Ast *item);

/* The power of two a global of `type` is aligned to in the data
 * sections (`.p2align` operand): its natural alignment, so a class or
 * I64 after a `U8 a[3]` does not land on an odd address. */
int asmDataAlignLog2(AstType *type);
int asmAddrConst(Cctrl *cc, Ast *init, AoStr **_sym, s64 *_off,
                 int for_jit);
void asmEmitAddrConst(AoStr *buf, AoStr *sym, s64 off);

/* Append `init`, laid out by asmInitImage, to `buf` as data directives. */
void asmEmitInitData(AoStr *buf, Ast *init, AstType *type);

#endif
