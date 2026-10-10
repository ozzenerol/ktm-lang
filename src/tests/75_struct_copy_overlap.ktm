#include "testhelper.ktm"

/* A <=16-byte class returned by value comes back in registers; the callee
 * copies the returned value into a struct-sized return slot that the
 * epilogue then loads. The frame layout used to give that slot only the
 * 8 bytes of a hidden out-pointer, so `return local;` was a memcpy between
 * overlapping stack ranges (undefined behaviour, flagged by ASan as
 * memcpy-param-overlap under -jit). Only >16-byte returns use an
 * out-pointer. */

class Pair { I64 a, b; };          /* 16B -> 2 INTEGER eightbytes */
class Mix  { I64 n; F64 f; };      /* 16B -> INTEGER + SSE */
class Tri  { I32 a, b, c; };       /* 12B -> 2 INTEGER eightbytes */

Pair MakePair(I64 a, I64 b) { Pair p; p.a = a; p.b = b; return p; }
Mix  MakeMix(I64 n, F64 f)  { Mix m; m.n = n; m.f = f; return m; }
Tri  MakeTri(I32 n)         { Tri t; t.a = n; t.b = n * 2; t.c = n * 3; return t; }

/* Several locals and a return from each branch. */
Pair PickPair(I64 n)
{
  Pair lo, hi;
  I64 sum = 0;
  for (I64 i = 1; i <= n; i++) sum += i;
  lo.a = sum; lo.b = -sum;
  hi.a = sum * 100; hi.b = n;
  if (n > 5) return hi;
  return lo;
}

Bool ReturnLocal()
{
  Pair p = MakePair(100, 200);
  Mix m = MakeMix(42, 9.5);
  Tri t = MakeTri(7);
  return p.a == 100 && p.b == 200 && m.n == 42 && m.f == 9.5 &&
         t.a == 7 && t.b == 14 && t.c == 21;
}

Bool ReturnFromBranches()
{
  Pair lo = PickPair(4);           /* 1+2+3+4 */
  Pair hi = PickPair(10);          /* 1+..+10 */
  return lo.a == 10 && lo.b == -10 && hi.a == 5500 && hi.b == 10;
}

Bool ReturnIntoExisting()
{
  Pair p;
  p = MakePair(-1, -2);
  p = MakePair(p.b, p.a);          /* swap through a call */
  return p.a == -2 && p.b == -1 && MakePair(3, 4).b == 4;
}

I32 Main()
{
  "Test - struct copies don't overlap: ";
  I64 tests = 3, correct = 0;
  if (ReturnLocal())        correct++;
  if (ReturnFromBranches()) correct++;
  if (ReturnIntoExisting()) correct++;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
