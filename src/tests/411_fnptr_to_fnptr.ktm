#include "testhelper.ktm"

/* A pointer to a function pointer, `I64 (**pp)(I64)`, was rejected
 * ("Expected function pointer name got *"), and `&fp` on a local function
 * pointer panicked in the IR. Now it works for locals, globals, parameters,
 * struct fields and arrays, reading and writing through it. */

I64 Twice(I64 x) { return x * 2; }
I64 Thrice(I64 x) { return x * 3; }

I64 (*g_fp)(I64) = &Twice;
I64 (**g_pp)(I64) = NULL;

struct Slot
{
  I64 (**at)(I64);
  i64 (**at2)(i64);
};

/* Point *pp at Thrice; returns what it pointed at before, applied to v */
I64 Swap(I64 (**pp)(I64), I64 v)
{
  I64 (*old)(I64) = *pp;
  *pp = &Thrice;
  return old(v);
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - pointers to function pointers: ";

  tests++;   /* a local, pointing at a local function pointer */
  I64 (*f)(I64) = &Twice;
  I64 (**pp)(I64) = &f;
  I64 (*g)(I64) = *pp;
  I64 before = g(5);
  *pp = &Thrice;
  if (before == 10 && f(5) == 15) correct++;
  else "\n  local\n";

  tests++;   /* a global */
  g_pp = &g_fp;
  I64 (*h)(I64) = *g_pp;
  I64 b2 = h(4);
  *g_pp = &Thrice;
  if (b2 == 8 && g_fp(4) == 12) correct++;
  else "\n  global\n";

  tests++;   /* a parameter */
  I64 (*k)(I64) = &Twice;
  if (Swap(&k, 3) == 6 && k(3) == 9) correct++;
  else "\n  parameter\n";

  tests++;   /* struct fields and an array of them */
  i64 (*m)(i64) = &Twice;
  Slot s;
  s.at = &g_fp;
  s.at2 = &m;
  I64 (**arr[2])(I64);
  arr[0] = &g_fp;
  arr[1] = &k;
  I64 (*a0)(I64) = *arr[0];
  I64 (*a1)(I64) = *arr[1];
  I64 (*fa)(I64) = *s.at;
  i64 (*fb)(i64) = *s.at2;
  if (fa(2) == 6 && fb(2) == 4 && a0(1) == 3 && a1(1) == 3) correct++;
  else "\n  fields and arrays\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
