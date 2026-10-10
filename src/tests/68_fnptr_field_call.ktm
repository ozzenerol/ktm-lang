#include "testhelper.ktm"

/* Calling a function pointer field through anything other than a plain
 * variable - `arr[i]->f(x)`, `GetS()->f(x)`, `p->inner->f(x)` - was
 * parsed as a postfix cast `expr(Type)` and failed to compile. */

class Shape
{
  U8 *name;
  F64 (*area)(Shape *s);
  F64 w;
};

class S
{
  I64 (*f)(I64 x);
  I64 (*none)();
  I64 (*add3)(I64 a, I64 b, I64 c);
  I64 v;
};

class Outer
{
  S *inner;
  S in2;
};

I64 g_calls = 0;

F64 SquareArea(Shape *s) { return s->w * s->w; }
F64 HalfArea(Shape *s) { return s->w / 2.0; }
I64 Dbl(I64 x) { g_calls++; return x * 2; }
I64 Seven() { return 7; }
I64 Add3(I64 a, I64 b, I64 c) { return a + b + c; }

S g_s;
S *GetS() { return &g_s; }

I32 Main()
{
  I64 correct = 0, tests = 20, i;

  "Test - call a function pointer field through an expression: ";

  Shape sq, half;
  sq.name = "square";
  sq.area = &SquareArea;
  sq.w = 3.0;
  half.name = "half";
  half.area = &HalfArea;
  half.w = 5.0;

  Shape *shapes[2];
  shapes[0] = &sq;
  shapes[1] = &half;
  F64 total = 0.0;
  for (i = 0; i < 2; i++) {
    total += shapes[i]->area(shapes[i]);
  }
  if (total == 11.5) correct++;

  S s;
  s.f = &Dbl;
  s.none = &Seven;
  s.add3 = &Add3;
  s.v = 9;
  g_s = s;

  S *arr[2];
  arr[0] = &s;
  arr[1] = &s;
  S vals[2];
  vals[0] = s;
  vals[1] = s;

  if (arr[0]->f(4) == 8) correct++;
  if (arr[1]->f(4) + 1 == 9) correct++;
  if (vals[1].f(6) == 12) correct++;
  if (GetS()->f(7) == 14) correct++;
  if (arr[0]->none() == 7) correct++;
  if (vals[0].none() == 7) correct++;
  if (arr[0]->add3(1, 2, 3) == 6) correct++;
  if (vals[0].add3(arr[0]->f(1), vals[1].none(), GetS()->f(2)) == 13) {
    correct++;
  }
  if (Dbl(arr[0]->f(10)) == 40) correct++;

  /* As a statement on its own. */
  g_calls = 0;
  arr[0]->f(4);
  vals[1].f(4);
  GetS()->f(4);
  if (g_calls == 3) correct++;

  Outer o;
  o.inner = &s;
  o.in2 = s;
  Outer *op = &o;
  Outer outs[1];
  outs[0] = o;
  if (op->inner->f(11) == 22) correct++;
  if (op->in2.f(12) == 24) correct++;
  if (outs[0].inner->f(13) == 26) correct++;
  if (outs[0].in2.f(14) == 28) correct++;

  /* Plain function pointers, field calls and postfix casts unchanged. */
  I64 (*fp)(I64 a) = &Dbl;
  S *p = &s;
  if (fp(15) == 30 && p->f(16) == 32 && s.f(17) == 34) correct++;
  if (arr[0]->v(F64) == 9.0) correct++;
  if (arr[0](I64) == &s) correct++;
  if (arr[0]->f(2)(F64) == 4.0) correct++;
  I64 a = 3, b = 4;
  a * b;
  if (a * b == 12) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
