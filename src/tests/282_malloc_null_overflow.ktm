#include "testhelper.ktm"

/* MAlloc wrote its size header through malloc's result without checking
 * it for NULL, so a failed allocation crashed, and size + 8 wrapped for
 * sizes near U64_MAX, so MAlloc(-1) returned a 7-byte block claiming
 * MSize -1. MAlloc, CAlloc and ReAlloc now return NULL instead; a failed
 * ReAlloc leaves the old block alone, like C's realloc. */

I32 Main()
{
  "Test - MAlloc NULL and overflow: ";
  I64 correct = 0, tests = 0, i, ok;
  U8 *p, *q;

  /* Sizes no machine can satisfy: fail straight away, nothing is
   * actually allocated. */
  tests++; if (MAlloc(1 << 62) == NULL) correct++;
  tests++; if (MAlloc(I64_MAX) == NULL) correct++;
  /* size + 8 wraps around to a tiny request */
  tests++; if (MAlloc(-1) == NULL) correct++;
  tests++; if (MAlloc(-8) == NULL) correct++;
  /* size + 8 == U64_MAX: no wrap, malloc itself says no */
  tests++; if (MAlloc(-9) == NULL) correct++;
  tests++; if (CAlloc(-4) == NULL) correct++;
  tests++; if (CAlloc(1 << 62) == NULL) correct++;
  tests++; if (ReAlloc(NULL, -1) == NULL) correct++;

  /* A failed ReAlloc returns NULL and keeps the old block intact. */
  p = MAlloc(32);
  for (i = 0; i < 32; i++) p[i] = i;
  q = ReAlloc(p, -1);
  ok = q == NULL && MSize(p) == 32;
  for (i = 0; i < 32; i++) if (p[i] != i) ok = FALSE;
  tests++; if (ok) correct++;
  q = ReAlloc(p, 1 << 62);
  ok = q == NULL && MSize(p) == 32;
  for (i = 0; i < 32; i++) if (p[i] != i) ok = FALSE;
  tests++; if (ok) correct++;
  Free(p);

  /* Ordinary allocations still work. */
  p = MAlloc(24);
  tests++; if (p && MSize(p) == 24) correct++;
  Free(p);
  p = MAlloc(0);
  tests++; if (p && MSize(p) == 0) correct++;
  Free(p);
  p = CAlloc(100);
  ok = p && MSize(p) == 100;
  for (i = 0; i < 100; i++) if (p[i]) ok = FALSE;
  tests++; if (ok) correct++;
  p = ReAlloc(p, 300);
  ok = p && MSize(p) == 300;
  for (i = 0; i < 100; i++) if (p[i]) ok = FALSE;
  tests++; if (ok) correct++;
  Free(p);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
