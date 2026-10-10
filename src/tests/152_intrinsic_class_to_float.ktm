#include "testhelper.ktm"

/* An intrinsic class (`I64 class CDate`, `U16 class`, `I32 class`) is an
 * integer underneath, but converting one to a float was a raw bit copy:
 * `CDate d = 7; F64 f = d;` gave 0.0 (with a warning), as did passing it to
 * an F64 parameter or returning it from an F64 function, and `d + 0.5`,
 * `d == 7.0` or `f = d` did not compile. A float into an intrinsic class
 * (`CDate d = 2.5;`) kept the double's bit pattern. Both now convert like
 * the base integer type does (sitofp / fptosi), without a warning. */

U16 class W { U8 lo, hi; };
I32 class N { I16 a, b; };
U64 class UC { U32 a, b; };

F64 Id(F64 x) { return x; }
F32 Id32(F32 x) { return x; }
F64 FromDate(CDate d) { return d; }
CDate ToDate(F64 f) { return f; }
F64 Sum3(I64 a, F64 b, F64 c) { return a + b + c; }

CDate g_d = 9;
F64 g_f = g_d;
CDate g_from_f = 2.5;

#define IF_TEST_FILE "__152_intrinsic_float.ktm"
#define IF_TEST_OUT  "__152_intrinsic_float.out"

U8 *diag_src =
"F64 Id(F64 x) { return x; }
F64 FromDate(CDate d) { return d; }
CDate ToDate(F64 f) { return f; }
U0 Main() {
  CDate d = 7;
  F64 f = d;
  f = d;
  f += d;
  Id(d);
  CDate e = 2.5;
  e = f;
  \"%f %d %d\\n\", f + d, d < f, ToDate(FromDate(d));
}
";

/* Runs `source` with the JIT; the combined stdout and stderr, or NULL. */
U8 *RunKtm(U8 *source)
{
  Rm(IF_TEST_FILE);
  Rm(IF_TEST_OUT);
  if (!FileWrite(IF_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return NULL;
  }
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " IF_TEST_FILE " > " IF_TEST_OUT " 2>&1");
  U8 *out = FileRead(IF_TEST_OUT);
  Rm(IF_TEST_FILE);
  Rm(IF_TEST_OUT);
  return out;
}

I32 Main()
{
  I64 correct = 0, tests = 22;

  "Test - intrinsic class to and from a float: ";

  CDate d = 7;
  F64 f = d;
  if (f == 7.0) correct++;
  f = 0;
  f = d;
  if (f == 7.0) correct++;
  f = 1.0;
  f += d;
  if (f == 8.0) correct++;
  f = 2.0;
  f *= d;
  if (f == 14.0) correct++;

  /* Arguments, returns, and an argument among other parameters. */
  if (Id(d) == 7.0 && Id32(d) == 7.0) correct++;
  if (FromDate(d) == 7.0 && FromDate(-3) == -3.0) correct++;
  if (Sum3(d, d, 0.5) == 14.5) correct++;

  /* Arithmetic and comparison with a float, on either side. */
  if (d + 0.5 == 7.5 && 0.5 * d == 3.5 && d / 2.0 == 3.5) correct++;
  if (d == 7.0 && d < 7.5 && 6.5 < d && !(d > 7.5)) correct++;
  if (d(F64) == 7.0) correct++;

  /* Signed and unsigned bases, narrow and wide. */
  CDate neg = -3;
  f = neg;
  if (f == -3.0) correct++;
  W w = 65535;
  N n = -5;
  F64 fw = w, fn = n;
  if (fw == 65535.0 && fn == -5.0) correct++;
  UC uc = 0xFFFFFFFFFFFFFFFF;
  f = uc;
  if (f == 18446744073709551615.0) correct++;
  F32 h = d;
  if (h == 7.0) correct++;

  /* Globals. */
  if (g_f == 9.0 && g_from_f == 2) correct++;

  /* Float into an intrinsic class truncates like an integer. */
  CDate e = 3.9;
  if (e == 3) correct++;
  e = -4.9;
  if (e == -4) correct++;
  if (ToDate(5.7) == 5 && ToDate(FromDate(d)) == 7) correct++;
  e = 7;
  e += 1.5;
  if (e == 8) correct++;
  W w2 = 300.7;
  if (w2 == 300) correct++;

  /* No diagnostics, and the right values from the JIT too. */
  U8 *out = RunKtm(diag_src);
  if (out && !strstr(out, "warning") && !strstr(out, "error")) correct++;
  else "\nunexpected diagnostics:\n%s\n", out;
  if (out && strstr(out, "21.000000 1 7\n")) correct++;
  else "\nunexpected output:\n%s\n", out;
  Free(out);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
