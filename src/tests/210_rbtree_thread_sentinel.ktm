#include "testhelper.ktm"

/* All red-black trees shared one global sentinel node: RBTreeNew reset
 * it and RBTransplant / the delete fix-up wrote its parent, so trees used
 * from different threads corrupted each other. Each tree now has its own
 * sentinel. */

#define RBT_THREADS 6
#define RBT_KEYS    300
#define RBT_OPS     200000

extern "c" U32 alarm(U32 seconds);

I64 RbtCmp(U0 *a, U0 *b)
{
  if (a(I64) < b(I64)) return -1;
  if (a(I64) > b(I64)) return 1;
  return 0;
}

/* A tree's sentinel is the only node whose `left` is NULL */
Bool IsNil(RBNode *n) { return n->left == NULL; }

/* Black height, or -1 if an invariant is broken */
I64 Check(RBNode *n, RBNode *parent, I64 lo, I64 hi, I64 *count)
{
  if (IsNil(n)) return 1;
  I64 k = n->key(I64);
  if (k <= lo || k >= hi) return -1;
  if (n->parent != parent) return -1;
  if (n->color == 0 && (n->left->color == 0 || n->right->color == 0)) return -1;
  I64 l = Check(n->left, n, lo, k, count);
  I64 r = Check(n->right, n, k, hi, count);
  if (l < 0 || r < 0 || l != r) return -1;
  ++*count;
  return l + (n->color == 1);
}

Bool Valid(RBTree *t)
{
  I64 count = 0;
  if (IsNil(t->root)) return t->size == 0;
  if (t->root->color != 1) return FALSE;
  if (Check(t->root, NULL, I64_MIN, I64_MAX, &count) < 0) return FALSE;
  return count == t->size;
}

class RbtArgs
{
  pthread_t *th;
  I64 seed;
  Bool ok;
};

/* Random inserts and deletes against a shadow array, checking the tree
 * every so often. Every few hundred operations the tree is released and
 * a new one made, so RBTreeNew runs while other threads use their trees. */
U0 *RbtWorker(RbtArgs *args)
{
  Bool shadow[RBT_KEYS];
  I64 i, k, n = 0, seed = args->seed;
  RBTree *t = RBTreeNew(NULL, NULL, &RbtCmp);
  MemSet(shadow, 0, sizeof(shadow));
  args->ok = TRUE;

  for (i = 0; i < RBT_OPS && args->ok; i++) {
    seed = seed * 6364136223846793005 + 1442695040888963407;
    k = ((seed >> 33) & 0x7FFFFFFF) % RBT_KEYS + 1;
    if ((seed >> 20) & 1) {
      RBTreeInsert(t, k(U0 *), (k * 2)(U0 *));
      if (!shadow[k - 1]) n++;
      shadow[k - 1] = TRUE;
    } else {
      RBTreeDelete(t, k(U0 *));
      if (shadow[k - 1]) n--;
      shadow[k - 1] = FALSE;
    }
    if (RBTreeHas(t, k(U0 *)) != shadow[k - 1] || t->size != n) args->ok = FALSE;
    if (i % 97 == 0 && !Valid(t)) args->ok = FALSE;
    if (i % 1500 == 1499) {
      RBTreeRelease(t);
      t = RBTreeNew(NULL, NULL, &RbtCmp);
      MemSet(shadow, 0, sizeof(shadow));
      n = 0;
    }
  }
  if (!Valid(t)) args->ok = FALSE;
  for (k = 1; k <= RBT_KEYS; k++) {
    if (shadow[k - 1] && RBTreeGet(t, k(U0 *))(I64) != k * 2) args->ok = FALSE;
  }
  RBTreeRelease(t);
  return NULL;
}

I32 Main()
{
  "Test - RBTree sentinel per tree: ";
  I64 correct = 0, tests = 5, i, k, n;
  RbtArgs args[RBT_THREADS];

  alarm(120); /* a corrupted tree can loop forever: fail instead of hanging */

  /* Several trees on several threads at once */
  for (i = 0; i < RBT_THREADS; i++) {
    args[i].seed = i * 7919 + 1;
    args[i].ok = FALSE;
    pthread_create(&args[i].th,, &RbtWorker, &args[i]);
  }
  for (i = 0; i < RBT_THREADS; i++) pthread_join(args[i].th);
  Bool ok = TRUE;
  for (i = 0; i < RBT_THREADS; i++) if (!args[i].ok) ok = FALSE;
  if (ok) correct++;

  /* Single thread, two trees interleaved: deleting from one doesn't
   * disturb the other */
  RBTree *a = RBTreeNew(NULL, NULL, &RbtCmp);
  RBTree *b = RBTreeNew(NULL, NULL, &RbtCmp);
  for (k = 1; k <= 200; k++) {
    RBTreeInsert(a, k(U0 *), k(U0 *));
    RBTreeInsert(b, (k * 3)(U0 *), k(U0 *));
    if (k % 3 == 0) {
      RBTreeDelete(a, (k / 3)(U0 *));
      RBTreeDelete(b, k(U0 *));
    }
  }
  if (Valid(a) && Valid(b) && a->size == 134 && b->size == 134) correct++;
  /* A new tree made while others hold nodes is empty and independent */
  RBTree *c = RBTreeNew(NULL, NULL, &RbtCmp);
  if (c->size == 0 && IsNil(c->root) && Valid(a) && Valid(b)) correct++;
  RBTreeRelease(a);
  RBTreeRelease(b);
  RBTreeRelease(c);

  /* IntSet unchanged */
  IntSet *s = IntSetNew();
  for (k = 10; k > 0; k--) IntSetAdd(s, k);
  IntSetDelete(s, 4);
  IntSetDelete(s, 99);
  IntSetIter *it = IntSetIterNew(s);
  IntSetEntry *e;
  n = 0;
  ok = TRUE;
  I64 prev = 0;
  while ((e = IntSetIterNext(it))) {
    if (e->key <= prev || e->key == 4) ok = FALSE;
    prev = e->key;
    n++;
  }
  IntSetIterRelease(it);
  if (ok && n == 9 && IntSetSize(s) == 9 && IntSetHas(s, 10) && !IntSetHas(s, 4)) correct++;
  U8 *str = IntSetToString(s);
  Free(str);
  IntSetRelease(s);

  /* RBIter walks in order */
  RBTree *t = RBTreeNew(NULL, NULL, &RbtCmp);
  for (k = 1; k <= 50; k++) RBTreeInsert(t, ((k * 37) % 101)(U0 *), NULL);
  RBIter *ri = RBIterNew(t);
  RBNode *node;
  n = 0; prev = -1; ok = TRUE;
  while ((node = RBIterNext(ri))) {
    if (node->key(I64) <= prev) ok = FALSE;
    prev = node->key(I64);
    n++;
  }
  RBIterRelease(ri);
  if (ok && n == 50) correct++;
  RBTreeRelease(t);

  alarm(0);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
