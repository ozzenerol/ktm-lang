#include "testhelper.ktm"

#define DW_TEST_FILE "__141_do_while_fail.ktm"
#define DW_TEST_OUT  "__141_do_while_fail.out"

/* ktm is run with a timeout where one exists: the undefined-function
 * error used to walk the token ring forever on long argument lists. */
#define DW_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 30) "

U0 CleanupDoWhileTest(U0)
{
  Rm(DW_TEST_FILE);
  Rm(DW_TEST_OUT);
  Rm("./a.out");
}

/* Compile `source`, expect a failure whose output contains both
 * `expected` and the location `where`. */
Bool CompileFailsWith(U8 *source, U8 *expected, U8 *where)
{
  U8 *out;
  I64 res;

  CleanupDoWhileTest;

  if (!FileWrite(DW_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System(DW_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DW_TEST_FILE " > " DW_TEST_OUT " 2>&1");
  if (res == 0) {
    "  compiled, expected: %s\n", expected;
    CleanupDoWhileTest;
    return FALSE;
  }

  out = FileRead(DW_TEST_OUT);
  if (!out) {
    CleanupDoWhileTest;
    return FALSE;
  }

  if (!strstr(out, expected) || !strstr(out, where)) {
    "  expected `%s` at `%s`, got:\n%s\n", expected, where, out;
    Free(out);
    CleanupDoWhileTest;
    return FALSE;
  }

  Free(out);
  CleanupDoWhileTest;
  return TRUE;
}

Bool TestDoIf(U0)
{
  return CompileFailsWith(
"U0 Main()
{
  I64 i = 0;
  do { i++; } if (i < 3);
  \"%d\\n\", i;
}
",
    "expected `while` after do block, got `if`",
    DW_TEST_FILE ":4:15");
}

Bool TestDoIdent(U0)
{
  return CompileFailsWith(
"U0 Main()
{
  I64 i = 0;
  do { i++; }
  i = 2;
}
",
    "expected `while` after do block, got `i`",
    DW_TEST_FILE ":5:3");
}

Bool TestDoFor(U0)
{
  return CompileFailsWith(
"U0 Main()
{
  I64 i = 0;
  do { i++; } for (;;);
}
",
    "expected `while` after do block, got `for`",
    DW_TEST_FILE ":4:15");
}

Bool TestInnerDoMissingWhile(U0)
{
  return CompileFailsWith(
"U0 Main()
{
  I64 i = 0, j = 0;
  do {
    do { j++; } if (j < 2);
    i++;
  } while (i < 2);
}
",
    "expected `while` after do block, got `if`",
    DW_TEST_FILE ":5:17");
}

/* An undefined function's error must point at its name, not at the
 * first argument token with the same length or prefix. */
Bool TestUndefinedFnSameLenArg(U0)
{
  return CompileFailsWith(
"I64 abc = 1;
foo(abc);
",
    "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

Bool TestUndefinedFnPrefixArg(U0)
{
  return CompileFailsWith(
"I64 foobar = 1;
foo(foobar);
",
    "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

Bool TestUndefinedFnMultiline(U0)
{
  return CompileFailsWith(
"I64 abc = 1;
foo(1,
    abc);
",
    "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

/* Name pushed out of the token ring: must still error, not hang. */
Bool TestUndefinedFnLongArgs(U0)
{
  return CompileFailsWith(
"foo(1,2,3,4,5,6,7,8,9,10,11,12);
",
    "Function: foo() not defined", DW_TEST_FILE ":1:");
}

I64 DoWhileBasic()
{
  I64 i = 0;
  do { i++; } while (i < 3);
  return i;
}

I64 DoWhileRunsOnce()
{
  I64 i = 10;
  do { i++; } while (i < 3);
  return i;
}

I64 DoWhileBodyIf()
{
  I64 i = 0, odd = 0;
  do {
    if (i & 1) odd++;
    i++;
  } while (i < 7);
  return odd;
}

/* A do-while followed directly by an if. */
I64 DoWhileThenIf()
{
  I64 i = 0;
  do { i++; } while (i < 4);
  if (i == 4) i = 40;
  return i;
}

I64 DoWhileNested()
{
  I64 i = 0, j, n = 0;
  do {
    j = 0;
    do {
      n++;
      j++;
    } while (j < 3);
    i++;
  } while (i < 4);
  return n;
}

I64 DoWhileStatementBody()
{
  I64 i = 0;
  do i += 2; while (i < 9);
  return i;
}

U0 Main()
{
  "Test - do while requires while: ";
  I64 correct = 0, tests = 0, r;

  tests++; if ((r = DoWhileBasic()) == 3) correct++; else "\n  DoWhileBasic: %d\n", r;
  tests++; if ((r = DoWhileRunsOnce()) == 11) correct++; else "\n  DoWhileRunsOnce: %d\n", r;
  tests++; if ((r = DoWhileBodyIf()) == 3) correct++; else "\n  DoWhileBodyIf: %d\n", r;
  tests++; if ((r = DoWhileThenIf()) == 40) correct++; else "\n  DoWhileThenIf: %d\n", r;
  tests++; if ((r = DoWhileNested()) == 12) correct++; else "\n  DoWhileNested: %d\n", r;
  tests++; if ((r = DoWhileStatementBody()) == 10) correct++; else "\n  DoWhileStatementBody: %d\n", r;

  tests++; if (TestDoIf) correct++;
  tests++; if (TestDoIdent) correct++;
  tests++; if (TestDoFor) correct++;
  tests++; if (TestInnerDoMissingWhile) correct++;
  tests++; if (TestUndefinedFnSameLenArg) correct++;
  tests++; if (TestUndefinedFnPrefixArg) correct++;
  tests++; if (TestUndefinedFnMultiline) correct++;
  tests++; if (TestUndefinedFnLongArgs) correct++;

  PrintResult(correct, tests);
  "====\n";
}
