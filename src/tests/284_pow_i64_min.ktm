#include "testhelper.ktm"

/* Pow(x, I64_MIN) returned 1.0: negating the exponent overflowed back
 * to I64_MIN, which is not > 0, so the loop never ran. */

Bool Near(F64 got, F64 want)
{
  F64 d = got - want;
  if (d < 0) d = -d;
  if (want < 0) want = -want;
  return d <= want * 1e-12;
}

I32 Main()
{
  "Test - Pow with I64_MIN: ";
  I64 correct = 0, tests = 0;
  F64 inf = 1.0 / 0.0;

  tests++; if (Pow(2, I64_MIN) == 0.0) correct++;
  tests++; if (Pow(0.5, I64_MIN) == inf) correct++;
  tests++; if (Pow(0.0, I64_MIN) == inf) correct++;
  tests++; if (Pow(-0.5, I64_MIN) == inf) correct++;  /* even exponent */
  tests++; if (Pow(1.0, I64_MIN) == 1.0) correct++;
  tests++; if (Pow(-1.0, I64_MIN) == 1.0) correct++;

  /* The exponent is an F64: I64_MAX and I64_MIN + 1 round to the even
   * +-2^63, so their sign follows pow(-x, 2^63). */
  tests++; if (Pow(2, I64_MAX) == inf) correct++;
  tests++; if (Pow(-2, I64_MAX) == inf) correct++;
  tests++; if (Pow(-1.0, I64_MAX) == 1.0) correct++;
  tests++; if (Pow(0.5, I64_MAX) == 0.0) correct++;
  tests++; if (Pow(-1.0, I64_MIN + 1) == 1.0) correct++;
  tests++; if (Pow(-1.0, 4503599627370497) == -1.0) correct++;  /* 2^52+1, odd */

  /* Ordinary exponents. */
  tests++; if (Pow(2, 10) == 1024.0) correct++;
  tests++; if (Pow(2, 0) == 1.0) correct++;
  tests++; if (Pow(0.0, 0) == 1.0) correct++;
  tests++; if (Pow(-2, 3) == -8.0) correct++;
  tests++; if (Pow(-2, 4) == 16.0) correct++;
  tests++; if (Pow(2, -2) == 0.25) correct++;
  tests++; if (Pow(-2, -3) == -0.125) correct++;
  tests++; if (Near(Pow(1.5, 7), 17.0859375)) correct++;
  tests++; if (Near(Pow(10, -3), 0.001)) correct++;
  tests++; if (Pow(0.0, 3) == 0.0) correct++;
  tests++; if (Pow(0.0, -1) == inf) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
