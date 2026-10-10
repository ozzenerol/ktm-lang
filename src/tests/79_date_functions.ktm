#include "testhelper.ktm"

/* Date2Struct, MPrintDate and MPrintTime segfaulted. The optimiser
 * folded `iadd t, param, k` into a later store's addressing mode across
 * a call, so `_ds->day_of_week = DayOfWeek(date)` stored through rdi
 * after DayOfWeek had clobbered it. Once that was fixed the time fields
 * were wrong (ModU64 did not divide) and FirstDayOfMon / LastDayOfMon
 * returned the wrong days. On the arm64 JIT, `Date2Struct(&ds, Now())`
 * in this big Main frame lost the CDate: its 16-byte slot sat past the
 * ldur/stur range and the register-offset store/load was not emitted. */

class Pair
{
  I64 a, b;
};

I64 AddOne(I64 x) { return x + 1; }
U0 SetB(Pair *p, I64 y) { p->b = AddOne(y); }

CDate MkDate(I64 y, I64 mo, I64 d, I64 h, I64 mi, I64 s)
{
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = y;
  ds.mon = mo;
  ds.day_of_mon = d;
  ds.hour = h;
  ds.min = mi;
  ds.sec = s;
  return Struct2Date(&ds);
}

I64 MkDay(I64 y, I64 mo, I64 d)
{
  CDate c = MkDate(y, mo, d, 0, 0, 0);
  return c.date;
}

/* Round trip through Date2Struct and check every field. */
Bool Check(I64 y, I64 mo, I64 d, I64 h, I64 mi, I64 s, I64 dow)
{
  CDateStruct ds;
  Date2Struct(&ds, MkDate(y, mo, d, h, mi, s));
  return ds.year == y && ds.mon == mo && ds.day_of_mon == d &&
         ds.hour == h && ds.min == mi && ds.sec == s &&
         ds.day_of_week == dow;
}

Bool SameHour(CDateStruct *a, CDateStruct *b)
{
  return a->year == b->year && a->mon == b->mon &&
         a->day_of_mon == b->day_of_mon &&
         a->day_of_week == b->day_of_week && a->hour == b->hour;
}

Bool StrIs(U8 *got, U8 *want)
{
  Bool ok = !StrCmp(got, want);
  if (!ok) "Failed - got '%s' want '%s'\n", got, want;
  Free(got);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 16;
  CDateStruct ds, now_ds;
  CDate c;
  I64 d;

  "Test - CDate functions (Date2Struct, MPrintDate, MPrintTime): ";

  /* The miscompile itself: field store through a param after a call. */
  Pair pr;
  pr.b = 0;
  SetB(&pr, 41);
  if (pr.b == 42) correct++;

  /* Known dates round trip, with the right day of week (0 = Sunday). */
  if (Check(2000, 1, 1, 0, 0, 0, 6)) correct++;
  if (Check(2024, 2, 29, 12, 34, 56, 4)) correct++;
  if (Check(1999, 12, 31, 23, 59, 59, 5)) correct++;
  if (Check(1970, 1, 1, 0, 0, 1, 4)) correct++;
  if (Check(2023, 2, 28, 7, 8, 9, 2)) correct++;

  /* formatting. */
  c = MkDate(2024, 2, 29, 12, 34, 56);
  if (StrIs(MPrintDate(c), "02/29/2024")) correct++;
  if (StrIs(MPrintTime(c), "12:34:56")) correct++;
  if (StrIs(MStrPrint("%D %T", c, c), "02/29/2024 12:34:56")) correct++;

  /* Day helpers. */
  d = MkDay(2024, 2, 15);
  if (FirstDayOfMon(d) == MkDay(2024, 2, 1) &&
      LastDayOfMon(d) == MkDay(2024, 2, 29)) correct++;
  d = MkDay(1999, 12, 15);
  if (FirstDayOfMon(d) == MkDay(1999, 12, 1) &&
      LastDayOfMon(d) == MkDay(1999, 12, 31)) correct++;
  if (FirstDayOfYear(d) == YearStartDate(1999) &&
      LastDayOfYear(d) == YearStartDate(2000) - 1) correct++;
  if (YearStartDate(2025) - YearStartDate(2024) == 366 &&
      DayOfWeek(YearStartDate(2000)) == 6) correct++;

  /* Parsing into a CDate and back. */
  c = StrToCDate("2024-02-29 13:45:07", "%Y-%m-%d %H:%M:%s");
  Date2Struct(&ds, c);
  if (ds.year == 2024 && ds.mon == 2 && ds.day_of_mon == 29 &&
      ds.hour == 13 && ds.min == 45 && ds.sec == 7) correct++;

  /* Now() agrees with the libc broken-down local time. Read it on
   * both sides of NowDateTimeStruct so an hour rolling over in between
   * can't fail the test. */
  CDateStruct ds2;
  Date2Struct(&ds, Now());
  NowDateTimeStruct(&now_ds);
  Date2Struct(&ds2, Now());
  if (SameHour(&ds, &now_ds) || SameHour(&ds2, &now_ds)) correct++;
  if (ds.year >= 2024 && ds.min < 60 && ds.sec < 60) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
