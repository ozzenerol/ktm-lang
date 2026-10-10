#include "testhelper.ktm"

/* IntSetRelease(NULL) and IntSetSize(NULL) crashed. The IntSet functions
 * that only read a set now treat NULL as the empty set, and the release
 * functions ignore NULL, like the vector and map APIs. */

I32 Main()
{
  "Test - IntSet NULL safety: ";
  I64 correct = 0, tests = 0;
  IntSet *s = IntSetNew, *r;
  IntSetIter *it;
  IntVec *v;
  U8 *str;

  IntSetRelease(NULL);
  tests++; correct++;
  IntSetIterRelease(NULL);
  tests++; correct++;
  tests++; if (IntSetSize(NULL) == 0) correct++;
  tests++; if (!IntSetHas(NULL, 0)) correct++;
  tests++; if (IntSetIterNext(NULL) == NULL) correct++;

  it = IntSetIterNew(NULL);
  tests++; if (it != NULL && IntSetIterNext(it) == NULL) correct++;
  IntSetIterRelease(it);

  IntSetAdd(s, 3);
  IntSetAdd(s, -1);

  r = IntSetUnion(s, NULL);
  tests++; if (IntSetEq(r, s)) correct++;
  IntSetRelease(r);
  r = IntSetUnion(NULL, NULL);
  tests++; if (IntSetSize(r) == 0) correct++;
  IntSetRelease(r);
  r = IntSetDiff(s, NULL);
  tests++; if (IntSetEq(r, s)) correct++;
  IntSetRelease(r);
  r = IntSetDiff(NULL, s);
  tests++; if (IntSetSize(r) == 0) correct++;
  IntSetRelease(r);
  r = IntSetIntersection(s, NULL);
  tests++; if (IntSetSize(r) == 0) correct++;
  IntSetRelease(r);
  r = IntSetSymDiff(NULL, s);
  tests++; if (IntSetEq(r, s)) correct++;
  IntSetRelease(r);

  tests++; if (IntSetEq(NULL, NULL)) correct++;
  tests++; if (!IntSetEq(s, NULL) && !IntSetEq(NULL, s)) correct++;

  v = IntSetToVec(NULL);
  tests++; if (v != NULL && v->size == 0) correct++;
  IntVecRelease(v);

  str = IntSetToString(NULL);
  tests++; if (str != NULL && !StrCmp(str, "{}")) correct++;
  Free(str);

  /* Non-NULL sets are unaffected. */
  tests++; if (IntSetSize(s) == 2 && IntSetHas(s, 3) && !IntSetHas(s, 4)) correct++;
  IntSetRelease(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
