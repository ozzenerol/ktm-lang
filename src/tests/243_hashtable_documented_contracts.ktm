#include "testhelper.ktm"

/* What the library docs and std.ktmh now promise about IntMap and StrMap:
 * the capacity rule (slots, a power of two, grows at 60% full, never
 * shrinks, not even on Clear), insertion-order iteration with deletes
 * allowed during it and a re-set key coming last, an update keeping the
 * stored key and freeing the key passed, and StrMapToString's format. */

I64 g_key_frees = 0;

U0 FreeKey(U0 *p)
{
  g_key_frees++;
  Free(p);
}

U8 *Str(U0 *v)
{
  return StrPrint(NULL, "%d", v);
}

I32 Main()
{
  "Test - hash table documented contracts: ";
  I64 tests = 0;
  I64 correct = 0;

  /* Capacity: rounded up, grows when 60% of the slots are used */
  IntMap *im = IntMapNew(1000);
  for (I64 i = 0; i < 614; i++) IntMapSet(im, i, i(U0 *));
  I64 before = im->capacity;
  IntMapSet(im, 614, NULL);
  tests++;
  if (before == 1024 && im->capacity == 2048) correct++;
  else "Failed - growth at 60%%: %d then %d\n", before, im->capacity;

  /* Never shrinks */
  for (I64 i = 0; i <= 614; i++) IntMapDelete(im, i);
  I64 after_delete = im->capacity;
  IntMapClear(im);
  tests++;
  if (after_delete == 2048 && im->capacity == 2048 && im->size == 0) correct++;
  else "Failed - shrank: %d %d\n", after_delete, im->capacity;
  IntMapRelease(im);

  /* Deleting every entry while iterating visits each once */
  StrMap *m = StrMapNew();
  U8 *names[5] = {"a", "b", "c", "d", "e"};
  for (I64 i = 0; i < 5; i++) StrMapSet(m, names[i], i(U0 *));
  I64 idx = 0, visited = 0;
  StrMapNode *node;
  while (StrMapIter(m, &idx, &node)) {
    if (node->value(I64) != visited) visited = -100;
    visited++;
    StrMapDelete(m, node->key);
  }
  tests++;
  if (visited == 5 && m->size == 0) correct++;
  else "Failed - delete while iterating: %d\n", visited;

  /* A deleted key set again comes last */
  for (I64 i = 0; i < 5; i++) StrMapSet(m, names[i], i(U0 *));
  StrMapDelete(m, "b");
  StrMapSet(m, "b", 9(U0 *));
  U8 *keys = StrMapKeysToString(m);
  U8 *all = StrMapToString(m, &Str);
  tests++;
  if (!StrCmp(keys, "{a, c, d, e, b}") &&
      !StrCmp(all, "{[a] => 0, [c] => 2, [d] => 3, [e] => 4, [b] => 9}"))
    correct++;
  else "Failed - order: %s %s\n", keys, all;
  Free(keys);
  Free(all);
  StrMapRelease(m);

  /* An update keeps the stored key and frees the one passed */
  m = StrMapNew();
  StrMapSetFreeKey(m, &FreeKey);
  U8 *first = StrNew("k");
  StrMapSet(m, first, 1(U0 *));
  StrMapSet(m, StrNew("k"), 2(U0 *));
  U8 *stored;
  idx = 0;
  StrMapKeyIter(m, &idx, &stored);
  tests++;
  if (g_key_frees == 1 && stored == first && StrMapGet(m, "k")(I64) == 2)
    correct++;
  else "Failed - update\n";
  StrMapRelease(m);
  tests++;
  if (g_key_frees == 2) correct++;
  else "Failed - release\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
