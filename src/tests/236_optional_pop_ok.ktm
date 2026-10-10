#include "testhelper.ktm"

/* IntVecPop/FloatVecPop/PtrVecPop wrote through `_ok` unconditionally, so
 * IntVecPop(v, NULL) crashed and the flag had to be passed. It now
 * defaults to NULL and is only written when given. The empty-vector
 * sentinels stay: -1, -1.0 and NULL. */

I32 Main()
{
  I64 correct = 0, tests = 0;
  Bool ok;
  "Test - optional pop ok flag: ";

  IntVec *iv = IntVecNew();
  IntVecPush(iv, 10);
  IntVecPush(iv, 20);
  tests++;
  if (IntVecPop(iv) == 20 && IntVecPop(iv, NULL) == 10 && IntVecPop(iv) == -1 &&
      iv->size == 0) correct++;
  ok = TRUE;
  tests++; if (IntVecPop(iv, &ok) == -1 && !ok) correct++;
  IntVecPush(iv, 5);
  ok = FALSE;
  tests++; if (IntVecPop(iv, &ok) == 5 && ok) correct++;
  IntVecRelease(iv);

  FloatVec *fv = FloatVecNew();
  FloatVecPush(fv, 2.5);
  tests++;
  if (FloatVecPop(fv) == 2.5 && FloatVecPop(fv) == -1.0 && fv->size == 0) correct++;
  ok = TRUE;
  tests++; if (FloatVecPop(fv, &ok) == -1.0 && !ok) correct++;
  FloatVecRelease(fv);

  PtrVec *pv = PtrVecNew();
  PtrVecPush(pv, "x");
  PtrVecPush(pv, NULL);
  ok = FALSE;
  /* a stored NULL is told apart from empty only through `_ok` */
  tests++; if (PtrVecPop(pv, &ok) == NULL && ok) correct++;
  tests++; if (!StrCmp(PtrVecPop(pv), "x") && PtrVecPop(pv) == NULL) correct++;
  ok = TRUE;
  tests++; if (PtrVecPop(pv, &ok) == NULL && !ok && pv->size == 0) correct++;
  PtrVecRelease(pv);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
