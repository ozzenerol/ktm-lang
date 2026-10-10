#include "testhelper.ktm"

#define IF_TEST_FILE "__90_if_expr_fail.ktm"
#define IF_TEST_OUT  "__90_if_expr_fail.out"

/* Valid conditions must keep working. */
#define IF_THREE 3
#define IF_FLAG
#define IF_STR "abc"

U0 CleanupIfTest(U0)
{
  Rm(IF_TEST_FILE);
  Rm(IF_TEST_OUT);
  Rm("./a.out");
}

/* A bad #if/#elif condition used to exit from the macro processor with
 * "Unexpected end of input Parsing macro:1" - no file, no line. It must
 * be a normal compile failure (exit status 1) reporting `expected` and
 * the directive's file:line. */
Bool CompileFailsWith(U8 *source, U8 *expected, U8 *where)
{
  U8 *out;
  I64 res;

  CleanupIfTest;

  if (!FileWrite(IF_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " IF_TEST_FILE " > " IF_TEST_OUT " 2>&1");
  if (res != 1) {
    "ktm exit status %d, expected a normal compile failure\n", res;
    CleanupIfTest;
    return FALSE;
  }

  out = FileRead(IF_TEST_OUT);
  if (!out) {
    CleanupIfTest;
    return FALSE;
  }

  if (!strstr(out, expected) || !strstr(out, where) ||
      strstr(out, "Parsing macro")) {
    "%s\n", out;
    Free(out);
    CleanupIfTest;
    return FALSE;
  }

  Free(out);
  CleanupIfTest;
  return TRUE;
}

Bool TestIfTrailingOp(U0)
{
  return CompileFailsWith(
"U0 Main() { }
#if 1 +
#endif
",
    "#if: the condition must be a constant expression",
    IF_TEST_FILE ":2:");
}

Bool TestIfOpenParen(U0)
{
  return CompileFailsWith(
"U0 Main() { }

#if (
#endif
",
    "#if: the condition must be a constant expression",
    IF_TEST_FILE ":3:");
}

Bool TestIfCall(U0)
{
  return CompileFailsWith(
"#if foo(
#endif
U0 Main() { }
",
    "#if: the condition must be a constant expression",
    IF_TEST_FILE ":1:");
}

Bool TestElifTrailingOp(U0)
{
  return CompileFailsWith(
"U0 Main()
{
#if 0
  \"a\\n\";
#elif 1 *
  \"b\\n\";
#endif
}
",
    "#elif: the condition must be a constant expression",
    IF_TEST_FILE ":5:");
}

/* A string is not a number: this used to panic in the evaluator. */
Bool TestIfString(U0)
{
  return CompileFailsWith(
"#if \"s\"
#endif
U0 Main() { }
",
    "#if: the condition must be a constant expression",
    IF_TEST_FILE ":1:");
}

Bool TestValidConditions(U0)
{
  I64 hits = 0;
#if 1
  hits++;
#endif
#if IF_THREE * 2 - 6
  hits = -100;
#elif IF_THREE > 2 && defined(IF_FLAG)
  hits++;
#endif
#if !defined(IF_NOT_DEFINED) && (IF_THREE + 1) == 4
  hits++;
#endif
#if defined(IF_STR)
  hits++;
#endif
#if UNDEFINED_NAME
  hits = -100;
#else
  hits++;
#endif
#if 0
  hits = -100;
#elif 2 / 2
  hits++;
#endif
  return hits == 6;
}

I32 Main()
{
  "Test - #if expression errors: \n";
  I64 tests = 6;
  I64 correct = 0;

  if (TestIfTrailingOp()) correct++;
  else "Failed - TestIfTrailingOp()\n";

  if (TestIfOpenParen()) correct++;
  else "Failed - TestIfOpenParen()\n";

  if (TestIfCall()) correct++;
  else "Failed - TestIfCall()\n";

  if (TestElifTrailingOp()) correct++;
  else "Failed - TestElifTrailingOp()\n";

  if (TestIfString()) correct++;
  else "Failed - TestIfString()\n";

  if (TestValidConditions()) correct++;
  else "Failed - TestValidConditions()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
