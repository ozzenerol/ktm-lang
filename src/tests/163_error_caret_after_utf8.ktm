#include "testhelper.ktm"

/* The `^` under an error was placed by counting bytes, so it drifted
 * right after non-ASCII text: `€` is 3 bytes but 1 column. A UTF-8
 * character now takes its width on screen (2 columns for wide East
 * Asian characters and emoji) in the `^` line. The reported column is
 * still the byte column, as the LSP expects. */

#define U8_TEST_FILE "__163_error_caret_after_utf8.ktm"
#define U8_TEST_OUT  "__163_error_caret_after_utf8.out"

U0 CleanupUtf8Test(U0)
{
  Rm(U8_TEST_FILE);
  Rm(U8_TEST_OUT);
  Rm("./a.out");
}

/* Does compiling `source` fail with `expected` in the output? */
Bool ExcerptIs(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupUtf8Test;

  if (!FileWrite(U8_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " U8_TEST_FILE " > " U8_TEST_OUT " 2>&1");
  if (res == 0) {
    CleanupUtf8Test;
    return FALSE;
  }

  out = FileRead(U8_TEST_OUT);
  if (!out) {
    CleanupUtf8Test;
    return FALSE;
  }

  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupUtf8Test;
    return FALSE;
  }

  Free(out);
  CleanupUtf8Test;
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - error caret after UTF-8 text: ";

  /* `é` after a 3-byte `€`: column 16, one place right of the `€` */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  I64 x = 1 €é;\n}\n",
      U8_TEST_FILE ":2:16\n"
      "     |\n"
      "   2 |      I64 x = 1 €é;\n"
      "     |                 ^\n")) correct++;

  /* after a comment holding a 2-byte character */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  /* ü */ I64 x = 1 2;\n}\n",
      U8_TEST_FILE ":2:22\n"
      "     |\n"
      "   2 |      /* ü */ I64 x = 1 2;\n"
      "     |                        ^\n")) correct++;

  /* wide characters take two columns */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  U8 *s = \"日本\" 2;\n}\n",
      U8_TEST_FILE ":2:20\n"
      "     |\n"
      "   2 |      U8 *s = \"日本\" 2;\n"
      "     |                     ^\n")) correct++;

  /* a tab after a UTF-8 character goes to the next stop on screen */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  U8 *s = \"é\t\" 2;\n}\n",
      U8_TEST_FILE ":2:17\n"
      "     |\n"
      "   2 |      U8 *s = \"é    \" 2;\n"
      "     |                      ^\n")) correct++;

  /* an underlined token holding UTF-8 is as wide as it is on screen */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  I64 é = 2;\n}\n",
      U8_TEST_FILE ":2:7\n"
      "     |\n"
      "   2 |      I64 é = 2;\n"
      "     |          ^\n")) correct++;

  /* plain ASCII is unchanged */
  tests++;
  if (ExcerptIs(
      "U0 Main() {\n  I64 x = 2 3;\n}\n",
      U8_TEST_FILE ":2:13\n"
      "     |\n"
      "   2 |      I64 x = 2 3;\n"
      "     |                ^\n")) correct++;

  CleanupUtf8Test;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
