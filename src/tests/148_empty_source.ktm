#include "testhelper.ktm"

/* An empty main source file stepped past its terminator once the
 * stdlib header pushed in front of it was done, so ktm lexed whatever
 * memory followed the 1-byte buffer: random errors over a garbage
 * source line ("Type `F` has not been declared"). An empty file is
 * valid input and must be treated like one holding only a comment:
 * no output, no errors, the same exit status every time. */

#define EMPTY_FILE "__148_empty.ktm"
#define EMPTY_INC  "__148_empty.ktmh"
#define EMPTY_OUT  "__148_empty.out"

U0 CleanupEmptyTest(U0)
{
  Rm(EMPTY_FILE);
  Rm(EMPTY_INC);
  Rm(EMPTY_OUT);
  Rm("./a.out");
}

/* Write `printf_fmt` (a printf format, so "" makes an empty file) to
 * `file` through the shell */
Bool WriteFile(U8 *file, U8 *printf_fmt)
{
  U8 *cmd = MStrPrint("printf '%s' > %s", printf_fmt, file);
  I64 res = System(cmd);
  Free(cmd);
  return res == 0;
}

/* Run ktm with `flags` on EMPTY_FILE; returns the exit code and
 * leaves the output in `*_out` (NULL if it could not be read) */
I64 RunKtm(U8 *flags, U8 **_out)
{
  U8 *cmd = MStrPrint(
      "T=; command -v timeout >/dev/null 2>&1 && T='timeout 10'; "
      "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
      "%s " EMPTY_FILE " > " EMPTY_OUT " 2>&1", flags);
  I64 res = System(cmd);
  Free(cmd);
  *_out = FileRead(EMPTY_OUT);
  return res;
}

/* What `ktm -jit` prints for a file holding only a comment: it has no
 * code and no Main */
U8 *g_comment_out = NULL;

/* The exit status of `ktm -jit` on a file holding only a comment: what
 * a program with no code and no Main does */
I64 CommentOnlyStatus(U0)
{
  U8 *out;
  I64 res;
  CleanupEmptyTest;
  if (!WriteFile(EMPTY_FILE, "// nothing here\\n")) return -1;
  res = RunKtm("-jit", &out);
  g_comment_out = out;
  CleanupEmptyTest;
  return res;
}

/* `content` as the main file: `-tokens` prints nothing, and `-jit`
 * (several times, the bug read whatever happened to be in memory)
 * prints and exits like the comment-only file */
Bool TestNoCode(U8 *name, U8 *content, I64 want_status)
{
  U8 *out;
  I64 res, i;
  Bool ok = TRUE;

  CleanupEmptyTest;
  if (!WriteFile(EMPTY_FILE, content)) return FALSE;

  for (i = 0; i < 3; i++) {
    res = RunKtm("-jit", &out);
    if (!out || !g_comment_out || res != want_status ||
        StrCmp(out, g_comment_out)) {
      "%s, -jit run %d: status %d (want %d)\n%s\n", name, i, res,
        want_status, out;
      ok = FALSE;
    }
    if (out) Free(out);
  }

  res = RunKtm("-tokens", &out);
  if (!out || res != 0 || StrLen(out) != 0) {
    "%s, -tokens: status %d\n%s\n", name, res, out;
    ok = FALSE;
  }
  if (out) Free(out);

  CleanupEmptyTest;
  return ok;
}

/* An empty file included from a real program */
Bool TestEmptyInclude(U0)
{
  U8 *out;
  I64 res;
  Bool ok = TRUE;

  CleanupEmptyTest;
  if (!WriteFile(EMPTY_INC, "")) return FALSE;
  if (!WriteFile(EMPTY_FILE,
        "#include \"" EMPTY_INC "\"\\nU0 Main() { \"ok\\\\n\"; }\\n")) {
    CleanupEmptyTest;
    return FALSE;
  }
  res = RunKtm("-jit", &out);
  if (!out || res != 0 || StrCmp(out, "ok\n")) {
    "empty include: status %d\n%s\n", res, out;
    ok = FALSE;
  }
  if (out) Free(out);
  CleanupEmptyTest;
  return ok;
}

I32 Main()
{
  "Test - empty source file: ";
  I64 correct = 0, tests = 0;
  I64 status = CommentOnlyStatus;

  tests++; if (TestNoCode("empty", "", status))                  correct++;
  tests++; if (TestNoCode("newline", "\\n", status))             correct++;
  tests++; if (TestNoCode("whitespace", "  \\t \\n \\n  ", status)) correct++;
  tests++; if (TestNoCode("comment", "/* nothing */", status))   correct++;
  tests++; if (TestEmptyInclude)                                 correct++;

  CleanupEmptyTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
