#include "testhelper.ktm"

/* CDate used the original calendar: no leap year in years divisible by
 * 4000, and none in year 0. From 4000-03-01 every date was a day off from
 * the Gregorian calendar (4000-02-29 didn't exist), which StrToCDate and
 * IsoFromTimespec use. It is now the proleptic Gregorian calendar, with
 * year 0 a leap year, and day numbers of years 1 to 3999 are unchanged. */

Bool IsLeap(I64 y)
{
  return (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
}

I64 MonLen(I64 y, I64 m)
{
  if (m == 2) {
    if (IsLeap(y)) return 29;
    return 28;
  }
  if (m == 4 || m == 6 || m == 9 || m == 11) return 30;
  return 31;
}

CDate MkDay(I64 y, I64 mo, I64 d)
{
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = y;
  ds.mon = mo;
  ds.day_of_mon = d;
  return Struct2Date(&ds);
}

Bool DayIs(I64 date, I64 y, I64 mo, I64 d, I64 dow)
{
  CDateStruct ds;
  CDate c = 0;
  c.date = date;
  Date2Struct(&ds, c);
  if (ds.year == y && ds.mon == mo && ds.day_of_mon == d &&
      ds.day_of_week == dow && MkDay(y, mo, d).date == date)
    return TRUE;
  "day %d: got %d-%d-%d (%d), want %d-%d-%d (%d)\n", date, ds.year,
    ds.mon, ds.day_of_mon, ds.day_of_week, y, mo, d, dow;
  return FALSE;
}

Bool StrIs(U8 *s, U8 *want)
{
  Bool ok = !StrCmp(s, want);
  if (!ok) "got %s want %s\n", s, want;
  Free(s);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 10, y, m, d, n, bad, dow;
  CDateStruct ds;
  CDate c = 0;
  "Test - proleptic Gregorian calendar: ";

  /* Day numbers of years 1-3999 are what they were */
  if (YearStartDate(1) == 365 && YearStartDate(2000) == 730484 &&
      DayIs(365, 1, 1, 1, 1) && DayIs(730484, 2000, 1, 1, 6)) correct++;

  /* 4000 and 8000 are leap years, the days after them line up again */
  if (DayIs(1461028, 4000, 2, 29, 2) && DayIs(1461029, 4000, 3, 1, 3) &&
      DayIs(2921998, 8000, 2, 29, 2) && DayIs(3652423, 9999, 12, 31, 5))
    correct++;

  /* Year 0 is a leap year and day 0 its Jan 2; years before it follow
   * the same 400-year rule */
  if (YearStartDate(0) == -1 && DayIs(-1, 0, 1, 1, 6) &&
      DayIs(0, 0, 1, 2, 0) && DayIs(58, 0, 2, 29, 2) &&
      DayIs(365, 1, 1, 1, 1)) correct++;

  bad = 0;
  for (y = -1200; y <= 10000; y++) {
    n = YearStartDate(y + 1) - YearStartDate(y);
    if ((IsLeap(y) && n != 366) || (!IsLeap(y) && n != 365)) {
      if (!bad) "year %d has %d days\n", y, n;
      bad++;
    }
  }
  if (!bad) correct++;

  /* Every day of years 1 to 9999 (and the leap year 0 and the years
   * before it): the next day number, the right weekday, both ways */
  bad = 0;
  n = YearStartDate(-401);
  dow = DayOfWeek(n);
  for (y = -401; y <= 9999; y++) {
    for (m = 1; m <= 12; m++) {
      for (d = 1; d <= MonLen(y, m); d++) {
        c.date = n;
        Date2Struct(&ds, c);
        if (ds.year != y || ds.mon != m || ds.day_of_mon != d ||
            ds.day_of_week != dow || MkDay(y, m, d).date != n) {
          if (!bad) "%d-%d-%d: day %d came out %d-%d-%d\n", y, m, d, n,
            ds.year, ds.mon, ds.day_of_mon;
          bad++;
        }
        n++;
        dow = (dow + 1) % 7;
      }
    }
  }
  if (!bad) correct++;

  /* The parser, which always used Gregorian leap years, now agrees */
  c = StrToCDate("4000-02-29", "%Y-%m-%d");
  if (c.date == 1461028 && StrIs(MPrintDate(c), "02/29/4000")) correct++;
  if (StrToCDate("4000-03-01", "%Y-%m-%d").date == 1461029 &&
      StrToCDate("0000-02-29", "%Y-%m-%d").date == 58 &&
      StrToCDate("9999-12-31", "%Y-%m-%d").date == 3652423) correct++;

  /* The month and year helpers */
  if (LastDayOfMon(1461000) == 1461028 && FirstDayOfMon(1461028) == 1461000 &&
      LastDayOfYear(1461028) - FirstDayOfYear(1461028) == 365 &&
      LastDayOfYear(0) == 364 && FirstDayOfYear(364) == -1) correct++;

  /* Carrying across Feb 29 of 4000 */
  if (StrIs(MPrintDate(MkDay(4000, 2, 30)), "03/01/4000") &&
      StrIs(MPrintDate(MkDay(4000, 3, 0)), "02/29/4000")) correct++;

  if (StrIs(MPrintDate(MkDay(4000, 12, 31)), "12/31/4000") &&
      MkDay(4001, 1, 1).date - MkDay(4000, 1, 1).date == 366) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
