#include "testhelper.ktm"

/* FzF trimmed the pattern in place: the caller's string was changed, and
 * a string literal with leading or trailing spaces segfaulted under AOT
 * (literals are read-only). It works on a copy now. */

Bool Literal(U8 *text, I64 want_score, I64 want_first)
{
  FzFPosition *pos;
  Bool ok;
  pos = FzF(text, "  ab  ");
  ok = pos && pos->score == want_score && pos->size == 2 && pos->data[0] == want_first;
  FzFPositionRelease(pos);
  return ok;
}

Bool Unchanged(U8 *pat, U8 *text)
{
  U8 *copy = StrNew(pat);
  FzFPosition *pos = FzF(text, copy);
  Bool ok = !StrCmp(copy, pat);
  if (!ok) "\n  [%s] became [%s]", pat, copy;
  if (pos) FzFPositionRelease(pos);
  Free(copy);
  return ok;
}

I32 Main()
{
  "Test - fzf pattern const: ";
  I64 correct = 0, tests = 0;
  FzFPosition *pos;

  tests++; if (Literal("abc", 56, 0)) correct++;

  pos = FzF("abc", "ab ");
  tests++; if (pos && pos->size == 2) correct++;
  FzFPositionRelease(pos);

  pos = FzF("abc", "   ");
  tests++; if (pos == NULL) correct++;

  pos = FzF("foo bar", "  foo\\ bar  ");
  tests++; if (pos && pos->score == 176 && pos->size == 7) correct++;
  FzFPositionRelease(pos);

  pos = FzF("abc", "  b   c ");
  tests++; if (pos && pos->score == 32 && pos->size == 2) correct++;
  FzFPositionRelease(pos);

  tests++; if (Unchanged("ab  ", "abc")) correct++;
  tests++; if (Unchanged("   ab", "abc")) correct++;
  tests++; if (Unchanged("  x\\ y  ", "x y")) correct++;
  tests++; if (Unchanged("   ", "abc")) correct++;

  if (correct != tests) "\n";
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
