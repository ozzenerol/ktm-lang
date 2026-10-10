#include "testhelper.ktm"

/* Every FzF call leaked its parsed terms: releasing a term set freed the
 * terms but not their text, and in case-sensitive mode the term's copy of
 * the text was not even recorded (og_str stayed NULL). */

I64 RunAll(U8 *text)
{
  I64 hits = 0, i, j;
  U8 *pats[7];
  I32 modes[3];
  FzFPosition *pos;
  pats[0] = "abc"; pats[1] = "ABC"; pats[2] = "'ab !zz"; pats[3] = "^a c$ | b";
  pats[4] = "x"; pats[5] = "Ab cD"; pats[6] = "| Ab";
  modes[0] = FZF_CASE_SMART; modes[1] = FZF_CASE_IGNORE; modes[2] = FZF_CASE_RESPECT;
  for (i = 0; i < 7; i++) {
    for (j = 0; j < 3; j++) {
      U8 *p = StrNew(pats[i]);
      pos = FzF(text, p, modes[j]);
      if (pos) hits++;
      FzFPositionRelease(pos);
      Free(p);
    }
  }
  return hits;
}

I32 Main()
{
  "Test - fzf term leak: ";
  I64 correct = 0, tests = 0, i, before, delta, hits;

  hits = RunAll("aBc abc ABC abcd");
  for (i = 0; i < 20; i++) RunAll("aBc abc ABC abcd");
  before = HeapInUse();
  for (i = 0; i < 500; i++) {
    if (RunAll("aBc abc ABC abcd") != hits) hits = -1;
  }
  delta = HeapInUse() - before;
  tests++; if (hits > 0) correct++;
  tests++; if (delta < 4096) correct++; else "leaked %d bytes ", delta;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
