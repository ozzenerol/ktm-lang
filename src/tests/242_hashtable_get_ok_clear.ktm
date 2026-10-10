#include "testhelper.ktm"

/* Hash table API additions and fixes:
 *  - IntMapGet/StrMapGet/StrMapGetLen take an optional `Bool *_ok`, TRUE
 *    when the key is there, so a stored NULL or 0 can be told from a
 *    missing key (calls without it still work);
 *  - a pointer that is both the key and the value (`StrMapSet(m,s,s)`)
 *    with both destructors registered was freed twice; it is now freed
 *    once, on release, delete, clear and update;
 *  - IntMapClear/StrMapClear remove every entry (running the
 *    destructors) and keep the capacity;
 *  - a NULL map or key no longer crashes: Get/Has give NULL/FALSE,
 *    Set/Delete FALSE.
 * Every allocation is counted against the frees (valgrind-clean). */

I64 g_allocs = 0;
I64 g_key_frees = 0;
I64 g_value_frees = 0;

U8 *New(U8 *s)
{
  g_allocs++;
  return StrNew(s);
}

U0 FreeKey(U0 *p)
{
  g_key_frees++;
  Free(p);
}

U0 FreeValue(U0 *p)
{
  g_value_frees++;
  Free(p);
}

StrMap *CountingMap()
{
  StrMap *m = StrMapNew();
  StrMapSetFreeKey(m, &FreeKey);
  StrMapSetFreeValue(m, &FreeValue);
  return m;
}

Bool AllFreed()
{
  return g_key_frees + g_value_frees == g_allocs;
}

