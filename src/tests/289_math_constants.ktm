#include "testhelper.ktm"

/* M_PI, M_E and the other maths constants were defined in math.ktm but
 * not exported in std.ktmh, so programs got "M_PI has not been defined". */

/* within an ulp or two: libm results may differ in the last bit */
Bool Near(F64 a, F64 b)
{
  return fabs(a - b) <= fabs(b) * 1e-15;
}

I32 Main()
{
  "Test - maths constants: ";
  I64 correct = 0, tests = 0;

  tests++; if (Near(M_PI, acos(-1.0))) correct++;
  tests++; if (M_PI == 3.141592653589793) correct++;
  tests++; if (M_E == 2.718281828459045) correct++;
  tests++; if (M_PI_2 == M_PI / 2) correct++;
  tests++; if (M_PI_4 == M_PI / 4) correct++;
  tests++; if (M_1_PI == 1 / M_PI) correct++;
  tests++; if (M_2_PI == 2 / M_PI) correct++;
  tests++; if (Near(M_2_SQRTPI, 2 / sqrt(M_PI))) correct++;
  tests++; if (Near(M_E, exp(1.0))) correct++;
  tests++; if (Near(M_LOG2E, log2(M_E))) correct++;
  tests++; if (Near(M_LOG10E, log10(M_E))) correct++;
  tests++; if (Near(M_LN2, log(2.0))) correct++;
  tests++; if (Near(M_LN10, log(10.0))) correct++;
  tests++; if (M_SQRT2 == sqrt(2.0)) correct++;
  tests++; if (M_SQRT1_2 == sqrt(0.5)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
