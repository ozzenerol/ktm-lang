#include "testhelper.ktm"

/* A float comparison used as a value (not a branch condition) was
 * typed as F64 by the parser, so `I64 x = b < 3.0;` converted the 0/1
 * result as if it were a double and always produced 0. Comparisons
 * involving NaN must also follow C: only `!=` is true. Every check
 * below compares the materialised value as an integer, so the test
 * itself never relies on a float comparison being a branch. */

I64 g_correct = 0;

U0 Expect(I64 got, I64 want)
{
  if (got == want) g_correct++;
}

Bool IsTwo(F64 x) { return x == 2.0; }
I64 Less(F64 a, F64 c) { return a < c; }
I64 Id(I64 v) { return v; }

I32 Main()
{
  I64 tests = 77;

  "Test - F64 comparison used as a value: ";

  F64 b = 2.0, c = 3.0, z = 0.0;
  F64 nan = z / z;
  F32 f = 2.0;
  I64 v;

  /* The original report. */
  I64 eq = b == 2.0, lt = b < 3.0, ne = b != 1.0, gt = b > 1.0;
  Bool both = 1 == 1 && b == 2.0;
  Expect(eq, 1); Expect(lt, 1); Expect(ne, 1); Expect(gt, 1);
  Expect(both, 1);

  /* Every operator, both operand orders, F64 vs F64 variables. */
  v = b == c; Expect(v, 0);  v = b != c; Expect(v, 1);
  v = b < c;  Expect(v, 1);  v = b <= c; Expect(v, 1);
  v = b > c;  Expect(v, 0);  v = b >= c; Expect(v, 0);
  v = c == b; Expect(v, 0);  v = c != b; Expect(v, 1);
  v = c < b;  Expect(v, 0);  v = c <= b; Expect(v, 0);
  v = c > b;  Expect(v, 1);  v = c >= b; Expect(v, 1);
  v = b == b; Expect(v, 1);  v = b != b; Expect(v, 0);
  v = b < b;  Expect(v, 0);  v = b <= b; Expect(v, 1);
  v = b > b;  Expect(v, 0);  v = b >= b; Expect(v, 1);

  /* Literal on either side. */
  v = 2.0 == b; Expect(v, 1);  v = 2.0 != b; Expect(v, 0);
  v = 1.0 < b;  Expect(v, 1);  v = 3.0 <= b; Expect(v, 0);
  v = 3.0 > b;  Expect(v, 1);  v = 2.0 >= b; Expect(v, 1);

  /* F32 and mixed F64/I64. */
  v = f == 2.0; Expect(v, 1);  v = f != 2.0; Expect(v, 0);
  v = f < 3.0;  Expect(v, 1);  v = f <= 1.0; Expect(v, 0);
  v = f > 1.0;  Expect(v, 1);  v = f >= 3.0; Expect(v, 0);
  v = b == 2;   Expect(v, 1);  v = b != 2;   Expect(v, 0);
  v = b < 3;    Expect(v, 1);  v = 2 == b;   Expect(v, 1);

  /* NaN: unordered, so everything but != is false. */
  v = nan == nan; Expect(v, 0);  v = nan != nan; Expect(v, 1);
  v = nan < b;    Expect(v, 0);  v = nan <= b;   Expect(v, 0);
  v = nan > b;    Expect(v, 0);  v = nan >= b;   Expect(v, 0);
  v = b == nan;   Expect(v, 0);  v = b != nan;   Expect(v, 1);
  v = b < nan;    Expect(v, 0);  v = b <= nan;   Expect(v, 0);
  v = b > nan;    Expect(v, 0);  v = b >= nan;   Expect(v, 0);
  v = 0; if (nan == nan) v = 1; Expect(v, 0);
  v = 0; if (nan != nan) v = 1; Expect(v, 1);
  v = 0; if (nan < b) v = 1;    Expect(v, 0);
  v = 0; if (nan <= b) v = 1;   Expect(v, 0);
  v = 0; if (b > nan) v = 1;    Expect(v, 0);
  v = 0; if (b >= nan) v = 1;   Expect(v, 0);

  /* Negation and && / || chains. */
  v = !(b == 2.0); Expect(v, 0);
  v = !(b != 2.0); Expect(v, 1);
  v = !(nan == nan); Expect(v, 1);
  v = b < 1.0 || b > 1.0;     Expect(v, 1);
  v = b < 1.0 || nan == nan;  Expect(v, 0);
  v = b == 2.0 && c == 3.0;   Expect(v, 1);
  v = b == 2.0 && c < 3.0;    Expect(v, 0);

  /* Function arguments and return values. */
  Expect(b < c, 1);
  Expect(Id(b > 1.0), 1);
  Expect(IsTwo(2.0), 1);
  Expect(IsTwo(3.0), 0);
  Expect(Less(b, c), 1);
  Expect(Less(c, b), 0);

  /* Stored into Bool / U8, and used in arithmetic. */
  Bool bb = b == 2.0;
  U8 u = b < c;
  Expect(bb, 1);
  Expect(u, 1);
  I64 n = 0;
  n += b > 1.0;
  n += c > 1.0;
  n += nan > 1.0;
  Expect(n, 2);
  v = (b < c) + (c > b) * 10;
  Expect(v, 11);

  /* Range comparison. */
  v = 1.0 < b < 3.0; Expect(v, 1);
  v = 1.0 < b < 1.5; Expect(v, 0);

  /* Printed directly. */
  U8 buf[32];
  StrPrint(buf, "%d%d%d", b == 2.0, b < 1.0, nan != nan);
  Expect(StrCmp(buf, "101"), 0);

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
