#include "testhelper.ktm"

/* IntVecNew(0)/FloatVecNew(0) never grew (capacity * 2 of 0) and every
 * push wrote past the block; PtrVecNew ignored its size and always made
 * room for 32. A size below 1 (0 or negative) now starts with room for
 * one entry, and growing from a tiny capacity goes to at least 8. */

Bool IntVecFrom(I64 size, I64 n)
{
  IntVec *v = IntVecNew(size);
  if (IntVecCapacity(v) < 1) return FALSE;
  for (I64 i = 0; i < n; ++i) IntVecPush(v, i * 3);
  Bool ok = v->size == n && IntVecCapacity(v) > n;
  for (I64 i = 0; i < n; ++i) if (IntVecGet(v, i) != i * 3) ok = FALSE;
  IntVecRelease(v);
  return ok;
}

Bool FloatVecFrom(I64 size, I64 n)
{
  FloatVec *v = FloatVecNew(size);
  for (I64 i = 0; i < n; ++i) FloatVecPush(v, i * 0.5);
  Bool ok = v->size == n;
  for (I64 i = 0; i < n; ++i) if (FloatVecGet(v, i) != i * 0.5) ok = FALSE;
  FloatVecRelease(v);
  return ok;
}

Bool PtrVecFrom(I64 size, I64 n)
{
  PtrVec *v = PtrVecNew(size);
  for (I64 i = 0; i < n; ++i) PtrVecPush(v, (i + 1)(U0 *));
  Bool ok = v->size == n;
  for (I64 i = 0; i < n; ++i) if (PtrVecGet(v, i)(I64) != i + 1) ok = FALSE;
  PtrVecRelease(v);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - vector zero capacity: ";

  I64 sizes[5] = {0, -1, -1000, 1, 2};
  Bool ok = TRUE;
  for (I64 i = 0; i < 5; ++i) {
    if (!IntVecFrom(sizes[i], 1000)) { ok = FALSE; "IntVec size %d\n", sizes[i]; }
  }
  tests++; if (ok) correct++;

  ok = TRUE;
  for (I64 i = 0; i < 5; ++i) {
    if (!FloatVecFrom(sizes[i], 1000)) { ok = FALSE; "FloatVec size %d\n", sizes[i]; }
  }
  tests++; if (ok) correct++;

  ok = TRUE;
  for (I64 i = 0; i < 5; ++i) {
    if (!PtrVecFrom(sizes[i], 1000)) { ok = FALSE; "PtrVec size %d\n", sizes[i]; }
  }
  tests++; if (ok) correct++;

  /* The requested size is the initial capacity, for all three */
  IntVec *iv = IntVecNew(100);
  FloatVec *fv = FloatVecNew(100);
  PtrVec *pv = PtrVecNew(100);
  PtrVec *pv0 = PtrVecNew(0);
  PtrVec *pvd = PtrVecNew();
  tests++;
  if (IntVecCapacity(iv) == 100 && FloatVecCapacity(fv) == 100 &&
      PtrVecCapacity(pv) == 100 && PtrVecCapacity(pv0) == 1 &&
      PtrVecCapacity(pvd) == 32) correct++;
  else "initial capacity: %d %d %d %d %d\n", IntVecCapacity(iv),
       FloatVecCapacity(fv), PtrVecCapacity(pv), PtrVecCapacity(pv0),
       PtrVecCapacity(pvd);
  IntVecRelease(iv);
  FloatVecRelease(fv);
  PtrVecRelease(pv);
  PtrVecRelease(pv0);
  PtrVecRelease(pvd);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
