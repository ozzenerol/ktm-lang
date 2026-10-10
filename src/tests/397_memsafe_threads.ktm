#include "testhelper.ktm"

/* `ktm -jit -Memsafe` crashed (SIGSEGV) as soon as a pthread MAlloc'd:
 * the allocation-site walk followed frame pointers past the end of the
 * thread's chain, which, unlike the main thread's, doesn't end in a NULL
 * frame pointer. The walk now stays inside the calling thread's stack,
 * and the tracking tables, shared by every thread, are locked. Leaks and
 * double frees in threads are still reported, with their sites. */

#define MT_PROG "./ktm-397-mt.ktm"
#define MT_OUT  "./ktm-397-mt.out"
#define MT_ERR  "./ktm-397-mt.err"
#define MT_RUN "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit -Memsafe ./ktm-397-mt.ktm > ./ktm-397-mt.out 2> ./ktm-397-mt.err"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else {
    "\n  failed: %s\n", what;
    System("cat " MT_OUT " " MT_ERR " | head -10");
  }
}

/* Run `src` under -Memsafe: TRUE if ktm exited 0 */
Bool RunMt(U8 *src)
{
  FileWrite(MT_PROG, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  return System(MT_RUN) == 0;
}

/* `what` (an extended regex) is on `times` lines of the report */
Bool ErrHas(U8 *what, I64 times = 1)
{
  U8 cmd[512];
  StrPrint(cmd, "[ \"$(grep -E -c -- '%s' " MT_ERR ")\" = %d ]", what, times);
  return System(cmd) == 0;
}

I32 Main()
{
  "Test - -Memsafe in threads: ";

  /* 8 threads x 2000 rounds of MAlloc/ReAlloc/CAlloc/Free, all freed:
   * no crash and nothing reported */
  Check(RunMt(
"U0 *Churn(U0 *arg)
{
  I64 i, n = arg(I64);
  U8 *keep[8];
  for (i = 0; i < 8; i++) keep[i] = MAlloc(16 + i);
  for (i = 0; i < n; i++) {
    U8 *p = MAlloc(64 + i % 32);
    p[0] = i;
    p = ReAlloc(p, 128);
    U8 *q = CAlloc(24);
    Free(p);
    Free(q);
  }
  for (i = 0; i < 8; i++) Free(keep[i]);
  return NULL;
}
I32 Main()
{
  pthread_t *th[8];
  I64 i;
  for (i = 0; i < 8; i++) pthread_create(&th[i], NULL, &Churn, 2000(U0 *));
  for (i = 0; i < 8; i++) pthread_join(th[i]);
  \"done\\n\";
  return 0;
}
"), "8 threads allocate and free");
  Check(System("[ \"$(cat " MT_OUT ")\" = done ] && [ ! -s " MT_ERR " ]") == 0,
        "correct threaded code reports nothing");

  /* a leak in one thread, a double free in another */
  Check(RunMt(
"U0 *Leaker(U0 *arg)
{
  U8 *lost = MAlloc(48);
  lost[0] = 1;
  return NULL;
}
U0 *DoubleFree(U0 *arg)
{
  U8 *p = MAlloc(40);
  Free(p);
  Free(p);
  return NULL;
}
I32 Main()
{
  pthread_t *a, *b;
  pthread_create(&a, NULL, &Leaker, NULL);
  pthread_join(a);
  pthread_create(&b, NULL, &DoubleFree, NULL);
  pthread_join(b);
  \"done\\n\";
  return 0;
}
"), "leak and double free in threads run");
  /* macOS names the functions with a leading `_` */
  Check(ErrHas("memsafe: 1 allocation\\(s\\) \\(48 bytes\\) never freed") &&
        ErrHas("in `_?Leaker`"), "leak in a thread reported, with its site");
  Check(ErrHas("double free of") && ErrHas("this free   in `_?DoubleFree`"),
        "double free in a thread caught, with its site");

  System("rm -f " MT_PROG " " MT_OUT " " MT_ERR);
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
