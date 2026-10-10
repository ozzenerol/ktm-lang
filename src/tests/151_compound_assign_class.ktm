#include "testhelper.ktm"

/* A compound assignment whose operands can't be combined, like
 * `y += p` with `p` a class, is the same type error as `y = y + p`.
 * It used to hang the compiler: the error rewound the tokens looking
 * for a `+` that isn't there (the source has `+=`), the per-statement
 * recovery restarted before the statement and parsed it again, forever. */

#define CA_TEST_FILE "__151_compound_assign_class.ktm"
#define CA_TEST_OUT  "__151_compound_assign_class.out"

/* ktm is run with a memory cap and a timeout where one exists, so a
 * regression fails the test instead of hanging the suite. */
#define CA_TIMEOUT "ulimit -v 4000000 2>/dev/null; $(command -v timeout >/dev/null 2>&1 && echo timeout 10) "

#define CA_DECLS "class P { I64 a, b; };\nunion U { I64 i; F64 f; };\n"

U0 CleanupCompoundAssignTest(U0)
{
  Rm(CA_TEST_FILE);
  Rm(CA_TEST_OUT);
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

/* Compile `source`: ktm must exit with status 1 having printed `errors`
 * errors, the first being `expected` at `where`. */
Bool CompileFailsWith(U8 *source, U8 *expected, U8 *where, I64 errors=1)
{
  U8 *out, *err;
  I64 res;
  Bool ok;

  CleanupCompoundAssignTest;

  if (!FileWrite(CA_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System(CA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CA_TEST_FILE " > " CA_TEST_OUT " 2>&1");

  out = FileRead(CA_TEST_OUT);
  if (!out) {
    "status %d, no output\n", res;
    CleanupCompoundAssignTest;
    return FALSE;
  }

  ok = res == 1 && CountErrors(out) == errors;
  err = strstr(out, "error: ");
  if (!err || StrNCmp(err + 7, expected, StrLen(expected))) ok = FALSE;
  if (!strstr(out, where)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupCompoundAssignTest;
  return ok;
}

/* Every compound operator with a class on the right, then on the left */
Bool TestEveryOperator(U0)
{
  U8 *ops[10] = {"+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>="};
  U8 src[512], msg[256];
  I64 i;
  Bool ok = TRUE;

  for (i = 0; i < 10; ++i) {
    snprintf(src, sizeof(src),
             CA_DECLS "U0 Main()\n{\n  P p;\n  I64 y = 1;\n  y %s p;\n}\n",
             ops[i]);
    snprintf(msg, sizeof(msg),
             "The `%s` operator cannot be applied to a integer type with a local variable of class",
             ops[i]);
    if (!CompileFailsWith(src, msg, CA_TEST_FILE ":7:5")) {
      "  y %s p\n", ops[i];
      ok = FALSE;
    }

    snprintf(src, sizeof(src),
             CA_DECLS "U0 Main()\n{\n  P p;\n  p %s 1;\n}\n", ops[i]);
    snprintf(msg, sizeof(msg),
             "The `%s` operator cannot be applied to a class with a literal of integer type",
             ops[i]);
    if (!CompileFailsWith(src, msg, CA_TEST_FILE ":6:5")) {
      "  p %s 1\n", ops[i];
      ok = FALSE;
    }
  }
  return ok;
}

Bool TestSameAsBinaryForm(U0)
{
  return CompileFailsWith(CA_DECLS
"U0 Main()
{
  P p;
  I64 y = 1;
  y = y + p;
}
",
    "The `+` operator cannot be applied to a integer type with a local variable of class",
    CA_TEST_FILE ":7:9");
}

Bool TestUnion(U0)
{
  return CompileFailsWith(CA_DECLS
"U0 Main()
{
  U u;
  I64 y = 1;
  y -= u;
}
",
    "The `-=` operator cannot be applied to a integer type with a local variable of union",
    CA_TEST_FILE ":7:5");
}

Bool TestThroughPointer(U0)
{
  return CompileFailsWith(CA_DECLS
"U0 Main()
{
  P p, *pp = &p;
  pp->a *= *pp;
}
",
    "The `*=` operator cannot be applied to a integer type with a unary op of class",
    CA_TEST_FILE ":6:9");
}

Bool TestClassAndClass(U0)
{
  return CompileFailsWith(CA_DECLS
"U0 Main()
{
  P p, q;
  p += q;
}
",
    "The `+=` operator cannot be applied to a class with a local variable of class",
    CA_TEST_FILE ":6:5");
}

/* Each bad statement is reported and the parser carries on */
Bool TestRecoveryContinues(U0)
{
  return CompileFailsWith(CA_DECLS
"U0 Main()
{
  P p;
  I64 y = 1;
  y += p;
  y <<= p;
  y = y + p;
}
",
    "The `+=` operator cannot be applied to a integer type with a local variable of class",
    CA_TEST_FILE ":8:5", 3);
}

/* Compound assignments that are valid keep working */
class CaPoint
{
  I64 x, y;
};

Bool TestValid(U0)
{
  I64 arr[4] = {10, 20, 30, 40};
  I64 *ptr = arr;
  F64 f = 1.5;
  I64 i = 7;
  CDate d = 100;
  CaPoint pt, *ppt = &pt;
  Bool ok = TRUE;

  ptr += 2;
  if (*ptr != 30) ok = FALSE;
  ptr -= 1;
  if (*ptr != 20) ok = FALSE;

  f += i;
  if (f != 8.5) ok = FALSE;
  f *= 2;
  if (f != 17.0) ok = FALSE;

  d += 1;
  if (d != 101) ok = FALSE;

  pt.x = 3;
  pt.y = 4;
  pt.x += 5;
  ppt->y <<= 2;
  if (pt.x != 8 || pt.y != 16) ok = FALSE;

  i += 3;  if (i != 10) ok = FALSE;
  i -= 4;  if (i != 6) ok = FALSE;
  i *= 5;  if (i != 30) ok = FALSE;
  i /= 4;  if (i != 7) ok = FALSE;
  i %= 4;  if (i != 3) ok = FALSE;
  i <<= 3; if (i != 24) ok = FALSE;
  i >>= 1; if (i != 12) ok = FALSE;
  i &= 10; if (i != 8) ok = FALSE;
  i |= 3;  if (i != 11) ok = FALSE;
  i ^= 15; if (i != 4) ok = FALSE;

  return ok;
}

I32 Main()
{
  "Test - compound assignment with a class: ";
  I64 correct = 0, tests = 0;

  tests++; if (TestEveryOperator)     correct++;
  tests++; if (TestSameAsBinaryForm)  correct++;
  tests++; if (TestUnion)             correct++;
  tests++; if (TestThroughPointer)    correct++;
  tests++; if (TestClassAndClass)     correct++;
  tests++; if (TestRecoveryContinues) correct++;
  tests++; if (TestValid)             correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
