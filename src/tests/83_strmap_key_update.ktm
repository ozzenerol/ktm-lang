#include "testhelper.ktm"

/* StrMapSet stores the caller's key pointer (by design), but setting a key
 * that already existed replaced the stored pointer with the new one. Counting
 * words from a reused buffer then left the map's keys pointing at that
 * buffer, so every key turned into the last word read. Updates now keep the
 * stored key; with destructors registered the duplicate key and the replaced
 * value are freed exactly once. */

I64 g_key_frees = 0;
I64 g_value_frees = 0;

U0 CountingFreeKey(U0 *key)
{
  g_key_frees++;
  Free(key);
}

U0 CountingFreeValue(U0 *value)
{
  g_value_frees++;
  Free(value);
}

U8 *Dup(U8 *s)
{
  I64 len = StrLen(s);
  U8 *d = MAlloc(len + 1);
  MemCpy(d, s, len + 1);
  return d;
}

/* Allocate test strings in this file so -Memsafe tracks every one. */
U8 *Fmt(U8 *fmt, I64 i)
{
  U8 tmp[64];
  StrPrint(tmp, fmt, i);
  return Dup(tmp);
}

I64 *Box(I64 v)
{
  I64 *b = MAlloc(sizeof(I64));
  *b = v;
  return b;
}

I32 Main()
{
  I64 correct = 0, tests = 0, i, bad;

  "Test - StrMapSet keeps the stored key on update: ";

  U8 *words[8];
  words[0] = "the";
  words[1] = "cat";
  words[2] = "sat";
  words[3] = "on";
  words[4] = "the";
  words[5] = "mat";
  words[6] = "the";
  words[7] = "cat";

  /* Word count from a reused buffer: new words get a copy, updates pass
   * the buffer itself. */
  StrMap *counts = StrMapNew();
  U8 buf[32];
  for (i = 0; i < 8; i++) {
    StrCpy(buf, words[i]);
    if (StrMapHas(counts, buf)) {
      StrMapSet(counts, buf, (StrMapGet(counts, buf)(I64) + 1)(U0 *));
    } else {
      StrMapSet(counts, Dup(buf), 1(U0 *));
    }
  }
  StrCpy(buf, "zzzzzz");

  tests++;
  if (counts->size == 5) correct++;
  tests++;
  if (StrMapGet(counts, "the")(I64) == 3 && StrMapGet(counts, "cat")(I64) == 2 &&
      StrMapGet(counts, "sat")(I64) == 1 && StrMapGet(counts, "on")(I64) == 1 &&
      StrMapGet(counts, "mat")(I64) == 1) correct++;
  tests++;
  if (!StrMapHas(counts, "zzzzzz")) correct++;

  /* Every stored key is still its own copy, in insertion order. */
  U8 *order[5];
  order[0] = "the";
  order[1] = "cat";
  order[2] = "sat";
  order[3] = "on";
  order[4] = "mat";
  I64 idx = 0, n = 0;
  StrMapNode *node;
  bad = 0;
  while (StrMapIter(counts, &idx, &node) && n < 10) {
    if (n >= 5 || node->key == buf || StrCmp(node->key, order[n])) bad++;
    n++;
  }
  tests++;
  if (n == 5 && bad == 0) {
    correct++;
    /* Only free the keys if none of them is `buf`, which lives on the stack. */
    StrMapSetFreeKey(counts, &Free);
  }
  StrMapRelease(counts);

  /* Many updates through one buffer, across resizes. */
  StrMap *big = StrMapNew(8);
  for (i = 0; i < 2000; i++) {
    StrPrint(buf, "word-%d", i);
    StrMapSet(big, Dup(buf), 0(U0 *));
  }
  for (I64 round = 1; round <= 3; round++) {
    for (i = 0; i < 2000; i++) {
      StrPrint(buf, "word-%d", i);
      StrMapSet(big, buf, round(U0 *));
    }
  }
  StrCpy(buf, "gone");
  bad = 0;
  for (i = 0; i < 2000; i++) {
    U8 *k = Fmt("word-%d", i);
    if (StrMapGet(big, k)(I64) != 3) bad++;
    Free(k);
  }
  idx = 0;
  n = 0;
  while (StrMapIter(big, &idx, &node) && n <= 2000) {
    if (node->key == buf) bad++;
    n++;
  }
  tests++;
  if (bad == 0 && n == 2000 && big->size == 2000) {
    correct++;
    StrMapSetFreeKey(big, &Free);
  }
  StrMapRelease(big);

  /* The map owns keys and values: an update frees the duplicate key that
   * was passed and the value it replaces, never the stored key. */
  StrMap *own = StrMapNew();
  StrMapSetFreeKey(own, &CountingFreeKey);
  StrMapSetFreeValue(own, &CountingFreeValue);
  for (i = 0; i < 100; i++) {
    U8 *k = Fmt("k%d", i);
    StrMapSet(own, Dup(k), Box(i));
    Free(k);
  }
  tests++;
  if (g_key_frees == 0 && g_value_frees == 0 && own->size == 100) correct++;

  for (i = 0; i < 100; i++) {
    U8 *k = Fmt("k%d", i);
    StrMapSet(own, Dup(k), Box(i + 1000));
    Free(k);
  }
  tests++;
  if (g_key_frees == 100 && g_value_frees == 100 && own->size == 100) correct++;

  /* Setting the stored key and the stored value again frees nothing. */
  idx = 0;
  n = 0;
  while (StrMapIter(own, &idx, &node) && n < 200) {
    StrMapSet(own, node->key, node->value);
    n++;
  }
  tests++;
  if (n == 100 && g_key_frees == 100 && g_value_frees == 100) correct++;

  bad = 0;
  for (i = 0; i < 100; i++) {
    U8 *k = Fmt("k%d", i);
    I64 *v = StrMapGet(own, k);
    if (!v || *v != i + 1000) bad++;
    Free(k);
  }
  tests++;
  if (bad == 0) correct++;

  StrMapRelease(own);
  tests++;
  if (g_key_frees == 200 && g_value_frees == 200) correct++;

  /* IntMap: replacing a value frees the old one once. */
  g_value_frees = 0;
  IntMap *im = IntMapNew();
  IntMapSetFreeValue(im, &CountingFreeValue);
  for (i = 0; i < 300; i++) {
    IntMapSet(im, i, Box(i));
  }
  for (i = 0; i < 300; i++) {
    IntMapSet(im, i, Box(i * 2));
  }
  tests++;
  if (g_value_frees == 300 && im->size == 300) correct++;
  for (i = 0; i < 300; i++) {
    IntMapSet(im, i, IntMapGet(im, i));
  }
  bad = 0;
  for (i = 0; i < 300; i++) {
    I64 *v = IntMapGet(im, i);
    if (!v || *v != i * 2) bad++;
  }
  tests++;
  if (bad == 0 && g_value_frees == 300) correct++;
  IntMapRelease(im);
  tests++;
  if (g_value_frees == 600) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
