#include "testhelper.ktm"

/* `I64 v = **pp(I64 *);` with `Pt **pp`: a cast is postfix, so this is
 * `**(pp(I64 *))`, and the second `*` dereferences an I64. -jit and AOT
 * report that; -transpile printed "Unary type is being assigned as NULL"
 * and segfaulted. It now reports the error like -jit (test 226). The
 * transpiler also wrote a cast unbracketed, so `p(I64 *)[1]` became
 * `(long *)p[1]` (a cast of `p[1]` in C). */

#define TC_FILE "__223_transpile.ktm"
#define TC_C    "__223_transpile.c"
#define TC_EXE  "__223_transpile.exe"
#define TC_OUT  "__223_transpile.out"
#define TC_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TC_KTM TC_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupTranspileCastTest(U0)
{
  System("rm -f " TC_FILE " " TC_C " " TC_EXE " " TC_OUT);
}

U8 *tc_bad =
"class Pt { i64 x; i64 y; };
void Main()
{
  Pt pt;
  pt.x = 7;
  Pt *q = &pt;
  Pt **pp = &q;
  i64 v = **pp(i64 *);
  \"%d\\n\", v;
}
";

U8 *tc_good =
"class Pt { i64 x; i64 y; };
void Main()
{
  Pt pt;
  pt.x = 7; pt.y = 9;
  Pt *q = &pt;
  Pt **pp = &q;
  i64 v = *(*pp)(i64 *);
  i64 w = (*pp)(i64 *)[1];
  i64 a = 3, b = 4;
  f64 f = (a + b)(f64) / 2;
  i64 n = -a(f64) * 2;
  u8 *bytes = q(u8 *);
  i64 z = *(bytes + 8)(i64 *);
  \"%d %d %.1f %d %d\\n\", v, w, f, n, z;
}
";

class Pt { I64 x; I64 y; };

U0 WriteSource(U8 *src)
{
  CleanupTranspileCastTest;
  FileWrite(TC_FILE, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
}

U0 Main()
{
  "Test - transpile cast after a dereference: ";
  I64 correct = 0, tests = 0, res;
  U8 *out;

  /* The same expressions compiled here (AOT or JIT) */
  tests++;
  Pt pt;
  pt.x = 7; pt.y = 9;
  Pt *q = &pt;
  Pt **pp = &q;
  I64 a = 3, b = 4;
  U8 *bytes = q(U8 *);
  if (*(*pp)(I64 *) == 7 && (*pp)(I64 *)[1] == 9 &&
      (a + b)(F64) / 2 == 3.5 && -a(F64) * 2 == -6.0 &&
      *(bytes + 8)(I64 *) == 9) correct++;

  /* The wrong program is an error under -jit... */
  WriteSource(tc_bad);
  tests++;
  res = System(TC_KTM "-jit " TC_FILE " > " TC_OUT " 2>&1");
  out = FileRead(TC_OUT);
  if (res == 1 && out && strstr(out, "`*` on `i64` is invalid")) correct++;
  else "jit: %d %s\n", res, out;
  Free(out);

  /* ...and -transpile no longer crashes on it: it reports the same
   * error (test 226) */
  tests++;
  res = System(TC_KTM "-transpile " TC_FILE " > " TC_C " 2> " TC_OUT);
  out = FileRead(TC_OUT);
  U8 *code = FileRead(TC_C);
  if (res == 1 && out && !strstr(out, "NULL") &&
      strstr(out, "`*` on `i64` is invalid")) correct++;
  else "transpile: %d %s\n", res, out;
  Free(out);
  Free(code);

  /* The valid program transpiles to C that brackets the casts, compiles
   * and prints what the JIT does (only with a C compiler on the PATH) */
  WriteSource(tc_good);
  tests++;
  res = System(TC_KTM "-transpile " TC_FILE " > " TC_C " 2>/dev/null");
  code = FileRead(TC_C);
  if (res == 0 && code && strstr(code, "long w = ((long *)*pp)[1];") &&
      strstr(code, "((double)(a + b)) / 2") &&
      strstr(code, "*((long *)(bytes + 8))")) correct++;
  else "%s\n", code;
  Free(code);

  tests++;
  res = System("command -v cc >/dev/null 2>&1");
  if (res != 0) {
    correct++;
  } else {
    res = System("cc -w -o " TC_EXE " " TC_C " -lm > " TC_OUT " 2>&1 && " TC_TIMEOUT "./" TC_EXE " > " TC_OUT " 2>&1");
    out = FileRead(TC_OUT);
    if (res == 0 && out && !StrCmp(out, "7 9 3.5 -6 9\n")) correct++;
    else "cc: %s\n", out;
    Free(out);
  }

  CleanupTranspileCastTest;
  PrintResult(correct, tests);
  "====\n";
}
