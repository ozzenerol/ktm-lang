#include "testhelper.ktm"

/* `#define F (1.5 * N)` with `#define N 3` folded to the I64 3: the
 * define took the type of the last macro (or the first literal) in its
 * value, so the float was truncated. A numeric define has the type of
 * its value, as C's arithmetic conversions give it: any float operand
 * makes it an F64, otherwise it is an I64. */

#define N          3
#define HALF       0.5
#define F_FIRST    (1.5 * N)
#define F_LAST     (N * 1.5)
#define F_MID      (N + 1.5 + N)
#define F_MACROS   (N * HALF)
#define F_MIX      (1 + 2.5)
#define F_ONLY     (1.5 * 2.0)
#define F_DIV      (N / 2.0)
#define F_NEG      (-N * 0.5)
#define F_NESTED   (F_FIRST + N)
#define I_ONLY     (N * 4 + 1)
#define I_DIV      (7 / 2)
#define I_NESTED   (I_ONLY - N)
#define I_CAST     (2.7(I64) * N)
#define I_CMP_F    (1.5 > 1)
#define I_CMP_M    (N > 2.5)
#define I_CMP_EQ   (N == 3.0)
#define C_PLUS     ('a' + 1)
#define C_HALF     ('d' * 0.5)
#define S_VAL      "str"
#define S_COPY     (S_VAL)

I32 Main()
{
  I64 correct = 0, tests = 20;

  "Test - #define with mixed int and float operands: ";

  if (F_FIRST == 4.5 && !StrCmp(typeof(F_FIRST), "F64")) correct++;
  if (F_LAST == 4.5 && !StrCmp(typeof(F_LAST), "F64")) correct++;
  if (F_MID == 7.5 && !StrCmp(typeof(F_MID), "F64")) correct++;
  if (F_MACROS == 1.5 && !StrCmp(typeof(F_MACROS), "F64")) correct++;
  if (F_MIX == 3.5 && !StrCmp(typeof(F_MIX), "F64")) correct++;
  if (F_ONLY == 3.0 && !StrCmp(typeof(F_ONLY), "F64")) correct++;
  if (F_DIV == 1.5 && !StrCmp(typeof(F_DIV), "F64")) correct++;
  if (F_NEG == -1.5 && !StrCmp(typeof(F_NEG), "F64")) correct++;
  if (F_NESTED == 7.5 && !StrCmp(typeof(F_NESTED), "F64")) correct++;

  if (I_ONLY == 13 && !StrCmp(typeof(I_ONLY), "I64")) correct++;
  if (I_DIV == 3 && !StrCmp(typeof(I_DIV), "I64")) correct++;
  if (I_NESTED == 10 && !StrCmp(typeof(I_NESTED), "I64")) correct++;
  if (I_CAST == 6 && !StrCmp(typeof(I_CAST), "I64")) correct++;

  /* A comparison is an integer, but compares its operands as floats. */
  if (I_CMP_F == 1 && !StrCmp(typeof(I_CMP_F), "I64")) correct++;
  if (I_CMP_M == 1 && I_CMP_EQ == 1) correct++;

  if (C_PLUS == 'b') correct++;
  if (C_HALF == 50.0 && !StrCmp(typeof(C_HALF), "F64")) correct++;
  if (!StrCmp(S_VAL, "str") && !StrCmp(S_COPY, "str")) correct++;

  /* The same values outside a macro. */
  if (F_FIRST == 1.5 * N && F_LAST == N * 1.5) correct++;
  if (F_MIX == 1 + 2.5 && I_DIV == 7 / 2) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
