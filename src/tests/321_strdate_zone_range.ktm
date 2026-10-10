#include "testhelper.ktm"

/* Str2CDateStruct's %z read any 4 digits, so "+0199" gave zone_diff 199
 * (99 minutes) and "+2500" 2500. An offset is +-hhmm with hh 00-23 and
 * mm 00-59; anything else is a field out of range and fails. */

Bool Zone(U8 *in, Bool want_ok, I64 want_zone)
{
  CDateStruct ds;
  Bool ok = Str2CDateStruct(in, "%Y-%m-%d %z", &ds);
  if (ok != want_ok) {
    "\"%s\": ok %d\n", in, ok;
    return FALSE;
  }
  if (ok && ds.zone_diff != want_zone) {
    "\"%s\": zone_diff %d\n", in, ds.zone_diff;
    return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - %%z offset range: ";

  tests++;
  if (Zone("2024-03-05 +0100", TRUE, 100) && Zone("2024-03-05 -0530", TRUE, -530) &&
      Zone("2024-03-05 +0000", TRUE, 0) && Zone("2024-03-05 +2359", TRUE, 2359) &&
      Zone("2024-03-05 -1200", TRUE, -1200))
    correct++;

  tests++;   /* minutes past 59 */
  if (Zone("2024-03-05 +0199", FALSE, 0) && Zone("2024-03-05 -0060", FALSE, 0) &&
      Zone("2024-03-05 +1275", FALSE, 0))
    correct++;

  tests++;   /* hours past 23 */
  if (Zone("2024-03-05 +2400", FALSE, 0) && Zone("2024-03-05 -9999", FALSE, 0))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
