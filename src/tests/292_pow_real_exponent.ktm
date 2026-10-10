#include "testhelper.ktm"

/* Pow took an I64 exponent, so a real one was silently truncated:
 * Pow(4.0, 0.5) was 1.0 and Pow(2.0, 2.9) was 4.0. The exponent is now
 * an F64: integral values in the I64 range keep the exact repeated
 * squaring, everything else (fractional, NaN, infinite, huge) is pow's. */

Bool Near(F64 got, F64 want)
{
  F64 d = got - want;
  if (d < 0) d = -d;
  if (want < 0) want = -want;
  return d <= want * 1e-12;
}

Bool IsNan(F64 x) { return x != x; }

I32 Main()
{
  "Test - Pow with a real exponent: ";
  I64 correct = 0, tests = 0;
  F64 inf = 1.0 / 0.0, nn = 0.0 / 0.0, half = 0.5, ex = 1.5;
  I64 ib = 3, ie = 4;

  /* Fractional exponents. */
  tests++; if (Pow(4.0, 0.5) == 2.0) correct++;
  tests++; if (Pow(4.0, half) == 2.0) correct++;
  tests++; if (Pow(4.0, ex) == 8.0) correct++;
  tests++; if (Pow(2.0, 2.9) == pow(2.0, 2.9)) correct++;
  tests++; if (Near(Pow(2.0, 2.9), 7.464263932294459)) correct++;
  tests++; if (Near(Pow(2.0, -0.5), 0.7071067811865476)) correct++;
  tests++; if (Pow(27.0, 1.0 / 3.0) == pow(27.0, 1.0 / 3.0)) correct++;
  tests++; if (Pow(0.0, 0.5) == 0.0) correct++;
  tests++; if (Pow(0.0, -0.5) == inf) correct++;
  tests++; if (IsNan(Pow(-8.0, 1.0 / 3.0))) correct++;
  tests++; if (IsNan(Pow(-2.0, 0.5))) correct++;

  /* Integral exponents keep the repeated-squaring results. */
  tests++; if (Pow(2, 10) == 1024.0) correct++;
  tests++; if (Pow(ib, ie) == 81.0) correct++;
  tests++; if (Pow(2.0, 3.0) == 8.0) correct++;
  tests++; if (Pow(-2.0, 3.0) == -8.0) correct++;
  tests++; if (Pow(-2, 4) == 16.0) correct++;
  tests++; if (Pow(10, -2) == 0.1 * 0.1) correct++;
  tests++; if (Pow(2.0, -0.0) == 1.0) correct++;
  tests++; if (Pow(-0.0, -1) == -inf) correct++;
  tests++; if (Pow(-1.0, 4503599627370497.0) == -1.0) correct++;
  tests++; if (Pow(2, I64_MIN) == 0.0) correct++;
  tests++; if (Pow(0.5, I64_MIN) == inf) correct++;
  I64 r = Pow(3, 3);
  tests++; if (r == 27) correct++;

  /* NaN and infinite exponents behave like pow. */
  tests++; if (IsNan(Pow(2.0, nn))) correct++;
  tests++; if (Pow(1.0, nn) == 1.0) correct++;
  tests++; if (Pow(nn, 0) == 1.0) correct++;
  tests++; if (Pow(nn, 0.0) == 1.0) correct++;
  tests++; if (IsNan(Pow(nn, 2))) correct++;
  tests++; if (Pow(2.0, inf) == inf) correct++;
  tests++; if (Pow(0.5, inf) == 0.0) correct++;
  tests++; if (Pow(2.0, -inf) == 0.0) correct++;
  tests++; if (Pow(0.5, -inf) == inf) correct++;
  tests++; if (Pow(-1.0, inf) == 1.0) correct++;
  tests++; if (Pow(-1.0, -inf) == 1.0) correct++;

  /* Integral but outside the I64 range: pow. */
  tests++; if (Pow(-1.0, 1e300) == 1.0) correct++;
  tests++; if (Pow(1.5, 1e19) == inf) correct++;
  tests++; if (Pow(1.5, -1e19) == 0.0) correct++;
  tests++; if (Pow(-2.0, 9223372036854775808.0) == inf) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
