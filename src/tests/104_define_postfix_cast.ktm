#include "testhelper.ktm"

/* A postfix cast in a #define's value, `#define X (5(U64) % 3)`, crashed
 * ktm: the stub parser that folds a define had no type table to look `U64`
 * up in. The cast must fold the way it runs, and set the define's type. */

#define CAST_MOD    (5(U64) % 3)
#define CAST_ONLY   5(U64)
#define CAST_END    (1 + 2(U8))
#define CAST_U8     300(U8)
#define CAST_I8     (200(I8))
#define CAST_U32    (-1)(U32)
#define CAST_PTR    0x1000(U8*)
#define CAST_PTR_OP (0(U8*) + 3)
#define CAST_F64    5(F64)
#define CAST_F64_OP (5(F64) / 2)
#define CAST_TO_INT 2.7(I64)
#define CAST_INT_OP (2.7(I64) * 2)
#define CAST_NESTED (CAST_ONLY % 3)
#define CAST_TWICE  (CAST_NESTED(U8) + 1(I16))
#define CAST_FMAC   (CAST_F64 * CAST_ONLY(F64))
#define PLAIN_INT   (1 + 2)
#define PLAIN_MIX   (1 + 2.5)

#if 7(U64) % 4 == 3
#define CAST_IF 1
#else
#define CAST_IF 0
#endif

I32 Main()
{
  I64 correct = 0, tests = 20;

  "Test - postfix cast inside a #define: ";

  if (CAST_MOD == 2) correct++;
  if (CAST_ONLY == 5 && !StrCmp(typeof(CAST_ONLY), "u64")) correct++;
  if (!StrCmp(typeof(CAST_MOD), "u64")) correct++;
  if (CAST_END == 3) correct++;
  if (CAST_U8 == 44 && CAST_U8 == 300(U8)) correct++;
  if (CAST_I8 == -56) correct++;
  if (CAST_U32 == 4294967295) correct++;
  if (CAST_PTR == 4096) correct++;
  if (CAST_PTR_OP == 3) correct++;
  if (CAST_F64 == 5.0 && !StrCmp(typeof(CAST_F64), "f64")) correct++;
  if (CAST_F64_OP == 2.5) correct++;
  if (CAST_TO_INT == 2 && !StrCmp(typeof(CAST_TO_INT), "i64")) correct++;
  if (CAST_INT_OP == 4) correct++;
  if (CAST_NESTED == 2) correct++;
  if (CAST_TWICE == 3) correct++;
  if (CAST_FMAC == 25.0) correct++;
  if (CAST_IF == 1) correct++;

  /* The same expressions outside a macro. */
  if ((5(U64) % 3) == CAST_MOD && (5(F64) / 2) == CAST_F64_OP) correct++;

  /* Without a cast a define has the type of its value: any float
   * operand makes it an F64 (test 127). */
  if (PLAIN_INT == 3 && !StrCmp(typeof(PLAIN_INT), "i64")) correct++;
  if (PLAIN_MIX == 3.5 && !StrCmp(typeof(PLAIN_MIX), "f64")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
