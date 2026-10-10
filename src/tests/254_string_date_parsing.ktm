#include "testhelper.ktm"

/* Str2CDateStruct: %a/%b never matched (and read past short input), %b
 * stored a 0-based month, 12 PM became hour 24, a format without seconds
 * left sec at -1 (garbage time from StrToCDate), and empty, partial or
 * out-of-range input still returned TRUE. */

Bool Parse(U8 *s, U8 *f, I64 y, I64 mon, I64 d, I64 h, I64 mi, I64 sec, I64 dow)
{
  CDateStruct t;
  if (!Str2CDateStruct(s, f, &t)) {
    "\"%s\" \"%s\": failed\n", s, f;
    return FALSE;
  }
  Bool ok = t.year == y && t.mon(I64) == mon && t.day_of_mon(I64) == d &&
            t.hour(I64) == h && t.min(I64) == mi && t.sec(I64) == sec &&
            t.day_of_week(I64) == dow;
  if (!ok) "\"%s\" \"%s\": y=%d m=%d d=%d %d:%d:%d dow=%d\n", s, f, t.year,
           t.mon(I64), t.day_of_mon(I64), t.hour(I64), t.min(I64), t.sec(I64),
           t.day_of_week(I64);
  return ok;
}

Bool Fails(U8 *s, U8 *f)
{
  CDateStruct t;
  if (Str2CDateStruct(s, f, &t)) {
    "\"%s\" \"%s\": accepted\n", s, f;
    return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - string date parsing: ";

  tests++;
  if (Parse("2024-03-15", "%Y-%m-%d", 2024, 3, 15, 0, 0, 0, -1) &&
      Parse("15/03/2024", "%d/%m/%Y", 2024, 3, 15, 0, 0, 0, -1) &&
      Parse("2024-03-15 13:45:30", "%Y-%m-%d %H:%M:%s", 2024, 3, 15, 13, 45, 30, -1) &&
      Parse("2024-03-15 13:45:30", "%Y-%m-%d %H:%M:%S", 2024, 3, 15, 13, 45, 30, -1) &&
      Parse("2024-02-29", "%Y-%m-%d", 2024, 2, 29, 0, 0, 0, -1)) correct++;

  /* %a / %b: three letters or the full name, any case; %b is 1-based */
  tests++;
  if (Parse("Fri 2024", "%a %Y", 2024, -1, -1, 0, 0, 0, 5) &&
      Parse("friday 2024", "%a %Y", 2024, -1, -1, 0, 0, 0, 5) &&
      Parse("SUN", "%a", -1, -1, -1, 0, 0, 0, 0) &&
      Parse("Mar 15 2024", "%b %d %Y", 2024, 3, 15, 0, 0, 0, -1) &&
      Parse("15 December 2024", "%d %b %Y", 2024, 12, 15, 0, 0, 0, -1) &&
      Parse("Tue, 01 jan 2030", "%a, %d %b %Y", 2030, 1, 1, 0, 0, 0, 2)) correct++;

  /* %p */
  tests++;
  if (Parse("12:30 PM", "%H:%M %p", -1, -1, -1, 12, 30, 0, -1) &&
      Parse("12:05 am", "%H:%M %p", -1, -1, -1, 0, 5, 0, -1) &&
      Parse("01:30 PM", "%H:%M %p", -1, -1, -1, 13, 30, 0, -1) &&
      Parse("11:59 AM", "%H:%M %p", -1, -1, -1, 11, 59, 0, -1)) correct++;

  /* rejected input */
  tests++;
  if (Fails("", "%Y") && Fails("garbage", "%Y-%m-%d") && Fails("2024", "%Y-%m-%d") &&
      Fails("2024-03", "%Y-%m-%d") && Fails("2024-13-01", "%Y-%m-%d") &&
      Fails("2024-00-10", "%Y-%m-%d") && Fails("2023-02-29", "%Y-%m-%d") &&
      Fails("2024-04-31", "%Y-%m-%d") && Fails("2024-03-15 24:00", "%Y-%m-%d %H:%M") &&
      Fails("10:60", "%H:%M") && Fails("10:00:60", "%H:%M:%s") &&
      Fails("13:00 PM", "%H:%M %p") && Fails("Fx 2024", "%a %Y") &&
      Fails("Mxr 1", "%b %d") && Fails("2024/03/15", "%Y-%m-%d") &&
      Fails("2024", "%Q") && Fails("PM", "%p")) correct++;

  /* %% and %z, trailing text ignored */
  CDateStruct t;
  tests++;
  if (Str2CDateStruct("15% -0130", "%d%% %z", &t) && t.day_of_mon == 15 &&
      t.zone_diff(I64) == -130 && Str2CDateStruct("2024-01-02T", "%Y-%m-%d", &t) &&
      t.day_of_mon == 2) correct++;
  else "%%/%%z/trailing wrong\n";

  /* StrToCDate: seconds default to 0, failure is 0 */
  CDateStruct ds;
  CDate c = StrToCDate("2024-03-15 10:20", "%Y-%m-%d %H:%M");
  Date2Struct(&ds, c);
  tests++;
  if (ds.year == 2024 && ds.mon == 3 && ds.day_of_mon == 15 && ds.hour == 10 &&
      ds.min == 20 && ds.sec == 0 && StrToCDate("x", "%Y") == 0 &&
      StrToCDate("10:20", "%H:%M") == 0) correct++;
  else "StrToCDate wrong\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
