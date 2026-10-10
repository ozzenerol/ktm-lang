#include "testhelper.ktm"

/* Classes passed AND returned by value in the same function. Under -jit on
 * x86_64 the callee's parameter prologue reserved rdi for a hidden return
 * pointer whenever the function returned any class, but only >16-byte
 * returns use one (<=16 bytes come back in rax/rdx/xmm0/xmm1). Every
 * register parameter was read one GP register too late, so e.g.
 * `Point Add(Point a, Point b)` returned `22 <garbage>` instead of `11 22`.
 * Ahead-of-time compiled code was already correct. */

class Point { I64 x, y; };          /* 16B -> 2 INTEGER eightbytes */
class One   { I64 v; };             /* 8B  -> 1 INTEGER */
class Flt   { F64 a; };             /* 8B  -> 1 SSE */
class Vf    { F64 x, y; };          /* 16B -> 2 SSE */
class Mix   { I64 n; F64 f; };      /* 16B -> INTEGER + SSE */
class Tri   { I64 a, b, c; };       /* 24B -> MEMORY / hidden sret */
class Five  { I64 a; F64 b; I64 c; F64 d; I64 e; }; /* 40B -> MEMORY */

Point Add(Point a, Point b)
{
  Point r;
  r.x = a.x + b.x;
  r.y = a.y + b.y;
  return r;
}

Point Add3(Point a, Point b, Point c)
{
  Point r;
  r.x = a.x + b.x + c.x;
  r.y = a.y + b.y + c.y;
  return r;
}

Point Neg(Point a)
{
  Point r;
  r.x = -a.x;
  r.y = -a.y;
  return r;
}

/* Scalars mixed in before, between and after the by-value classes. */
Point Scale(I64 k, Point a, F64 f, Point b, I64 tail)
{
  Point r;
  r.x = k * a.x + b.x + tail;
  r.y = k * a.y + b.y + f;
  return r;
}

One AddOne(One a, One b)  { One r; r.v = a.v + b.v; return r; }
Flt AddFlt(Flt a, Flt b)  { Flt r; r.a = a.a + b.a; return r; }
Vf  AddVf(Vf a, Vf b)     { Vf r; r.x = a.x + b.x; r.y = a.y + b.y; return r; }
Mix AddMix(Mix a, Mix b)  { Mix r; r.n = a.n + b.n; r.f = a.f + b.f; return r; }

Tri AddTri(Tri a, Tri b)
{
  Tri r;
  r.a = a.a + b.a;
  r.b = a.b + b.b;
  r.c = a.c + b.c;
  return r;
}

Five AddFive(Five a, Five b)
{
  Five r;
  r.a = a.a + b.a;
  r.b = a.b + b.b;
  r.c = a.c + b.c;
  r.d = a.d + b.d;
  r.e = a.e + b.e;
  return r;
}

/* Small by-value args, big (sret) return, plus a scalar. */
Tri SpreadTri(Point p, One o, I64 k)
{
  Tri r;
  r.a = p.x * k;
  r.b = p.y * k;
  r.c = o.v * k;
  return r;
}

/* Big by-value args, small register return. */
Point FoldTri(Tri a, Tri b)
{
  Point r;
  r.x = a.a + a.b + a.c;
  r.y = b.a + b.b + b.c;
  return r;
}

I32 Main()
{
  "Test - classes passed and returned by value together:\n";
  I64 tests = 11, correct = 0;

  Point a, b, c;
  a.x = 1;  a.y = 2;
  b.x = 10; b.y = 20;
  c.x = 100; c.y = 200;

  Point r = Add(a, b);
  if (r.x == 11 && r.y == 22) correct++;

  r = Add3(a, b, c);
  if (r.x == 111 && r.y == 222) correct++;

  r = Neg(b);
  if (r.x == -10 && r.y == -20) correct++;

  r = Scale(3, a, 0.0, b, 5);
  if (r.x == 18 && r.y == 26) correct++;

  One o1, o2;
  o1.v = 7; o2.v = 35;
  One o = AddOne(o1, o2);
  if (o.v == 42) correct++;

  Flt f1, f2;
  f1.a = 1.5; f2.a = 2.25;
  Flt f = AddFlt(f1, f2);
  if (f.a == 3.75) correct++;

  Vf v1, v2;
  v1.x = 0.5; v1.y = 1.5;
  v2.x = 2.0; v2.y = 4.0;
  Vf v = AddVf(v1, v2);
  if (v.x == 2.5 && v.y == 5.5) correct++;

  Mix m1, m2;
  m1.n = 3; m1.f = 0.25;
  m2.n = 4; m2.f = 0.5;
  Mix m = AddMix(m1, m2);
  if (m.n == 7 && m.f == 0.75) correct++;

  Tri t1, t2;
  t1.a = 1; t1.b = 2; t1.c = 3;
  t2.a = 10; t2.b = 20; t2.c = 30;
  Tri t = AddTri(t1, t2);
  if (t.a == 11 && t.b == 22 && t.c == 33) correct++;

  Five x1, x2;
  x1.a = 1; x1.b = 1.5; x1.c = 3; x1.d = 4.5; x1.e = 5;
  x2.a = 10; x2.b = 0.5; x2.c = 30; x2.d = 0.5; x2.e = 50;
  Five x = AddFive(x1, x2);
  t = SpreadTri(a, o1, 2);
  r = FoldTri(t1, t2);
  if (x.a == 11 && x.b == 2.0 && x.c == 33 && x.d == 5.0 && x.e == 55 &&
      t.a == 2 && t.b == 4 && t.c == 14 && r.x == 6 && r.y == 60) correct++;

  /* A by-value result fed straight back in as a by-value argument. Main's
   * frame is large enough here that the temporaries sit beyond stur range:
   * on arm64 spilling the returned buffer address used x9 both as the value
   * and as the frame-offset scratch, and the next access segfaulted. */
  r = Add(Add(a, b), Neg(c));
  if (r.x == -89 && r.y == -178) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
