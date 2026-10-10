#include "testhelper.ktm"

/* `#define X (0.5 && 1)` folded to 0: the constant folder truncated the
 * float operands of &&, || and ! to integers, so 0.5 counted as false.
 * An operand is true when it isn't 0 (0.5, -0.5 and 0.25 are true, 0.0
 * is false) and the result is an integer 0 or 1, so the define is an
 * I64, and the folded value is what the same expression gives at run
 * time. A float define with a logical operator inside, which the
 * float folder rejected, folds too. */

#define N         2
#define AND_F     (0.5 && 1)
#define AND_F2    (1 && 0.5)
#define AND_FF    (0.5 && 0.25)
#define AND_ZERO  (0.5 && 0.0)
#define AND_NEG   (-0.5 && 1)
#define AND_MACRO (N && 0.25)
#define OR_F      (0.25 || 0)
#define OR_ZERO   (0.0 || 0)
#define OR_ZEROF  (0 || 0.0)
#define NOT_F     (!0.5)
#define NOT_ZERO  (!0.0)
#define NOT_NOT   (!!0.5)
#define CMP_AND   (0.5 > 0.25 && 0.1)
#define F_AND     ((0.5 && 1) * 1.5)
#define F_NOT     (1.5 + !0.5)

I32 Main()
{
  I64 correct = 0, tests = 15;
  F64 h = 0.5, q = 0.25, z = 0.0;
  I64 n = N;

  "Test - #define with float operands of &&, || and !: ";

  if (AND_F == 1 && !StrCmp(typeof(AND_F), "I64")) correct++;
  if (AND_F2 == 1 && AND_FF == 1) correct++;
  if (AND_ZERO == 0 && AND_NEG == 1) correct++;
  if (AND_MACRO == 1) correct++;
  if (OR_F == 1 && !StrCmp(typeof(OR_F), "I64")) correct++;
  if (OR_ZERO == 0 && OR_ZEROF == 0) correct++;
  if (NOT_F == 0 && !StrCmp(typeof(NOT_F), "I64")) correct++;
  if (NOT_ZERO == 1 && NOT_NOT == 1) correct++;
  if (CMP_AND == 1) correct++;
  if (F_AND == 1.5 && !StrCmp(typeof(F_AND), "F64")) correct++;
  if (F_NOT == 1.5 && !StrCmp(typeof(F_NOT), "F64")) correct++;

  /* The folded value equals the run-time one. */
  if (AND_F == (h && 1) && AND_NEG == (-h && 1)) correct++;
  if (AND_MACRO == (n && q) && OR_F == (q || 0)) correct++;
  if (OR_ZERO == (z || 0) && NOT_F == !h) correct++;
  if (NOT_ZERO == !z && AND_ZERO == (h && z)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
