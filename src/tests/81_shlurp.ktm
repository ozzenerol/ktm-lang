#include "testhelper.ktm"

/* Shlurp("cmd") without format arguments used to run the command
 * through System(), so the output went to the terminal and the exit
 * status came back as a pointer. It also wrote to one fixed temporary
 * file it never removed, read output[-1] for empty output and leaked
 * the formatted command. Shlurp returns what the command printed to
 * stdout, minus one trailing newline; a failing command gives whatever
 * it printed (stderr is not captured); a NULL format gives NULL. */

/* The result is `expected`; frees it */
Bool Is(U8 *got, U8 *expected)
{
  Bool ok;
  if (!got) {
    "got NULL, want '%s'\n", expected;
    return FALSE;
  }
  ok = StrCmp(got, expected) == 0;
  if (!ok) "got '%s', want '%s'\n", got, expected;
  Free(got);
  return ok;
}

extern "c" U8 *getenv(U8 *name);
extern "c" I32 setenv(U8 *name, U8 *value, I32 overwrite);

/* Number of Shlurp temporary files in $TMPDIR (the test's own) */
I64 TempFiles()
{
  U8 *out = Shlurp("ls \"${TMPDIR:-/tmp}\" | grep -c '^ktm-shlurp-'");
  I64 n = -1;
  if (out) {
    n = Atoi(out);
    Free(out);
  }
  return n;
}

I32 Main()
{
  "Test - Shlurp: ";
  I64 tests = 16, correct = 0, before, i;
  U8 *out;

  before = TempFiles();

  /* Without format arguments: captured, not printed */
  if (Is(Shlurp("echo hi"), "hi")) correct++;
  else "Failed - Shlurp without arguments\n";

  /* With arguments */
  if (Is(Shlurp("echo %s %d", "two", 2), "two 2")) correct++;
  else "Failed - Shlurp with arguments\n";

  /* The command is formatted even without arguments: %% is one % */
  if (Is(Shlurp("printf '%%s\\n' 50%%"), "50%")) correct++;
  else "Failed - Shlurp formats without arguments\n";

  /* Multi-line output keeps inner newlines, loses only the last one */
  if (Is(Shlurp("printf 'a\\nb\\nc\\n'"), "a\nb\nc")) correct++;
  else "Failed - Shlurp multi-line output\n";
  if (Is(Shlurp("printf 'x\\n\\n'"), "x\n")) correct++;
  else "Failed - Shlurp removes only one trailing newline\n";
  if (Is(Shlurp("printf 'no newline'"), "no newline")) correct++;
  else "Failed - Shlurp output without a newline\n";

  /* Every command of a list is captured */
  if (Is(Shlurp("echo one; echo two"), "one\ntwo")) correct++;
  else "Failed - Shlurp command list\n";

  /* Empty output is an empty string */
  if (Is(Shlurp("true"), "")) correct++;
  else "Failed - Shlurp empty output\n";
  if (Is(Shlurp("printf '%%s' ''"), "")) correct++;
  else "Failed - Shlurp empty output with arguments\n";

  /* A failing command: what it printed to stdout, stderr isn't captured */
  if (Is(Shlurp("echo partial; ls /ktm-81-does-not-exist 2>/dev/null; exit 3"),
         "partial")) correct++;
  else "Failed - Shlurp failing command\n";
  if (Is(Shlurp("false"), "")) correct++;
  else "Failed - Shlurp failing command without output\n";

  /* NULL format */
  if (Shlurp(NULL) == NULL) correct++;
  else "Failed - Shlurp(NULL)\n";

  /* Larger output */
  out = Shlurp("seq 1 %d", 5000);
  if (out && StrLen(out) == 23892 && StrNCmp(out, "1\n2\n3\n", 6) == 0
      && out[StrLen(out) - 1] == '0') correct++;
  else "Failed - Shlurp large output\n";
  Free(out);

  /* No temporary file is left behind, even after many calls */
  for (i = 0; i < 20; i++) Free(Shlurp("echo %d", i));
  if (before >= 0 && TempFiles() == before) correct++;
  else "Failed - Shlurp left temporary files: %d before, %d after\n",
         before, TempFiles();

  /* Shlurp reads the command's output through a pipe: no temporary file
   * at all, not even while TempFiles' own Shlurp runs */
  if (before == 0) correct++;
  else "Failed - Shlurp made a temporary file in $TMPDIR (%d there)\n", before;

  /* A TMPDIR with a space in it changes nothing */
  U8 *tmpdir = getenv("TMPDIR");
  if (!tmpdir) tmpdir = "/tmp";
  tmpdir = StrNew(tmpdir);
  MkDir("./ktm-81 tmp");
  setenv("TMPDIR", "./ktm-81 tmp", 1);
  if (Is(Shlurp("echo spaced"), "spaced")) correct++;
  else "Failed - Shlurp with a space in TMPDIR\n";
  setenv("TMPDIR", tmpdir, 1);
  Free(tmpdir);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
