#include "testhelper.ktm"

/* IntVecSort/PtrVecSort used a last-element-pivot Lomuto quicksort that
 * recursed on both sides: O(n^2) on sorted, reversed and all-equal input,
 * and a stack overflow around 1e5 elements. It now uses a median-of-three
 * pivot, a three-way partition and recurses only into the smaller part.
 * A watchdog alarm turns a pathological slowdown into a failure. */

extern "c" U32 alarm(U32 seconds);

I64 g_seed = 42;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

I64 MakeValue(I64 kind, I64 i, I64 n)
{
  switch (kind) {
    case 0: return i;                         /* sorted */
    case 1: return n - i;                     /* reversed */
    case 2: return 7;                         /* all equal */
    case 3: return NextRand() % 1000000;      /* random */
    case 4: return NextRand() % 4;            /* few distinct */
    case 5: if (i < n / 2) return i; return n - i; /* organ pipe */
  }
  return 0;
}

U8 *g_kinds[6] = {"sorted", "reversed", "equal", "random", "few", "pipe"};

Bool SortInts(I64 kind, I64 n)
{
  IntVec *v = IntVecNew();
  I64 sum = 0, xsum = 0;
  for (I64 i = 0; i < n; ++i) {
    I64 x = MakeValue(kind, i, n);
    sum += x;
    xsum += x * x;
    IntVecPush(v, x);
  }
  IntVecSort(v);
  Bool ok = v->size == n;
  I64 sum2 = 0, xsum2 = 0;
  for (I64 i = 0; i < v->size; ++i) {
    if (i > 0 && v->entries[i - 1] > v->entries[i]) ok = FALSE;
    sum2 += v->entries[i];
    xsum2 += v->entries[i] * v->entries[i];
  }
  ok = ok && sum == sum2 && xsum == xsum2;
  if (!ok) "IntVecSort %s n=%d wrong\n", g_kinds[kind], n;
  IntVecRelease(v);
  return ok;
}

class Item
{
  I64 key;
  I64 id;
};

I64 ItemLess(Item *a, Item *b) { return a->key < b->key; }
I64 ItemLessEq(Item *a, Item *b) { return a->key <= b->key; }
I64 ItemCStyle(Item *a, Item *b) { return a->key - b->key; }

Bool SortItems(I64 n, I64 (*cmp)(U0 *a, U0 *b), Bool check_order)
{
  PtrVec *v = PtrVecNew();
  Item *items = MAlloc(sizeof(Item) * n);
  I64 idsum = 0;
  for (I64 i = 0; i < n; ++i) {
    items[i].key = NextRand() % 100;
    items[i].id = i;
    idsum += i;
    PtrVecPush(v, &items[i]);
  }
  PtrVecSort(v, cmp);
  Bool ok = v->size == n;
  I64 idsum2 = 0;
  for (I64 i = 0; i < v->size; ++i) {
    Item *it = v->entries[i];
    idsum2 += it->id;
    if (check_order && i > 0) {
      Item *prev = v->entries[i - 1];
      if (prev->key > it->key) ok = FALSE;
    }
  }
  ok = ok && idsum == idsum2;
  PtrVecRelease(v);
  Free(items);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - vector sort worst case: ";
  alarm(300);

  /* small sizes around the insertion-sort cutoff */
  Bool ok = TRUE;
  for (I64 n = 0; n < 70; ++n) {
    for (I64 k = 0; k < 6; ++k) if (!SortInts(k, n)) ok = FALSE;
  }
  tests++; if (ok) correct++;

  ok = TRUE;
  for (I64 k = 0; k < 6; ++k) if (!SortInts(k, 100000)) ok = FALSE;
  tests++; if (ok) correct++;

  ok = TRUE;
  for (I64 k = 0; k < 6; ++k) if (!SortInts(k, 1000000)) ok = FALSE;
  tests++; if (ok) correct++;

  tests++; if (SortItems(200000, &ItemLess, TRUE)) correct++;
  else "PtrVecSort with duplicates wrong\n";

  /* Other comparator styles must still terminate and keep every item;
   * `<=` still sorts, a C-style -1/0/1 comparator does not (documented). */
  tests++; if (SortItems(50000, &ItemLessEq, TRUE)) correct++;
  else "`<=` comparator wrong\n";
  tests++; if (SortItems(50000, &ItemCStyle, FALSE)) correct++;
  else "C-style comparator lost items\n";

  alarm(0);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
