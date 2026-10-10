#include "testhelper.ktm"

/* Date2Struct estimates the year from the day number and only corrected
 * an estimate that was too high. For 182 years in 1..10000 (1903,
 * 1904..1932 every 4 years, 2304..2320, ...) the estimate for Jan 1 was
 * one too low and the day came out as Dec 32 of the year before. */

CDate MkDay(I64 y, I64 mo, I64 d)
{
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = y;
  ds.mon = mo;
  ds.day_of_mon = d;
  return Struct2Date(&ds);
}

Bool Is(CDate c, I64 y, I64 mo, I64 d)
{
  CDateStruct ds;
  Date2Struct(&ds, c);
  if (ds.year == y && ds.mon == mo && ds.day_of_mon == d) return TRUE;
  "got %d-%d-%d want %d-%d-%d\n", ds.year, ds.mon, ds.day_of_mon, y, mo, d;
  return FALSE;
}

/* Jan 1 of `y` and the day before it */
Bool YearEdge(I64 y)
{
  CDate c = MkDay(y, 1, 1);
  if (!Is(c, y, 1, 1)) return FALSE;
  c.date--;
  return Is(c, y - 1, 12, 31);
}

I32 Main()
{
  I64 correct = 0, tests = 7, y, d, bad;
  CDateStruct ds, prev;
  CDate c = 0;
  "Test - Date2Struct at the start of a year: ";

  if (YearEdge(1903)) correct++;
  if (YearEdge(1904)) correct++;

  bad = 0;
  for (y = 1904; y <= 1932; y += 4) if (!YearEdge(y)) bad++;
  if (!bad) correct++;

  bad = 0;
  for (y = 2304; y <= 2320; y += 4) if (!YearEdge(y)) bad++;
  if (!bad) correct++;

  /* Every year boundary from 1 to 10000 */
  bad = 0;
  for (y = 2; y <= 10000; y++) if (!YearEdge(y)) bad++;
  if (!bad) correct++;
  else "%d bad years\n", bad;

  /* Every day 1890..2410: round trips and follows the day before */
  bad = 0;
  c.date = MkDay(1890, 1, 1).date;
  Date2Struct(&prev, c);
  for (d = c.date + 1; d < MkDay(2410, 1, 1).date; d++) {
    c.date = d;
    Date2Struct(&ds, c);
    if (Struct2Date(&ds).date != d) bad++;
    if (ds.day_of_mon < 1 || ds.day_of_mon > 31 || ds.mon < 1 || ds.mon > 12)
      bad++;
    if (ds.day_of_mon == 1) {
      if (!(ds.mon == prev.mon + 1 && ds.year == prev.year) &&
          !(ds.mon == 1 && prev.mon == 12 && ds.year == prev.year + 1))
        bad++;
    } else if (ds.day_of_mon != prev.day_of_mon + 1 || ds.mon != prev.mon) {
      bad++;
    }
    prev = ds;
  }
  if (!bad) correct++;
  else "%d bad days\n", bad;

  /* MPrintDate goes through Date2Struct */
  U8 *s = MPrintDate(MkDay(1904, 1, 1));
  if (!StrCmp(s, "01/01/1904")) correct++;
  else "MPrintDate: %s\n", s;
  Free(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
