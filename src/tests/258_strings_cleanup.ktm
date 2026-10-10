#include "testhelper.ktm"

/* strings.ktm cleanup: strlen was declared twice (once as I64, once as
 * U64), strncasecmp and atol were public in the library but missing from
 * std.ktmh, and dead code went: MPrintQ's '$$' branch (a byte never equals
 * the two-byte constant), U64ToStr's rem > 9 branch and the PRTF_/CH_
 * constants nothing used. Behaviour is unchanged; this checks that the
 * declarations are usable and the touched functions still work. */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - strings cleanup: ";

  /* from std.ktmh, with no declaration here. (The I32 result goes through
   * a variable: comparing an extern "c" I32 call directly with < 0 is a
   * separate, reported codegen bug.) */
  I32 eq = strncasecmp("HeLLo", "hello", 5), lt = strncasecmp("a", "B", 1);
  tests++;
  if (eq == 0 && lt < 0 && atol("  -42x") == -42 && strlen("four") == 4) correct++;

  /* U64ToStr writes the digits least significant first (internal helper) */
  U8 buf[32];
  I64 n = U64ToStr(buf, 12345);
  I64 z = U64ToStr(buf + 16, 0);
  tests++; if (n == 5 && !StrCmp(buf, "54321") && z == 1 && !StrCmp(buf + 16, "0")) correct++;

  /* MPrintQ: '$' is an ordinary character */
  U8 *q = MPrintQ("a$$b\n\"c\"");
  tests++; if (!StrCmp(q, "a$$b\\n\\\"c\\\"")) correct++;
  else "MPrintQ -> %s\n", q;
  Free(q);

  /* the removed constants are gone, the used ones stay */
  Bool gone = TRUE;
#ifdef CH_ESC
  gone = FALSE;
#endif
#ifdef PRTF_NEG_E
  gone = FALSE;
#endif
  tests++; if (gone && CH_SPACE == ' ' && PRTF_PAD_ZERO == 1) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