I32 Main()
{
  "Test - hash table _ok, Clear, shared keys and NULL: ";
  I64 tests = 0;
  I64 correct = 0;
  Bool ok;

  /* _ok */
  IntMap *im = IntMapNew();
  IntMapSet(im, 7, NULL);
  IntMapSet(im, 8, 0(U0 *));
  IntMapSet(im, 9, 99(U0 *));
  tests++;
  ok = TRUE;
  Bool ok7 = FALSE, ok8 = FALSE, ok9 = FALSE;
  if (IntMapGet(im, 1, &ok) == NULL && !ok &&
      IntMapGet(im, 7, &ok7) == NULL && ok7 &&
      IntMapGet(im, 8, &ok8) == NULL && ok8 &&
      IntMapGet(im, 9, &ok9)(I64) == 99 && ok9 &&
      IntMapGet(im, 9)(I64) == 99)
    correct++;
  else "Failed - IntMapGet _ok\n";

  StrMap *sm = StrMapNew();
  StrMapSet(sm, "null", NULL);
  StrMapSet(sm, "one", 1(U0 *));
  tests++;
  Bool okn = FALSE, ok1 = FALSE, okl = FALSE, okm = TRUE, okml = TRUE;
  if (StrMapGet(sm, "null", &okn) == NULL && okn &&
      StrMapGet(sm, "one", &ok1)(I64) == 1 && ok1 &&
      StrMapGet(sm, "two", &okm) == NULL && !okm &&
      StrMapGetLen(sm, "one", 3, &okl)(I64) == 1 && okl &&
      StrMapGetLen(sm, "one", 2, &okml) == NULL && !okml &&
      StrMapGet(sm, "one")(I64) == 1)
    correct++;
  else "Failed - StrMapGet _ok\n";

  /* Clear keeps the capacity, empties iteration, and the map is reusable */
  for (I64 i = 0; i < 100; i++) IntMapSet(im, i * 3, i(U0 *));
  I64 cap = im->capacity;
  IntMapClear(im);
  I64 it = 0;
  IntMapNode *inode;
  Bool iter_empty = !IntMapIter(im, &it, &inode);
  IntMapSet(im, 5, 6(U0 *));
  tests++;
  if (im->size == 1 && im->capacity == cap && iter_empty &&
      !IntMapHas(im, 9) && IntMapGet(im, 5)(I64) == 6)
    correct++;
  else "Failed - IntMapClear\n";
  IntMapRelease(im);
  StrMapRelease(sm);

  /* IntMapClear runs the value destructor, also after deletes */
  im = IntMapNew();
  IntMapSetFreeValue(im, &FreeValue);
  for (I64 i = 0; i < 50; i++) IntMapSet(im, i, New("v"));
  for (I64 i = 0; i < 50; i += 5) IntMapDelete(im, i);
  IntMapClear(im);
  tests++;
  if (g_value_frees == 50 && im->size == 0) correct++;
  else "Failed - IntMapClear destructors: %d\n", g_value_frees;
  IntMapRelease(im);

  /* StrMapClear with destructors, shared and separate pointers */
  sm = CountingMap();
  for (I64 i = 0; i < 40; i++) {
    U8 *k = StrPrint(NULL, "k%d", i);
    g_allocs++;
    if (i % 2) StrMapSet(sm, k, k);
    else       StrMapSet(sm, k, New("v"));
  }
  cap = sm->capacity;
  StrMapDelete(sm, "k1");
  StrMapDelete(sm, "k2");
  StrMapClear(sm);
  tests++;
  if (AllFreed() && sm->size == 0 && sm->capacity == cap && !StrMapHas(sm, "k3"))
    correct++;
  else "Failed - StrMapClear: allocs %d, freed %d + %d\n",
         g_allocs, g_key_frees, g_value_frees;
  StrMapSet(sm, New("again"), New("v"));
  StrMapRelease(sm);
  tests++;
  if (AllFreed()) correct++;
  else "Failed - release after clear\n";

  /* Shared key and value: release, delete, and every kind of update */
  sm = CountingMap();
  I64 freed_before = g_key_frees + g_value_frees;
  U8 *s = New("a");
  StrMapSet(sm, s, s);               /* insert shared */
  StrMapSet(sm, s, s);               /* same again: nothing freed */
  U8 *s2 = New("a");
  StrMapSet(sm, s2, s2);             /* shared over shared: s freed once */
  tests++;
  if (g_key_frees + g_value_frees == freed_before + 1 &&
      StrMapGet(sm, "a") == s2)
    correct++;
  else "Failed - shared over shared: %d %d %d\n", g_key_frees, g_value_frees,
         StrMapGet(sm, "a") == s2;
  StrMapSet(sm, New("a"), New("x")); /* separate over shared */
  U8 *s3 = New("a");
  StrMapSet(sm, s3, s3);             /* shared over separate */
  StrMapSet(sm, New("b"), New("y"));
  U8 *s4 = New("c");
  StrMapSet(sm, s4, s4);
  StrMapDelete(sm, "c");             /* delete shared */
  StrMapRelease(sm);
  tests++;
  if (AllFreed()) correct++;
  else "Failed - shared keys: allocs %d, freed %d + %d\n",
         g_allocs, g_key_frees, g_value_frees;

  /* NULL map and NULL key */
  ok = TRUE;
  Bool ok2 = TRUE, ok3 = TRUE;
  I64 cursor = 0;
  StrMapNode *snode;
  U8 *str;
  tests++;
  if (IntMapGet(NULL, 1, &ok) == NULL && !ok && !IntMapHas(NULL, 1) &&
      !IntMapSet(NULL, 1, NULL) && !IntMapDelete(NULL, 1) &&
      !IntMapIter(NULL, &cursor, &inode) && !IntMapResize(NULL) &&
      StrMapGet(NULL, "a", &ok2) == NULL && !ok2 && !StrMapHas(NULL, "a") &&
      !StrMapSet(NULL, "a", NULL) && !StrMapDelete(NULL, "a") &&
      !StrMapHasLen(NULL, "a", 1) && !StrMapIter(NULL, &cursor, &snode) &&
      !StrMapResize(NULL))
    correct++;
  else "Failed - NULL map\n";
  IntMapClear(NULL);
  StrMapClear(NULL);
  IntMapRelease(NULL);
  StrMapRelease(NULL);
  IntMapSetFreeValue(NULL, &FreeValue);
  StrMapSetFreeKey(NULL, &FreeKey);
  StrMapSetFreeValue(NULL, &FreeValue);
  str = IntMapKeysToString(NULL);
  Bool empty_str = !StrCmp(str, "{}");
  Free(str);

  sm = StrMapNew();
  StrMapSet(sm, "a", 1(U0 *));
  tests++;
  if (empty_str && StrMapGet(sm, NULL, &ok3) == NULL && !ok3 &&
      !StrMapHas(sm, NULL) && !StrMapSet(sm, NULL, 2(U0 *)) &&
      !StrMapDelete(sm, NULL) && StrMapGetLen(sm, NULL, 3) == NULL &&
      !StrMapHasLen(sm, NULL, 0) && sm->size == 1)
    correct++;
  else "Failed - NULL key\n";
  StrMapRelease(sm);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
