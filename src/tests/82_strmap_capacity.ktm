#include "testhelper.ktm"

/* StrMapNew and IntMapNew rounded the capacity up to a power of two but
 * built the mask, the threshold and the entries array from the unrounded
 * value. Any other capacity - StrMapNew(100), StrMapNew(0) - gave a mask
 * whose probe sequence can't reach every slot, so inserting a few hundred
 * keys hung forever (and Release read past the entries array). Each map is
 * checked for a consistent mask before anything is inserted, so a
 * regression fails here instead of hanging. */

#define N_KEYS 10000

Bool IsPow2(U64 v)
{
  return v != 0 && (v & (v - 1)) == 0;
}

Bool StrMapShapeOk(StrMap *m)
{
  return IsPow2(m->capacity) && m->mask == m->capacity - 1 &&
         m->threashold > 0 && m->threashold < m->capacity;
}

Bool IntMapShapeOk(IntMap *m)
{
  return IsPow2(m->capacity) && m->mask == m->capacity - 1 &&
         m->threashold > 0 && m->threashold < m->capacity;
}

I64 CheckStrMap(StrMap *m, U8 **keys, U64 start_capacity)
{
  I64 correct = 0, i, bad;

  if (!StrMapShapeOk(m)) {
    /* Inserting into this map would loop forever; fail its checks. */
    return 0;
  }
  correct++;

  for (i = 0; i < N_KEYS; i++) {
    StrMapSet(m, keys[i], i(U0 *));
  }

  bad = 0;
  for (i = 0; i < N_KEYS; i++) {
    if (!StrMapHas(m, keys[i]) || StrMapGet(m, keys[i])(I64) != i) bad++;
  }
  if (bad == 0) correct++;

  if (m->size == N_KEYS) correct++;

  I64 idx = 0, count = 0;
  StrMapNode *node;
  bad = 0;
  while (StrMapIter(m, &idx, &node) && count <= N_KEYS) {
    if (node->key != keys[count] || node->value(I64) != count) bad++;
    count++;
  }
  if (count == N_KEYS && bad == 0) correct++;

  if (!StrMapHas(m, "missing") && StrMapGet(m, "key-") == NULL &&
      !StrMapHas(m, "key-10000")) correct++;

  /* It grew, and the grown table is still consistent. */
  if (m->capacity > start_capacity && StrMapShapeOk(m)) correct++;

  /* Replace every value with the same key pointer. */
  for (i = 0; i < N_KEYS; i++) {
    StrMapSet(m, keys[i], (i * 2)(U0 *));
  }
  bad = 0;
  for (i = 0; i < N_KEYS; i++) {
    if (StrMapGet(m, keys[i])(I64) != i * 2) bad++;
  }
  if (bad == 0 && m->size == N_KEYS) correct++;

  return correct;
}

I64 CheckIntMap(IntMap *m, U64 start_capacity)
{
  I64 correct = 0, i, bad;

  if (!IntMapShapeOk(m)) {
    return 0;
  }
  correct++;

  for (i = 0; i < N_KEYS; i++) {
    IntMapSet(m, i * 7, (i + 1)(U0 *));
  }

  bad = 0;
  for (i = 0; i < N_KEYS; i++) {
    if (!IntMapHas(m, i * 7) || IntMapGet(m, i * 7)(I64) != i + 1) bad++;
  }
  if (bad == 0) correct++;

  if (m->size == N_KEYS) correct++;

  I64 idx = 0, count = 0;
  IntMapNode *node;
  bad = 0;
  while (IntMapIter(m, &idx, &node) && count <= N_KEYS) {
    if (node->key != count * 7 || node->value(I64) != count + 1) bad++;
    count++;
  }
  if (count == N_KEYS && bad == 0) correct++;

  if (!IntMapHas(m, 3) && !IntMapHas(m, N_KEYS * 7) && !IntMapHas(m, -7)) correct++;

  if (m->capacity > start_capacity && IntMapShapeOk(m)) correct++;

  return correct;
}

I32 Main()
{
  I64 correct = 0, tests = 0, i;
  U64 caps[7];
  caps[0] = 0;
  caps[1] = 1;
  caps[2] = 3;
  caps[3] = 100;
  caps[4] = 128;
  caps[5] = 1000;
  caps[6] = 1025;

  "Test - StrMapNew/IntMapNew with any capacity: ";

  U8 **keys = MAlloc(N_KEYS * sizeof(U8 *));
  for (i = 0; i < N_KEYS; i++) {
    keys[i] = StrPrint(NULL, "key-%d", i);
  }

  for (i = 0; i < 7; i++) {
    StrMap *sm = StrMapNew(caps[i]);
    correct += CheckStrMap(sm, keys, sm->capacity);
    tests += 7;
    /* Release walks `capacity` slots, which overran a mis-sized table. */
    if (StrMapShapeOk(sm)) StrMapRelease(sm);

    IntMap *im = IntMapNew(caps[i]);
    correct += CheckIntMap(im, im->capacity);
    tests += 6;
    if (IntMapShapeOk(im)) IntMapRelease(im);
  }

  /* Capacities are rounded up, never down. */
  StrMap *s100 = StrMapNew(100);
  IntMap *i1000 = IntMapNew(1000);
  if (s100->capacity == 128 && i1000->capacity == 1024 &&
      StrMapShapeOk(s100) && IntMapShapeOk(i1000)) {
    correct++;
    StrMapRelease(s100);
    IntMapRelease(i1000);
  }
  tests++;

  /* The default matches the one declared in std.ktmh. */
  StrMap *sd = StrMapNew();
  IntMap *id = IntMapNew();
  if (sd->capacity == 32 && sd->mask == 31 && StrMapShapeOk(sd)) correct++;
  if (id->capacity == 32 && id->mask == 31 && IntMapShapeOk(id)) correct++;
  tests += 2;

  /* A map that owns its keys frees them on release. */
  StrMapSetFreeKey(sd, &Free);
  for (i = 0; i < 500; i++) {
    StrMapSet(sd, StrPrint(NULL, "owned-%d", i), i(U0 *));
  }
  if (sd->size == 500 && StrMapGet(sd, "owned-499")(I64) == 499) correct++;
  tests++;
  StrMapRelease(sd);
  IntMapRelease(id);

  for (i = 0; i < N_KEYS; i++) {
    Free(keys[i]);
  }
  Free(keys);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
