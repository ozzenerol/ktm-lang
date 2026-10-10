#include "testhelper.ktm"

/* The naive exact matcher (used for 'term, for every term when fuzzy is
 * FALSE, and for !inverse terms) reset its state after the first matching
 * character instead of after a whole match, so only 1-character patterns
 * could match. Expected results are upstream telescope-fzf-native's. */

/* FzF's result as "score: positions" or "NULL", compared with the output
 * of upstream telescope-fzf-native (V1 algorithm) for the same input. */
Bool Check(U8 *text, U8 *pat, I32 case_mode, Bool fuzzy, U8 *want)
{
  U8 got[256], *p = StrNew(pat);
  I64 len;
  FzFPosition *pos = FzF(text, p, case_mode, fuzzy);
  if (!pos) {
    StrCpy(got, "NULL");
  } else {
    len = snprintf(got, sizeof(got), "%d:", pos->score);
    for (I64 i = 0; i < pos->size; i++) {
      len += snprintf(got + len, sizeof(got) - len, " %d", pos->data[i]);
    }
    FzFPositionRelease(pos);
  }
  Free(p);
  if (StrCmp(got, want)) {
    "\n  FzF(\"%s\", \"%s\", %d, %d) = %s, want %s", text, pat, case_mode, fuzzy, got, want;
    return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  "Test - fzf exact match: ";
  I64 correct = 0, tests = 0;

  tests++; if (Check("foobar", "'oba", 0, TRUE, "56: 2 3 4")) correct++;
  tests++; if (Check("foobar", "oba", 0, FALSE, "56: 2 3 4")) correct++;
  tests++; if (Check("foobar", "!oba", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("foobar", "'bar", 0, TRUE, "56: 3 4 5")) correct++;
  tests++; if (Check("hello world", "wor", 0, FALSE, "80: 6 7 8")) correct++;
  tests++; if (Check("src/main.c", "!main.c", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("src/main.c", "'main !test", 0, TRUE, "104: 4 5 6 7")) correct++;
  tests++; if (Check("FooBar", "'oob", 2, TRUE, "NULL")) correct++;
  tests++; if (Check("FooBar", "'OOB", 1, TRUE, "59: 1 2 3")) correct++;
  tests++; if (Check("aab", "ab", 0, FALSE, "36: 1 2")) correct++;
  tests++; if (Check("ababc", "'abc", 0, TRUE, "56: 2 3 4")) correct++;
  tests++; if (Check("xabcabd", "abd", 0, FALSE, "56: 4 5 6")) correct++;
  tests++; if (Check("foo bar", "'o b", 0, TRUE, "48: 1 4")) correct++;

  if (correct != tests) "\n";
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
