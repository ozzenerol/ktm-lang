#include "testhelper.ktm"

/* The lexer silently dropped characters it has no use for (`?`, a
 * backtick, `$` and `@` outside an asm block, non-ASCII bytes), so
 * `I64 x = 2.5 ? 3;` was reported as "Unexpected integer `3`". Each
 * one must be reported where it is as "unexpected character" (a UTF-8
 * character once, not once per byte) and ktm must exit 1, while the
 * same characters in strings, comments, asm blocks and skipped #if
 * groups stay fine. */

#define UNKNOWN_FILE "__143_unknown_char.ktm"
#define UNKNOWN_OUT  "__143_unknown_char.out"

U0 CleanupUnknownTest(U0)
{
  Rm(UNKNOWN_FILE);
  Rm(UNKNOWN_OUT);
  Rm("./a.out");
}

I64 CountErrors(U8 *out)
{
  I64 n = 0;
  U8 *p = out;
  while ((p = strstr(p, "error: "))) {
    n++;
    p++;
  }
  return n;
}

/* Compile `source`. With `count` 0 it must compile cleanly; otherwise
 * ktm must exit 1 with exactly `count` errors, the output containing
 * `want` (when not NULL). */
Bool Compile(U8 *source, I64 count, U8 *want)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupUnknownTest;

  if (!FileWrite(UNKNOWN_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNKNOWN_FILE " > " UNKNOWN_OUT " 2>&1");

  out = FileRead(UNKNOWN_OUT);
  if (!out) {
    CleanupUnknownTest;
    return FALSE;
  }

  if (count == 0) {
    if (res != 0) ok = FALSE;
  } else {
    if (res != 1) ok = FALSE;
  }
  if (want && !strstr(out, want)) ok = FALSE;
  if (CountErrors(out) != count) ok = FALSE;
  if (!ok) "exit status %d\n%s\n", res, out;

  Free(out);
  CleanupUnknownTest;
  return ok;
}

/* The report: located at the `$`, then the parser's own complaint
 * about the `3` it now sees after `2.5` */
Bool TestDollarSign(U0)
{
  return Compile(
"U0 Main() { I64 x = 2.5 $ 3; }
", 2, "error: unexpected character '$'\n --> " UNKNOWN_FILE ":1:25\n");
}

/* Stray characters between statements: one error each, nothing else */
Bool TestBacktick(U0)
{
  return Compile(
"U0 Main() {
  \"hi\\n\"; `
}
", 1, "error: unexpected character '`'\n --> " UNKNOWN_FILE ":2:11\n");
}

Bool TestDollarAt(U0)
{
  return Compile(
"U0 Main() {
  $
    @
}
", 2, "error: unexpected character '@'\n --> " UNKNOWN_FILE ":3:5\n");
}

Bool TestControlChar(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; \x01 }
", 1, "error: unexpected character '\\x01'\n --> " UNKNOWN_FILE ":1:21\n");
}

/* A UTF-8 character outside a string is reported once, as itself */
Bool TestUtf8(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; \xe2\x82\xac }
", 1, "error: unexpected character '\xe2\x82\xac'\n --> " UNKNOWN_FILE ":1:21\n");
}

Bool TestTwoUtf8(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; \xc3\xa9\xf0\x9f\x98\x80 }
", 2, "error: unexpected character '\xf0\x9f\x98\x80'\n");
}

/* Fine in strings, character constants and comments */
Bool TestQuoted(U0)
{
  return Compile(
"// ? ` $ @ \xe2\x82\xac
/* ? ` $ @ \xc3\xa9 */
U0 Main() {
  \"? ` $ @ \xe2\x82\xac\\n\";
  I64 c = '?';
}
", 0, NULL);
}

/* An #if group that is skipped may hold them, like an x86_64 asm block
 * with `@@1` labels skipped on arm64 */
Bool TestSkippedGroup(U0)
{
  return Compile(
"#if 0
  JMP @@1 ? ` $ \xe2\x82\xac
#endif
U0 Main() { \"hi\\n\"; }
", 0, NULL);
}

/* `$` and `@` are fine in an asm block, and `$` inside a name */
Bool TestAsm(U0)
{
  return Compile(
"#ifdef IS_X86_64
U0 AsmLabels() {
asm {
    JMP @@1
@@1:
    RET
}
}
#endif
U0 Main() { I64 a$b = 1; \"%d\\n\", a$b; }
", 0, NULL);
}

/* The echoed source line keeps the characters and the re-lexer that
 * echoes it adds no errors of its own */
Bool TestEcho(U0)
{
  return Compile(
"U0 Main() { I64 x = 1 ? \xe2\x82\xac; }
", 2, "   1 |    U0 Main() { I64 x = 1 ? \xe2\x82\xac; }\n");
}

I32 Main()
{
  "Test - unknown character: ";
  I64 correct = 0, tests = 0;

  tests++; if (TestDollarSign) correct++;
  tests++; if (TestBacktick)     correct++;
  tests++; if (TestDollarAt)     correct++;
  tests++; if (TestControlChar)  correct++;
  tests++; if (TestUtf8)         correct++;
  tests++; if (TestTwoUtf8)      correct++;
  tests++; if (TestQuoted)       correct++;
  tests++; if (TestSkippedGroup) correct++;
  tests++; if (TestAsm)          correct++;
  tests++; if (TestEcho)         correct++;

  CleanupUnknownTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
