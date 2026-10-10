#include "testhelper.ktm"

/* Sh and Shlurp ran fmt as-is when no arguments followed, so
 * Sh("echo '100%%'") printed 100%% and the same command meant different
 * things with and without arguments. fmt is now always formatted. */

Bool Is(U8 *got, U8 *want)
{
  Bool ok = got && !StrCmp(got, want);
  if (!ok) "got '%s' want '%s'\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 6;
  "Test - Sh and Shlurp format without arguments: ";

  if (Sh("exit $(printf '%%d' 7)") == 7) correct++;
  if (Sh("exit $(printf '%%d' %d)", 9) == 9) correct++;
  if (Is(Shlurp("echo 100%%"), "100%")) correct++;
  if (Is(Shlurp("printf '%%s-%%s' a b"), "a-b")) correct++;
  if (Is(Shlurp("echo %d%%", 5), "5%")) correct++;
  if (Is(Shlurp("echo plain"), "plain")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
