#include "testhelper.ktm"

/* StrNCpy(buf, len) copied exactly len bytes, past the end of a shorter
 * string, returned an uninitialised byte for len 0 (it returned before
 * writing the NUL) and crashed on a negative len. It is now strndup: at
 * most len bytes, stopping at the NUL, always terminated, len < 0 is 0.
 * fzf's internal str_replace called it as if it were C's strncpy (leaking
 * the copy and leaving the result unwritten); that use is fixed too. It
 * is checked through FzF, whose pattern parsing uses it to turn an escaped
 * space (`\ `) into one that is part of the term. */

/* FzF(text, pattern) found a match covering `size` characters */
Bool Matches(U8 *text, U8 *pattern, I64 size)
{
  FzFPosition *pos = FzF(text, pattern);
  Bool ok = pos && pos->size == size;
  if (pos) FzFPositionRelease(pos);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - StrNCpy strndup semantics: ";

  U8 *a = StrNCpy("hello", 3);
  U8 *b = StrNCpy("hi", 10);
  U8 *c = StrNCpy("hello", 0);
  U8 *d = StrNCpy("hello", -1);
  U8 *e = StrNCpy("hello", 5);
  U8 *f = StrNCpy(NULL, 4);
  tests++;
  if (!StrCmp(a, "hel") && !StrCmp(b, "hi") && c[0] == '\0' && d[0] == '\0' &&
      !StrCmp(e, "hello") && f[0] == '\0') correct++;
  /* only what is needed is allocated, so a short source can't be read past */
  tests++; if (MSize(b) == 3 && MSize(a) == 4) correct++;
  Free(a); Free(b); Free(c); Free(d); Free(e); Free(f);

  tests++;
  if (Matches("xa bx", "a\\ b", 3) && !FzF("xabx", "a\\ b") &&
      Matches("a b c d", "b\\ c\\ d", 5))
    correct++;
  else "FzF with escaped spaces\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
