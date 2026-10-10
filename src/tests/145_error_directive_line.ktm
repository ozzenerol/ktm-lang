#include "testhelper.ktm"

/* `#error 1.5 0x10 'a'` printed only "1.5": the message was the first
 * token after `#error`. As in C it must be the rest of the line as
 * written (up to a comment, trimmed), reported at the `#error`, and
 * ktm must exit 1. A lone string literal is shown without its quotes,
 * as before. */

#define ERRDIR_FILE "__145_error_directive.ktm"
#define ERRDIR_OUT  "__145_error_directive.out"

U0 CleanupErrorDirTest(U0)
{
  Rm(ERRDIR_FILE);
  Rm(ERRDIR_OUT);
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

  CleanupErrorDirTest;

  if (!FileWrite(ERRDIR_FILE, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }

  res = System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ERRDIR_FILE " > " ERRDIR_OUT " 2>&1");

  out = FileRead(ERRDIR_OUT);
  if (!out) {
    CleanupErrorDirTest;
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
  CleanupErrorDirTest;
  return ok;
}

Bool TestWholeLine(U0)
{
  return Compile(
"#error 1.5 0x10 'a'
U0 Main() { \"hi\\n\"; }
", 1, "error: 1.5 0x10 'a'\n --> " ERRDIR_FILE ":1:1\n");
}

Bool TestLoneString(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
#error \"stop here\"
", 1, "error: stop here\n --> " ERRDIR_FILE ":2:1\n");
}

Bool TestStringAndMore(U0)
{
  return Compile(
"#error \"a\" and \"b\"
U0 Main() { \"hi\\n\"; }
", 1, "error: \"a\" and \"b\"\n");
}

/* Indented, extra spaces kept inside, trimmed at both ends, and a
 * trailing comment left out */
Bool TestTrimAndComment(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
  #error   too  many   spaces   // why
", 1, "error: too  many   spaces\n --> " ERRDIR_FILE ":2:3\n");
}

/* A block comment after it may run on to later lines */
Bool TestBlockComment(U0)
{
  return Compile(
"#error stop /* a comment
that ends here */
U0 Main() { \"hi\\n\"; }
", 1, "error: stop\n --> " ERRDIR_FILE ":1:1\n");
}

/* Comment markers in quotes are part of the message */
Bool TestQuotedCommentMarkers(U0)
{
  return Compile(
"#error \"// not\" '/*' a comment
U0 Main() { \"hi\\n\"; }
", 1, "error: \"// not\" '/*' a comment\n");
}

/* Nothing after it: the message is "#error", the next line is kept */
Bool TestEmpty(U0)
{
  return Compile(
"#error
U0 Main() { \"hi\\n\"; }
", 1, "error: #error\n --> " ERRDIR_FILE ":1:1\n");
}

/* Lexing carries on after each one */
Bool TestTwo(U0)
{
  return Compile(
"#error first one
U0 Main() { \"hi\\n\"; }
#error second one
", 2, "error: second one\n --> " ERRDIR_FILE ":3:1\n");
}

/* At the end of the file, with no newline */
Bool TestAtEof(U0)
{
  return Compile(
"U0 Main() { \"hi\\n\"; }
#error the end", 1, "error: the end\n --> " ERRDIR_FILE ":2:1\n");
}

/* Not reported in a group that is skipped */
Bool TestSkipped(U0)
{
  return Compile(
"#if 0
#error 1.5 0x10 'a'
#endif
U0 Main() { \"hi\\n\"; }
", 0, NULL);
}

I32 Main()
{
  "Test - #error directive message: ";
  I64 correct = 0, tests = 0;

  tests++; if (TestWholeLine)            correct++;
  tests++; if (TestLoneString)           correct++;
  tests++; if (TestStringAndMore)        correct++;
  tests++; if (TestTrimAndComment)       correct++;
  tests++; if (TestBlockComment)         correct++;
  tests++; if (TestQuotedCommentMarkers) correct++;
  tests++; if (TestEmpty)                correct++;
  tests++; if (TestTwo)                  correct++;
  tests++; if (TestAtEof)                correct++;
  tests++; if (TestSkipped)              correct++;

  CleanupErrorDirTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
