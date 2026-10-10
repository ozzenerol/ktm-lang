#include "testhelper.ktm"

/* "%d" of the smallest I64 printed punctuation: I64ToStr negated the number
 * (-I64_MIN overflows back to I64_MIN) and took `% 10` of a negative. */

I64 g_correct = 0;

U0 Expect(U8 *got, U8 *want)
{
  if (!StrCmp(got, want)) g_correct++;
  else "\n  got '%s' want '%s'\n", got, want;
  Free(got);
}

I32 Main()
{
  I64 tests = 21, min = I64_MIN, max = I64_MAX;
  U8 buf[64];
  "Test - %%d of the smallest I64: ";

  Expect(StrPrint(NULL, "%d", 0x8000000000000000), "-9223372036854775808");
  Expect(StrPrint(NULL, "%d", min), "-9223372036854775808");
  Expect(StrPrint(NULL, "%d", min + 1), "-9223372036854775807");
  Expect(StrPrint(NULL, "%d", max), "9223372036854775807");
  Expect(StrPrint(NULL, "%d", -1), "-1");
  Expect(StrPrint(NULL, "%d", -10), "-10");
  Expect(StrPrint(NULL, "%d", 0), "0");
  Expect(StrPrint(NULL, "%u", min), "9223372036854775808");
  Expect(StrPrint(NULL, "%u", -1), "18446744073709551615");

  /* widths, padding, justification */
  Expect(StrPrint(NULL, "%25d|", min), "     -9223372036854775808|");
  Expect(StrPrint(NULL, "%-25d|", min), "-9223372036854775808     |");
  Expect(StrPrint(NULL, "%025d", min), "-000009223372036854775808");
  Expect(StrPrint(NULL, "%5d", min), "-9223372036854775808");
  Expect(StrPrint(NULL, "%05d", -42), "-0042");
  Expect(StrPrint(NULL, "%-5d|", -42), "-42  |");
  Expect(StrPrint(NULL, "[%d,%d]", min, min), "[-9223372036854775808,-9223372036854775808]");

  /* the other ways of formatting */
  Expect(StrPrint(NULL, "%x", min), "8000000000000000");
  Expect(MStrPrint("%d", min), "-9223372036854775808");
  Expect(CatPrint(StrNew("x="), "%d", min), "x=-9223372036854775808");

  /* I64ToStr itself writes the digits reversed */
  Bool neg;
  I64 len = I64ToStr(buf, min, &neg);
  if (len == 19 && neg && !StrCmp(buf, "8085774586302733229")) g_correct++;
  len = I64ToStr(buf, -305, &neg);
  if (len == 3 && neg && !StrCmp(buf, "503")) g_correct++;

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
