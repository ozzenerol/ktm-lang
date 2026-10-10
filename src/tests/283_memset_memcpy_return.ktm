#include "testhelper.ktm"

/* MemSet and MemCpy are declared to return U0 * but returned junk: the
 * x86_64 MemSet copied RAX into RDI instead of the other way round,
 * MemCpy never set RAX, and on arm64 MemCpy returned dst + len and
 * MemSet with len 0 returned an uninitialised register. Both return
 * dst now, like C's memset/memcpy. */

I32 Main()
{
  "Test - MemSet/MemCpy return dst: ";
  I64 correct = 0, tests = 0, i, ok;
  U8 buf[64], src[64];

  for (i = 0; i < 64; i++) { buf[i] = 0; src[i] = 'a' + i % 26; }

  tests++; if (MemSet(buf, 'x', 4) == buf) correct++;
  tests++; if (MemSet(buf, 'y', 0) == buf) correct++;
  tests++; if (MemSet(buf + 3, 0, 1) == buf + 3) correct++;
  tests++; if (MemCpy(buf, src, 5) == buf) correct++;
  tests++; if (MemCpy(buf, src, 0) == buf) correct++;
  tests++; if (MemCpy(buf + 10, src, 64 - 10) == buf + 10) correct++;

  /* The copies and fills themselves still work. */
  MemSet(buf, 0, 64);
  ok = MemCpy(buf + 1, src, 3) == buf + 1;
  ok = ok && buf[0] == 0 && buf[1] == 'a' && buf[3] == 'c' && buf[4] == 0;
  tests++; if (ok) correct++;
  ok = MemSet(buf, 'z', 2) == buf && buf[0] == 'z' && buf[1] == 'z' && buf[2] == 'b';
  tests++; if (ok) correct++;

  /* Chaining on the result. */
  U8 *p = MemSet(MAlloc(16), 7, 16);
  ok = p != NULL && MSize(p) == 16;
  for (i = 0; ok && i < 16; i++) if (p[i] != 7) ok = FALSE;
  tests++; if (ok) correct++;
  U8 *q = MemCpy(MAlloc(16), p, 16);
  ok = q != NULL && q != p && MSize(q) == 16;
  for (i = 0; ok && i < 16; i++) if (q[i] != 7) ok = FALSE;
  tests++; if (ok) correct++;
  Free(p);
  Free(q);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
