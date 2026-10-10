#include "testhelper.ktm"

/* The `^` under an error drifted left on a line with tabs: the excerpt
 * echoed the tab, which the terminal shows several columns wide, but the
 * `^` line was padded with one space per character. Tabs are now expanded
 * to spaces (to the next multiple of 8) in both lines, so the `^` lands
 * under the token and a tab inside the underlined span is covered. The
 * reported column is still the 1-based character column. */

#define TAB_TEST_FILE "__146_error_caret_with_tabs.ktm"
#define TAB_TEST_OUT  "__146_error_caret_with_tabs.out"

U0 CleanupTabTest(U0)
{
  Rm(TAB_TEST_FILE);
  Rm(TAB_TEST_OUT);
  Rm("./a.out");
}

/* Does compiling `source` fail with `expected` in the output? */
Bool ExcerptIs(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupTabTest;

  if (!FileWrite(TAB_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " TAB_TEST_FILE " > " TAB_TEST_OUT " 2>&1");
  if (res == 0) {
    CleanupTabTest;
    return FALSE;
  }

  out = FileRead(TAB_TEST_OUT);
  if (!out) {
    CleanupTabTest;
    return FALSE;
  }

  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupTabTest;
    return FALSE;
  }

  Free(out);
  CleanupTabTest;
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 7;

  "Test - error caret on a line with tabs: ";

  /* a tab of indentation */
  if (ExcerptIs(
      "U0 Main() {\n\tI64 x = 2 3;\n}\n",
      TAB_TEST_FILE ":2:12\n"
      "     |\n"
      "   2 |            I64 x = 2 3;\n"
      "     |                      ^\n")) correct++;

  /* two tabs, and tabs between the tokens */
  if (ExcerptIs(
      "U0 Main() {\n\t\tI64 x =\t2\t3;\n}\n",
      TAB_TEST_FILE ":2:13\n"
      "     |\n"
      "   2 |                    I64 x = 2       3;\n"
      "     |                                    ^\n")) correct++;

  /* a tab that moves less than 8 columns, to the next stop */
  if (ExcerptIs(
      "U0 Main() {\n  I64 abc =\t2 3;\n}\n",
      TAB_TEST_FILE ":2:15\n"
      "     |\n"
      "   2 |      I64 abc =     2 3;\n"
      "     |                      ^\n")) correct++;

  /* a tab inside a string before the error */
  if (ExcerptIs(
      "U0 Main() {\n  U8 *s = \"a\tb\" 3;\n}\n",
      TAB_TEST_FILE ":2:17\n"
      "     |\n"
      "   2 |      U8 *s = \"a    b\" 3;\n"
      "     |                       ^\n")) correct++;

  /* a wide underline after a tab */
  if (ExcerptIs(
      "U0 Main() {\n\tI64 x = 1;\n\tx = 2 + undefined_thing;\n}\n",
      TAB_TEST_FILE ":3:10\n"
      "     |\n"
      "   3 |            x = 2 + undefined_thing;\n"
      "     |                    ^^^^^^^^^^^^^^^\n")) correct++;

  /* the issue's repro: tabs before and inside the underlined span */
  if (ExcerptIs(
      "#define N 3\n\t#define Z (N\t? 2.50)\nU0 Main() {}\n",
      TAB_TEST_FILE ":2:12\n"
      "     |\n"
      "   2 |            #define Z (N    ? 2.50)\n"
      "     |                      ^^^^^^^^^^^^^\n")) correct++;

  /* no tabs: unchanged */
  if (ExcerptIs(
      "U0 Main() {\n    I64 x = 2 3;\n}\n",
      TAB_TEST_FILE ":2:15\n"
      "     |\n"
      "   2 |        I64 x = 2 3;\n"
      "     |                  ^\n")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
