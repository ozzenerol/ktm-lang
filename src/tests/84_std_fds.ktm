#include "testhelper.ktm"

/* std.ktmh (and the library's defs.ktmh) defined STDOUT as 0 and STDIN as
 * 2, so Write(STDOUT, ...) wrote to stdin: it only worked while fd 0
 * happened to be a read/write terminal and was lost when stdout was
 * redirected. They are the POSIX numbers: STDIN 0, STDOUT 1, STDERR 2.
 * The program below is run through the JIT with each stream
 * redirected to a file, which is read back. */

#define FD_TEST_PROG "./ktm-84-prog.ktm"
#define FD_TEST_OUT  "./ktm-84-out.txt"
#define FD_TEST_ERR  "./ktm-84-err.txt"
/* A single literal: adjacent strings don't concatenate inside #define */
#define FD_TEST_KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit ./ktm-84-prog.ktm"

U0 CleanupFdTest(U0)
{
  System("rm -f " FD_TEST_PROG " " FD_TEST_OUT " " FD_TEST_ERR);
}

/* The file at `path` holds exactly `expected` */
Bool FileIs(U8 *path, U8 *expected)
{
  U8 *got = FileRead(path);
  Bool ok;
  if (!got) {
    "%s missing\n", path;
    return FALSE;
  }
  ok = StrCmp(got, expected) == 0;
  if (!ok) "%s: got '%s', want '%s'\n", path, got, expected;
  Free(got);
  return ok;
}

I32 Main()
{
  "Test - STDIN, STDOUT and STDERR: ";
  I64 tests = 7, correct = 0;
  U8 *prog;

  if (STDIN == 0 && STDOUT == 1 && STDERR == 2) correct++;
  else "Failed - STDIN %d, STDOUT %d, STDERR %d\n", STDIN, STDOUT, STDERR;

  CleanupFdTest;
  /* Copies stdin to stdout, then writes one line to each stream */
  prog =
"I32 Main()
{
  U8 buf[64];
  I64 n = read(STDIN, buf, 64);
  if (n > 0) Write(STDOUT, buf, n);
  Write(STDOUT, \"to stdout\\n\", 10);
  Write(STDERR, \"to stderr\\n\", 10);
  return 0;
}
";
  FileWrite(FD_TEST_PROG, prog, StrLen(prog), O_CREAT | O_RDWR | O_TRUNC);

  /* stdin is /dev/null (read-only), stdout and stderr are files */
  if (System(FD_TEST_KTM " < /dev/null > " FD_TEST_OUT " 2> " FD_TEST_ERR) == 0) correct++;
  else "Failed - running the program\n";
  if (FileIs(FD_TEST_OUT, "to stdout\n")) correct++;
  else "Failed - Write(STDOUT, ...) to a redirected stdout\n";
  if (FileIs(FD_TEST_ERR, "to stderr\n")) correct++;
  else "Failed - Write(STDERR, ...) to a redirected stderr\n";

  /* STDIN reads piped input */
  if (System("printf 'piped\\n' | " FD_TEST_KTM " > " FD_TEST_OUT " 2> " FD_TEST_ERR) == 0) correct++;
  else "Failed - running the program with piped stdin\n";
  if (FileIs(FD_TEST_OUT, "piped\nto stdout\n")) correct++;
  else "Failed - read(STDIN, ...) from a pipe\n";

  /* stdout and stderr stay separate when both go to one pipe */
  if (System(FD_TEST_KTM " < /dev/null 2>/dev/null | grep -qx 'to stdout'") == 0
      && System(FD_TEST_KTM " < /dev/null 2>&1 >/dev/null | grep -qx 'to stderr'") == 0) correct++;
  else "Failed - stdout and stderr through pipes\n";

  CleanupFdTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
