#include "testhelper.ktm"

/* ReAlloc copied MSize(old) bytes into the new block even when the new
 * size was smaller, writing past the end of the new allocation. */

I32 Main()
{
  "Test - ReAlloc shrink: ";
  I64 correct = 0, tests = 0, i, round, ok;
  U8 *p, *q[64];

  /* Shrinking a big block: the old code copied 4096 bytes into a
   * 16-byte block and trashed the heap around it. */
  for (round = 0; round < 64; round++) {
    p = MAlloc(4096);
    for (i = 0; i < 4096; i++) p[i] = i & 0xFF;
    q[round] = ReAlloc(p, 16);
  }
  ok = TRUE;
  for (round = 0; round < 64; round++) {
    if (MSize(q[round]) != 16) ok = FALSE;
    for (i = 0; i < 16; i++) if (q[round][i] != i) ok = FALSE;
  }
  for (round = 0; round < 64; round++) Free(q[round]);
  tests++; if (ok) correct++;

  p = MAlloc(100);
  for (i = 0; i < 100; i++) p[i] = i;
  p = ReAlloc(p, 4);
  tests++; if (MSize(p) == 4 && p[0] == 0 && p[3] == 3) correct++;

  /* Growing still copies all of the old contents. */
  p = ReAlloc(p, 200);
  tests++; if (MSize(p) == 200 && p[0] == 0 && p[3] == 3) correct++;

  /* Same size. */
  for (i = 0; i < 200; i++) p[i] = 200 - i;
  p = ReAlloc(p, 200);
  ok = MSize(p) == 200;
  for (i = 0; i < 200; i++) if (p[i] != 200 - i) ok = FALSE;
  tests++; if (ok) correct++;

  /* Down to zero bytes. */
  p = ReAlloc(p, 0);
  tests++; if (p && MSize(p) == 0) correct++;
  Free(p);

  /* NULL acts like MAlloc. */
  p = ReAlloc(NULL, 7);
  tests++; if (p && MSize(p) == 7) correct++;
  Free(p);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
