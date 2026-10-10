#include "testhelper.ktm"

/* IntSetToString appended every element with CatPrint, which measures
 * the whole string each time: O(n^2), about 24 s for 500k elements. */

extern "c" I64 clock();

I64 Digits(I64 v)
{
  I64 n = 1;
  if (v < 0) { n++; v = -v; }
  while (v >= 10) { v /= 10; n++; }
  return n;
}

Bool StrIs(U8 *got, U8 *want)
{
  Bool ok = got != NULL && !StrCmp(got, want);
  if (!ok) "\n  got \"%s\" want \"%s\"\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  "Test - IntSetToString: ";
  I64 correct = 0, tests = 0, i, n, len, t;
  IntSet *s = IntSetNew;
  U8 *str;

  tests++; if (StrIs(IntSetToString(s), "{}")) correct++;
  IntSetAdd(s, 42);
  tests++; if (StrIs(IntSetToString(s), "{42}")) correct++;
  IntSetAdd(s, -7);
  IntSetAdd(s, 0);
  IntSetAdd(s, 42);
  tests++; if (StrIs(IntSetToString(s), "{-7, 0, 42}")) correct++;
  IntSetAdd(s, I64_MIN);
  IntSetAdd(s, I64_MAX);
  tests++; if (StrIs(IntSetToString(s),
      "{-9223372036854775808, -7, 0, 42, 9223372036854775807}")) correct++;
  IntSetRelease(s);

  /* A large set: right length, right ends, and linear time (the old
   * code took several seconds here). */
  s = IntSetNew;
  n = 300000;
  len = 2 + 2 * (n - 1);
  for (i = 0; i < n; i++) {
    IntSetAdd(s, n / 2 - i);
    len += Digits(n / 2 - i);
  }
  t = clock();
  str = IntSetToString(s);
  t = (clock() - t) / 1000; /* CLOCKS_PER_SEC is 1000000 on POSIX */
  tests++; if (StrLen(str) == len && MSize(str) > len) correct++;
  tests++; if (!StrNCmp(str, "{-149999, -149998, ", 19)) correct++;
  tests++; if (!StrCmp(str + len - 17, ", 149999, 150000}")) correct++;
  tests++;
  if (t < 2000) correct++;
  else "\n  IntSetToString of %d elements took %d ms\n", n, t;
  Free(str);
  IntSetRelease(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
