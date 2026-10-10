#include "testhelper.ktm"

/* Comparing a float with an integer truncated the float to an integer
 * (`F64 b = 2.5; b == 2` was 1). Like C, the integer operand must be
 * converted to floating point and the comparison done on floats.
 * Every check compares the materialised value as an integer, so the
 * test never relies on a mixed comparison being a branch. */

I64 g_correct = 0;

U0 Expect(I64 got, I64 want)
{
  if (got == want) g_correct++;
}

F64 U64ToF64(U64 u) { F64 r = u; return r; }
F32 U64ToF32(U64 u) { F32 r = u; return r; }

I64 EqTwo(F64 x) { return x == 2; }
Bool LessInt(F64 x, I64 n) { return x < n; }

I32 Main()
{
  I64 tests = 85;

  "Test - mixed float/integer comparison: ";

  F64 b = 2.5, m = -2.5, w = 2.0, z = 0.0;
  F64 nan = z / z;
  F32 f = 2.5;
  I64 two = 2, three = 3;
  I32 i32 = 2;
  U8 u8 = 2;
  U64 u64 = 2;
  I64 v;

  /* The original report. */
  I64 r = b == 2;
  Expect(r, 0);

  /* Every operator, integer literal on either side. */
  v = b == 2; Expect(v, 0);  v = b != 2; Expect(v, 1);
  v = b < 3;  Expect(v, 1);  v = b <= 2; Expect(v, 0);
  v = b > 2;  Expect(v, 1);  v = b >= 3; Expect(v, 0);
  v = 2 == b; Expect(v, 0);  v = 2 != b; Expect(v, 1);
  v = 2 < b;  Expect(v, 1);  v = 3 <= b; Expect(v, 0);
  v = 2 > b;  Expect(v, 0);  v = 2 >= b; Expect(v, 0);

  /* Integer variables of several widths, both orders. */
  v = b == two;   Expect(v, 0);  v = b > two;    Expect(v, 1);
  v = b < three;  Expect(v, 1);  v = two < b;    Expect(v, 1);
  v = three <= b; Expect(v, 0);  v = two != b;   Expect(v, 1);
  v = b == i32;   Expect(v, 0);  v = b > i32;    Expect(v, 1);
  v = i32 >= b;   Expect(v, 0);  v = i32 < b;    Expect(v, 1);
  v = b == u8;    Expect(v, 0);  v = b > u8;     Expect(v, 1);
  v = u8 >= b;    Expect(v, 0);  v = u8 < b;     Expect(v, 1);
  v = b == u64;   Expect(v, 0);  v = b > u64;    Expect(v, 1);
  v = u64 >= b;   Expect(v, 0);  v = u64 < b;    Expect(v, 1);

  /* Exact matches still compare equal. */
  v = w == 2;    Expect(v, 1);  v = w == two;  Expect(v, 1);
  v = w <= two;  Expect(v, 1);  v = two >= w;  Expect(v, 1);
  v = w == u8;   Expect(v, 1);  v = w != i32;  Expect(v, 0);

  /* Negative values: truncation toward zero gave -2 == -2. */
  v = m == -2; Expect(v, 0);  v = m < -2;  Expect(v, 1);
  v = -2 > m;  Expect(v, 1);  v = m >= -2; Expect(v, 0);

  /* F32 operand. */
  v = f == 2;    Expect(v, 0);  v = f > 2;     Expect(v, 1);
  v = f <= two;  Expect(v, 0);  v = 2 < f;     Expect(v, 1);
  v = f == i32;  Expect(v, 0);  v = u8 >= f;   Expect(v, 0);

  /* NaN against an integer: only != is true. */
  v = nan == 0; Expect(v, 0);  v = nan != 0; Expect(v, 1);
  v = nan < 1;  Expect(v, 0);  v = nan >= 1; Expect(v, 0);
  v = 0 == nan; Expect(v, 0);  v = 0 != nan; Expect(v, 1);

  /* Branch form behaves like the value form. */
  v = 0; if (b == 2) v = 1;    Expect(v, 0);
  v = 0; if (b != 2) v = 1;    Expect(v, 1);
  v = 0; if (b > two) v = 1;   Expect(v, 1);
  v = 0; if (2 >= b) v = 1;    Expect(v, 0);
  v = 0; if (nan == 0) v = 1;  Expect(v, 0);
  v = 0; while (b <= 2) { v = 1; break; } Expect(v, 0);
  v = 0; for (; b > 2; ) { v = 7; break; } Expect(v, 7);

  /* Function returns. */
  Expect(EqTwo(2.5), 0);
  Expect(EqTwo(2.0), 1);
  Expect(LessInt(2.5, 3), 1);
  Expect(LessInt(2.5, 2), 0);

  /* Mixed arithmetic already promotes to float. */
  F64 s = b + 2, p = 2 * b, q = b / 2;
  I64 si = s * 10, pi = p * 10, qi = q * 100;
  Expect(si, 45);
  Expect(pi, 50);
  Expect(qi, 125);

  /* U64 -> float uses an unsigned conversion: values with the top bit
   * set used to come out negative on x86_64. */
  F64 two64 = 65536.0 * 65536.0 * 65536.0 * 65536.0;
  F64 two63 = two64 / 2, two53 = two64 / 2048;
  U64 u0 = 0, u1 = 1, u53 = 1 << 53, u63 = 1 << 63;
  U64 u63b = (1 << 63) + (1 << 11), umax = 0xFFFFFFFFFFFFFFFF;
  F64 x;
  v = U64ToF64(u0) == 0.0;      Expect(v, 1);
  v = U64ToF64(u1) == 1.0;      Expect(v, 1);
  v = U64ToF64(u53) == two53;   Expect(v, 1);
  v = U64ToF64(u63) == two63;   Expect(v, 1);
  v = U64ToF64(u63b) == two63 + 2048.0; Expect(v, 1);
  v = U64ToF64(umax) == two64;  Expect(v, 1);
  x = umax;                     v = x == two64; Expect(v, 1);
  x = umax * 1.0;               v = x == two64; Expect(v, 1);
  x = umax(F64);                v = x == two64; Expect(v, 1);
  x = u63(F64);                 v = x == two63; Expect(v, 1);
  x = 0xFFFFFFFFFFFFFFFF(U64);  v = x == two64; Expect(v, 1);
  v = umax > 1.0;  Expect(v, 1);  v = u63 > 0.0;  Expect(v, 1);
  v = 1.0 < u63;   Expect(v, 1);  v = umax == two64; Expect(v, 1);
  v = U64ToF32(umax) == two64;  Expect(v, 1);
  v = U64ToF32(u63) == two63;   Expect(v, 1);
  v = U64ToF32(u1) == 1.0;      Expect(v, 1);

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
