#include "testhelper.ktm"

#define EXP_TEST_FILE "__89_float_exp_fail.ktm"
#define EXP_TEST_OUT  "__89_float_exp_fail.out"

/* `1e3` used to lex as the integer 1 (strtoll stops at the `e`) and
 * `1.0e-7` as `1.0e` followed by `- 7`. A literal with an exponent is
 * an F64 whether or not it has a `.`. */
Bool TestExponentLiterals(U0)
{
  Bool ok = TRUE;
  F64 a = 1e3, b = 1E3, c = 1e+3, d = 1e-3, e = 1.5e3, f = .5e2, g = 2.e1;
  F64 h = 1.0e-7, k = 1.0e21, m = 1e308, n = 1e-320, z = 0e0;

  if (a != 1000.0) { "1e3 = %f\n", a; ok = FALSE; }
  if (b != 1000.0) { "1E3 = %f\n", b; ok = FALSE; }
  if (c != 1000.0) { "1e+3 = %f\n", c; ok = FALSE; }
  if (d != 0.001) { "1e-3 = %f\n", d; ok = FALSE; }
  if (e != 1500.0) { "1.5e3 = %f\n", e; ok = FALSE; }
  if (f != 50.0) { ".5e2 = %f\n", f; ok = FALSE; }
  if (g != 20.0) { "2.e1 = %f\n", g; ok = FALSE; }
  if (h != 0.0000001) { "1.0e-7 = %e\n", h; ok = FALSE; }
  if (k != 1000000000000000000000.0) { "1.0e21 = %e\n", k; ok = FALSE; }
  if (m < 9.9e307 || m > 1.1e308) { "1e308 = %e\n", m; ok = FALSE; }
  /* 1e-320 is a denormal: non-zero and below the smallest normal. */
  if (n <= 0.0 || n >= 2.2e-308) { "1e-320 = %e\n", n; ok = FALSE; }
  if (z != 0.0) { "0e0 = %f\n", z; ok = FALSE; }
  /* In an expression, and negated */
  if (1e3 + 1 != 1001.0) { "1e3 + 1 wrong\n"; ok = FALSE; }
  if (-2e2 != -200.0) { "-2e2 wrong\n"; ok = FALSE; }
  if (2e2-1 != 199.0) { "2e2-1 wrong\n"; ok = FALSE; }
  return ok;
}

/* `e`/`E` are hex digits; binary literals, `...` ranges and the
 * f32/f64 suffix share the same scanner. */
Bool TestNeighbours(U0)
{
  Bool ok = TRUE;
  I64 x = 0x1E3, y = 0xe, w = 0b1010, hit = 0;
  F64 s = 1.5e1f64, t = 2e2f32, u = 2.5f64;

  if (x != 483) { "0x1E3 = %d\n", x; ok = FALSE; }
  if (y != 14) { "0xe = %d\n", y; ok = FALSE; }
  if (0x1E+3 != 33) { "0x1E+3 wrong\n"; ok = FALSE; }
  if (w != 10) { "0b1010 = %d\n", w; ok = FALSE; }
  if (s != 15.0) { "1.5e1f64 = %f\n", s; ok = FALSE; }
  if (t != 200.0) { "2e2f32 = %f\n", t; ok = FALSE; }
  if (u != 2.5) { "2.5f64 = %f\n", u; ok = FALSE; }
  switch (2) {
    case 1...3: hit = 1; break;
  }
  if (!hit) { "case 1...3 missed\n"; ok = FALSE; }
  return ok;
}

/* JSON numbers like `1e+15` have no `.` but are still floats. */
Bool TestJsonExponent(U0)
{
  Bool ok = TRUE;
  Json *j = JsonParse("{\"a\": 1e+15, \"b\": 2E3, \"c\": -5e-1, \"d\": 1.5e2, \"e\": 42}");
  Json *o;

  if (!j) return FALSE;
  o = JsonSelect(j, ".a");
  if (!o || o->type != JSON_FLOAT || o->f64 != 1e15) { "json 1e+15 wrong\n"; ok = FALSE; }
  o = JsonSelect(j, ".b");
  if (!o || o->type != JSON_FLOAT || o->f64 != 2000.0) { "json 2E3 wrong\n"; ok = FALSE; }
  o = JsonSelect(j, ".c");
  if (!o || o->type != JSON_FLOAT || o->f64 != -0.5) { "json -5e-1 wrong\n"; ok = FALSE; }
  o = JsonSelect(j, ".d");
  if (!o || o->type != JSON_FLOAT || o->f64 != 150.0) { "json 1.5e2 wrong\n"; ok = FALSE; }
  o = JsonSelect(j, ".e");
  if (!o || o->type != JSON_INT || o->i64 != 42) { "json 42 wrong\n"; ok = FALSE; }
  return ok;
}

U0 CleanupExpTest(U0)
{
  Rm(EXP_TEST_FILE);
  Rm(EXP_TEST_OUT);
  Rm("./a.out");
}

/* An exponent with no digits must be a normal compile error with the
 * file and line, not silently `1`. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupExpTest;

  if (!FileWrite(EXP_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " EXP_TEST_FILE " > " EXP_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    CleanupExpTest;
    return FALSE;
  }

  out = FileRead(EXP_TEST_OUT);
  if (!out) {
    CleanupExpTest;
    return FALSE;
  }

  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupExpTest;
    return FALSE;
  }

  Free(out);
  CleanupExpTest;
  return TRUE;
}

Bool TestMissingExponentDigits(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith("U0 Main()\n{\n  F64 x = 1e;\n}\n",
                        EXP_TEST_FILE ":3:")) ok = FALSE;
  if (!CompileFailsWith("U0 Main()\n{\n  F64 x = 1e+;\n}\n",
                        "malformed numeric literal")) ok = FALSE;
  if (!CompileFailsWith("U0 Main()\n{\n\n  F64 x = 1.5E-;\n}\n",
                        EXP_TEST_FILE ":4:")) ok = FALSE;
  if (!CompileFailsWith("U0 Main()\n{\n  F64 x = 1e3e2;\n}\n",
                        "malformed numeric literal")) ok = FALSE;
  return ok;
}

I32 Main()
{
  "Test - Float exponent literals: \n";
  I64 tests = 4;
  I64 correct = 0;

  if (TestExponentLiterals()) correct++;
  else "Failed - TestExponentLiterals()\n";

  if (TestNeighbours()) correct++;
  else "Failed - TestNeighbours()\n";

  if (TestJsonExponent()) correct++;
  else "Failed - TestJsonExponent()\n";

  if (TestMissingExponentDigits()) correct++;
  else "Failed - TestMissingExponentDigits()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
