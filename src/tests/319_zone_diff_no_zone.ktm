#include "testhelper.ktm"

/* Str2CDateStruct left zone_diff at -1 when the format had no %z, the
 * same value as a real -00:01 offset (zone_diff is +-hhmm). It is now
 * CDATE_NO_ZONE, which no offset can be. */

I32 Main()
{
  I64 correct = 0, tests = 7;
  CDateStruct t;
  "Test - zone_diff without a zone: ";

  if (CDATE_NO_ZONE < -9999 || CDATE_NO_ZONE > 9999) correct++;

  if (Str2CDateStruct("2024-01-02", "%Y-%m-%d", &t) &&
      t.zone_diff == CDATE_NO_ZONE && t.zone_diff != -1) correct++;
  else "no %%z: %d\n", t.zone_diff;

  if (Str2CDateStruct("2024-01-02 -0001", "%Y-%m-%d %z", &t) &&
      t.zone_diff == -1 && t.zone_diff != CDATE_NO_ZONE) correct++;
  else "-0001: %d\n", t.zone_diff;

  if (Str2CDateStruct("+0000", "%z", &t) && t.zone_diff == 0 &&
      Str2CDateStruct("-0130", "%z", &t) && t.zone_diff == -130 &&
      Str2CDateStruct("+1400", "%z", &t) && t.zone_diff == 1400 &&
      Str2CDateStruct("0530", "%z", &t) && t.zone_diff == 530) correct++;

  /* A failed parse has no zone either, also after a %z that matched */
  t.zone_diff = 100;
  if (!Str2CDateStruct("", "%z", &t) && t.zone_diff == CDATE_NO_ZONE)
    correct++;
  if (!Str2CDateStruct("-0001 x", "%z %Y", &t) &&
      t.zone_diff == CDATE_NO_ZONE) correct++;

  /* A struct reused for a format without %z doesn't keep the old zone */
  if (Str2CDateStruct("+0200", "%z", &t) && t.zone_diff == 200 &&
      Str2CDateStruct("10:30", "%H:%M", &t) && t.zone_diff == CDATE_NO_ZONE)
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
