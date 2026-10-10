#include "testhelper.ktm"

/* StrCmp / StrNCmp / StrNICmp must order strings like C's strcmp /
 * strncmp / strncasecmp: negative if s1 < s2, 0 if equal, positive if
 * s1 > s2, comparing bytes as unsigned. On x86_64 StrCmp had the sign
 * reversed, StrNICmp returned the distance between the two pointers
 * (even for equal strings shorter than n) and StrNCmp kept comparing
 * past the terminating NUL. */

extern "c" I32 strncasecmp(U8 *s1, U8 *s2, U64 n);

I64 Sign(I64 x)
{
  if (x < 0) return -1;
  if (x > 0) return 1;
  return 0;
}

Bool CmpOk(U8 *a, U8 *b, I64 want)
{
  I32 c = strcmp(a, b);
  return Sign(StrCmp(a, b)) == want && Sign(c) == want;
}

Bool NCmpOk(U8 *a, U8 *b, I64 n, I64 want)
{
  I32 c = strncmp(a, b, n);
  return Sign(StrNCmp(a, b, n)) == want && Sign(c) == want;
}

Bool NICmpOk(U8 *a, U8 *b, I64 n, I64 want)
{
  I32 c = strncasecmp(a, b, n);
  return Sign(StrNICmp(a, b, n)) == want && Sign(c) == want;
}

I32 Main()
{
  I64 correct = 0, tests = 30;

  "Test - StrCmp/StrNCmp/StrNICmp sign matches C: ";

  /* High bytes compare as unsigned: "\xff" > "a" */
  U8 hi[2], lo[2];
  hi[0] = 0xFF; hi[1] = 0;
  lo[0] = 'a';  lo[1] = 0;

  /* Equal up to the NUL, different garbage after it */
  U8 g1[8], g2[8];
  MemSet(g1, 0, 8);
  MemSet(g2, 0, 8);
  g1[0] = 'a'; g1[1] = 'b'; g1[3] = 'X';
  g2[0] = 'a'; g2[1] = 'b'; g2[3] = 'Y';

  /* StrCmp */
  if (CmpOk("abc", "abd", -1)) correct++;
  if (CmpOk("abd", "abc", 1)) correct++;
  if (CmpOk("abc", "abc", 0)) correct++;
  if (CmpOk("ab", "abc", -1)) correct++;
  if (CmpOk("abc", "ab", 1)) correct++;
  if (CmpOk("", "", 0)) correct++;
  if (CmpOk("", "a", -1)) correct++;
  if (CmpOk("a", "", 1)) correct++;
  if (CmpOk(hi, lo, 1) && CmpOk(lo, hi, -1)) correct++;

  /* StrNCmp */
  if (NCmpOk("abc", "abd", 3, -1)) correct++;
  if (NCmpOk("abd", "abc", 3, 1)) correct++;
  if (NCmpOk("abc", "abd", 2, 0)) correct++;  /* n stops before the difference */
  if (NCmpOk("abc", "abc", 10, 0)) correct++;
  if (NCmpOk("ab", "abc", 3, -1)) correct++;
  if (NCmpOk("abc", "ab", 3, 1)) correct++;
  if (NCmpOk("", "a", 1, -1)) correct++;
  if (NCmpOk("abc", "xyz", 0, 0)) correct++;
  if (NCmpOk(hi, lo, 1, 1) && NCmpOk(lo, hi, 1, -1)) correct++;
  if (NCmpOk(g1, g2, 8, 0)) correct++;        /* stops at the NUL */

  /* StrNICmp */
  if (NICmpOk("abc", "ABD", 3, -1)) correct++;
  if (NICmpOk("ABD", "abc", 3, 1)) correct++;
  if (NICmpOk("Hello", "hELLO", 5, 0)) correct++;
  if (NICmpOk("Hello", "hELLO", 10, 0)) correct++; /* equal, shorter than n */
  if (NICmpOk("abc", "ABD", 2, 0)) correct++;
  if (NICmpOk("ab", "ABC", 3, -1)) correct++;
  if (NICmpOk("ABC", "ab", 3, 1)) correct++;
  if (NICmpOk("_", "A", 1, -1)) correct++;    /* lowercases, like C */
  if (NICmpOk("", "", 5, 0) && NICmpOk("x", "y", 0, 0)) correct++;
  if (NICmpOk(hi, lo, 1, 1) && NICmpOk(lo, hi, 1, -1)) correct++;
  if (NICmpOk(g1, g2, 8, 0)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
