#include "testhelper.ktm"

/* Pwd() returned getcwd(NULL,0)'s libc malloc block, and Free of it
 * aborted ("free(): invalid pointer"): Free expects MAlloc's header. It
 * now returns an MAlloc'd copy. */

extern "c" U8 *getcwd(U8 *buf, U64 size);

I32 Main()
{
  I64 correct = 0, tests = 4, i;
  U8 buf[4096], small[2], *p;
  "Test - Free(Pwd()): ";

  getcwd(buf, sizeof(buf));
  p = Pwd();
  if (p && !StrCmp(p, buf)) correct++;
  if (p && MSize(p) >= StrLen(p) + 1) correct++;
  Free(p);

  for (i = 0; i < 100; i++) Free(Pwd());
  correct++;

  /* With a caller's buffer it fills that buffer */
  if (Pwd(buf, sizeof(buf)) == buf && Pwd(small, sizeof(small)) == NULL)
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
