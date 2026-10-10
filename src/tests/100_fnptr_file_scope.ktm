#include "testhelper.ktm"

/* A function pointer declared at file scope - `I64 (*g)(I64 a, I64 b)
 * = &Add;` - failed with "Identifier expected", with or without
 * parameter names, initialiser or not. */

class S
{
  I64 (*f)(I64 x);
};

I64 Add(I64 a, I64 b) { return a + b; }
I64 Sub(I64 a, I64 b) { return a - b; }
I64 Dbl(I64 x) { return x * 2; }
I64 Apply(I64 (*f)(I64 x), I64 v) { return f(v); }
U8 *Name(I64 i) { if (i) return "one"; return "zero"; }
I64 g_hits = 0;
U0 Hit() { g_hits++; }
I64 Count(...) { return argc; }

I64 (*g)(I64 a, I64 b) = &Add;
I64 (*h)(I64, I64) = &Sub;
I64 (*later)(I64 a, I64 b);
I64 (*k)(I64 a, I64 b = 10) = &Add;
I64 (*p1)(I64 x) = &Dbl, (*p2)(I64 x);
I64 (*ap)(I64 (*f)(I64 x), I64 v) = &Apply;
U8 *(*nm)(I64 i) = &Name;
U0 (*cb)() = &Hit;
I64 (*va)(...) = &Count;
static I64 (*st)(I64 x) = &Dbl;
I64 after = 41;

I64 CallG(I64 a, I64 b) { return g(a, b); }

I32 Main()
{
  I64 correct = 0, tests = 14;

  "Test - function pointer at file scope: ";

  if (g(2, 3) == 5) correct++;
  if (h(9, 4) == 5) correct++;
  later = &Sub;
  if (later(10, 3) == 7) correct++;
  if (k(5) == 15 && k(5, 1) == 6) correct++;
  p2 = &Dbl;
  if (p1(4) == 8 && p2(5) == 10) correct++;
  if (ap(&Dbl, 21) == 42 && ap(p1, 3) == 6) correct++;
  if (!StrCmp(nm(1), "one") && !StrCmp(nm(0), "zero")) correct++;
  cb(); cb();
  if (g_hits == 2) correct++;
  if (va(1, 2, 3) == 3) correct++;
  if (st(8) == 16 && after == 41) correct++;

  /* Reassigned, called from another function, passed as an argument. */
  g = &Sub;
  if (CallG(7, 2) == 5) correct++;
  if (Apply(p1, 6) == 12) correct++;

  /* Local and class-field function pointers unchanged. */
  I64 (*lp)(I64 a, I64 b) = &Add;
  if (lp(1, 2) == 3) correct++;
  S s;
  s.f = &Dbl;
  if (s.f(7) == 14) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
