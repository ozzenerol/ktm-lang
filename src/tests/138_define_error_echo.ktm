#include "testhelper.ktm"

/* An error on a #define line echoed a rewritten line in its excerpt:
 * `#define T (N > 2 ? 1 : 2.5)` showed as `#define T (N > 2  1 :
 * 2.500000)`. The renderer re-lexes the line to colour it and printed
 * each token from its value: a number came out as `%ld`/`%f` (`0x1F`
 * as 31, `2.5` as 2.500000, `1e2` as 100.000000), a char const lost its
 * quotes, and a character the lexer skips (`?`) vanished, moving the
 * text out from under the `^`. The excerpt is the line as written. */

#define ECHO_TEST_FILE "__138_define_error_echo.ktm"
#define ECHO_TEST_OUT  "__138_define_error_echo.out"

U0 CleanupEchoTest(U0)
{
  Rm(ECHO_TEST_FILE);
  Rm(ECHO_TEST_OUT);
  Rm("./a.out");
}

/* Does compiling `source` fail with `expected` (the excerpt's source
 * line, and its `^` line) in the output? */
Bool ExcerptIs(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;

  CleanupEchoTest;

  if (!FileWrite(ECHO_TEST_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ECHO_TEST_FILE " > " ECHO_TEST_OUT " 2>&1");
  if (res == 0) {
    CleanupEchoTest;
    return FALSE;
  }

  out = FileRead(ECHO_TEST_OUT);
  if (!out) {
    CleanupEchoTest;
    return FALSE;
  }

  if (!strstr(out, expected)) {
    "%s\n", out;
    Free(out);
    CleanupEchoTest;
    return FALSE;
  }

  Free(out);
  CleanupEchoTest;
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 6;

  "Test - error excerpt shows the line as written: ";

  /* the issue's repro: a macro, a `$` and a float */
  if (ExcerptIs(
      "#define N 3\n#define T (N > 2 $ 1 : 2.5)\nU0 Main() {}\n",
      "   2 |    #define T (N > 2 $ 1 : 2.5)\n     |              ^^^^^^^^^^^^^^^^^")) correct++;

  /* hex, exponent, char const and string macro in a #define */
  if (ExcerptIs(
      "#define N 3\n#define S \"x\\ty\"\n#define T (N + 0x1F + 1e2 + 'a' + S)\nU0 Main() {}\n",
      "   3 |    #define T (N + 0x1F + 1e2 + 'a' + S)\n     |              ^^^^^^^^^^^^^^^^^^^^^^^^^^")) correct++;

  /* comments kept in a #define */
  if (ExcerptIs(
      "#define N 3\n#define X (N + 1.5 /* c */ + \"s\")   // tail\nU0 Main() {}\n",
      "   2 |    #define X (N + 1.5 /* c */ + \"s\")   // tail\n     |              ^^^^^^^^^^^^^^^^^^^^^^^")) correct++;

  /* an #if with a float and a `$` */
  if (ExcerptIs(
      "#define N 3\n#if N > 2.5 $ 1\n#endif\nU0 Main() {}\n",
      "   2 |    #if N > 2.5 $ 1\n     |                  ^")) correct++;

  /* a statement using a macro next to number literals */
  if (ExcerptIs(
      "#define N 3\nU0 Main() { I64 x = N + 0x10 + 1e3 + 2.5 3; }\n",
      "   2 |    U0 Main() { I64 x = N + 0x10 + 1e3 + 2.5 3; }\n     |                                             ^")) correct++;

  /* a char const and an escaped string */
  if (ExcerptIs(
      "U0 Main() { I64 x = 'ab' + \"a\\tb\\\"c\" + 0x10 + 1e3 + 10U + 1.5f64; }\n",
      "   1 |    U0 Main() { I64 x = 'ab' + \"a\\tb\\\"c\" + 0x10 + 1e3 + 10U + 1.5f64; }\n")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
