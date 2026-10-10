#include "testhelper.ktm"

/* `p || T()` where p is a pointer and T() returns Bool (or any other
 * char-kind operand: a Bool/U8/I8 variable) failed to compile with
 * "The `||` operator cannot be applied to a pointer type with a function
 * call of character type". Logical ops only test against zero, so any
 * integer-like operand next to a pointer is fine. */

#define LOGPTR_TEST_FILE "__101_logical_ptr_bool_fail.ktm"
#define LOGPTR_TEST_OUT  "__101_logical_ptr_bool_fail.out"

I64 g_calls = 0;

Bool T() { g_calls++; return TRUE; }
Bool F() { g_calls++; return FALSE; }
U8 C0() { g_calls++; return 0; }
I8 CNeg() { g_calls++; return -1; }

I64 Id(I64 x) { return x; }
Bool RetOr(U8 *p) { return p || T(); }
Bool RetAnd(U8 *p) { return p && T(); }

U0 CleanupLogPtrTest(U0)
{
  Rm(LOGPTR_TEST_FILE);
  Rm(LOGPTR_TEST_OUT);
  Rm("./a.out");
}

/* A pointer next to a class value is still invalid. */
Bool CompileFailsWith(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupLogPtrTest;
  if (!FileWrite(LOGPTR_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " LOGPTR_TEST_FILE " > " LOGPTR_TEST_OUT " 2>&1");
  if (res == 0) {
    CleanupLogPtrTest;
    return FALSE;
  }
  out = FileRead(LOGPTR_TEST_OUT);
  if (!out) {
    CleanupLogPtrTest;
    return FALSE;
  }
  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupLogPtrTest;
    return FALSE;
  }
  Free(out);
  CleanupLogPtrTest;
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 33, i, n;
  U8 *p = NULL;
  U8 buf[4];
  U8 *q = buf;
  I64 *ip = NULL;
  Bool bt = TRUE, bf = FALSE;
  U8 u0 = 0;
  I8 i8 = -3;
  I64 zero = 0, one = 1;
  Bool b;
  I64 r;

  "Test - logical ops on a pointer next to a Bool: ";

  /* The reported case. */
  g_calls = 0;
  if (p || T()) correct++;
  if (g_calls == 1) correct++;

  /* Short-circuit: T() must not run when the pointer decides. */
  g_calls = 0;
  if (q || T()) correct++;
  if (p && T()) {} else correct++;
  if (g_calls == 0) correct++;

  /* Both orders, both operators. */
  g_calls = 0;
  if (T() || p) correct++;
  if (F() || p) {} else correct++;
  if (T() && q) correct++;
  if (T() && p) {} else correct++;
  if (q && T()) correct++;
  if (g_calls == 5) correct++;

  /* Negated pointer, Bool/U8/I8 variables, I64, two pointers. */
  if (!p || F()) correct++;
  if (!q || F()) {} else correct++;
  if ((p || bt) && !(p || bf)) correct++;
  if ((q && bt) && !(q && bf)) correct++;
  if (!(p || u0) && (q || u0) && (p || i8)) correct++;
  if ((p || C0()) == 0 && (p || CNeg()) == 1) correct++;
  if (!(p || zero) && (p || one) && (q && one) && !(q && zero)) correct++;
  if ((q || ip) && !(p || ip) && !(q && ip)) correct++;

  /* Results are 0/1, also as values. */
  b = p || T();
  if (b == TRUE) correct++;
  b = q && F();
  if (b == FALSE) correct++;
  r = q || F();
  if (r == 1) correct++;
  r = (T() && q) + (q || T()) + (p || T());
  if (r == 3) correct++;
  if (Id(p || T()) == 1 && Id(q && F()) == 0) correct++;

  /* The right operand is tested as a whole, not truncated to a byte:
   * a pointer / I64 whose low byte is 0 is still true. */
  U8 *aligned = 0x1000(U8 *);
  I64 big = 256;
  if ((T() && aligned) && (p || aligned) && (zero || big)) correct++;
  if ((one && big) == 1 && (zero || 5) == 1) correct++;
  r = T() && aligned;
  if (r == 1) correct++;

  /* Return value, nested, while. */
  if (RetOr(NULL) && RetOr(q) && !RetAnd(NULL) && RetAnd(q)) correct++;
  if ((p || (q && T())) && !(p && (q || T()))) correct++;
  n = 0;
  i = 0;
  while (p || i < 3) { i++; n++; }
  if (n == 3) correct++;
  n = 0;
  while (q && n < 4) n++;
  if (n == 4) correct++;

  /* Still invalid: a pointer next to a class value. */
  if (CompileFailsWith("
class C { I64 a; };
U0 Main() { C c; U8 *p = NULL; if (p || c) \"x\\n\"; }
", "operator cannot be applied")) correct++;
  else "Failed - p || class value\n";
  if (CompileFailsWith("
class C { I64 a; };
U0 Main() { C c; U8 *p = NULL; if (c && p) \"x\\n\"; }
", "operator cannot be applied")) correct++;
  else "Failed - class value && p\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
