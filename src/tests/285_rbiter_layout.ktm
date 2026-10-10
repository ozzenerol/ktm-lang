#include "testhelper.ktm"

/* std.ktmh declared RBIter with an extra `node` field that set.ktm's
 * RBIter doesn't have, so user code saw a 32-byte class while
 * RBIterNew allocated 24 bytes. */

I64 Cmp(U0 *a, U0 *b) { return a(I64) - b(I64); }

I32 Main()
{
  "Test - RBIter layout: ";
  I64 correct = 0, tests = 0, i, prev, n, ok;
  RBTree *t = RBTreeNew(NULL, NULL, &Cmp);
  RBNode *node;

  RBIter *it = RBIterNew(t);
  tests++; if (MSize(it) == sizeof(RBIter)) correct++;
  tests++; if (RBIterNext(it) == NULL) correct++;
  RBIterRelease(it);

  for (i = 0; i < 100; i++) RBTreeInsert(t, ((i * 37) % 101)(U0 *), i(U0 *));
  it = RBIterNew(t);
  tests++; if (MSize(it) == sizeof(RBIter)) correct++;
  ok = TRUE; n = 0; prev = -1;
  while ((node = RBIterNext(it)) != NULL) {
    if (node->key(I64) <= prev) ok = FALSE;
    prev = node->key(I64);
    n++;
  }
  tests++; if (ok && n == 100) correct++;
  /* the fields user code can see are the ones RBIterNew filled in */
  tests++; if (it->sp == 0 && it->stack != NULL) correct++;
  RBIterRelease(it);
  RBTreeRelease(t);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
