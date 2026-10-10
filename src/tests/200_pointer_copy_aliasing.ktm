#include "testhelper.ktm"

/* `I64 *z = v; v = &x;` made z follow v: the IR store forwarding
 * recorded "z holds v" and kept rewriting reads of z into reads of v
 * after v was given a new address (`lea v, x` is not a store, so the
 * entry was never dropped). `*z` read x instead of y, and with
 * `v = NULL` it crashed. A copy must keep the value it was given. */

class Node
{
  I64 val;
  Node *next;
};

I64 g_a = 11, g_b = 22;
I64 *g_p = &g_a;

I64 ParamCopy(I64 *v, I64 *other)
{
  I64 *z = v;
  v = other;
  return *z * 100 + *v;
}

I64 ParamCopyNull(I64 *v)
{
  I64 *z = v;
  v = NULL;
  if (v == NULL) return *z;
  return -1;
}

I32 Main()
{
  I64 correct = 0, tests = 16, i;
  I64 x = 3, y = 7;

  "Test - pointer copy keeps its value after the source changes: ";

  /* Declaration copy, then the source gets a new address */
  I64 *v = &y;
  I64 *z = v;
  v = &x;
  if (*z == 7 && z == &y && *v == 3) correct++;

  /* Plain assignment copy */
  I64 *v2, *z2;
  v2 = &y;
  z2 = v2;
  v2 = &x;
  if (*z2 == 7 && z2 == &y) correct++;

  /* Source set to NULL: the copy must not become NULL */
  I64 *v3 = &y;
  I64 *z3 = v3;
  v3 = NULL;
  if (v3 == NULL && *z3 == 7) correct++;

  /* U0 * */
  U0 *u = &y;
  I64 *zu = u;
  u = &x;
  U0 *zu2 = u;
  u = NULL;
  if (*zu == 7 && zu2 == &x && u == NULL) correct++;

  /* Parameters */
  if (ParamCopy(&y, &x) == 703) correct++;
  if (ParamCopyNull(&y) == 7) correct++;

  /* Class pointers */
  Node a, b;
  a.val = 1; a.next = &b;
  b.val = 2; b.next = NULL;
  Node *n = &a;
  Node *keep = n;
  n = n->next;
  if (keep->val == 1 && n->val == 2 && keep == &a) correct++;

  /* Globals */
  I64 *gz = g_p;
  g_p = &g_b;
  if (*gz == 11 && *g_p == 22) correct++;
  I64 *lp = &g_a;
  I64 *lz = lp;
  lp = &g_b;
  if (*lz == 11 && *lp == 22) correct++;

  /* Pointer to pointer */
  I64 *pa = &x, *pb = &y;
  I64 **pp = &pa;
  I64 **pz = pp;
  pp = &pb;
  if (pz == &pa && **pz == 3 && **pp == 7) correct++;

  /* Walking a list: prev keeps the node it was given */
  Node c;
  c.val = 3; c.next = NULL;
  b.next = &c;
  Node *cur = &a, *prev = NULL;
  I64 sum = 0;
  while (cur) {
    prev = cur;
    cur = cur->next;
    if (cur) sum += prev->val * 10 + cur->val;
  }
  if (prev == &c && sum == 12 + 23) correct++;

  /* Copy then reassign in a loop */
  I64 arr[4] = {10, 20, 30, 40};
  I64 *p = arr, *q;
  I64 ok = 1;
  for (i = 0; i < 3; i++) {
    q = p;
    p = p + 1;
    if (*q != arr[i] || *p != arr[i + 1]) ok = 0;
  }
  if (ok) correct++;

  /* Copy then reassign in branches */
  I64 *bv = &x;
  I64 *bz = bv;
  if (x == 3) bv = &y; else bv = NULL;
  if (*bz == 3 && *bv == 7) correct++;
  I64 *cv = &y;
  I64 *cz = cv;
  if (x == 4) cv = &x;
  else cv = NULL;
  if (*cz == 7 && cv == NULL) correct++;

  /* Copy of a copy */
  I64 *c1 = &x;
  I64 *c2 = c1;
  I64 *c3 = c2;
  c1 = &y;
  c2 = NULL;
  if (*c3 == 3 && c2 == NULL && *c1 == 7) correct++;

  /* Integer copies were already right; keep them so */
  I64 ia = 1, ib = 2;
  I64 ic = ia;
  ia = ib;
  if (ic == 1 && ia == 2) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
