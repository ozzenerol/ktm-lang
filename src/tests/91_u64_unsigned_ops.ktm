#include "testhelper.ktm"

/* `>>`, `/`, `%` and `<`/`>` on U64 behaved as signed (sarq/asr,
 * idiv/sdiv, signed compares): every integer binop was typed I64. An
 * operation is now unsigned when either operand is U64 (C's usual
 * arithmetic conversions with U64 = unsigned long). U8/U16/U32 promote
 * to I64, as they would to a 64-bit long in C. */

/* Globals are folded at compile time by the parser's evaluator. */
U64 g_shr = 0xC004000000000000(U64) >> 63;
U64 g_div = 0x8000000000000000(U64) / 3;
U64 g_rem = 0x8000000000000000(U64) % 3;
I64 g_gt  = 0x8000000000000000(U64) > 1;
I64 g_sar = -8 >> 1;
U64 g_lit_div = 0xFFFFFFFFFFFFFFFF / 2;

/* Literals above I64_MAX are typed U64 (C does this for hex literals);
 * a #define keeps the type of the value it folds to. */
#define BIG_LIT  0xC004000000000000
#define BIG_EXPR (0xFFFFFFFFFFFFFFFF / 2)

U64 g_hi = 0xC004000000000000;
I64 g_neg = -8;

U64 Shr(U64 a, I64 n) { return a >> n; }
U64 Div(U64 a, U64 b) { return a / b; }
U64 Rem(U64 a, U64 b) { return a % b; }
I64 Gt(U64 a, U64 b) { return a > b; }
I64 SDiv(I64 a, I64 b) { return a / b; }
I64 SRem(I64 a, I64 b) { return a % b; }
I64 Sar(I64 a, I64 n) { return a >> n; }

I32 Main()
{
  I64 correct = 0, tests = 38;

  "Test - U64 >>, /, %% and compares are unsigned: ";

  /* Runtime path through variables. */
  U64 w = g_hi;
  U64 s = w >> 63;
  if (s == 1) correct++;
  U64 v = 1;
  v <<= 63;
  if (v / 3 == 3074457345618258602) correct++;
  if (v % 3 == 2) correct++;
  if (Shr(w, 63) == 1) correct++;
  if (Div(v, 3) == 3074457345618258602) correct++;
  if (Rem(v, 3) == 2) correct++;
  if (Gt(v, 1)) correct++;

  /* Compound assignments. */
  U64 t = w;
  t >>= 62;
  if (t == 3) correct++;
  t = v;
  t /= 3;
  if (t == 3074457345618258602) correct++;
  t = v;
  t %= 3;
  if (t == 2) correct++;

  /* Comparisons, including in a branch. */
  U64 big = v;
  if (big > 1) correct++;
  if (!(big < 1)) correct++;
  if (big >= 2 && 2 <= big) correct++;
  I64 m = -1;
  if ((m < big) == 0) correct++;  /* -1 converts to U64 max, like C */

  /* Mixing I64 and U64 is unsigned; the result is U64. */
  if (m / 2(U64) == 0x7FFFFFFFFFFFFFFF) correct++;
  if (m >> 63(U64) == 1) correct++;

  /* Narrow unsigned types promote to I64 and are zero-extended. */
  U32 x = 0xFFFFFFFF;
  if (x >> 31 == 1) correct++;
  if (x / 2 == 0x7FFFFFFF) correct++;
  U16 h = 0xFFFF;
  U8 c = 0xFF;
  if (h >> 15 == 1 && c >> 7 == 1) correct++;
  if (x > m) correct++;  /* U32 -> I64, signed compare as in C */

  /* Signed behaviour is unchanged. */
  I64 neg = g_neg;
  if (neg >> 1 == -4 && Sar(neg, 1) == -4) correct++;
  I64 a = -7, b = 2;
  if (a / b == -3 && SDiv(a, b) == -3) correct++;
  if (a % b == -1 && SRem(a, b) == -1) correct++;
  neg >>= 1;
  if (neg == -4) correct++;

  /* Constant folding agrees with the runtime results. */
  if (g_shr == 1) correct++;
  if (g_div == 3074457345618258602 && g_rem == 2) correct++;
  if (g_gt == 1 && g_sar == -4) correct++;
  if (0xC004000000000000(U64) >> 63 == 1) correct++;
  if (0x8000000000000000(U64) / 3 == 3074457345618258602 &&
      0x8000000000000000(U64) % 3 == 2) correct++;
  if (-8 >> 1 == -4 && -7 / 2 == -3 && -7 % 2 == -1) correct++;

  /* Big literals are U64, literals that fit stay I64. */
  if (0xC004000000000000 >> 63 == 1) correct++;
  if (0xFFFFFFFFFFFFFFFF / 2 == 0x7FFFFFFFFFFFFFFF && g_lit_div == 0x7FFFFFFFFFFFFFFF) correct++;
  if (0x8000000000000000 > 1 && 18446744073709551615 % 10 == 5) correct++;
  if (BIG_LIT >> 63 == 1 && BIG_EXPR == 0x7FFFFFFFFFFFFFFF) correct++;
  if (U64_MAX > 1 && U64_MAX / 2 == I64_MAX) correct++;
  if (I64_MIN < 0 && I64_MIN / 2 == -4611686018427387904 && I64_MIN >> 62 == -2) correct++;
  if (-1 >> 1 == -1 && 0x7FFFFFFFFFFFFFFF > -1 && 9223372036854775807 > -1) correct++;
  if (!StrCmp(typeof(0xFFFFFFFFFFFFFFFF), "u64") &&
      !StrCmp(typeof(0x7FFFFFFFFFFFFFFF), "i64")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
