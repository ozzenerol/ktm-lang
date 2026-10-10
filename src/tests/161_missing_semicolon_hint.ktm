#include "testhelper.ktm"

/* The "perhaps a missing `;`" note went to the last token of the
 * nearest earlier line whatever the error was: `I64 x = 2 3;` after
 * `U0 Main() {` put it on `{`. It is now given only when the unexpected
 * token starts its line, at the end of the line before, and not when
 * that line already ends in `;` or `{`. */

#define SH_TEST_FILE "__161_missing_semicolon_hint.ktm"
#define SH_TEST_OUT  "__161_missing_semicolon_hint.out"
#define SH_HINT "perhaps a missing `;`"

U0 CleanupHintTest(U0)
{
  Rm(SH_TEST_FILE);
  Rm(SH_TEST_OUT);
}

/* Compile `source` with `ktm -jit`: does it fail, print `expected` (if
 * given) and not print `unwanted` (if given)? */
Bool FailsWith(U8 *source, U8 *expected, U8 *unwanted)
{
  U8 *out;
  I64 res;
  Bool good = TRUE;

  CleanupHintTest;
  if (!FileWrite(SH_TEST_FILE, source, StrLen(source),
                 O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " SH_TEST_FILE " > " SH_TEST_OUT " 2>&1");
  if (res == 0) good = FALSE;
  out = FileRead(SH_TEST_OUT);
  if (!out) {
    CleanupHintTest;
    return FALSE;
  }
  if (expected && !strstr(out, expected)) good = FALSE;
  if (unwanted && strstr(out, unwanted)) good = FALSE;
  if (!good) "%s\n", out;
  Free(out);
  CleanupHintTest;
  return good;
}

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - placement of the missing `;` hint: ";

  /* the unexpected token is in the middle of its line: no hint */
  tests++;
  if (FailsWith("U0 Main() {\n  I64 x = 2 3;\n}\n",
                "Unexpected integer `3`", SH_HINT)) correct++;
  tests++;
  if (FailsWith("U0 Main() {\n  I64 y = 1;\n  I64 x = 2 3;\n}\n",
                "Unexpected integer `3`", SH_HINT)) correct++;
  tests++;
  if (FailsWith("U0 Main() {\n  I64 x = 1;\n  x = (2 3;\n}\n",
                "unexpected integer `3`", "previous statement starts here"))
    correct++;

  /* it starts its line and the line before has no `;`: hint there */
  tests++;
  if (FailsWith("U0 Main() {\n  I64 x = 1\n  return;\n}\n",
                "info: " SH_HINT " at the end of this line?\n"
                " --> " SH_TEST_FILE ":2:11\n"
                "     |\n"
                "   2 |      I64 x = 1\n", NULL)) correct++;
  tests++;
  if (FailsWith("U0 Main() {\n  I64 x = 1\n\n  // gap\n  I64 y = 2;\n}\n",
                SH_TEST_FILE ":2:11", NULL)) correct++;

  /* the line before already ends in `;` or `{`: no hint */
  tests++;
  if (FailsWith("U0 Main() {\n  I64 x = 1;\n  ) x;\n}\n", NULL, SH_HINT))
    correct++;
  tests++;
  if (FailsWith("U0 Main()\n{\n  ) x;\n}\n", NULL, SH_HINT)) correct++;

  CleanupHintTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
