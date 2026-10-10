#include "testhelper.ktm"

/* Struct2Date indexed its month table with mon-1 unchecked: month 13
 * read past the table (January of the same year), 0 read before it.
 * A month outside 1-12 now carries into the year, the way a day past
 * the end of a month carries into the next month. Date2Struct read
 * mon_start_days[k+1] before checking k<11. */

CDate MkDate(I64 y, I64 mo, I64 d)
{
  CDateStruct ds;
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = y;
  ds.mon = mo;
  ds.day_of_mon = d;
  return Struct2Date(&ds);
}

Bool Same(I64 y1, I64 m1, I64 d1, I64 y2, I64 m2, I64 d2)
{
  CDate a = MkDate(y1, m1, d1), b = MkDate(y2, m2, d2);
  if (a.date == b.date) return TRUE;
  "%d-%d-%d is day %d, want %d-%d-%d (%d)\n", y1, m1, d1, a.date,
    y2, m2, d2, b.date;
  return FALSE;
}

Bool Is(CDate c, I64 y, I64 mo, I64 d)
{
  CDateStruct ds;
  Date2Struct(&ds, c);
  return ds.year == y && ds.mon == mo && ds.day_of_mon == d;
}

I32 Main()
{
  I64 correct = 0, tests = 10;
  "Test - CDate month bounds: ";

  if (Same(2023, 13, 1, 2024, 1, 1)) correct++;
  if (Same(2024, 14, 29, 2025, 3, 1)) correct++;  /* Feb 2025 has 28 */
  if (Same(2024, 0, 1, 2023, 12, 1)) correct++;
  if (Same(2024, -1, 15, 2023, 11, 15)) correct++;
  if (Same(2024, 25, 1, 2026, 1, 1)) correct++;
  if (Same(2024, -11, 1, 2023, 1, 1)) correct++;
  if (Same(2024, -12, 1, 2022, 12, 1)) correct++;
  /* Days already carried: Jan 32 is Feb 1 */
  if (Same(2024, 1, 32, 2024, 2, 1)) correct++;

  /* December, the last table entry, in a common and a leap year */
  if (Is(MkDate(2023, 12, 31), 2023, 12, 31) &&
      Is(MkDate(2023, 12, 1), 2023, 12, 1)) correct++;
  if (Is(MkDate(2024, 12, 31), 2024, 12, 31) &&
      Is(MkDate(2024, 11, 30), 2024, 11, 30)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
