#include "testhelper.ktm"

/* A class value as an item of a local initialiser list,
 * `L l = {1, p0, 4}` or `P ps[2] = {p0, p0}`, stored the class's
 * address in the first 8 bytes of the field/element instead of copying
 * the class. Globals were right. Class items from a global, a local, a
 * parameter, `*pp`, a field, an array element, a call, an assignment;
 * classes of 1 to 24 bytes, nested, in arrays and unions, mixed with
 * brace-elided scalars. */

class P { I64 x, y; };              /* 16 */
class L { I64 a; P p; I64 b; };
class Q { I64 v; };                 /* 8 */
class T { I64 a, b, c; };           /* 24 */
class B { U8 a, b, c; };            /* 3 */
class W { I32 i; U8 c; };           /* odd fields, padded */
class Mix { U8 h; B b; Q q; T t; W w; U8 e; };
class N { I64 h; L l; I64 k; };
class M { P a[2]; I64 z; };
union U { P p; I64 n; };

P p0 = {2, 3};
B gb = {7, 8, 9};
T gt = {10, 11, 12};
L gl = {1, p0, 4};
P gps[2] = {p0, p0};

P MkP(I64 x, I64 y)
{
  P r;
  r.x = x;
  r.y = y;
  return r;
}

T MkT(I64 n)
{
  T r;
  r.a = n;
  r.b = n + 1;
  r.c = n + 2;
  return r;
}

Bool IsL(L *l, I64 a, I64 x, I64 y, I64 b)
{
  return l->a == a && l->p.x == x && l->p.y == y && l->b == b;
}

Bool IsP(P *p, I64 x, I64 y)
{
  return p->x == x && p->y == y;
}

Bool IsB(B *b, I64 a, I64 c2, I64 c)
{
  return b->a == a && b->b == c2 && b->c == c;
}

Bool IsT(T *t, I64 a, I64 b, I64 c)
{
  return t->a == a && t->b == b && t->c == c;
}

/* From by-value parameters */
Bool FromParams(P q, B b, T t)
{
  L l = {5, q, 6};
  P ps[3] = {q, {1, 1}, q};
  B bs[2] = {b, b};
  T ts[2] = {t, t};
  return IsL(&l, 5, q.x, q.y, 6) && IsP(&ps[0], q.x, q.y) &&
         IsP(&ps[1], 1, 1) && IsP(&ps[2], q.x, q.y) &&
         IsB(&bs[0], b.a, b.b, b.c) && IsB(&bs[1], b.a, b.b, b.c) &&
         IsT(&ts[0], t.a, t.b, t.c) && IsT(&ts[1], t.a, t.b, t.c);
}

