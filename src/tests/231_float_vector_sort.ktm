#include "testhelper.ktm"

/* FloatVecSort did nothing: its comparator took two F64 parameters but
 * QSortGeneric passes the entries as U0 * in integer registers, so it
 * compared whatever was in the float registers. */

I64 g_seed = 12345;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

Bool IsSorted(FloatVec *v)
{
  for (I64 i = 1; i < v->size; ++i) {
    if (v->entries[i - 1] > v->entries[i]) return FALSE;
  }
  return TRUE;
}

/* Sort `n` values made by `kind` and check order and contents */
Bool SortCase(I64 n, I64 kind)
{
  FloatVec *v = FloatVecNew();
  F64 sum = 0.0;
  for (I64 i = 0; i < n; ++i) {
    F64 x;
    switch (kind) {
      case 0: x = (NextRand() % 2001 - 1000)(F64) / 8.0; break; /* +/-, dups */
      case 1: x = (n - i)(F64) * -0.25; break;                  /* negative, sorted desc */
      case 2: x = 1.5; break;                                   /* all equal */
      case 3: x = (i % 7)(F64) - 3.0 + (i % 3)(F64) / 4.0; break;
    }
    sum += x;
    FloatVecPush(v, x);
  }
  FloatVecSort(v);
  F64 after = 0.0;
  for (I64 i = 0; i < v->size; ++i) after += v->entries[i];
  Bool ok = v->size == n && IsSorted(v) && after == sum;
  FloatVecRelease(v);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - FloatVecSort: ";

  FloatVec *v = FloatVecNew();
  FloatVecPush(v, 3.5);
  FloatVecPush(v, -1.25);
  FloatVecPush(v, 2.0);
  FloatVecPush(v, -7.0);
  FloatVecPush(v, 0.0);
  FloatVecPush(v, 2.0);
  FloatVecSort(v);
  F64 want[6] = {-7.0, -1.25, 0.0, 2.0, 2.0, 3.5};
  Bool ok = v->size == 6;
  for (I64 i = 0; i < 6 && ok; ++i) if (v->entries[i] != want[i]) ok = FALSE;
  tests++; if (ok) correct++;
  else {
    for (I64 i = 0; i < v->size; ++i) "%.2f ", v->entries[i];
    "\n";
  }
  FloatVecRelease(v);

  /* empty and single-element vectors */
  FloatVec *e = FloatVecNew();
  FloatVecSort(e);
  FloatVecPush(e, 4.0);
  FloatVecSort(e);
  tests++; if (e->size == 1 && e->entries[0] == 4.0) correct++;
  FloatVecRelease(e);

  ok = TRUE;
  I64 sizes[4] = {2, 17, 500, 5000};
  for (I64 k = 0; k < 4; ++k) {
    for (I64 s = 0; s < 4; ++s) {
      if (!SortCase(sizes[s], k)) { ok = FALSE; "kind %d n %d unsorted\n", k, sizes[s]; }
    }
  }
  tests++; if (ok) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
