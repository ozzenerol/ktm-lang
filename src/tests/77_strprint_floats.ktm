#include "testhelper.ktm"

/* StrPrint and friends printed "Floats not supported" and exited on %f,
 * so JsonToString exited on any JSON containing a float. %f, %e and %g
 * must now print exactly what C's printf prints for the same double. */

extern "c" I64 snprintf(U8 *buf, U64 len, U8 *fmt, ...);
extern "c" F64 strtod(U8 *nptr, U8 **endptr);

I64 g_checks = 0;
I64 g_bad = 0;

U0 Check(U8 *fmt, F64 v)
{
  U8 ours[512], want[512];
  StrPrint(ours, fmt, v);
  snprintf(want, sizeof(want), fmt, v);
  g_checks++;
  if (StrCmp(ours, want)) {
    g_bad++;
    "\n  %s: got '%s', C printf gives '%s'", fmt, ours, want;
  }
}

I32 Main()
{
  I64 correct = 0, tests = 13, i, j;
  U8 *fmts[14] = {
    "%f", "%.2f", "%.0f", "%.1f", "%.10f", "%8.2f", "%-8.2f|", "%08.2f",
    "%e", "%.3e", "%.0e", "%g", "%.3g", "%10.4g"
  };
  F64 vals[24];
  F64 neg_zero = 0.0;
  neg_zero = neg_zero * -1.0;

  "Test - StrPrint %%f/%%e/%%g match C printf: ";

  vals[0] = 0.0;
  vals[1] = neg_zero;
  vals[2] = 1.5;
  vals[3] = -1.5;
  vals[4] = 2.675;
  vals[5] = 0.125;
  vals[6] = 0.5;
  vals[7] = 2.5;
  vals[8] = 3.14159265358979;
  vals[9] = 1.0e15;
  vals[10] = 1.0e300;
  vals[11] = strtod("1e-7", NULL); /* ktm lexes 1.0e-7 wrong */
  vals[12] = -123456.789;
  vals[13] = -0.0001234;
  vals[14] = 9.9999999;
  vals[15] = 0.05;
  vals[16] = strtod("1e-300", NULL);
  vals[17] = strtod("4.9406564584124654e-324", NULL); /* smallest denormal */
  vals[18] = strtod("1.7976931348623157e308", NULL);  /* largest F64 */
  vals[19] = 100.0;
  vals[20] = 1.0e21;
  vals[21] = 0.000099999;
  vals[22] = 999999.5;
  vals[23] = 1.0 / 3.0;

  for (i = 0; i < 24; ++i) {
    for (j = 0; j < 14; ++j) {
      Check(fmts[j], vals[i]);
    }
  }
  if (!g_bad) correct++;

  /* inf and nan, with width and flags */
  F64 inf = strtod("inf", NULL), nan = strtod("nan", NULL);
  g_bad = 0;
  for (j = 0; j < 14; ++j) {
    Check(fmts[j], inf);
    Check(fmts[j], -inf);
    Check(fmts[j], nan);
  }
  if (!g_bad) correct++;

  /* the exact strings, not just "same as C" */
  U8 buf[64];
  if (!StrCmp(StrPrint(buf, "%f", 1.5), "1.500000")) correct++;
  if (!StrCmp(StrPrint(buf, "%.2f", 2.675), "2.67")) correct++;
  if (!StrCmp(StrPrint(buf, "[%8.2f][%-8.2f][%08.2f]", -1.5, 1.5, -1.5),
              "[   -1.50][1.50    ][-0001.50]")) correct++;
  if (!StrCmp(StrPrint(buf, "%f %f", vals[11], neg_zero),
              "0.000000 -0.000000")) correct++;
  if (!StrCmp(StrPrint(buf, "%f|%f|%5f", inf, -inf, nan),
              "inf|-inf|  nan")) correct++;
  if (!StrCmp(StrPrint(buf, "%d %.3f %s", 7, 0.0005, "x"), "7 0.001 x")) {
    correct++; /* 0.0005 is just above the tie, so it rounds up */
  }

  /* %s into a caller's buffer must not MSize/ReAlloc it: mid-array the
   * size header OutStr read is 0, so it "grew" and freed a bad pointer */
  U8 *mid = CAlloc(64);
  if (!StrCmp(StrPrint(mid + 16, "%s|%c", "ab", 'z'), "ab|z")) {
    correct++;
  }
  Free(mid);

  /* MStrPrint and CatPrint go through the same formatter */
  U8 *m = MStrPrint("pi=%.4f e=%.3e", 3.14159265358979, 2.718281828);
  if (!StrCmp(m, "pi=3.1416 e=2.718e+00")) correct++;
  Free(m);

  /* JsonParse -> JsonToString with floats no longer exits */
  Json *js = JsonParse("{\"pi\": 3.5, \"neg\": -0.25, \"arr\": [1.5, 2, 0.1]}");
  U8 *s = JsonToString(js);
  if (!StrCmp(s, "{\"pi\": 3.5,\"neg\": -0.25,\"arr\": [1.5,2,0.1]}")) {
    correct++;
  } else {
    "\n  JsonToString gave: %s", s;
  }
  Free(s);
  JsonRelease(js);

  /* floats print so they read back as the same float (with 15, 16 or 17
   * digits, so the smallest denormal is not the shortest -5e-324) */
  js = JsonParse("[2.0, 1.0e21, 0.30000000000000004, 1.0e-7, -4.9406564584124654e-324]");
  s = JsonToString(js);
  if (!StrCmp(s, "[2.0,1.0e+21,0.30000000000000004,1.0e-07,-4.94065645841247e-324]")) {
    correct++;
  } else {
    "\n  JsonToString gave: %s", s;
  }
  Json *back = JsonParse(s);
  if (back->array->type == JSON_FLOAT &&
      back->array->next->f64 == 1.0e21 &&
      back->array->next->next->f64 == 0.30000000000000004) {
    correct++;
  }
  Free(s);
  JsonRelease(back);
  JsonRelease(js);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
