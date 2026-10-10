#include "testhelper.ktm"

/* Abs returned a U64, so everything done with its result was unsigned:
 * `Abs(x) > -1` was FALSE and `F64 f = Abs(-3) - 5` was 1.8e19. It now
 * returns an I64; Abs(I64_MIN) stays I64_MIN, like C's labs. */

I64 g_neg_one = -1;

I32 Main()
{
  "Test - Abs returns I64: ";
  I64 correct = 0, tests = 0;
  I64 x = -5, y = 12, zero = 0, mn = I64_MIN, mx = I64_MAX;
  F64 f;
  I64 i;

  tests++; if (Abs(x) > -1) correct++;
  tests++; if (Abs(y) > g_neg_one) correct++;
  tests++; if (Abs(zero) > -1) correct++;
  tests++; if (!(Abs(x) < -1)) correct++;
  tests++; if (Abs(x) >= g_neg_one) correct++;

  f = Abs(-3) - 5;
  tests++; if (f == -2.0) correct++;
  f = Abs(x) - 10;
  tests++; if (f == -5.0) correct++;
  tests++; if (Abs(-3) - 5 < 0) correct++;
  i = Abs(-7) / -2;
  tests++; if (i == -3) correct++;
  tests++; if (Abs(-7) % -4 == 3) correct++;
  tests++; if (-Abs(-9) < 0) correct++;
  tests++; if (Abs(-9) * -1 == -9) correct++;
  tests++; if ((Abs(-16) - 32) >> 2 == -4) correct++;
  f = Abs(-4);
  tests++; if (f == 4.0) correct++;

  tests++; if (Abs(x) == 5) correct++;
  tests++; if (Abs(y) == 12) correct++;
  tests++; if (Abs(zero) == 0) correct++;
  tests++; if (Abs(mx) == I64_MAX) correct++;
  tests++; if (Abs(-mx) == I64_MAX) correct++;
  tests++; if (Abs(mn + 1) == I64_MAX) correct++;
  tests++; if (Abs(mn) == I64_MIN) correct++;
  tests++; if (Abs(mn) < 0) correct++;
  f = Abs(mn);
  tests++; if (f < 0.0) correct++;

  U8 buf[32];
  StrPrint(buf, "%d", Abs(-42) - 50);
  tests++; if (!StrCmp(buf, "-8")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
