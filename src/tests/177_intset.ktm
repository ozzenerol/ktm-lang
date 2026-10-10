#include "testhelper.ktm"

/* IntSet stored nothing: IntSetAdd is `return RBTreeInsert(...);` in a U0
 * function and the compiler dropped the call (fixed in the compiler, test
 * 185). This checks IntSet end to end. Deleting is covered by test 186. */

I64 g_correct = 0;
U0 Check(Bool ok, U8 *what)
{
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

IntSet *SetOf(I64 *keys, I64 n)
{
  IntSet *s = IntSetNew;
  for (I64 i = 0; i < n; i++) IntSetAdd(s, keys[i]);
  return s;
}

/* The set's keys, in iteration order, as "a,b,c" */
U8 *Keys(IntSet *s)
{
  U8 *buf = StrNew("");
  IntSetIter *it = IntSetIterNew(s);
  IntSetEntry *e;
  Bool first = TRUE;
  while ((e = IntSetIterNext(it))) {
    if (!first) buf = CatPrint(buf, ",");
    buf = CatPrint(buf, "%d", e->key);
    first = FALSE;
  }
  IntSetIterRelease(it);
  return buf;
}

Bool KeysAre(IntSet *s, U8 *want)
{
  U8 *got = Keys(s);
  Bool ok = !StrCmp(got, want);
  if (!ok) "\n  keys '%s', want '%s'\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  I64 tests = 28, i;
  I64 a_keys[6] = {5, -3, 9, 0, 5, I64_MIN};
  I64 b_keys[4] = {9, 1, -3, I64_MAX};
  IntSet *empty = IntSetNew, *a, *b, *u, *d, *sd, *in, *big;
  U8 *str;
  "Test - IntSet: ";

  Check(IntSetSize(empty) == 0 && !IntSetHas(empty, 0), "empty set");
  Check(KeysAre(empty, ""), "empty iteration");

  a = SetOf(a_keys, 6);            /* 5 twice */
  b = SetOf(b_keys, 4);
  Check(IntSetSize(a) == 5, "size ignores duplicates");
  Check(IntSetHas(a, 5) && IntSetHas(a, -3) && IntSetHas(a, 0) && IntSetHas(a, I64_MIN), "has");
  Check(!IntSetHas(a, 1) && !IntSetHas(a, -5) && !IntSetHas(a, I64_MAX), "has not");
  Check(KeysAre(a, "-9223372036854775808,-3,0,5,9"), "sorted iteration");

  u = IntSetUnion(a, b);
  Check(IntSetSize(u) == 7, "union size");
  Check(KeysAre(u, "-9223372036854775808,-3,0,1,5,9,9223372036854775807"), "union");
  d = IntSetDiff(a, b);
  Check(KeysAre(d, "-9223372036854775808,0,5"), "a - b");
  IntSetRelease(d);
  d = IntSetDiff(b, a);
  Check(KeysAre(d, "1,9223372036854775807"), "b - a");
  sd = IntSetSymDiff(a, b);
  Check(KeysAre(sd, "-9223372036854775808,0,1,5,9223372036854775807"), "symmetric difference");
  in = IntSetIntersection(a, b);
  Check(KeysAre(in, "-3,9"), "intersection");
  Check(IntSetSize(IntSetUnion(a, empty)) == 5 && IntSetSize(IntSetDiff(a, empty)) == 5 &&
        IntSetSize(IntSetIntersection(a, empty)) == 0 && IntSetSize(IntSetDiff(empty, a)) == 0,
        "with the empty set");

  Check(IntSetEq(a, a) && !IntSetEq(a, b), "equality");
  IntSet *a2 = SetOf(a_keys, 6);
  Check(IntSetEq(a, a2), "equal sets built separately");
  IntSetAdd(a2, 42);
  Check(!IntSetEq(a, a2), "one extra key");

  IntVec *v = IntSetToVec(in);
  Check(v->size == 2 && v->entries[0] == -3 && v->entries[1] == 9, "IntSetToVec");

  str = IntSetToString(in);
  Check(!StrCmp(str, "{-3, 9}"), "IntSetToString");
  if (StrCmp(str, "{-3, 9}")) "  got '%s'\n", str;
  Free(str);
  str = IntSetToString(empty);
  Check(!StrCmp(str, "{}"), "IntSetToString of an empty set");
  if (StrCmp(str, "{}")) "  got '%s'\n", str;
  Free(str);
  IntSet *one = IntSetNew;
  IntSetAdd(one, 7);
  str = IntSetToString(one);
  Check(!StrCmp(str, "{7}"), "IntSetToString of one key");
  Free(str);

  /* a big set, keys added in a scrambled order, half negative */
  big = IntSetNew;
  for (i = 0; i < 2000; i++) IntSetAdd(big, (i * 7919) % 2000 - 1000);
  for (i = 0; i < 2000; i++) IntSetAdd(big, (i * 31) % 2000 - 1000); /* again */
  Check(IntSetSize(big) == 2000, "big set size");
  Bool ok = TRUE;
  for (i = -1000; i < 1000; i++) if (!IntSetHas(big, i)) ok = FALSE;
  Check(ok && !IntSetHas(big, 1000) && !IntSetHas(big, -1001), "big set has");
  IntSetIter *it = IntSetIterNew(big);
  IntSetEntry *e;
  I64 count = 0, prev = -1001;
  ok = TRUE;
  while ((e = IntSetIterNext(it))) {
    if (e->key != prev + 1) ok = FALSE;
    prev = e->key;
    count++;
  }
  IntSetIterRelease(it);
  Check(ok && count == 2000, "big set iterates in order");
  IntSet *evens = IntSetNew;
  for (i = -1000; i < 1000; i += 2) IntSetAdd(evens, i);
  IntSet *odds = IntSetDiff(big, evens);
  Check(IntSetSize(odds) == 1000 && IntSetHas(odds, -999) && !IntSetHas(odds, 0), "big difference");
  Check(IntSetSize(IntSetIntersection(odds, evens)) == 0, "disjoint intersection");
  Check(IntSetEq(IntSetUnion(odds, evens), big), "union restores");
  Check(IntSetSize(IntSetSymDiff(big, evens)) == 1000, "big symmetric difference");

  IntSetRelease(a);
  IntSetRelease(b);
  IntSetRelease(u);
  IntSetRelease(big);
  Check(TRUE, "release");

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
