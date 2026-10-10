#include "testhelper.ktm"

/* `ktm -jit -Memsafe` reported leaks and double frees in correct code:
 * its pointer table (a containers.c Map) ended every lookup at the
 * first deleted slot, so after a few Frees the live blocks behind a
 * tombstone could no longer be found - they were "never freed", and
 * freeing them again looked like a wild free. A freed block's address
 * could also be reused by a MAlloc inside libktm while still in the
 * recently-freed ring, so freeing that looked like a double free.
 * Correct programs must report nothing; real leaks, double frees and
 * wild frees must be reported exactly. */

#define MS_PROG "./ktm-97-memsafe.ktm"
#define MS_OUT  "./ktm-97-memsafe.out"
#define MS_ERR  "./ktm-97-memsafe.err"
/* A single literal: adjacent strings don't concatenate inside #define */
#define MS_RUN "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit -Memsafe ./ktm-97-memsafe.ktm > ./ktm-97-memsafe.out 2> ./ktm-97-memsafe.err"
#define MS_REPL "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -repl < ./ktm-97-memsafe.ktm > ./ktm-97-memsafe.out 2> ./ktm-97-memsafe.err"

U0 CleanupMemsafeTest(U0)
{
  System("rm -f " MS_PROG " " MS_OUT " " MS_ERR);
}

/* Write `source` and run it under -Memsafe; TRUE if ktm exited 0 */
Bool RunMemsafe(U8 *source, U8 *cmd = MS_RUN)
{
  FileWrite(MS_PROG, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  return System(cmd) == 0;
}

/* The program's stdout must be exactly `expected` */
Bool StdoutIs(U8 *expected)
{
  U8 *out = FileRead(MS_OUT);
  Bool ok;
  if (!out) return FALSE;
  ok = StrCmp(out, expected) == 0;
  if (!ok) "got: '%s'\n", out;
  Free(out);
  return ok;
}

/* A shell test over the captured report */
Bool Check(U8 *cmd)
{
  if (System(cmd) == 0) return TRUE;
  System("cat " MS_ERR);
  return FALSE;
}

I32 Main()
{
  "Test - -Memsafe reports: ";
  I64 tests = 6;
  I64 correct = 0;

  CleanupMemsafeTest;

  /* Correct code: thousands of blocks freed in mixed order, every
   * allocator entry point, stdlib strings and containers with
   * destructors. Nothing may be reported. */
  if (RunMemsafe(
"U0 FreeIt(U0 *p) { Free(p); }
U0 Main()
{
  I64 i, j;
  U8 *p, *s, **arr = MAlloc(3000 * sizeof(U8 *));
  for (j = 0; j < 3; j++) {
    for (i = 0; i < 3000; i++) arr[i] = MAlloc(i % 97 + 1);
    for (i = 0; i < 3000; i += 2) Free(arr[i]);
    for (i = 1; i < 3000; i += 2) Free(arr[i]);
  }
  Free(arr);
  p = CAlloc(64); Free(p);
  p = MAlloc(10); p = ReAlloc(p, 1000); p = ReAlloc(p, 4); Free(p);
  p = ReAlloc(NULL, 32); p = ReAlloc(p, 0); Free(p);
  p = MAlloc(0); Free(p);
  p = MAlloc(40); if (MSize(p) != 40) \"bad MSize\\n\"; Free(p);
  Free(NULL);
  s = StrNew(\"hello\"); Free(s);
  s = StrPrint(NULL, \"%d\", 42); Free(s);
  s = MStrPrint(\"%d\", 7); Free(s);
  s = StrNew(\"a\"); s = CatPrint(s, \"%d\", 123); Free(s);
  PtrVec *v = PtrVecNew;
  for (i = 0; i < 1000; i++) PtrVecPush(v, MAlloc(24));
  PtrVecRelease(v, &FreeIt);
  StrMap *m = StrMapNew;
  StrMapSetFreeValue(m, &Free);
  StrMapSetFreeKey(m, &Free);
  for (i = 0; i < 2000; i++) {
    s = MAlloc(16);
    StrPrint(s, \"k%d\", i);
    StrMapSet(m, s, CAlloc(8));
  }
  StrMapRelease(m);
  \"ok\\n\";
}
") && StdoutIs("ok\n") && Check("test ! -s " MS_ERR)) correct++;
  else "Failed - correct program reported problems\n";

  /* Exactly the 3 blocks leaked among 2000 freed ones, with sizes */
  if (RunMemsafe(
"U0 Main()
{
  I64 i;
  U8 *keep[3];
  for (i = 0; i < 1000; i++) Free(MAlloc(i + 1));
  keep[0] = MAlloc(10);
  keep[1] = CAlloc(20);
  keep[2] = ReAlloc(MAlloc(5), 30);
  for (i = 0; i < 1000; i++) Free(MAlloc(i + 1));
}
") && Check("grep -qx 'memsafe: 3 allocation(s) (60 bytes) never freed' " MS_ERR
            " && test $(grep -c ' bytes  ' " MS_ERR ") -eq 3"
            " && grep -q ' 30 bytes  .*line 8$' " MS_ERR
            " && grep -q ' 20 bytes  .*line 7$' " MS_ERR
            " && grep -q ' 10 bytes  .*line 6$' " MS_ERR
            " && ! grep -q 'double free\\|never heap' " MS_ERR)) correct++;
  else "Failed - leak of 3 blocks\n";

  /* One double free, reported once and skipped; no leak */
  if (RunMemsafe(
"U0 Main()
{
  I64 i;
  for (i = 0; i < 500; i++) Free(MAlloc(8));
  U8 *p = MAlloc(5);
  Free(p);
  Free(p);
  \"after\\n\";
}
") && StdoutIs("after\n") &&
      Check("test $(grep -c 'double free of .* (5 bytes)' " MS_ERR ") -eq 1"
            " && ! grep -q 'never freed\\|never heap' " MS_ERR)) correct++;
  else "Failed - double free\n";

  /* Freeing a stack array and a global: wild frees, skipped */
  if (RunMemsafe(
"I64 g[4];
U0 Main()
{
  I64 x[4];
  Free(x);
  Free(g);
  \"after\\n\";
}
") && StdoutIs("after\n") &&
      Check("test $(grep -c 'was never heap-allocated' " MS_ERR ") -eq 2"
            " && ! grep -q 'never freed\\|double free' " MS_ERR)) correct++;
  else "Failed - wild free\n";

  /* The REPL always tracks: churn leaves nothing live, a double free
   * is reported once, ReplHeap shows the one real live block */
  if (RunMemsafe(
"I64 i;
U8 *a[300];
for (i = 0; i < 300; i++) a[i] = MAlloc(8);
for (i = 0; i < 300; i++) Free(a[i]);
U8 *p = MAlloc(7);
U8 *q = MAlloc(9);
Free(q);
Free(q);
ReplHeap;
", MS_REPL) && Check("grep -q '^heap: 1 live allocation(s), 7 bytes$' " MS_OUT
                       " && test $(grep -c 'double free of .* (9 bytes)' " MS_ERR ") -eq 1"))
    correct++;
  else "Failed - repl\n";

  /* Exact leak count and total over thousands of survivors */
  if (RunMemsafe(
"U0 Main()
{
  I64 i;
  U8 *p;
  for (i = 0; i < 4000; i++) {
    p = MAlloc(8);
    if (i % 4) Free(p);
  }
}
") && Check("grep -qx 'memsafe: 1000 allocation(s) (8000 bytes) never freed' " MS_ERR))
    correct++;
  else "Failed - leak count over thousands\n";

  CleanupMemsafeTest;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
