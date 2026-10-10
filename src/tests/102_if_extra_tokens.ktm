#include "testhelper.ktm"

#define IF_EXTRA_TEST_FILE "__102_if_extra_tokens.ktm"
#define IF_EXTRA_TEST_OUT  "__102_if_extra_tokens.out"
#define IF_EXTRA_TEST_BIN  "__102_if_extra_tokens.bin"

#define IF_EXTRA_X 3
#define IF_EXTRA_Y 1

U0 CleanupIfExtraTest(U0)
{
  Rm(IF_EXTRA_TEST_FILE);
  Rm(IF_EXTRA_TEST_OUT);
  Rm(IF_EXTRA_TEST_BIN);
}

/* Compile `source` with ktm; return TRUE if the result matches
 * `should_fail` and, when failing, the output contains `expected`. */
Bool CompileCheck(U8 *source, Bool should_fail, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupIfExtraTest;

  if (!FileWrite(IF_EXTRA_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " IF_EXTRA_TEST_FILE " -o " IF_EXTRA_TEST_BIN " > " IF_EXTRA_TEST_OUT " 2>&1");

  if (should_fail) {
    if (res == 0) {
      ok = FALSE;
    } else {
      out = FileRead(IF_EXTRA_TEST_OUT);
      if (!out || !strstr(out, expected)) {
        if (out) "%s\n", out;
        ok = FALSE;
      }
      Free(out);
    }
  } else if (res != 0) {
    out = FileRead(IF_EXTRA_TEST_OUT);
    if (out) "%s\n", out;
    Free(out);
    ok = FALSE;
  }

  CleanupIfExtraTest;
  return ok;
}

Bool TestTwoLiterals(U0)
{
  return CompileCheck(
"#if 0 1
I32 Main() { return 1; }
#else
I32 Main() { return 0; }
#endif
", TRUE, "#if: unexpected '1' after the condition");
}

Bool TestLineNumber(U0)
{
  return CompileCheck(
"I64 a = 1;

#if 1 2
#endif
I32 Main() { return 0; }
", TRUE, ":3:");
}

Bool TestAfterDefined(U0)
{
  return CompileCheck(
"#define FOO
#if defined(FOO) 1
#endif
I32 Main() { return 0; }
", TRUE, "#if: unexpected '1' after the condition");
}

Bool TestAfterExpression(U0)
{
  return CompileCheck(
"#define A 3
#if A > 2 && A 7
#endif
I32 Main() { return 0; }
", TRUE, "#if: unexpected '7' after the condition");
}

Bool TestElif(U0)
{
  return CompileCheck(
"#if 0
#elif 1 1
#endif
I32 Main() { return 0; }
", TRUE, "#elif: unexpected '1' after the condition");
}

Bool TestStraySemicolon(U0)
{
  return CompileCheck(
"#if 1;
#endif
I32 Main() { return 0; }
", TRUE, "#if: unexpected ';' after the condition");
}

Bool TestValidConditionsCompile(U0)
{
  return CompileCheck(
"#define A 3
#define B 1
#define FLAG
#if 1 // note
#endif
#if 1 /* block */
#endif
#if A > 2 && B
#endif
#if defined(FLAG) && !defined(NOPE)
#endif
#ifdef FLAG
#endif
#if 0
#elif (A + 1) * 2 == 8 // trailing
#endif
#if 1
#if B
#endif
#endif
I32 Main() { return 0; }
", FALSE, NULL);
}

/* The same conditions in this file: the test only builds if they are
 * still accepted, and each must pick the right branch. */
Bool TestValidConditionsInline(U0)
{
  I64 hits = 0;
#if 1 // note
  hits++;
#endif
#if 1 /* block */
  hits++;
#endif
#if IF_EXTRA_X > 2 && IF_EXTRA_Y
  hits++;
#endif
#if defined(IF_EXTRA_X) && !defined(IF_EXTRA_NOPE)
  hits++;
#endif
#if 0
  hits += 100;
#elif (IF_EXTRA_X + 1) * 2 == 8 // trailing
  hits++;
#endif
#if 1
#if IF_EXTRA_Y
  hits++;
#endif
#endif
#if 0
  hits += 100;
#endif
  return hits == 6;
}

I32 Main()
{
  "Test - #if extra tokens: \n";
  I64 tests = 8;
  I64 correct = 0;

  if (TestTwoLiterals()) correct++;
  else "Failed - TestTwoLiterals()\n";

  if (TestLineNumber()) correct++;
  else "Failed - TestLineNumber()\n";

  if (TestAfterDefined()) correct++;
  else "Failed - TestAfterDefined()\n";

  if (TestAfterExpression()) correct++;
  else "Failed - TestAfterExpression()\n";

  if (TestElif()) correct++;
  else "Failed - TestElif()\n";

  if (TestStraySemicolon()) correct++;
  else "Failed - TestStraySemicolon()\n";

  if (TestValidConditionsCompile()) correct++;
  else "Failed - TestValidConditionsCompile()\n";

  if (TestValidConditionsInline()) correct++;
  else "Failed - TestValidConditionsInline()\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
