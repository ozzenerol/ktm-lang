#include "testhelper.ktm"

/* `-x`, `+x` and `~x` need a number. On a string, pointer, array, class
 * or function they compiled and gave the negated/complemented address;
 * they must be a located type error. Valid uses keep their values, and
 * `-` on a U8/U16/I32.. is an I64 (it read back as 251 for `-(U8)5`). */

#define UNOP_TEST_FILE "__134_unary_op_type_fail.ktm"
#define UNOP_TEST_OUT  "__134_unary_op_type_fail.out"
#define NEG_DEFINE -5
#define NEG_EXPR_DEFINE -(3 + 4)

class Pt { I64 x, y; };
class HasPtr { I64 *q; };

I64 Twice(I64 a) { return a * 2; }

U0 CleanupUnopTest(U0)
{
  Rm(UNOP_TEST_FILE);
  Rm(UNOP_TEST_OUT);
  Rm("./a.out");
}

Bool CompileFailsWith(U8 *name, U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupUnopTest;
  if (!FileWrite(UNOP_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    "Failed - %s: could not write the source\n", name;
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNOP_TEST_FILE " > " UNOP_TEST_OUT " 2>&1");
  if (res == 0) {
    "Failed - %s: compiled\n", name;
    CleanupUnopTest;
    return FALSE;
  }

  out = FileRead(UNOP_TEST_OUT);
  if (!out || !strstr(out, expected)) {
    "Failed - %s: expected '%s' in:\n%s\n", name, expected, out;
    Free(out);
    CleanupUnopTest;
    return FALSE;
  }

  Free(out);
  CleanupUnopTest;
  return TRUE;
}

I64 TestErrors(U0)
{
  I64 ok = 0;
  if (CompileFailsWith("-string",
      "U0 Main() { I64 u = -\"s\"; }\n",
      "The `-` operator cannot be applied to a string literal")) ok++;
  if (CompileFailsWith("+string",
      "U0 Main() { I64 u = +\"s\"; }\n",
      "The `+` operator cannot be applied to a string literal")) ok++;
  if (CompileFailsWith("~string",
      "U0 Main() { I64 u = ~\"s\"; }\n",
      "The `~` operator cannot be applied to a string literal")) ok++;
  /* Located on the operator: line 3, column of the `-`. */
  if (CompileFailsWith("-pointer",
      "U0 Main() {\n  I64 *p = 0;\n  I64 u = 1 + -p;\n}\n",
      ":3:15")) ok++;
  if (CompileFailsWith("-pointer message",
      "U0 Main() { I64 *p = 0; I64 u = -p; }\n",
      "`-` on `I64 *` is invalid")) ok++;
  if (CompileFailsWith("-array",
      "U0 Main() { I64 a[3]; I64 u = -a; }\n",
      "cannot be applied to a local variable of array type")) ok++;
  if (CompileFailsWith("-class",
      "class C { I64 x; };\nU0 Main() { C c; I64 u = -c; }\n",
      "cannot be applied to a local variable of class")) ok++;
  if (CompileFailsWith("-fnptr",
      "I64 F(I64 a) { return a; }\nU0 Main() { I64 (*fp)(I64 a) = &F; I64 u = -fp; }\n",
      "The `-` operator cannot be applied to a function pointer")) ok++;
  if (CompileFailsWith("-&fn",
      "U0 F() {}\nU0 Main() { I64 u = -&F; }\n",
      "The `-` operator cannot be applied")) ok++;
  /* A pointer expression, not just a variable. */
  if (CompileFailsWith("-(ptr+1)",
      "class S { I64 *q; };\nU0 Main() { S s; S *sp = &s; I64 u = -(sp->q + 1); }\n",
      "cannot be applied to a binary op of pointer type")) ok++;
  if (CompileFailsWith("~F64",
      "U0 Main() { F64 f = 2.5; I64 u = ~f; }\n",
      "The `~` operator cannot be applied to a local variable of float type")) ok++;
  if (CompileFailsWith("-U0 call",
      "U0 F() {}\nU0 Main() { I64 u = -F(); }\n",
      "The `-` operator cannot be applied to a function call of void type")) ok++;
  return ok;
}

I32 Main()
{
  "Test - Unary operator types: \n";
  I64 tests = 12 + 14;
  I64 correct = TestErrors;

  I64 i = 7, x = 4;
  U8 c = 5;
  I8 sc = -128;
  I16 h = 7;
  U32 w = 3;
  U64 big = 1;
  F64 f = 2.5, z = 0.0;
  I64 *p = &x, *np = NULL;
  I64 arr[2];
  arr[1] = 6;
  U8 *s = "ab";
  Pt pt;
  pt.x = 9;
  CDate d = 5(CDate);
  CDate nd = -d;

  if (-i == -7 && +i == 7 && ~i == -8) correct++;
  if (-f == -2.5 && +f == 2.5 && -f * 2 == -5.0) correct++;
  /* Small integer types promote to I64. */
  I64 nc = -c;
  if (nc == -5 && -c == -5 && -c + 0 == -5 && ~c == -6 && +c == 5) correct++;
  if (-sc == 128 && -h == -7 && -w == -3 && ~w == -4) correct++;
  if (-big == 0xFFFFFFFFFFFFFFFF) correct++;
  if (-s[0] == -97 && -*s == -97) correct++;
  if (-*p == -4 && -arr[1] == -6 && -pt.x == -9 && - -x == 4) correct++;
  if (-(p - p) == 0) correct++;
  /* `!` takes anything. */
  if (!"s" == 0 && !p == 0 && !np == 1 && !arr == 0) correct++;
  if (!f == 0 && !z == 1) correct++;
  /* Intrinsic classes are integers. */
  if (nd == -5 && ~d == -6 && +d == 5) correct++;
  if (NEG_DEFINE == -5 && NEG_EXPR_DEFINE == -7) correct++;
  if (-Twice(3) == -6) correct++;
  /* Passed straight to a vararg, not through a typed variable. */
  U8 *buf = MStrPrint("%d %d", -c, -sc);
  if (!StrCmp(buf, "-5 128")) correct++;
  Free(buf);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
