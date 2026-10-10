#include "testhelper.ktm"

#define DEF_AFTER_CLASS_FILE "__110_define_after_class.ktm"
#define DEF_AFTER_CLASS_OUT  "__110_define_after_class.out"

#define DEF_MSG_CONST   "#define X: the value must be a constant expression or a string, made of literals and other macros"
#define DEF_MSG_NUM     "Error while parsing #define X; #define must be a numerical expression or a string"
#define DEF_MSG_FNLIKE  "function-like macros are not supported: #define X(1)"
#define DEF_MSG_UNDEF   "Variable or function `X` has not been defined"

/* A successful #define right after a class must keep working */
class DefAfterClassOk { I64 a; };
#define DEF_AFTER_CLASS_OK (2+1)

U0 CleanupDefAfterClassTest(U0)
{
  Rm(DEF_AFTER_CLASS_FILE);
  Rm(DEF_AFTER_CLASS_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p += 7;
  }
  return n;
}

/* Compile `source`, which must fail with exactly `n_expected` errors,
 * each of `m1`/`m2` (when not NULL) among them. */
Bool CompileFailsExactly(U8 *source, I64 n_expected, U8 *m1, U8 *m2)
{
  U8 *out;
  I64 res;
  Bool ok;

  CleanupDefAfterClassTest;

  if (!FileWrite(DEF_AFTER_CLASS_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DEF_AFTER_CLASS_FILE " > " DEF_AFTER_CLASS_OUT " 2>&1");
  if (res == 0) {
    CleanupDefAfterClassTest;
    return FALSE;
  }

  out = FileRead(DEF_AFTER_CLASS_OUT);
  if (!out) {
    CleanupDefAfterClassTest;
    return FALSE;
  }

  ok = CountErrors(out) == n_expected;
  if (m1 && !strstr(out, m1)) ok = FALSE;
  if (m2 && !strstr(out, m2)) ok = FALSE;
  if (!ok) "%s\n", out;

  Free(out);
  CleanupDefAfterClassTest;
  return ok;
}

Bool TestNoPreceding(U0)
{
  return CompileFailsExactly(
"#define X (zz+1)
U0 Main() { I64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

Bool TestAfterClass(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#define X (zz+1)
U0 Main() { I64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

Bool TestAfterUnion(U0)
{
  return CompileFailsExactly(
"union Foo { I64 a; U8 b; };
#define X (zz+1)
U0 Main() { I64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

Bool TestAfterGlobal(U0)
{
  return CompileFailsExactly(
"I64 g = 5;
#define X (zz+1)
U0 Main() { I64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

/* The class must be complete: using it after the failing define used
 * to crash the compiler. */
Bool TestClassStillUsable(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#define X (zz+1)
U0 Main() { Foo f; f.a = 1; }
", 1, DEF_MSG_CONST, NULL);
}

Bool TestNonConstAfterClass(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#define X zz
U0 Main() { I64 y = X; }
", 2, DEF_MSG_NUM, DEF_MSG_UNDEF);
}

Bool TestFnLikeAfterClass(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#define X(1)
U0 Main() { I64 y = 1; }
", 1, DEF_MSG_FNLIKE, NULL);
}

Bool TestTwoBadDefines(U0)
{
  return CompileFailsExactly(
"class Foo { I64 a; };
#define X (zz+1)
#define Y (yy+1)
class Bar { I64 b; };
U0 Main() { Bar b; b.b = 1; }
", 2, DEF_MSG_CONST, "#define Y: the value must be a constant expression");
}

I32 Main()
{
  "Test - #define error after a class: \n";
  I64 tests = 9;
  I64 correct = 0;

  if (TestNoPreceding()) correct++;
  else "Failed - TestNoPreceding()\n";

  if (TestAfterClass()) correct++;
  else "Failed - TestAfterClass()\n";

  if (TestAfterUnion()) correct++;
  else "Failed - TestAfterUnion()\n";

  if (TestAfterGlobal()) correct++;
  else "Failed - TestAfterGlobal()\n";

  if (TestClassStillUsable()) correct++;
  else "Failed - TestClassStillUsable()\n";

  if (TestNonConstAfterClass()) correct++;
  else "Failed - TestNonConstAfterClass()\n";

  if (TestFnLikeAfterClass()) correct++;
  else "Failed - TestFnLikeAfterClass()\n";

  if (TestTwoBadDefines()) correct++;
  else "Failed - TestTwoBadDefines()\n";

  DefAfterClassOk ok;
  ok.a = DEF_AFTER_CLASS_OK;
  if (ok.a == 3) correct++;
  else "Failed - successful #define after a class\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