I32 Main()
{
  "Test - class item in a local initialiser list: ";
  I64 tests = 0;
  I64 correct = 0;

  /* The bug report */
  L l = {1, p0, 4};
  P ps[2] = {p0, p0};
  tests++;
  if (IsL(&l, 1, 2, 3, 4) && IsP(&ps[0], 2, 3) && IsP(&ps[1], 2, 3))
    correct++;
  else "Failed - global item: %d %d %d %d | %d %d %d %d\n",
         l.a, l.p.x, l.p.y, l.b, ps[0].x, ps[0].y, ps[1].x, ps[1].y;

  /* From a local, a field, an array element */
  P lp = {20, 21};
  L l2 = {1, lp, 4};
  L l3 = {7, l2.p, 8};
  P ps2[3] = {ps[1], lp, l3.p};
  tests++;
  if (IsL(&l2, 1, 20, 21, 4) && IsL(&l3, 7, 20, 21, 8) &&
      IsP(&ps2[0], 2, 3) && IsP(&ps2[1], 20, 21) && IsP(&ps2[2], 20, 21))
    correct++;
  else "Failed - local / field / element item\n";

  /* Through a pointer */
  P *pp = &lp;
  L *lptr = &l2;
  L l4 = {3, *pp, 9};
  P ps3[2] = {*pp, lptr->p};
  tests++;
  if (IsL(&l4, 3, 20, 21, 9) && IsP(&ps3[0], 20, 21) &&
      IsP(&ps3[1], 20, 21))
    correct++;
  else "Failed - pointer item\n";

  /* From a by-value parameter */
  P q = {30, 31};
  B b = {1, 2, 3};
  T t = {4, 5, 6};
  tests++;
  if (FromParams(q, b, t)) correct++;
  else "Failed - parameter item\n";

  /* From a call: 16 bytes (registers) and 24 bytes (memory) */
  L l5 = {1, MkP(40, 41), 2};
  P ps4[2] = {MkP(42, 43), MkP(44, 45)};
  T ts[2] = {MkT(50), MkT(60)};
  tests++;
  if (IsL(&l5, 1, 40, 41, 2) && IsP(&ps4[0], 42, 43) &&
      IsP(&ps4[1], 44, 45) && IsT(&ts[0], 50, 51, 52) &&
      IsT(&ts[1], 60, 61, 62))
    correct++;
  else "Failed - call item\n";

  /* From an assignment */
  P pa;
  L l6 = {1, pa = p0, 2};
  tests++;
  if (IsL(&l6, 1, 2, 3, 2) && IsP(&pa, 2, 3)) correct++;
  else "Failed - assignment item\n";

  /* Classes of 3, 8, 8 (padded), 24 bytes inside a class */
  Q lq = {77};
  W lw = {-5, 200};
  Mix mx = {1, gb, lq, gt, lw, 2};
  tests++;
  if (mx.h == 1 && IsB(&mx.b, 7, 8, 9) && mx.q.v == 77 &&
      IsT(&mx.t, 10, 11, 12) && mx.w.i == -5 && mx.w.c == 200 &&
      mx.e == 2)
    correct++;
  else "Failed - classes of other sizes\n";

  /* Arrays of small classes: the neighbours must be kept */
  B bs[4] = {gb, b, gb, b};
  Q qs[3] = {lq, {5}, lq};
  tests++;
  if (IsB(&bs[0], 7, 8, 9) && IsB(&bs[1], 1, 2, 3) &&
      IsB(&bs[2], 7, 8, 9) && IsB(&bs[3], 1, 2, 3) &&
      qs[0].v == 77 && qs[1].v == 5 && qs[2].v == 77)
    correct++;
  else "Failed - array of small classes\n";

  /* Nested: a class item inside a class inside the list */
  N n1 = {1, {2, p0, 3}, 4};
  N n2 = {1, 2, lp, 3, 4};      /* braces of `l` elided */
  L ls[2] = {{1, p0, 2}, {3, lp, 4}};
  L ls2[2] = {1, p0, 2, 3, lp, 4};
  tests++;
  if (n1.h == 1 && IsL(&n1.l, 2, 2, 3, 3) && n1.k == 4 &&
      n2.h == 1 && IsL(&n2.l, 2, 20, 21, 3) && n2.k == 4 &&
      IsL(&ls[0], 1, 2, 3, 2) && IsL(&ls[1], 3, 20, 21, 4) &&
      IsL(&ls2[0], 1, 2, 3, 2) && IsL(&ls2[1], 3, 20, 21, 4))
    correct++;
  else "Failed - nested class item\n";

  /* A class item next to brace-elided scalars of the same array */
  M m1 = {p0, 5, 6, 7};
  M m2 = {5, 6, lp, 7};
  tests++;
  if (IsP(&m1.a[0], 2, 3) && IsP(&m1.a[1], 5, 6) && m1.z == 7 &&
      IsP(&m2.a[0], 5, 6) && IsP(&m2.a[1], 20, 21) && m2.z == 7)
    correct++;
  else "Failed - class item mixed with elided scalars\n";

  /* A short list: the rest is still zero */
  L l7 = {1, lp};
  P ps5[3] = {lp};
  tests++;
  if (IsL(&l7, 1, 20, 21, 0) && IsP(&ps5[0], 20, 21) &&
      IsP(&ps5[1], 0, 0) && IsP(&ps5[2], 0, 0))
    correct++;
  else "Failed - short list\n";

  /* A union set through its class member */
  U u = {lp};
  tests++;
  if (IsP(&u.p, 20, 21)) correct++;
  else "Failed - union item\n";

  /* A copy, not an alias: changing the source later leaves it */
  lp.x = 99;
  tests++;
  if (l2.p.x == 20 && ps2[1].x == 20 && n2.l.p.x == 20) correct++;
  else "Failed - copy aliases its source\n";

  /* Globals still right */
  tests++;
  if (IsL(&gl, 1, 2, 3, 4) && IsP(&gps[0], 2, 3) && IsP(&gps[1], 2, 3))
    correct++;
  else "Failed - globals\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
