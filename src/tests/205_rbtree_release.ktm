#include "testhelper.ktm"

/* RBTreeRelease freed the nodes but not the tree, and never called the
 * key/value destructors. RBTreeDelete already called them; updating an
 * existing key now also frees the duplicate key and the replaced value,
 * like StrMapAdd. */

I64 g_key_frees, g_key_sum, g_val_frees, g_val_sum;

U0 ResetCounts()
{
  g_key_frees = 0; g_key_sum = 0;
  g_val_frees = 0; g_val_sum = 0;
}

U0 CountKey(U0 *key)   { g_key_frees++; g_key_sum += key(I64); }
U0 CountValue(U0 *val) { g_val_frees++; g_val_sum += val(I64); }

I64 IntCmp(U0 *a, U0 *b)
{
  if (a(I64) < b(I64)) return -1;
  if (a(I64) > b(I64)) return 1;
  return 0;
}

/* Boxed keys: the tree owns them, the destructor frees them */
I64 BoxCmp(U0 *a, U0 *b) { return IntCmp((*a(I64 *))(U0 *), (*b(I64 *))(U0 *)); }
U0 FreeBox(U0 *key)
{
  g_key_frees++;
  g_key_sum += *key(I64 *);
  Free(key);
}
I64 *Box(I64 v)
{
  I64 *p = MAlloc(sizeof(I64));
  *p = v;
  return p;
}

Bool ReleaseCallsDestructors()
{
  ResetCounts();
  RBTree *t = RBTreeNew(&CountKey, &CountValue, &IntCmp);
  I64 key_sum = 0, val_sum = 0;
  for (I64 i = 1; i <= 100; ++i) {
    RBTreeInsert(t, i(U0 *), (i * 10)(U0 *));
    key_sum += i;
    val_sum += i * 10;
  }
  RBTreeRelease(t);
  return g_key_frees == 100 && g_val_frees == 100 &&
         g_key_sum == key_sum && g_val_sum == val_sum;
}

Bool DeleteThenRelease()
{// Every entry is freed exactly once, by RBTreeDelete or RBTreeRelease
  ResetCounts();
  RBTree *t = RBTreeNew(&CountKey, &CountValue, &IntCmp);
  I64 key_sum = 0, val_sum = 0;
  for (I64 i = 1; i <= 64; ++i) {
    RBTreeInsert(t, i(U0 *), (i * 3)(U0 *));
    key_sum += i;
    val_sum += i * 3;
  }
  for (I64 i = 2; i <= 64; i += 3) {
    RBTreeDelete(t, i(U0 *));
  }
  RBTreeDelete(t, 1000(U0 *)); /* missing: frees nothing */
  Bool ok = g_key_frees == 21 && g_val_frees == 21 && t->size == 43;
  RBTreeRelease(t);
  return ok && g_key_frees == 64 && g_val_frees == 64 &&
         g_key_sum == key_sum && g_val_sum == val_sum;
}

Bool UpdateFreesOldValue()
{
  ResetCounts();
  RBTree *t = RBTreeNew(&FreeBox, &CountValue, &BoxCmp);
  RBTreeInsert(t, Box(7), 70(U0 *));
  RBTreeInsert(t, Box(7), 71(U0 *));       /* duplicate key + old value freed */
  Bool ok = g_key_frees == 1 && g_key_sum == 7 &&
            g_val_frees == 1 && g_val_sum == 70;
  I64 seven = 7;
  ok = ok && RBTreeGet(t, &seven)(I64) == 71;
  /* Setting the same value again frees nothing but the duplicate key */
  RBTreeInsert(t, Box(7), 71(U0 *));
  ok = ok && g_key_frees == 2 && g_val_frees == 1;
  RBTreeInsert(t, Box(8), 80(U0 *));
  RBTreeRelease(t);
  return ok && g_key_frees == 4 && g_key_sum == 7 + 7 + 7 + 8 &&
         g_val_frees == 3 && g_val_sum == 70 + 71 + 80;
}

Bool NullDestructors()
{
  ResetCounts();
  RBTree *t = RBTreeNew(NULL, NULL, &IntCmp);
  for (I64 i = 1; i <= 50; ++i) {
    RBTreeInsert(t, i(U0 *), i(U0 *));
  }
  RBTreeInsert(t, 5(U0 *), 500(U0 *));
  RBTreeDelete(t, 10(U0 *));
  RBTreeRelease(t);
  return g_key_frees == 0 && g_val_frees == 0;
}

Bool EmptyTree()
{
  ResetCounts();
  RBTree *t = RBTreeNew(&CountKey, &CountValue, &IntCmp);
  RBTreeRelease(t);
  RBTreeRelease(NULL);
  return g_key_frees == 0 && g_val_frees == 0;
}

Bool IntSetStillReleases()
{
  IntSet *s = IntSetNew();
  for (I64 i = 0; i < 20; ++i) IntSetAdd(s, i);
  IntSetDelete(s, 3);
  Bool ok = IntSetHas(s, 4) && !IntSetHas(s, 3);
  IntSetRelease(s);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - RBTreeRelease frees entries and tree: ";
  tests++; if (ReleaseCallsDestructors()) correct++;
  else "release did not call the destructors for every entry\n";
  tests++; if (DeleteThenRelease()) correct++;
  else "delete + release did not free every entry exactly once\n";
  tests++; if (UpdateFreesOldValue()) correct++;
  else "updating a key did not free the duplicate key / old value\n";
  tests++; if (NullDestructors()) correct++;
  tests++; if (EmptyTree()) correct++;
  tests++; if (IntSetStillReleases()) correct++;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
