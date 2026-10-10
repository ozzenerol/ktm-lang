#include "testhelper.ktm"

/* IntMap hashed a key as `key & mask`, so keys sharing their low bits
 * (`i << 20`, multiples of 1024, aligned pointers) all went to the same
 * few slots and every insert and lookup walked one long probe chain:
 * 40000 keys `i << 20` took about 2 s. Keys are now mixed before masking.
 * Checks the clustering directly (deterministic) and a generous time
 * bound, plus edge keys and lookups of missing keys. */

I64 NowUs()
{
  timeval tv;
  gettimeofday(&tv, NULL);
  return tv.tv_sec * 1000000 + tv.tv_usec;
}

I64 g_errors = 0;

/* The longest run of occupied slots: every probe for a key in the run
 * walks it */
I64 MaxProbe(IntMap *m)
{
  I64 run = 0, worst = 0;
  for (I64 s = 0; s < m->capacity; s++) {
    if (m->entries[s]) {
      run++;
      if (run > worst) worst = run;
    } else {
      run = 0;
    }
  }
  return worst;
}

/* Insert, find and delete `n` keys `i * mul` (or `i << shift`) */
I64 Run(I64 n, I64 shift, I64 mul)
{
  IntMap *m = IntMapNew();
  for (I64 i = 0; i < n; i++) IntMapSet(m, (i * mul) << shift, i(U0 *));
  for (I64 i = 0; i < n; i++) {
    if (IntMapGet(m, (i * mul) << shift)(I64) != i) g_errors++;
    if (IntMapHas(m, ((i * mul) << shift) + 1) && mul * (1 << shift) > 1) g_errors++;
  }
  I64 worst = MaxProbe(m);
  for (I64 i = 0; i < n; i += 2) {
    if (!IntMapDelete(m, (i * mul) << shift)) g_errors++;
  }
  if (m->size != n / 2) g_errors++;
  IntMapRelease(m);
  return worst;
}

I32 Main()
{
  "Test - IntMap hash mixing: ";
  I64 tests = 0;
  I64 correct = 0;
  I64 t0 = NowUs();
  I64 w12 = Run(40000, 12, 1);
  I64 w20 = Run(40000, 20, 1);
  I64 w32 = Run(40000, 32, 1);
  I64 w1k = Run(40000, 0, 1024);
  I64 wneg = Run(40000, 16, -1);
  I64 wseq = Run(40000, 0, 1);
  I64 ms = (NowUs() - t0) / 1000;

  tests++;
  if (g_errors == 0) correct++;
  else "Failed - %d wrong results\n", g_errors;

  tests++;
  if (w12 < 100 && w20 < 100 && w32 < 100 && w1k < 100 && wneg < 100 &&
      wseq < 100)
    correct++;
  else "Failed - longest runs %d %d %d %d %d %d\n",
         w12, w20, w32, w1k, wneg, wseq;

  /* Generous: about 30 ms on a laptop, seconds before */
  tests++;
  if (ms < 1500) correct++;
  else "Failed - took %d ms\n", ms;

  /* Edge keys still work */
  IntMap *m = IntMapNew(8);
  I64 edge[6] = {0, -1, 1, I64_MIN, I64_MAX, 1 << 63 >> 1};
  for (I64 i = 0; i < 6; i++) IntMapSet(m, edge[i], (i + 1)(U0 *));
  I64 bad = 0;
  for (I64 i = 0; i < 6; i++) if (IntMapGet(m, edge[i])(I64) != i + 1) bad++;
  tests++;
  if (bad == 0 && m->size == 6) correct++;
  else "Failed - edge keys\n";
  IntMapRelease(m);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
