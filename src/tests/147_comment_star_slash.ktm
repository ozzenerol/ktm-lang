#include "testhelper.ktm"

/* The block comment scan started on the opener's `*`, so `/` `*` `/`
 * opened and closed a comment in three characters and what followed
 * was compiled as code. As in C, the `*` of the opener can't also be
 * the start of the closing `*` `/`: the comment runs on to a later
 * `*` `/`. */

#define SLASH_FILE "__147_comment_slash.ktm"
#define SLASH_OUT  "__147_comment_slash.out"

U0 CleanupSlashTest(U0)
{
  Rm(SLASH_FILE);
  Rm(SLASH_OUT);
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

  CleanupSlashTest;

  if (!FileWrite(SLASH_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SLASH_FILE " > " SLASH_OUT " 2>&1");

  out = FileRead(SLASH_OUT);
  if (!out) {
    CleanupSlashTest;
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
  CleanupSlashTest;
  return ok;
}

Bool TestSlashStarSlash(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
/*/ x
*/
", 0, NULL);
}

/* Code between it and the real end is commented out */
Bool TestSpansLines(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
/*/
I64 x = ;
U0 F( {
/ */
", 0, NULL);
}

/* `/` `*` `/` `*` `/` is one whole comment */
Bool TestFiveChars(U0)
{
  return Compile(
"U0 Main() { /*/*/ \"hi\\n\"; }
", 0, NULL);
}

Bool TestShortComments(U0)
{
  return Compile(
"U0 Main() { I64 a /**/ = 1 /***/ + /****/ 2; \"%d\\n\", a; }
", 0, NULL);
}

/* Left open at the end of the file it is still reported */
Bool TestUnterminatedAtEof(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
/*/", 1, "error: unterminated comment\n --> " SLASH_FILE ":2:1\n");
}

Bool TestUnterminatedLine(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
  /*/ never closed
", 1, "error: unterminated comment\n --> " SLASH_FILE ":2:3\n");
}

I32 Main()
{
  "Test - comment star slash: ";
  I64 correct = 0, tests = 0;

  tests++; if (TestSlashStarSlash)    correct++;
  tests++; if (TestSpansLines)        correct++;
  tests++; if (TestFiveChars)         correct++;
  tests++; if (TestShortComments)     correct++;
  tests++; if (TestUnterminatedAtEof) correct++;
  tests++; if (TestUnterminatedLine)  correct++;

  /* The same in this file itself */
  I64 n = 1 /**/ + /***/ 1;
  tests++; if (n == 2) correct++;
  tests++; /*/ correct--; */ correct++;
  tests++; /*/*/ correct++;

  CleanupSlashTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
