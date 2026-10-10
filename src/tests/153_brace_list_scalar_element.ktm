#include "testhelper.ktm"

/* A nested `{ ... }` in an array initialiser must initialise an element
 * that is an array, a class or a union. For a scalar element
 * (`I64 a[2] = {1, {2}};`) it used to be accepted silently and the
 * element was given the list's address. And an array initialised from
 * something other than a list (`I64 a[2] = 5;`) printed the token's
 * value as a character, e.g. "got: '\x05'", instead of the token.
 *
 * Unlike C, a scalar can't be initialised from a braced list either
 * (`I64 x = {1};`): it's rejected, and reported once (the recovery used
 * to stop inside the list and report its `}` as a second error). */

#define SB_TEST_FILE "__153_brace_list_scalar_element.ktm"
#define SB_TEST_OUT  "__153_brace_list_scalar_element.out"

#define MSG_ELEM_I64 "Cannot use an initialiser list for an element of type i64"
#define MSG_EXPECTED "Expected initializer list starting with '{', got "

U0 CleanupScalarBraceTest(U0)
{
  Rm(SB_TEST_FILE);
  Rm(SB_TEST_OUT);
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

  CleanupScalarBraceTest;

  if (!FileWrite(SB_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SB_TEST_FILE " > " SB_TEST_OUT " 2>&1");

  out = FileRead(SB_TEST_OUT);
  if (!out) {
    "status %d, no output\n", res;
    CleanupScalarBraceTest;
    return FALSE;
  }

  ok = res == 1 && CountErrors(out) == errors;
  err = strstr(out, "error: ");
  if (!err || StrNCmp(err + 7, expected, StrLen(expected))) ok = FALSE;
  if (!strstr(out, where)) ok = FALSE;
  if (!ok) "status %d\n%s\n", res, out;

  Free(out);
  CleanupScalarBraceTest;
  return ok;
}

Bool TestScalarElement(U0)
{
  return CompileFailsWith(
"void Main()
{
  i64 a[2] = {1, {2}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:18");
}

Bool TestEveryElementBraced(U0)
{
  return CompileFailsWith(
"void Main()
{
  i64 a[2] = {{1}, {2}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:15");
}

Bool TestInnerDimension(U0)
{
  return CompileFailsWith(
"void Main()
{
  i64 a[2][2] = {{1, 2}, {3, {4}}};
}
", MSG_ELEM_I64, SB_TEST_FILE ":3:30");
}

Bool TestGlobal(U0)
{
  return CompileFailsWith(
"i64 g[2] = {1, {2}};
void Main() {}
", MSG_ELEM_I64, SB_TEST_FILE ":1:16");
}

Bool TestOtherScalars(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith(
"void Main()
{
  f64 f[2] = {1.0, {2.0}};
}
", "Cannot use an initialiser list for an element of type f64",
      SB_TEST_FILE ":3:20")) ok = FALSE;
  if (!CompileFailsWith(
"void Main()
{
  i64 *p[2] = {{1}, {2}};
}
", "Cannot use an initialiser list for an element of type i64 *",
      SB_TEST_FILE ":3:16")) ok = FALSE;
  /* An intrinsic class is a scalar */
  if (!CompileFailsWith(
"void Main()
{
  CDate d[2] = {1, {2}};
}
", "Cannot use an initialiser list for an element of type CDate",
      SB_TEST_FILE ":3:20")) ok = FALSE;
  return ok;
}

Bool TestClassScalarField(U0)
{
  return CompileFailsWith(
"class C { i64 a, b; };
void Main()
{
  C c = {1, {2}};
}
", "Cannot use an initialiser list for field b of type i64",
    SB_TEST_FILE ":4:13");
}

Bool TestBracedScalar(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith(
"void Main()
{
  i64 x = {1};
}
", "Cannot use an initialiser list for a variable of type i64",
      SB_TEST_FILE ":3:11")) ok = FALSE;
  if (!CompileFailsWith(
"void Main()
{
  CDate d = {1, {2}};
  i64 *p = {};
}
", "Cannot use an initialiser list for a variable of type CDate",
      SB_TEST_FILE ":3:13", 2)) ok = FALSE;
  /* At file scope it's read as an expression */
  if (!CompileFailsWith(
"i64 g = {1};
void Main() {}
", "Second operand missing to `g =` got invalid character `{`",
      SB_TEST_FILE ":1:7")) ok = FALSE;
  return ok;
}

Bool TestExpectedList(U0)
{
  Bool ok = TRUE;
  if (!CompileFailsWith(
"void Main()
{
  i64 a[2] = 5;
}
", MSG_EXPECTED "`5`", SB_TEST_FILE ":3:14")) ok = FALSE;
  if (!CompileFailsWith(
"void Main()
{
  i64 n = 1;
  i64 a[2] = n;
}
", MSG_EXPECTED "`n`", SB_TEST_FILE ":4:14")) ok = FALSE;
  /* Points at the `;`, not at the next line */
  if (!CompileFailsWith(
"void Main()
{
  i64 a[2] = ;
  i64 b = 1;
}
", MSG_EXPECTED "`;`", SB_TEST_FILE ":3:14")) ok = FALSE;
  return ok;
}

/* Each bad initialiser is reported once and the parser carries on */
Bool TestRecoveryContinues(U0)
{
  return CompileFailsWith(
"i64 g[2] = 5;
void Main()
{
  i64 a[2] = {1, {2}};
  i64 b[2][2] = {{1, 2}, {3, {4}}};
  i64 c[2] = ;
  i64 x = undefined_thing;
}
", MSG_EXPECTED "`5`", SB_TEST_FILE ":1:12", 5);
}

/* Valid nested initialisers keep working */
class SbPair
{
  I64 a, b;
};

class SbRow
{
  I64 n;
  I64 v[3];
};

union SbNum
{
  I64 i;
  F64 f;
};

SbPair g_pairs[2] = {{1, 2}, {3}};
I64 g_grid[2][2] = {{5, 6}, {7}};

Bool TestValid(U0)
{
  I64 grid[2][3] = {{1, 2, 3}, {4}};
  SbPair pairs[2] = {{1, 2}, {3, 4}};
  SbRow row = {9, {7, 8}};
  SbNum nums[2] = {{11}, {12}};
  U8 strs[2][4] = {"ab", "cd"};
  F64 fs[2] = {1.5, 2.5};
  CDate ds[2] = {1, 2};
  Bool ok = TRUE;

  if (grid[0][2] != 3 || grid[1][0] != 4 || grid[1][1] != 0) ok = FALSE;
  if (pairs[1].a != 3 || pairs[1].b != 4) ok = FALSE;
  if (row.n != 9 || row.v[0] != 7 || row.v[1] != 8 || row.v[2] != 0) ok = FALSE;
  if (nums[0].i != 11 || nums[1].i != 12) ok = FALSE;
  if (StrCmp(strs[1], "cd")) ok = FALSE;
  if (fs[1] != 2.5 || ds[1] != 2) ok = FALSE;
  if (g_pairs[0].b != 2 || g_pairs[1].a != 3 || g_pairs[1].b != 0) ok = FALSE;
  if (g_grid[0][1] != 6 || g_grid[1][0] != 7 || g_grid[1][1] != 0) ok = FALSE;
  return ok;
}

I32 Main()
{
  "Test - brace list for a scalar element: ";
  I64 correct = 0, tests = 0;

  tests++; if (TestScalarElement)      correct++;
  tests++; if (TestEveryElementBraced) correct++;
  tests++; if (TestInnerDimension)     correct++;
  tests++; if (TestGlobal)             correct++;
  tests++; if (TestOtherScalars)       correct++;
  tests++; if (TestClassScalarField)   correct++;
  tests++; if (TestBracedScalar)       correct++;
  tests++; if (TestExpectedList)       correct++;
  tests++; if (TestRecoveryContinues)  correct++;
  tests++; if (TestValid)              correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
