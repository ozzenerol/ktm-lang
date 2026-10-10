#include "testhelper.ktm"

/* Inside a function `P p; I64 z = p;` was only a warning and stored the
 * class's address, while `z = p` and a global `I64 gz = gp;` are errors.
 * A local declaration the `=` operator rejects is now the same error,
 * and the only diagnostic. */

#define CI_SRC "./ktm-222-ci.ktm"
#define CI_OUT "./ktm-222-ci.out"

/* Compile `source`; `*_status` gets ktm's exit code; returns its output */
U8 *Diagnose(U8 *source, I64 *_status)
{
  System("rm -f " CI_SRC " " CI_OUT);
  FileWrite(CI_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  *_status = System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " CI_SRC " -o /dev/null > " CI_OUT " 2>&1");
  U8 *out = FileRead(CI_OUT);
  System("rm -f " CI_SRC " " CI_OUT);
  return out;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* ktm fails with exactly one error, containing `want` and `where` */
Bool OneError(U8 *source, U8 *want, U8 *where)
{
  I64 status;
  U8 *out = Diagnose(source, &status);
  Bool ok = status != 0 && out && Count(out, "error:") == 1 &&
            Count(out, "warning:") == 0 && strstr(out, want) &&
            strstr(out, where);
  if (!ok) "got (status %d): '%s'\n", status, out;
  Free(out);
  return ok;
}

/* ktm compiles it with no diagnostics */
Bool Clean(U8 *source)
{
  I64 status;
  U8 *out = Diagnose(source, &status);
  Bool ok = status == 0 && out && !strstr(out, "error:") &&
            !strstr(out, "warning:");
  if (!ok) "got (status %d): '%s'\n", status, out;
  Free(out);
  return ok;
}

#define CLS "class P { i64 x, y; };\nclass Q { i64 a; };\n"

I32 Main()
{
  "Test - local class to integer is an error: ";
  I64 tests = 0, correct = 0;

  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  i64 z = p;\n}\n",
               "`i64 = P` is invalid", "222-ci.ktm:5:9\n")) correct++;
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  u8 c = p;\n}\n",
               "`u8 = P` is invalid", "222-ci.ktm:5:8\n")) correct++;
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  f64 f = p;\n}\n",
               "`f64 = P` is invalid", "222-ci.ktm:5:9\n")) correct++;
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  i64 *ip = p;\n}\n",
               "`i64 * = P` is invalid", "222-ci.ktm:5:11\n")) correct++;
  /* A class value into a class of another type was accepted silently */
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  Q q = p;\n}\n",
               "`Q = P` is invalid", "222-ci.ktm:5:7\n")) correct++;
  /* Same message as the assignment and the global */
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  i64 z;\n  z = p;\n}\n",
               "`i64 = P` is invalid", "222-ci.ktm:6:5\n") &&
      OneError(CLS "P gp;\nI64 gz = gp;\n",
               "`i64 = P` is invalid", "222-ci.ktm:4:8\n")) correct++;
  /* Still in a declaration list */
  tests++;
  if (OneError(CLS "void Main() {\n  P p;\n  i64 a = 1, b = p;\n}\n",
               "`i64 = P` is invalid", "222-ci.ktm:5:16\n")) correct++;

  /* Legal declarations are unchanged */
  tests++;
  if (Clean(CLS "void Main() {\n  P p;\n  p.x = 1;\n  P same = p;\n"
            "  P *pp = &p;\n  void *u = &p;\n  void *v = pp;\n  auto a = p;\n"
            "  i64 x = 1, y = p.x;\n  f64 f = 2;\n  u8 *s = \"hi\";\n"
            "  i64 *ip = &p.y;\n  \"%d %d %d %d\\n\", same.x, a.x, x, y;\n}\n"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
