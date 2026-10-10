#include "testhelper.ktm"

/* Arrays of function pointers, `I64 (*fns[2])(I64 x);`, did not parse
 * ("unexpected character `[`"): the declarator only took a name between
 * `(*` and `)`. Locals, globals, class fields and parameters, with calls
 * through `fns[i](x)`. */

I64 Dbl(I64 x) { return x * 2; }
I64 Sq(I64 x) { return x * x; }
I64 Add(I64 a, I64 b = 10) { return a + b; }
F64 Half(F64 x) { return x / 2.0; }

I64 (*g_fns[2])(I64 x);
I64 (*g_init[])(I64 x) = {&Sq, &Dbl};

class Ops
{
  I64 n;
  I64 (*fns[2])(I64 x);
  I64 last;
};

Ops g_ops = {3, {&Dbl, &Sq}, 9};

I64 ApplyAll(I64 (*fs[])(I64 x), I64 n, I64 v)
{
  I64 i, total = 0;
  for (i = 0; i < n; i++) total += fs[i](v);
  return total;
}

I32 Main()
{
  "Test - arrays of function pointers: ";
  I64 correct = 0, tests = 0, i;

  I64 (*fns[2])(I64 x);
  fns[0] = &Dbl;
  fns[1] = &Sq;
  tests++; if (fns[0](5) == 10 && fns[1](5) == 25) correct++;
  tests++; if (sizeof(fns) == 16) correct++;

  I64 sum = 0;
  for (i = 0; i < 2; i++) sum += fns[i](i + 3);
  tests++; if (sum == 6 + 16) correct++;

  g_fns[0] = &Sq;
  g_fns[1] = &Dbl;
  tests++; if (g_fns[0](3) == 9 && g_fns[1](3) == 6) correct++;
  tests++; if (g_init[0](4) == 16 && g_init[1](4) == 8 && sizeof(g_init) == 16) correct++;

  I64 (*init[])(I64 x) = {&Dbl, &Sq, &Dbl};
  tests++; if (sizeof(init) == 24 && init[2](7) == 14 && init[1](3) == 9) correct++;

  Ops o;
  o.n = 4;
  o.fns[0] = &Sq;
  o.fns[1] = &Dbl;
  o.last = 1;
  tests++; if (o.fns[0](o.n) == 16 && o.fns[1](o.n) == 8 && o.last == 1) correct++;
  tests++; if (sizeof(Ops) == 32 && g_ops.fns[1](g_ops.n) == 9 && g_ops.last == 9) correct++;
  Ops *op = &o;
  tests++; if (op->fns[1](6) == 12) correct++;
  Ops lo = {2, {&Dbl, &Dbl}, 5};
  tests++; if (lo.fns[1](lo.n) == 4 && lo.last == 5) correct++;

  tests++; if (ApplyAll(fns, 2, 4) == 8 + 16) correct++;

  I64 (*grid[2][3])(I64 x);
  grid[1][2] = &Sq;
  tests++; if (grid[1][2](5) == 25 && sizeof(grid) == 48) correct++;

  I64 (*adds[1])(I64 a, I64 b = 10);
  adds[0] = &Add;
  tests++; if (adds[0](1) == 11 && adds[0](1, 2) == 3) correct++;

  F64 (*fl[1])(F64 x);
  fl[0] = &Half;
  tests++; if (fl[0](3.0) == 1.5) correct++;

  /* Plain function pointers and more declarators after the array */
  I64 (*one)(I64 x) = &Sq, (*more[2])(I64 x);
  more[1] = one;
  tests++; if (more[1](3) == 9 && one(2) == 4) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
