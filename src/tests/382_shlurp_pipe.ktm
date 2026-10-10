#include "testhelper.ktm"

/* Shlurp read the command's output back from a temporary file in
 * $TMPDIR, so it returned NULL when $TMPDIR was missing or read-only and
 * left a file there whenever the program was killed mid-call. It reads a
 * pipe now (process.ktm), started the way system() starts its shell:
 * nothing in $TMPDIR, the same result, the user's own inheritable fds
 * still reach the command, and a background job the command starts
 * doesn't make Shlurp wait for it. */

extern "c" U8 *getenv(U8 *name);
extern "c" I32 setenv(U8 *name, U8 *value, I32 overwrite);
extern "c" I32 unsetenv(U8 *name);
extern "c" I32 rmdir(U8 *path);
extern "c" I64 time(I64 *t);
extern "c" I32 dup(I32 fd);
extern "c" I32 dup2(I32 fd, I32 fd2);

#define DIR "./ktm-382-tmp"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* The result is `want`; frees it */
Bool Is(U8 *got, U8 *want)
{
  Bool ok = got && !StrCmp(got, want);
  Free(got);
  return ok;
}

U0 *Worker(U0 *arg)
{
  I64 id = arg(I64), j, *bad = MAlloc(sizeof(I64));
  *bad = 0;
  for (j = 0; j < 25; j++) {
    U8 *want = MStrPrint("w%d-%d", id, j);
    if (!Is(Shlurp("echo %s", want), want)) *bad += 1;
    Free(want);
  }
  return bad;
}

I32 Main()
{
  "Test - Shlurp through a pipe: ";
  I64 i, t0, bad = 0, *r;
  U8 *out;

  U8 *tmpdir = getenv("TMPDIR");
  if (tmpdir) tmpdir = StrNew(tmpdir);

  /* $TMPDIR doesn't exist: Shlurp doesn't need it */
  setenv("TMPDIR", "./ktm-382-does-not-exist", 1);
  Check(Is(Shlurp("echo ok"), "ok"), "Shlurp with a missing $TMPDIR");

  /* nothing is created in $TMPDIR */
  MkDir(DIR);
  setenv("TMPDIR", DIR, 1);
  for (i = 0; i < 10; i++) Free(Shlurp("echo %d", i));
  Check(Is(Shlurp("ls -A %s | wc -l | tr -d ' '", DIR), "0"),
        "no file in $TMPDIR");
  rmdir(DIR);
  if (tmpdir) setenv("TMPDIR", tmpdir, 1);
  else unsetenv("TMPDIR");
  Free(tmpdir);

  /* more than a pipe buffer, with one trailing newline removed */
  out = Shlurp("yes | head -c 1048576");
  Check(out && StrLen(out) == 1048575 && out[0] == 'y' && out[1] == '\n',
        "1 MiB of output");
  Free(out);

  /* a background job keeps the pipe open; Shlurp still returns when the
   * shell exits */
  t0 = time(NULL);
  out = Shlurp("echo first; sleep 5 > /dev/null 2>&1 & sleep 5 &");
  Check(out && !StrCmp(out, "first") && time(NULL) - t0 < 4,
        "background job not waited for");
  Free(out);

  /* the user's inheritable fds reach the command, as with System */
  I32 fd = open("/dev/null", O_RDONLY);
  Check(Is(Shlurp("[ -e /dev/fd/%d ] && echo yes", fd), "yes"),
        "an inheritable fd reaches the command");
  close(fd);

  /* stdin is ours: /dev/null here, so cat ends at once */
  I32 saved = dup(0);
  I32 nul = open("/dev/null", O_RDONLY);
  dup2(nul, 0);
  Check(Is(Shlurp("cat; echo end"), "end"), "stdin is inherited");
  dup2(saved, 0);
  close(nul);
  close(saved);

  /* from 4 threads at once */
  pthread_t *t[4];
  for (i = 0; i < 4; i++) pthread_create(&t[i], NULL, &Worker, i(U0 *));
  for (i = 0; i < 4; i++) {
    pthread_join(t[i], &r);
    bad += *r;
    Free(r);
  }
  Check(bad == 0, "Shlurp from 4 threads");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
