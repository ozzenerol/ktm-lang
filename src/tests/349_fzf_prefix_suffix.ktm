#include "testhelper.ktm"

/* `^prefix` skipped the text's leading whitespace only when the pattern
 * started with a space (the test was inverted), and `^exact$` compared
 * the trimmed text length with 0 instead of the pattern length, so it
 * never matched. Expected results are upstream telescope-fzf-native's. */

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
  "Test - fzf prefix suffix: ";
  I64 correct = 0, tests = 0;

  tests++; if (Check("  abc", "^abc", 0, TRUE, "80: 2 3 4")) correct++;
  tests++; if (Check("abc", "^abc", 0, TRUE, "80: 0 1 2")) correct++;
  tests++; if (Check("  abc", "^ab", 0, TRUE, "56: 2 3")) correct++;
  tests++; if (Check("xabc", "^abc", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("abc", "^abc$", 0, TRUE, "80: 0 1 2")) correct++;
  tests++; if (Check("  abc  ", "^abc$", 0, TRUE, "80: 2 3 4")) correct++;
  tests++; if (Check("abcd", "^abc$", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("ab", "^abc$", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("Fzf", "^Fzf$", 2, TRUE, "80: 0 1 2")) correct++;
  tests++; if (Check(" Fzf", "^fzf$", 0, TRUE, "80: 1 2 3")) correct++;
  tests++; if (Check(" Fzf", "^fzf$", 2, TRUE, "NULL")) correct++;
  tests++; if (Check("  HC_abc ", "^HC_", 0, TRUE, "80: 2 3 4")) correct++;
  tests++; if (Check("src/main.c", "^src", 0, TRUE, "80: 0 1 2")) correct++;
  tests++; if (Check("a", "^a$", 0, TRUE, "32: 0")) correct++;
  tests++; if (Check("abc", "!^abc$", 0, TRUE, "NULL")) correct++;
  tests++; if (Check("abc", "!^ab", 0, TRUE, "NULL")) correct++;

  if (correct != tests) "\n";
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
