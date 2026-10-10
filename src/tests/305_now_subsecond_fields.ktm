#include "testhelper.ktm"

/* NowDateTimeStruct set sec10000 to usec/100 (0-9999), but Struct2Date
 * and Date2Struct use it as the ten-thousandths below sec100 (0-99), so
 * the fraction of a second was counted twice: Now() ran up to a second
 * fast and, just before midnight, wrapped to 00:00 of the same day.
 * zone_diff was left uninitialised; it is now the UTC offset as +-hhmm. */

extern "c" I32 usleep(U32 us);
extern "c" I32 setenv(U8 *name, U8 *value, I32 overwrite);
extern "c" U0 tzset();

I64 ZoneFor(U8 *tz)
{
  CDateStruct ds;
  setenv("TZ", tz, 1);
  tzset;
  MemSet(&ds, 0x55, sizeof(CDateStruct));
  NowDateTimeStruct(&ds);
  return ds.zone_diff;
}

I32 Main()
{
  I64 correct = 0, tests = 7, i, bad_range = 0, bad_trip = 0;
  CDateStruct ds, back;
  CDate c;
  "Test - Now() sub-second fields: ";

  /* Samples spread over ~1.2 s, so both halves of a second are seen */
  for (i = 0; i < 60; i++) {
    NowDateTimeStruct(&ds);
    if (ds.sec10000 > 99 || ds.sec100 > 99) bad_range++;
    c = Struct2Date(&ds);
    Date2Struct(&back, c);
    if (back.hour != ds.hour || back.min != ds.min || back.sec != ds.sec ||
        back.sec100 != ds.sec100 || back.day_of_mon != ds.day_of_mon) {
      if (bad_trip < 3) "%02d:%02d:%02d.%02d%02d -> %02d:%02d:%02d.%02d\n",
          ds.hour, ds.min, ds.sec, ds.sec100, ds.sec10000,
          back.hour, back.min, back.sec, back.sec100;
      bad_trip++;
    }
    usleep(20000);
  }
  if (!bad_range) correct++;
  if (!bad_trip) correct++;

  /* The last representable moment of a day stays on that day */
  MemSet(&ds, 0, sizeof(CDateStruct));
  ds.year = 2026; ds.mon = 10; ds.day_of_mon = 9;
  ds.hour = 23; ds.min = 59; ds.sec = 59; ds.sec100 = 99; ds.sec10000 = 99;
  c = Struct2Date(&ds);
  Date2Struct(&back, c);
  if (back.day_of_mon == 9 && back.hour == 23 && back.min == 59 &&
      back.sec == 59 && back.sec100 == 99) correct++;
  ds.hour = 0; ds.min = 0; ds.sec = 0; ds.sec100 = 0; ds.sec10000 = 0;
  if (Struct2Date(&ds).date == c.date) correct++;

  /* zone_diff is the UTC offset as +-hhmm */
  if (ZoneFor("ABC-5:30") == 530) correct++;
  if (ZoneFor("ABC+1:30") == -130) correct++;
  if (ZoneFor("UTC0") == 0) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
