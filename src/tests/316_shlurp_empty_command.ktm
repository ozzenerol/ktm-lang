#include "testhelper.ktm"

/* Shlurp wrapped the command as "{ cmd\n}": an empty command made "{ }",
 * and a command ending in a backslash swallowed the "}", both shell
 * syntax errors on stderr. The command now goes on its own line, then a
 * blank line and ":". Everything happens in a fresh mkdtemp directory. */

extern "c" U8 *mkdtemp(U8 *template);
extern "c" I32 dup(I32 fd);
extern "c" I32 dup2(I32 fd, I32 fd2);
extern "c" I32 fflush(U0 *stream);

U8 g_err[256];
I32 g_saved;

U0 CaptureErr()
{
  fflush(NULL);
  g_saved = dup(2);
  I32 fd = open(g_err, O_WRONLY|O_CREAT|O_TRUNC, 0x1A4);
  dup2(fd, 2);
  close(fd);
}

U8 *CapturedErr()
{
  fflush(NULL);
  dup2(g_saved, 2);
  close(g_saved);
  return FileRead(g_err);
}

Bool Quiet(U8 *cmd, U8 *want)
{
  CaptureErr;
  U8 *got = Shlurp(cmd), *err = CapturedErr;
  Bool ok = got && !StrCmp(got, want) && err && !StrCmp(err, "");
  if (!ok) "Shlurp(\"%s\"): got '%s' want '%s', stderr '%s'\n",
           cmd, got, want, err;
  Free(got);
  Free(err);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 6;
  U8 root[64], *cmd;
  "Test - Shlurp empty command: ";

  StrCpy(root, "./ktm-test-316-XXXXXX");
  if (!mkdtemp(root)) {
    PrintResult(0, tests);
    "====\n";
    return 0;
  }
  StrPrint(g_err, "%s/err.txt", root);

  if (Quiet("", "")) correct++;
  if (Quiet("   ", "")) correct++;
  if (Quiet("echo a \\", "a")) correct++;
  if (Quiet("echo a # comment", "a")) correct++;
  if (Quiet("echo a; echo b", "a\nb")) correct++;
  if (Quiet("echo x\necho y", "x\ny")) correct++;

  cmd = StrPrint(NULL, "rm -rf '%s'", root);
  System(cmd);
  Free(cmd);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
