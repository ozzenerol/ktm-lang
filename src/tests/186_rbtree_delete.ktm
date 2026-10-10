#include "testhelper.ktm"

/* RBTreeDelete (and so IntSetDelete) crashed: its search loop read `x->key`
 * with x NULL and never stopped on a match. It also tested for NULL where
 * the tree uses a sentinel node, the mirrored fix-up case coloured a node
 * red instead of black, and free_key/free_value got the node, not the
 * key/value. */

I64 RbTestCmp(U0 *a, U0 *b)
{
  if (a(I64) < b(I64)) return -1;
  if (a(I64) > b(I64)) return 1;
  return 0;
}

/* The sentinel is the only node whose `left` is NULL */
Bool IsNil(RBNode *n) { return n->left == NULL; }

/* Returns the black height, or -1 if an invariant is broken: BST order,
 * no red node with a red child, same black height on every path, parent
 * links. */
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
  if (IsNil(t->root) && t->size == 0) return TRUE;
  if (t->root->color != 1) return FALSE;  /* black root */
  if (Check(t->root, NULL, I64_MIN, I64_MAX, &count) < 0) return FALSE;
  return count == t->size;
}

I64 g_freed = 0, g_freed_sum = 0;
U0 CountFree(U0 *key) { g_freed++; g_freed_sum += key(I64); }

I32 Main()
{
  I64 correct = 0, tests = 17, i, k;
  Bool ok;
  RBTree *t = RBTreeNew(NULL, NULL, &RbTestCmp);
  "Test - RBTreeDelete: ";

  for (i = 1; i <= 7; i++) RBTreeInsert(t, i(U0*), (i * 10)(U0*));
  if (Valid(t) && t->size == 7) correct++;

  RBTreeDelete(t, 100(U0*));                     /* absent */
  if (Valid(t) && t->size == 7) correct++;

  k = t->root->key(I64);                         /* the root (two children) */
  RBTreeDelete(t, k(U0*));
  if (Valid(t) && t->size == 6 && !RBTreeHas(t, k(U0*))) correct++;

  RBTreeDelete(t, 1(U0*));                       /* a leaf */
  RBTreeDelete(t, 7(U0*));
  if (Valid(t) && t->size == 4 && !RBTreeHas(t, 1(U0*)) && !RBTreeHas(t, 7(U0*))) correct++;
  if (RBTreeGet(t, 3(U0*))(I64) == 30 && RBTreeGet(t, 6(U0*))(I64) == 60) correct++;

  RBTreeDelete(t, 7(U0*));                       /* already gone */
  if (Valid(t) && t->size == 4) correct++;

  for (i = 1; i <= 7; i++) RBTreeDelete(t, i(U0*)); /* the rest */
  if (Valid(t) && t->size == 0 && IsNil(t->root)) correct++;

  RBTreeInsert(t, 5(U0*), NULL);                 /* usable again */
  if (Valid(t) && t->size == 1 && RBTreeHas(t, 5(U0*))) correct++;
  RBTreeDelete(t, 5(U0*));
  if (t->size == 0 && IsNil(t->root)) correct++;

  /* Many keys in a scrambled order, deleted in another order */
  for (i = 0; i < 500; i++) RBTreeInsert(t, ((i * 37) % 500)(U0*), NULL);
  if (Valid(t) && t->size == 500) correct++;
  ok = TRUE;
  for (i = 0; i < 500; i += 2) {                 /* the even ones */
    RBTreeDelete(t, ((i * 101) % 500)(U0*));
    if (i % 50 == 0 && !Valid(t)) ok = FALSE;
  }
  if (ok && Valid(t)) correct++;
  ok = t->size == 250;
  /* exactly the keys (i*101)%500 for odd i are left */
  for (i = 1; i < 500; i += 2) if (!RBTreeHas(t, ((i * 101) % 500)(U0*))) ok = FALSE;
  if (ok) correct++;
  for (i = 499; i >= 0; i--) RBTreeDelete(t, i(U0*));
  if (Valid(t) && t->size == 0) correct++;

  /* free_key gets the key, once per deleted node */
  RBTree *t2 = RBTreeNew(&CountFree, NULL, &RbTestCmp);
  for (i = 1; i <= 10; i++) RBTreeInsert(t2, i(U0*), NULL);
  RBTreeDelete(t2, 4(U0*));
  RBTreeDelete(t2, 9(U0*));
  RBTreeDelete(t2, 42(U0*));
  if (g_freed == 2 && g_freed_sum == 13 && Valid(t2) && t2->size == 8) correct++;

  /* IntSetDelete */
  IntSet *s = IntSetNew;
  for (i = 0; i < 20; i++) RBTreeInsert(s->tree, i(U0*), NULL);
  IntSetDelete(s, 3);
  IntSetDelete(s, 0);
  IntSetDelete(s, 99);
  if (IntSetSize(s) == 18 && !IntSetHas(s, 3) && !IntSetHas(s, 0) && IntSetHas(s, 4)) correct++;
  if (Valid(s->tree)) correct++;
  for (i = 0; i < 20; i++) IntSetDelete(s, i);
  if (IntSetSize(s) == 0 && Valid(s->tree)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
