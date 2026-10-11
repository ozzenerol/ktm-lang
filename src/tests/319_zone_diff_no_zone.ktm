import date, test;

/* date.parseParts left zone_diff at -1 when the format had no %z, the
 * same value as a real -00:01 offset (zone_diff is +-hhmm). It is now
 * CDATE_NO_ZONE, which no offset can be. */

i32 main() {
    i64 correct = 0, tests = 7;
    date.Parts t;
    printf("Test - zone_diff without a zone: ");

    if (date.NO_ZONE < -9999 || date.NO_ZONE > 9999) { correct++; }

    if (date.parseParts("2024-01-02", "%Y-%m-%d", &t) &&
            t.zoneDiff == date.NO_ZONE && t.zoneDiff != -1) { correct++; }
    else { printf("no %%z: %d\n", t.zoneDiff); }

    if (date.parseParts("2024-01-02 -0001", "%Y-%m-%d %z", &t) &&
            t.zoneDiff == -1 && t.zoneDiff != date.NO_ZONE) { correct++; }
    else { printf("-0001: %d\n", t.zoneDiff); }

    if (date.parseParts("+0000", "%z", &t) && t.zoneDiff == 0 &&
            date.parseParts("-0130", "%z", &t) && t.zoneDiff == -130 &&
            date.parseParts("+1400", "%z", &t) && t.zoneDiff == 1400 &&
            date.parseParts("0530", "%z", &t) && t.zoneDiff == 530) { correct++; }

    /* A failed parse has no zone either, also after a %z that matched */
    t.zoneDiff = 100;
    if (!date.parseParts("", "%z", &t) && t.zoneDiff == date.NO_ZONE) {
        correct++;
    }
    if (!date.parseParts("-0001 x", "%z %Y", &t) &&
            t.zoneDiff == date.NO_ZONE) { correct++; }

    /* A struct reused for a format without %z doesn't keep the old zone */
    if (date.parseParts("+0200", "%z", &t) && t.zoneDiff == 200 &&
            date.parseParts("10:30", "%H:%M", &t) && t.zoneDiff == date.NO_ZONE) {
        correct++;
            }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
