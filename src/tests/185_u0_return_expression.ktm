#include "testhelper.ktm"

/* `return <expr>;` in a U0 function dropped the expression: the
 * function has no return slot, and the value was only evaluated to be
 * stored there, so `U0 Wrap() { return Inc(); }` never called Inc.
 * It is now evaluated for its side effects and the value discarded.
 * IntSetAdd in the stdlib is written this way. */

I64 g = 0;

U0 Inc() { g++; }
U0 Wrap() { return Inc(); }

I64 Inc2() { g++; return g; }
U0 Wrap2() { return Inc2(); }

U0 Early(I64 n)
{
  if (n > 0) return Inc();
  g += 100;
}

U0 Assign() { return g = 50; }
U0 PostInc() { return g++; }

inline U0 InlWrap() { return Inc(); }
inline U0 InlWrap2() { return Inc2(); }

I64 Twice(I64 x) { g += x; return x * 2; }
U0 Nested() { return Twice(Twice(1)); }

I32 Main()
{
  I64 correct = 0, tests = 0;
  IntSet *s;

  "Test - return with an expression in a U0 function: ";

  Wrap();
  tests++; if (g == 1) correct++;
  Wrap2();
  tests++; if (g == 2) correct++;
  Early(1);
  tests++; if (g == 3) correct++;
  Early(0);
  tests++; if (g == 103) correct++;
  Assign();
  tests++; if (g == 50) correct++;
  PostInc();
  tests++; if (g == 51) correct++;
  g = 0;
  Nested();
  tests++; if (g == 3) correct++;

  g = 0;
  InlWrap();
  tests++; if (g == 1) correct++;
  InlWrap2();
  tests++; if (g == 2) correct++;

  /* IntSetAdd is `U0 ... { return RBTreeInsert(...); }` */
  s = IntSetNew();
  IntSetAdd(s, 5);
  IntSetAdd(s, 7);
  IntSetAdd(s, 5);
  tests++; if (IntSetHas(s, 5) && IntSetHas(s, 7) && !IntSetHas(s, 6)) correct++;
  tests++; if (IntSetSize(s) == 2) correct++;
  IntSetRelease(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
