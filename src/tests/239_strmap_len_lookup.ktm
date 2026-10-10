#include "testhelper.ktm"

/* StrMapGetLen/StrMapHasLen compared `key_len` bytes but hashed the whole
 * NUL-terminated string, so a key given as the start of a longer buffer
 * (`("hello world", 5)`) was never found. StrMap now hashes exactly the
 * bytes it compares, with its own length-bounded hash and a final mix (the
 * old hash put similar keys like "key%d" in long runs of slots). Also
 * checks the empty key, keys with high bytes, and that iteration is still
 * in insertion order across resizes and deletes. */

I32 Main()
{
  "Test - StrMap length-bounded lookup: ";
  I64 tests = 0;
  I64 correct = 0;
  StrMap *m = StrMapNew();
  StrMapSet(m, "hello", 1(U0 *));
  StrMapSet(m, "key", 2(U0 *));
  StrMapSet(m, "", 3(U0 *));
  StrMapSet(m, "caf\xc3\xa9", 4(U0 *));

  tests++;
  if (StrMapHasLen(m, "hello world", 5) &&
      StrMapGetLen(m, "hello world", 5)(I64) == 1 &&
      StrMapGetLen(m, "key=value", 3)(I64) == 2 &&
      StrMapHasLen(m, "key=value", 3))
    correct++;
  else "Failed - prefix of a longer buffer\n";

  tests++;
  if (!StrMapHasLen(m, "hello world", 4) && !StrMapHasLen(m, "hello world", 6) &&
      StrMapGetLen(m, "keys", 4) == NULL && !StrMapHas(m, "hell"))
    correct++;
  else "Failed - near misses\n";

  /* The empty key: by length 0 from any buffer, and only once */
  StrMapSet(m, "", 5(U0 *));
  tests++;
  if (StrMapHas(m, "") && StrMapGet(m, "")(I64) == 5 &&
      StrMapGetLen(m, "xyz", 0)(I64) == 5 && m->size == 4)
    correct++;
  else "Failed - empty key\n";

  tests++;
  if (StrMapGetLen(m, "caf\xc3\xa9!", 5)(I64) == 4 &&
      StrMapGet(m, "caf\xc3\xa9")(I64) == 4)
    correct++;
  else "Failed - high bytes\n";
  StrMapRelease(m);

  /* Every key is found by Get, Has, GetLen and HasLen alike, also when it
   * is the start of a longer buffer; the map grows from 8 slots. */
  m = StrMapNew(8);
  StrMapSetFreeKey(m, &Free);
  I64 n = 20000, bad = 0;
  U8 buf[64];
  for (I64 i = 0; i < n; i++) {
    StrMapSet(m, StrPrint(NULL, "key%d", i), i(U0 *));
  }
  for (I64 i = 0; i < n; i++) {
    StrPrint(buf, "key%d-and-more", i);
    I64 len = StrLen(buf) - 9;
    if (StrMapGetLen(m, buf, len)(I64) != i || !StrMapHasLen(m, buf, len)) bad++;
    buf[len] = 0;
    if (StrMapGet(m, buf)(I64) != i || !StrMapHas(m, buf)) bad++;
  }
  tests++;
  if (bad == 0 && m->size == n) correct++;
  else "Failed - %d lookups wrong\n", bad;

  /* "key%d" keys spread out: no long runs of occupied slots */
  I64 run = 0, maxrun = 0;
  for (I64 i = 0; i < m->capacity; i++) {
    if (m->entries[i]) { run++; if (run > maxrun) maxrun = run; } else run = 0;
  }
  tests++;
  if (maxrun < 100) correct++;
  else "Failed - longest run of occupied slots %d\n", maxrun;

  /* Insertion order, after deleting every third key and growing again */
  for (I64 i = 0; i < n; i += 3) {
    StrPrint(buf, "key%d", i);
    StrMapDelete(m, buf);
  }
  for (I64 i = n; i < n + 5000; i++) {
    StrMapSet(m, StrPrint(NULL, "key%d", i), i(U0 *));
  }
  I64 prev = -1;
  bad = 0;
  StrMapNode *node;
  for (I64 it = 0; StrMapIter(m, &it, &node);) {
    I64 v = node->value(I64);
    if (v <= prev || (v < n && v % 3 == 0)) bad++;
    prev = v;
  }
  tests++;
  if (bad == 0) correct++;
  else "Failed - iteration order (%d)\n", bad;
  StrMapRelease(m);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
