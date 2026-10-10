#include "testhelper.ktm"

/* IntMapNew/StrMapNew rounded the requested capacity up to a power of two
 * without a bound: above 2^63 (`IntMapNew(-1)`) it wrapped to 0, the mask
 * became all ones and the first Set wrote far outside the table. A
 * negative request now gets the minimum capacity (8) and a huge one is
 * clamped to 2^28 slots; the map still grows past that as needed. */

I32 Main()
{
  "Test - hash table capacity bounds: ";
  I64 tests = 0;
  I64 correct = 0;

  IntMap *im = IntMapNew(-1);
  IntMapSet(im, 12345, 1(U0 *));
  tests++;
  if (im->capacity == 8 && im->mask == 7 && IntMapGet(im, 12345)(I64) == 1)
    correct++;
  else "Failed - IntMapNew(-1): capacity %d\n", im->capacity;
  IntMapRelease(im);

  im = IntMapNew(0x8000000000000001);
  tests++;
  if (im->capacity == 8) correct++;
  else "Failed - IntMapNew(2^63 + 1): capacity %d\n", im->capacity;
  IntMapRelease(im);

  StrMap *sm = StrMapNew(-5);
  StrMapSet(sm, "a", 1(U0 *));
  tests++;
  if (sm->capacity == 8 && StrMapGet(sm, "a")(I64) == 1) correct++;
  else "Failed - StrMapNew(-5): capacity %d\n", sm->capacity;
  StrMapRelease(sm);

  /* Ordinary requests are rounded as before */
  im = IntMapNew(0);
  sm = StrMapNew(1000);
  tests++;
  if (im->capacity == 8 && sm->capacity == 1024 && sm->mask == 1023) correct++;
  else "Failed - rounding: %d %d\n", im->capacity, sm->capacity;
  IntMapRelease(im);
  StrMapRelease(sm);

  /* A huge request is clamped (or NULL when there isn't the memory for
   * the clamped table); the table is freed directly, without walking its
   * 2^28 slots */
  im = IntMapNew(1 << 62);
  tests++;
  if (!im || (im->capacity == 1 << 28 && im->mask == (1 << 28) - 1)) {
    correct++;
  } else {
    "Failed - IntMapNew(2^62): capacity %d\n", im->capacity;
  }
  if (im) {
    Free(im->entries);
    IntVecRelease(im->indexes);
    Free(im);
  }

  /* A small map still grows */
  im = IntMapNew(-1);
  for (I64 i = 0; i < 1000; i++) IntMapSet(im, i, i(U0 *));
  I64 bad = 0;
  for (I64 i = 0; i < 1000; i++) if (IntMapGet(im, i)(I64) != i) bad++;
  tests++;
  if (bad == 0 && im->capacity >= 1024) correct++;
  else "Failed - growth\n";
  IntMapRelease(im);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
