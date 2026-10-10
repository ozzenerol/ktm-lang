#include "testhelper.ktm"

/* A tagged class or union defined inside a class body crashed ktm: the
 * field parser left the `class`/`union` keyword unread, so the tag was
 * never seen and the definition had no fields. Without a field name its
 * fields are used as the outer class's own (like an anonymous one) and
 * the tag is a type of its own; with a field name it is that field's
 * type. */

class Y
{
  I64 a;
  union U2 { I32 i; F32 f; };
  I64 z;
};

class Box
{
  I32 tag;
  class Pair { I64 x, y; };
  I32 end;
};

union Num
{
  I64 whole;
  union Half { I32 lo; I32 alt; };
};

class Named
{
  I8 c;
  union NU { I32 i; F32 f; } u, v;
  class NP { I32 p, q; } pt;
  NU *up;
};

class Anon
{
  I64 a;
  union { I32 i; F32 f; };
  I64 z;
};

I32 Main()
{
  "Test - tagged class or union inside a class: ";
  I64 correct = 0, tests = 0;

  Y y;
  y.a = 1; y.i = 2; y.z = 3;
  tests++; if (sizeof(Y) == 24 && y.a == 1 && y.i == 2 && y.z == 3) correct++;
  y.f = 1.5;
  tests++; if (y.f == 1.5 && y.a == 1 && y.z == 3) correct++;
  tests++; if (&y.i(U8 *) - &y(U8 *) == 8 && &y.z(U8 *) - &y(U8 *) == 16) correct++;

  /* The tag is a type of its own, with its own offsets */
  U2 u2;
  u2.i = 7;
  tests++; if (sizeof(U2) == 4 && &u2.f(U8 *) == &u2(U8 *) && u2.i == 7) correct++;

  Box b = {1, 2, 3, 4};
  tests++; if (sizeof(Box) == 32 && b.tag == 1 && b.x == 2 && b.y == 3 && b.end == 4) correct++;
  tests++; if (&b.x(U8 *) - &b(U8 *) == 8 && &b.end(U8 *) - &b(U8 *) == 24) correct++;
  Pair p = {5, 6};
  tests++; if (sizeof(Pair) == 16 && p.x == 5 && p.y == 6 && &p.y(U8 *) - &p(U8 *) == 8) correct++;

  Num n;
  n.whole = 0;
  n.lo = 9;
  tests++; if (sizeof(Num) == 8 && n.whole == 9 && n.alt == 9) correct++;
  Num n2 = {11};
  tests++; if (n2.whole == 11) correct++;

  Named nm;
  nm.c = 1; nm.u.i = 2; nm.v.f = 2.5; nm.pt.p = 3; nm.pt.q = 4;
  nm.up = &nm.u;
  tests++;
  if (sizeof(Named) == 32 && nm.u.i == 2 && nm.v.f == 2.5 &&
      nm.pt.q == 4 && nm.up->i == 2 && sizeof(NU) == 4 && sizeof(NP) == 8) correct++;
  Named nm2 = {1, {2}, {3}, {4, 5}, NULL};
  tests++; if (nm2.u.i == 2 && nm2.v.i == 3 && nm2.pt.p == 4 && nm2.pt.q == 5) correct++;

  Anon an = {1, 2, 3};
  tests++; if (sizeof(Anon) == 24 && an.a == 1 && an.i == 2 && an.z == 3) correct++;

  Y *yp = &y;
  yp->i = 42;
  tests++; if (yp->i == 42 && yp->z == 3) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
