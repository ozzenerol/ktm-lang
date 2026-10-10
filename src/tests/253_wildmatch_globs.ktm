#include "testhelper.ktm"

/* WildMatch got simple globs wrong: ("banana","*a") was FALSE,
 * ("abc","**") FALSE, ("","*x") TRUE, ("ab","ab*c") TRUE - it checked the
 * end of the text too early, never backtracked properly and searched for
 * '?' / '*' literally after a '*'. It is now a standard glob match ('*'
 * any run, '?' any one character) with backtracking to the last '*',
 * checked against a recursive reference on random cases. */

/* Obviously-correct (exponential) reference */
Bool RefMatch(U8 *s, U8 *p)
{
  if (*p == '\0') return *s == '\0';
  if (*p == '*') {
    for (I64 i = 0; ; ++i) {
      if (RefMatch(s + i, p + 1)) return TRUE;
      if (s[i] == '\0') return FALSE;
    }
  }
  if (*s == '\0') return FALSE;
  if (*p == '?' || *p == *s) return RefMatch(s + 1, p + 1);
  return FALSE;
}

I64 g_seed = 99;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

U0 RandStr(U8 *buf, U8 *alphabet, I64 max_len)
{
  I64 n = NextRand() % (max_len + 1), k = StrLen(alphabet);
  for (I64 i = 0; i < n; ++i) buf[i] = alphabet[NextRand() % k];
  buf[n] = '\0';
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - WildMatch globs: ";

  /* the audit's cases and the usual ones */
  tests++;
  if (WildMatch("banana", "*a") && WildMatch("abc", "**") && !WildMatch("", "*x") &&
      !WildMatch("ab", "ab*c") && WildMatch("", "") && WildMatch("", "*") &&
      !WildMatch("a", "") && WildMatch("main.ktm", "*.ktm") &&
      !WildMatch("main.c", "*.ktm") && WildMatch("abc", "a?c") &&
      !WildMatch("ac", "a?c") && WildMatch("mississippi", "m*iss*ppi") &&
      WildMatch("aaaab", "*a*b") && !WildMatch("aaaa", "*a*b") &&
      WildMatch("x?y", "x?y") && WildMatch("a*b", "a*b")) correct++;

  /* random cases against the reference */
  U8 s[16], p[16];
  I64 bad = 0;
  for (I64 i = 0; i < 20000; ++i) {
    RandStr(s, "abc", 8);
    RandStr(p, "ab*?c", 6);
    if (WildMatch(s, p) != RefMatch(s, p)) {
      if (bad < 10) "mismatch (\"%s\", \"%s\")\n", s, p;
      bad++;
    }
  }
  tests++; if (bad == 0) correct++;

  /* long input with many stars stays fast (no exponential backtracking) */
  U8 *long_s = MAlloc(20001);
  MemSet(long_s, 'a', 20000);
  long_s[20000] = '\0';
  tests++;
  if (!WildMatch(long_s, "*a*a*a*a*a*b") && WildMatch(long_s, "*a*a*a*a*a")) correct++;
  Free(long_s);

  tests++; if (!WildMatch(NULL, "*") && !WildMatch("a", NULL)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
