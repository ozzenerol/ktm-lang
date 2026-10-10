#include "testhelper.ktm"

/* Text in a skipped #if group reported errors: a malformed number
 * (`0b12`), a long or unterminated char const (an apostrophe in
 * `don't` ran on over the #endif), an unknown directive. Like C, a
 * skipped group is now only scanned for the directives that end it. */

#if 0
I64 x = 0b12;
I64 y = 1.2.3e;
I64 z = 0x;
I64 c = 'abcdefghij';
don't do this
#foo bar
#if 1
  nested 0b2 'too long for a char const'
  #bogus
#endif
#elif 0
it's skipped too, 0b9
#else
I64 g_branch = 3;
#endif

#ifdef NOT_DEFINED_174
#unknown_directive
I64 bad = 0b;
#endif

#if 1
I64 g_live = 1;
#else
we're not here 08x
#endif

#define SKIP_FILE "__174_skipped_if.ktm"
#define SKIP_OUT  "__174_skipped_if.out"

/* Code that is not skipped still reports the error */
Bool LiveCodeReports(U8 *source, U8 *expected)
{
  U8 *out;
  I64 res;
  Bool good = TRUE;

  Rm(SKIP_FILE);
  if (!FileWrite(SKIP_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " SKIP_FILE " > " SKIP_OUT " 2>&1");
  out = FileRead(SKIP_OUT);
  if (res == 0 || !out || !strstr(out, expected)) good = FALSE;
  if (!good && out) "%s\n", out;
  if (out) Free(out);
  Rm(SKIP_FILE);
  Rm(SKIP_OUT);
  return good;
}

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - skipped #if groups report nothing: ";

  tests++; if (g_branch == 3) correct++;
  tests++; if (g_live == 1) correct++;

  tests++;
  if (LiveCodeReports("I64 x = 0b12;\nU0 Main() {}\n",
                      "malformed numeric literal")) correct++;
  tests++;
  if (LiveCodeReports("#foo\nU0 Main() {}\n",
                      "invalid preprocessor directive '#foo'")) correct++;
  tests++;
  if (LiveCodeReports("I64 c = 'abcdefghij';\nU0 Main() {}\n",
                      "Char const limited to 8 characters")) correct++;
  /* a skipped group still has to end */
  tests++;
  if (LiveCodeReports("#if 0\ndon't\nU0 Main() {}\n", "Unterminated #if"))
    correct++;
  /* and #elif conditions read after skipped text are still checked */
  tests++;
  if (LiveCodeReports("#if 0\n0b2\n#elif (1 +\n#endif\nU0 Main() {}\n",
                      "#elif")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
