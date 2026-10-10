#include "testhelper.ktm"

/* `U0 *w = &F; if (w == &F)` was rejected with "The `==` operator
 * cannot be applied to a pointer type with a unary op of function".
 * As in C, a `U0 *` holding a function address compares equal / not
 * equal with a function address or function pointer, in both orders.
 * Other mixes (an `I64 *`, an F64, `<`, `-`) are still errors. */

#define FNCMP_FILE "__195_fncmp.ktm"
#define FNCMP_OUT  "__195_fncmp.out"

U0 F() {}
U0 G() {}

class Holder
{
  U0 *any;
};

U0 *g_w = &F;

U0 CleanupFnCmpTest(U0)
{
  Rm(FNCMP_FILE);
  Rm(FNCMP_OUT);
  Rm("./a.out");
}

/* `expr` must fail to compile with `expected` in the output */
Bool CompileFailsWith(U8 *expr, U8 *expected)
{
  U8 *src, *cmd, *out;
  I64 res;
  Bool ok = FALSE;

  CleanupFnCmpTest;
  src = MStrPrint(
"U0 F() {}
U0 Main() {
  U0 *w = &F; U0 (*fp)() = &F; I64 *q = NULL; F64 d = 1.0;
  \"%%d\\n\", %s;
}
", expr);
  if (!FileWrite(FNCMP_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    Free(src);
    return FALSE;
  }
  Free(src);
  cmd = MStrPrint(
      "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
      "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
      FNCMP_FILE " > " FNCMP_OUT " 2>&1");
  res = System(cmd);
  Free(cmd);
  if (res != 0) {
    out = FileRead(FNCMP_OUT);
    if (out) {
      if (strstr(out, expected)) {
        ok = TRUE;
      } else {
        "%s\n", out;
      }
      Free(out);
    }
  }
  CleanupFnCmpTest;
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 18;
  U0 *w = &F;
  U0 *n = NULL;
  U0 (*fp)() = &F;
  U0 (*gp)() = &G;
  Holder h;
  h.any = &G;

  "Test - compare a U0 pointer with a function pointer: ";

  if (w == &F) correct++;
  if (!(w != &F)) correct++;
  if (w != &G) correct++;
  if (&F == w) correct++;
  if (&G != w) correct++;
  if (w == fp && fp == w) correct++;
  if (w != gp && gp != w) correct++;
  if (n != fp && fp != n) correct++;
  if (fp != NULL && fp != 0 && !(fp == NULL)) correct++;
  if (g_w == &F && g_w != gp) correct++;
  if (h.any == &G && h.any != fp) correct++;
  /* The result is a 0/1 value */
  I64 eq = w == &F, ne = w == &G;
  if (eq == 1 && ne == 0) correct++;

  /* Still errors */
  if (CompileFailsWith("fp == 1.5", "cannot be applied to a function")) correct++;
  if (CompileFailsWith("fp == d", "cannot be applied to a function")) correct++;
  if (CompileFailsWith("q == &F", "`I64 * == <fn> U0` is invalid")) correct++;
  if (CompileFailsWith("w < &F", "`U0 * < <fn> U0` is invalid")) correct++;
  if (CompileFailsWith("w - &F", "`U0 * - <fn> U0` is invalid")) correct++;
  if (CompileFailsWith("w * fp", "is invalid")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
