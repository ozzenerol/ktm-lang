import test;

import date, mem;

/* NowDateTimeStruct set sec10000 to usec/100 (0-9999), but Struct2Date
 * and Date2Struct use it as the ten-thousandths below sec100 (0-99), so
 * the fraction of a second was counted twice: Now() ran up to a second
 * fast and, just before midnight, wrapped to 00:00 of the same day.
 * zone_diff was left uninitialised; it is now the UTC offset as +-hhmm. */

extern "c" i32 usleep(u32 us);
extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);
extern "c" void tzset();

i64 zoneFor(u8 *tz) {
    date.Parts ds;
    setenv("TZ", tz, 1);
    tzset();
    mem.set(&ds, 0x55, sizeof(date.Parts));
    date.nowParts(&ds);
    return ds.zoneDiff;
}

i32 main() {
    i64 correct = 0, tests = 7, i, badRange = 0, badTrip = 0;
    date.Parts ds, back;
    date.Date c;
    printf("Test - Now() sub-second fields: ");

    /* Samples spread over ~1.2 s, so both halves of a second are seen */
    for (i = 0; i < 60; i++) {
        date.nowParts(&ds);
        if (ds.sec10000 > 99 || ds.sec100 > 99) { badRange++; }
        c = date.fromParts(&ds);
        date.toParts(&back, c);
        if (back.hour != ds.hour || back.min != ds.min || back.sec != ds.sec ||
                back.sec100 != ds.sec100 || back.dayOfMon != ds.dayOfMon) {
            if (badTrip < 3) { printf("%02d:%02d:%02d.%02d%02d -> %02d:%02d:%02d.%02d\n",
                    ds.hour, ds.min, ds.sec, ds.sec100, ds.sec10000,
                    back.hour, back.min, back.sec, back.sec100);
            }
            badTrip++;
        }
        usleep(20000);
    }
    if (!badRange) { correct++; }
    if (!badTrip) { correct++; }

    /* The last representable moment of a day stays on that day */
    mem.set(&ds, 0, sizeof(date.Parts));
    ds.year = 2026; ds.mon = 10; ds.dayOfMon = 9;
    ds.hour = 23; ds.min = 59; ds.sec = 59; ds.sec100 = 99; ds.sec10000 = 99;
    c = date.fromParts(&ds);
    date.toParts(&back, c);
    if (back.dayOfMon == 9 && back.hour == 23 && back.min == 59 &&
            back.sec == 59 && back.sec100 == 99) { correct++; }
    ds.hour = 0; ds.min = 0; ds.sec = 0; ds.sec100 = 0; ds.sec10000 = 0;
    if (date.fromParts(&ds).date == c.date) { correct++; }

    /* zone_diff is the UTC offset as +-hhmm */
    if (zoneFor("ABC-5:30") == 530) { correct++; }
    if (zoneFor("ABC+1:30") == -130) { correct++; }
    if (zoneFor("UTC0") == 0) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
