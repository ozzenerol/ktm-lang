#include "testhelper.ktm"

/* RBTreeGet, RBTreeHas, RBTreeInsert, RBTreeDelete, RBTreeForEach and
 * RBIterNew dereferenced a NULL tree; only RBTreeRelease checked. Reads
 * now give NULL/FALSE, writes do nothing and RBIterNew(NULL) gives an
 * iterator that yields nothing. */

I64 g_calls;

U0 CountCall(U0 *user_data, U0 *key, U0 *value) { g_calls++; }

I64 IntCmp(U0 *a, U0 *b)
{
  if (a(I64) < b(I64)) return -1;
  if (a(I64) > b(I64)) return 1;
  return 0;
}

Bool NullReads()
{
  return RBTreeGet(NULL, 1(U0 *)) == NULL &&
         RBTreeGet(NULL, NULL) == NULL &&
         RBTreeHas(NULL, 1(U0 *)) == FALSE &&
         RBTreeHas(NULL, NULL) == FALSE;
}

Bool NullWrites()
{
  RBTreeInsert(NULL, 1(U0 *), 10(U0 *));
  RBTreeInsert(NULL, NULL, NULL);
  RBTreeDelete(NULL, 1(U0 *));
  RBTreeDelete(NULL, NULL);
  RBTreeRelease(NULL);
  return TRUE;
}

Bool NullForEach()
{
  g_calls = 0;
  RBTreeForEach(NULL, NULL, &CountCall);
  return g_calls == 0;
}

Bool NullIter()
{
  RBIter *it = RBIterNew(NULL);
  if (!it) return FALSE;
  I64 n = 0;
  while (RBIterNext(it)) n++;
  Bool ok = n == 0 && RBIterNext(it) == NULL;
  RBIterRelease(it);
  RBIterRelease(NULL);
  return ok && RBIterNext(NULL) == NULL;
}

Bool RealTreeUnchanged()
{
  RBTree *t = RBTreeNew(NULL, NULL, &IntCmp);
  for (I64 i = 1; i <= 30; ++i) RBTreeInsert(t, i(U0 *), (i * 2)(U0 *));
  RBTreeDelete(t, 7(U0 *));
  Bool ok = t->size == 29 && RBTreeGet(t, 8(U0 *))(I64) == 16 &&
            RBTreeHas(t, 30(U0 *)) && !RBTreeHas(t, 7(U0 *));
  g_calls = 0;
  RBTreeForEach(t, NULL, &CountCall);
  ok = ok && g_calls == 29;
  RBIter *it = RBIterNew(t);
  RBNode *node;
  I64 n = 0, prev = 0;
  while ((node = RBIterNext(it))) {
    if (node->key(I64) <= prev) ok = FALSE;
    prev = node->key(I64);
    n++;
  }
  RBIterRelease(it);
  RBTreeRelease(t);
  return ok && n == 29;
}

Bool EmptyTreeIter()
{
  RBTree *t = RBTreeNew(NULL, NULL, &IntCmp);
  RBIter *it = RBIterNew(t);
  Bool ok = RBIterNext(it) == NULL && RBTreeGet(t, 1(U0 *)) == NULL;
  RBIterRelease(it);
  RBTreeRelease(t);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - RBTree functions accept a NULL tree: ";
  tests++; if (NullReads()) correct++;
  else "RBTreeGet/RBTreeHas on NULL\n";
  tests++; if (NullWrites()) correct++;
  tests++; if (NullForEach()) correct++;
  else "RBTreeForEach(NULL) called the callback\n";
  tests++; if (NullIter()) correct++;
  else "RBIterNew(NULL) did not give an empty iterator\n";
  tests++; if (RealTreeUnchanged()) correct++;
  else "a real tree misbehaved\n";
  tests++; if (EmptyTreeIter()) correct++;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
