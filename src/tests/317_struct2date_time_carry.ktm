#include "testhelper.ktm"

/* Struct2Date did not carry the time past a day: the time of day wrapped
 * and the date stayed, so 2024-03-05 with hour 24, min 30 gave
 * 03/05/2024 00:30:00. Hours, minutes, seconds, sec100 and sec10000 now
 * carry into the next units and into the date, negative values borrow
 * from the day before. */

CDate Mk(I64 y, I64 mo, I64 d, I64 h, I64 mi, I64 s, I64 s100 = 0,
         I64 s10000 = 0)
{
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = y;
  ds.mon = mo;
  ds.day_of_mon = d;
  ds.hour = h;
  ds.min = mi;
  ds.sec = s;
  ds.sec100 = s100;
  ds.sec10000 = s10000;
  return Struct2Date(&ds);
}

I64 g_correct = 0, g_tests = 0;

U0 Expect(CDate c, U8 *want)
{
  g_tests++;
  U8 *got = MStrPrint("%D %T", c, c);
  if (!StrCmp(got, want)) g_correct++;
  else "Failed - got '%s' want '%s'\n", got, want;
  Free(got);
}

U0 ExpectSame(CDate got, CDate want, U8 *what)
{
  g_tests++;
  if (got(I64) == want(I64)) g_correct++;
  else "Failed - %s: %X != %X\n", what, got(I64), want(I64);
}

I32 Main()
{
  "Test - Struct2Date carries the time into the date: ";
  Expect(Mk(2024, 3, 5, 24, 30, 0), "03/06/2024 00:30:00");
  Expect(Mk(2024, 3, 5, 25, 0, 0), "03/06/2024 01:00:00");
  Expect(Mk(2024, 3, 5, 48, 0, 0), "03/07/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 23, 60, 0), "03/06/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 12, 90, 0), "03/05/2024 13:30:00");
  Expect(Mk(2024, 3, 5, 0, 1440, 0), "03/06/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 23, 59, 60), "03/06/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 0, 0, 86400 + 61), "03/06/2024 00:01:01");
  Expect(Mk(2024, 3, 5, 23, 59, 59, 100), "03/06/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 23, 59, 59, 99, 100), "03/06/2024 00:00:00");
  /* Across the end of a month and a year, leap and common years */
  Expect(Mk(2023, 12, 31, 23, 59, 60), "01/01/2024 00:00:00");
  Expect(Mk(2023, 12, 31, 24, 0, 0), "01/01/2024 00:00:00");
  Expect(Mk(2024, 2, 28, 23, 59, 60), "02/29/2024 00:00:00");
  Expect(Mk(2023, 2, 28, 23, 59, 60), "03/01/2023 00:00:00");
  Expect(Mk(2024, 1, 31, 47, 0, 0), "02/01/2024 23:00:00");
  /* Negative values borrow from the day before */
  Expect(Mk(2024, 3, 5, -1, 0, 0), "03/04/2024 23:00:00");
  Expect(Mk(2024, 3, 5, -24, 0, 0), "03/04/2024 00:00:00");
  Expect(Mk(2024, 3, 5, -25, 0, 0), "03/03/2024 23:00:00");
  Expect(Mk(2024, 3, 5, 0, -60, 0), "03/04/2024 23:00:00");
  Expect(Mk(2024, 1, 1, 0, 0, -1), "12/31/2023 23:59:59");
  Expect(Mk(2024, 3, 1, 0, 0, 0, -1), "02/29/2024 23:59:59");
  Expect(Mk(2024, 3, 5, 1, -30, 0), "03/05/2024 00:30:00");
  /* The carried CDate is the same as the one built from in-range fields */
  ExpectSame(Mk(2024, 3, 5, 24, 30, 0), Mk(2024, 3, 6, 0, 30, 0), "24:30");
  ExpectSame(Mk(2023, 12, 31, 23, 59, 59, 99, 100), Mk(2024, 1, 1, 0, 0, 0),
             "sec10000 carry");
  ExpectSame(Mk(2024, 3, 5, 0, 0, 0, 0, 12345),
             Mk(2024, 3, 5, 0, 0, 1, 23, 45), "sec10000 into sec");
  ExpectSame(Mk(2024, 3, 5, 0, 0, -1), Mk(2024, 3, 4, 23, 59, 59), "-1 s");
  /* In-range times are unchanged */
  Expect(Mk(2024, 2, 29, 12, 34, 56), "02/29/2024 12:34:56");
  Expect(Mk(2024, 3, 5, 0, 0, 0), "03/05/2024 00:00:00");
  Expect(Mk(2024, 3, 5, 23, 59, 59, 99, 99), "03/05/2024 23:59:59");
  CDateStruct ds;
  Date2Struct(&ds, Mk(2024, 3, 5, 23, 59, 59, 99));
  g_tests++;
  if (ds.day_of_mon == 5 && ds.hour == 23 && ds.min == 59 && ds.sec == 59 &&
      ds.sec100 == 99) g_correct++;
  else "Failed - round trip of 23:59:59.99\n";
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
