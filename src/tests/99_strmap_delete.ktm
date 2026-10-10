#include "testhelper.ktm"

/* StrMapDelete wrote HT_DELETED into the insertion-order vector at the
 * *slot* index (a heap overflow once the slot was past the vector's end),
 * returned TRUE for keys that were not there, and inserting after a delete
 * could reuse a deleted slot while the key still existed further along the
 * probe chain (duplicate keys). Deleted slots were never cleared, so enough
 * delete/insert cycles left no empty slot and a lookup for a missing key
 * never terminated. IntMapDelete left the node visible to iteration and its
 * freed value was freed again by IntMapRelease. Every loop here is bounded so
 * a regression fails instead of hanging. */

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

U8 *IntStr(U0 *v)
{
  return StrPrint(NULL, "%d", v(I64));
}

I64 KeyNum(U8 *key)
{/* "key-123" -> 123 */
  return Atoi(key + 4);
}

I32 Main()
{
  I64 correct = 0, tests = 0, i, bad, n, idx;
  StrMapNode *node;
  IntMapNode *inode;
  U8 buf[64];

  "Test - StrMapDelete / IntMapDelete: ";

  /* Basic semantics on a small map. */
  StrMap *s = StrMapNew();
  StrMapSet(s, "a", 1(U0 *));
  StrMapSet(s, "b", 2(U0 *));
  StrMapSet(s, "c", 3(U0 *));
  StrMapSet(s, "", 4(U0 *));
  tests++;
  if (StrMapDelete(s, "b") && !StrMapDelete(s, "b") &&
      !StrMapDelete(s, "zz") && s->size == 3) correct++;
  tests++;
  if (!StrMapHas(s, "b") && StrMapGet(s, "b") == NULL &&
      StrMapGet(s, "a")(I64) == 1 && StrMapGet(s, "c")(I64) == 3) correct++;
  /* The empty key must not match a deleted slot. */
  tests++;
  if (StrMapDelete(s, "") && !StrMapHas(s, "") && !StrMapDelete(s, "") &&
      s->size == 2) correct++;
  U8 *keys = StrMapKeysToString(s);
  tests++;
  if (!StrCmp(keys, "{a, c}")) correct++;
  Free(keys);
  keys = StrMapToString(s, &IntStr);
  tests++;
  if (!StrCmp(keys, "{[a] => 1, [c] => 3}")) correct++;
  Free(keys);
  StrMapSet(s, "b", 5(U0 *));
  keys = StrMapKeysToString(s);
  tests++;
  if (!StrCmp(keys, "{a, c, b}") && StrMapGet(s, "b")(I64) == 5 &&
      s->size == 3) correct++;
  Free(keys);
  StrMapRelease(s);

  /* 10,000 owned keys and values; delete the even half. */
  StrMap *big = StrMapNew();
  StrMapSetFreeKey(big, &CountingFreeKey);
  StrMapSetFreeValue(big, &CountingFreeValue);
  for (i = 0; i < 10000; i++) {
    StrMapSet(big, Fmt("key-%d", i), Box(i));
  }
  bad = 0;
  for (i = 0; i < 10000; i += 2) {
    StrPrint(buf, "key-%d", i);
    if (!StrMapDelete(big, buf)) bad++;
  }
  tests++;
  if (bad == 0 && big->size == 5000 && g_key_frees == 5000 &&
      g_value_frees == 5000) correct++;

  /* Deleting again or deleting keys that never existed is FALSE. */
  bad = 0;
  for (i = 0; i < 10000; i += 2) {
    StrPrint(buf, "key-%d", i);
    if (StrMapDelete(big, buf)) bad++;
    StrPrint(buf, "missing-%d", i);
    if (StrMapDelete(big, buf) || StrMapHas(big, buf)) bad++;
  }
  tests++;
  if (bad == 0 && big->size == 5000 && g_key_frees == 5000) correct++;

  bad = 0;
  for (i = 0; i < 10000; i++) {
    StrPrint(buf, "key-%d", i);
    I64 *v = StrMapGet(big, buf);
    if (i & 1) {
      if (!v || *v != i) bad++;
    } else if (v != NULL || StrMapHas(big, buf)) {
      bad++;
    }
  }
  tests++;
  if (bad == 0) correct++;

  /* Iteration skips the deleted keys and keeps insertion order. */
  idx = 0;
  n = 0;
  bad = 0;
  while (StrMapIter(big, &idx, &node) && n <= 10000) {
    if (KeyNum(node->key) != n * 2 + 1) bad++;
    n++;
  }
  tests++;
  if (bad == 0 && n == 5000) correct++;

  /* Updating surviving keys must not create duplicates. */
  for (i = 1; i < 10000; i += 2) {
    StrMapSet(big, Fmt("key-%d", i), Box(i + 100000));
  }
  idx = 0;
  n = 0;
  while (StrMapIter(big, &idx, &node) && n <= 20000) {
    n++;
  }
  tests++;
  if (big->size == 5000 && n == 5000 && g_key_frees == 10000 &&
      g_value_frees == 10000) correct++;

  /* Re-insert the deleted half: they come after the survivors. */
  for (i = 0; i < 10000; i += 2) {
    StrMapSet(big, Fmt("key-%d", i), Box(i));
  }
  idx = 0;
  n = 0;
  bad = 0;
  while (StrMapIter(big, &idx, &node) && n <= 20000) {
    I64 want;
    if (n < 5000) want = n * 2 + 1;
    else want = (n - 5000) * 2;
    if (KeyNum(node->key) != want) bad++;
    n++;
  }
  tests++;
  if (bad == 0 && n == 10000 && big->size == 10000) correct++;

  bad = 0;
  for (i = 0; i < 10000; i++) {
    StrPrint(buf, "key-%d", i);
    I64 *v = StrMapGet(big, buf);
    if (!v) bad++;
    else if ((i & 1) && *v != i + 100000) bad++;
    else if (!(i & 1) && *v != i) bad++;
  }
  tests++;
  if (bad == 0) correct++;

  /* Release frees every remaining key and value exactly once:
   * 20,000 keys and 20,000 values were handed to the map in total. */
  StrMapRelease(big);
  tests++;
  if (g_key_frees == 20000 && g_value_frees == 20000) correct++;

  /* Many delete/insert cycles on a small map: tombstones are recycled, the
   * table does not grow, and missing-key lookups still terminate. */
  g_key_frees = 0;
  g_value_frees = 0;
  StrMap *cyc = StrMapNew(8);
  StrMapSetFreeKey(cyc, &CountingFreeKey);
  StrMapSetFreeValue(cyc, &CountingFreeValue);
  StrMapSet(cyc, Dup("keep"), Box(-1));
  bad = 0;
  for (i = 0; i < 20000; i++) {
    StrMapSet(cyc, Fmt("key-%d", i), Box(i));
    StrPrint(buf, "key-%d", i);
    if (!StrMapHas(cyc, buf)) bad++;
    if (!StrMapDelete(cyc, buf)) bad++;
    if (StrMapHas(cyc, buf)) bad++;
    StrPrint(buf, "never-%d", i);
    if (StrMapHas(cyc, buf)) bad++;
  }
  tests++;
  if (bad == 0 && cyc->size == 1 && cyc->capacity <= 16 &&
      g_key_frees == 20000 && g_value_frees == 20000) correct++;
  idx = 0;
  n = 0;
  while (StrMapIter(cyc, &idx, &node) && n < 10) {
    if (StrCmp(node->key, "keep")) bad++;
    n++;
  }
  tests++;
  if (bad == 0 && n == 1 && *(StrMapGet(cyc, "keep")(I64 *)) == -1) correct++;
  StrMapRelease(cyc);
  tests++;
  if (g_key_frees == 20001 && g_value_frees == 20001) correct++;

  /* IntMap: 10,000 keys, delete half, re-insert, iterate, release. */
  g_value_frees = 0;
  IntMap *im = IntMapNew();
  IntMapSetFreeValue(im, &CountingFreeValue);
  for (i = 0; i < 10000; i++) {
    IntMapSet(im, i * 7 - 3000, Box(i));
  }
  bad = 0;
  for (i = 0; i < 10000; i += 2) {
    if (!IntMapDelete(im, i * 7 - 3000)) bad++;
    if (IntMapDelete(im, i * 7 - 3000)) bad++;
    if (IntMapDelete(im, i * 7 - 2999)) bad++;
  }
  tests++;
  if (bad == 0 && im->size == 5000 && g_value_frees == 5000) correct++;

  idx = 0;
  n = 0;
  bad = 0;
  while (IntMapIter(im, &idx, &inode) && n <= 10000) {
    if (inode->key != (n * 2 + 1) * 7 - 3000 || *(inode->value(I64 *)) != n * 2 + 1)
      bad++;
    n++;
  }
  tests++;
  if (bad == 0 && n == 5000) correct++;

  for (i = 0; i < 10000; i += 2) {
    IntMapSet(im, i * 7 - 3000, Box(i));
  }
  for (i = 1; i < 10000; i += 2) {
    IntMapSet(im, i * 7 - 3000, Box(i));
  }
  idx = 0;
  n = 0;
  bad = 0;
  while (IntMapIter(im, &idx, &inode) && n <= 20000) {
    n++;
  }
  for (i = 0; i < 10000; i++) {
    I64 *v = IntMapGet(im, i * 7 - 3000);
    if (!v || *v != i || IntMapHas(im, i * 7 - 2999)) bad++;
  }
  tests++;
  if (bad == 0 && n == 10000 && im->size == 10000 && g_value_frees == 10000)
    correct++;
  IntMapRelease(im);
  tests++;
  if (g_value_frees == 20000) correct++;

  /* IntMap collisions: keys 1, 33 and 65 share a probe chain in a 32-slot
   * map. Setting 65 after deleting 1 updates 65 instead of duplicating it. */
  IntMap *col = IntMapNew(32);
  IntMapSet(col, 1, 10(U0 *));
  IntMapSet(col, 33, 20(U0 *));
  IntMapSet(col, 65, 30(U0 *));
  IntMapDelete(col, 1);
  IntMapSet(col, 65, 31(U0 *));
  IntMapSet(col, 0x7fffffffffffffff, 40(U0 *));
  idx = 0;
  n = 0;
  while (IntMapIter(col, &idx, &inode) && n < 10) {
    n++;
  }
  keys = IntMapKeysToString(col);
  tests++;
  if (n == 3 && col->size == 3 && IntMapGet(col, 65)(I64) == 31 &&
      !IntMapHas(col, 1) && IntMapGet(col, 0x7fffffffffffffff)(I64) == 40 &&
      !StrCmp(keys, "{33, 65, 9223372036854775807}")) correct++;
  Free(keys);
  IntMapSet(col, 1, 11(U0 *));
  tests++;
  if (col->size == 4 && IntMapGet(col, 1)(I64) == 11 &&
      IntMapDelete(col, 0x7fffffffffffffff) &&
      !IntMapHas(col, 0x7fffffffffffffff)) correct++;

  /* IntMap delete/insert cycles terminate and do not grow the table. */
  bad = 0;
  for (i = 0; i < 20000; i++) {
    IntMapSet(col, 1000 + i, NULL);
    if (!IntMapDelete(col, 1000 + i)) bad++;
    if (IntMapHas(col, 1000 + i) || IntMapHas(col, -1 - i)) bad++;
  }
  tests++;
  if (bad == 0 && col->size == 3 && col->capacity <= 64) correct++;
  IntMapRelease(col);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
