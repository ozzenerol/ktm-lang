import date, test;

/* date.parseParts's %z read any 4 digits, so "+0199" gave zone_diff 199
 * (99 minutes) and "+2500" 2500. An offset is +-hhmm with hh 00-23 and
 * mm 00-59; anything else is a field out of range and fails. */

bool zone(u8 *in, bool wantOk, i64 wantZone) {
    date.Parts ds;
    bool ok = date.parseParts(in, "%Y-%m-%d %z", &ds);
    if (ok != wantOk) {
        printf("\"%s\": ok %d\n", in, ok);
        return false;
    }
    if (ok && ds.zoneDiff != wantZone) {
        printf("\"%s\": zone_diff %d\n", in, ds.zoneDiff);
        return false;
    }
    return true;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - %%z offset range: ");

    tests++;
    if (zone("2024-03-05 +0100", true, 100) && zone("2024-03-05 -0530", true, -530) &&
            zone("2024-03-05 +0000", true, 0) && zone("2024-03-05 +2359", true, 2359) &&
            zone("2024-03-05 -1200", true, -1200)) {
        correct++;
            }

    tests++;   /* minutes past 59 */
    if (zone("2024-03-05 +0199", false, 0) && zone("2024-03-05 -0060", false, 0) &&
            zone("2024-03-05 +1275", false, 0)) {
        correct++;
            }

    tests++;   /* hours past 23 */
    if (zone("2024-03-05 +2400", false, 0) && zone("2024-03-05 -9999", false, 0)) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
