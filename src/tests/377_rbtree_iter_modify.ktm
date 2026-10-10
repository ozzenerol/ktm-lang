#include "testhelper.ktm"

/* RBIterNew, IntSetIterNew and RBTreeForEach size their stack from the
 * tree's size when they start, so adding keys during the walk wrote past
 * it, and a delete left freed nodes on it. The tree now counts its
 * changes: once a new key is added or one is deleted, the iterator stops
 * (Next returns NULL, RBTreeForEach returns after that callback).
 * Replacing the value of a key that is there doesn't stop it. */

I64 Cmp(U0 *a, U0 *b)
{
  if (a(I64) < b(I64)) return -1;
  if (a(I64) > b(I64)) return 1;
  return 0;
}

I64 StrKeyCmp(U0 *a, U0 *b)
{
  return StrCmp(a, b);
}

RBTree *Tree(I64 n)
{
  RBTree *t = RBTreeNew(NULL, NULL, &Cmp);
  for (I64 i = 1; i <= n; i++) RBTreeInsert(t, (i * 10)(U0 *), i(U0 *));
  return t;
}

/* every key of t, in order, and the count is `n` */
Bool Sorted(RBTree *t, I64 n)
{
  RBIter *it = RBIterNew(t);
  RBNode *node;
  I64 count = 0, prev = I64_MIN;
  Bool ok = TRUE;
  while ((node = RBIterNext(it))) {
    if (node->key(I64) <= prev) ok = FALSE;
    prev = node->key(I64);
    count++;
  }
  RBIterRelease(it);
  return ok && count == n && t->size == n;
}

I64 g_calls;

U0 InsertMany(U0 *tree, U0 *key, U0 *value)
{
  g_calls++;
  for (I64 i = 0; i < 500; i++) RBTreeInsert(tree, (1000 + i)(U0 *), NULL);
}

U0 DeleteAll(U0 *tree, U0 *key, U0 *value)
{
  g_calls++;
  for (I64 i = 1; i <= 8; i++) RBTreeDelete(tree, (i * 10)(U0 *));
}

U0 ReplaceValue(U0 *tree, U0 *key, U0 *value)
{
  g_calls++;
  RBTreeInsert(tree, key, (value(I64) + 100)(U0 *));
}

I32 Main()
{
  "Test - RBTree and IntSet iterators after a change: ";
  I64 correct = 0, tests = 0, n, i;
  RBTree *t;
  RBIter *it;
  RBNode *node;
  IntSet *set;
  IntSetIter *sit;
  IntSetEntry *e;

  /* RBIter: inserting far more keys than the tree had stops the walk */
  t = Tree(3);
  it = RBIterNew(t);
  n = 0;
  while ((node = RBIterNext(it))) {
    n++;
    for (i = 0; i < 500; i++) RBTreeInsert(t, (1000 + i)(U0 *), NULL);
  }
  tests++; if (n == 1) correct++;
  tests++; if (RBIterNext(it) == NULL) correct++;
  RBIterRelease(it);
  tests++; if (Sorted(t, 503)) correct++;
  RBTreeRelease(t);

  /* RBIter: deleting every other node (stacked ones included) stops it */
  t = Tree(8);
  it = RBIterNew(t);
  n = 0;
  while ((node = RBIterNext(it))) {
    n++;
    for (i = 1; i <= 8; i++) {
      if (i != 4) RBTreeDelete(t, (i * 10)(U0 *));
    }
  }
  tests++; if (n == 1) correct++;
  RBIterRelease(it);
  tests++; if (Sorted(t, 1) && RBTreeHas(t, 40(U0 *))) correct++;
  RBTreeRelease(t);

  /* RBIter: deleting the current key, whose key the tree frees */
  t = RBTreeNew(&Free, NULL, &StrKeyCmp);
  RBTreeInsert(t, StrNew("a"), NULL);
  RBTreeInsert(t, StrNew("b"), NULL);
  RBTreeInsert(t, StrNew("c"), NULL);
  it = RBIterNew(t);
  n = 0;
  while ((node = RBIterNext(it))) {
    n++;
    RBTreeDelete(t, node->key);
  }
  tests++; if (n == 1 && t->size == 2) correct++;
  RBIterRelease(it);
  RBTreeRelease(t);

  /* RBIter: replacing values doesn't stop it */
  t = Tree(20);
  it = RBIterNew(t);
  n = 0;
  while ((node = RBIterNext(it))) {
    n++;
    RBTreeInsert(t, node->key, (node->value(I64) * 2)(U0 *));
  }
  tests++; if (n == 20 && RBTreeGet(t, 70(U0 *))(I64) == 14) correct++;
  RBIterRelease(it);
  RBTreeRelease(t);

  /* RBTreeForEach stops after the callback that changed the tree */
  t = Tree(3);
  g_calls = 0;
  RBTreeForEach(t, t, &InsertMany);
  tests++; if (g_calls == 1 && Sorted(t, 503)) correct++;
  RBTreeRelease(t);

  t = Tree(8);
  g_calls = 0;
  RBTreeForEach(t, t, &DeleteAll);
  tests++; if (g_calls == 1 && t->size == 0) correct++;
  RBTreeRelease(t);

  t = Tree(8);
  g_calls = 0;
  RBTreeForEach(t, t, &ReplaceValue);
  tests++; if (g_calls == 8 && RBTreeGet(t, 30(U0 *))(I64) == 103) correct++;
  RBTreeRelease(t);

  /* IntSet: adding many keys while iterating stops the iterator */
  set = IntSetNew();
  for (i = 1; i <= 3; i++) IntSetAdd(set, i);
  sit = IntSetIterNew(set);
  n = 0;
  while ((e = IntSetIterNext(sit))) {
    n++;
    for (i = 100; i < 600; i++) IntSetAdd(set, i);
  }
  tests++; if (n == 1 && IntSetSize(set) == 503) correct++;
  IntSetIterRelease(sit);

  /* adding a key that is there changes nothing, so it doesn't stop it */
  sit = IntSetIterNew(set);
  n = 0;
  while ((e = IntSetIterNext(sit))) {
    n++;
    IntSetAdd(set, e->key);
  }
  tests++; if (n == 503) correct++;
  IntSetIterRelease(sit);

  /* IntSet: deleting the current key stops it too */
  sit = IntSetIterNew(set);
  n = 0;
  while ((e = IntSetIterNext(sit))) {
    n++;
    IntSetDelete(set, e->key);
  }
  tests++; if (n == 1 && IntSetSize(set) == 502 && !IntSetHas(set, 1)) correct++;
  IntSetIterRelease(sit);
  IntSetRelease(set);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
