#include "testhelper.ktm"

import date;

/* Str2CDateStruct: %a/%b never matched (and read past short input), %b
 * stored a 0-based month, 12 PM became hour 24, a format without seconds
 * left sec at -1 (garbage time from StrToCDate), and empty, partial or
 * out-of-range input still returned true. */

bool parse(u8 *s, u8 *f, i64 y, i64 mon, i64 d, i64 h, i64 mi, i64 sec, i64 dow) {
    date.Parts t;
    if (!date.parseParts(s, f, &t)) {
        printf("\"%s\" \"%s\": failed\n", s, f);
        return false;
    }
    bool ok = t.year == y && (i64)t.mon == mon && (i64)t.dayOfMon == d &&
                        (i64)t.hour == h && (i64)t.min == mi && (i64)t.sec == sec &&
                        (i64)t.dayOfWeek == dow;
    if (!ok) { printf("\"%s\" \"%s\": y=%d m=%d d=%d %d:%d:%d dow=%d\n", s, f, t.year,
                     (i64)t.mon, (i64)t.dayOfMon, (i64)t.hour, (i64)t.min, (i64)t.sec,
                     (i64)t.dayOfWeek);
    }
    return ok;
}

bool fails(u8 *s, u8 *f) {
    date.Parts t;
    if (date.parseParts(s, f, &t)) {
        printf("\"%s\" \"%s\": accepted\n", s, f);
        return false;
    }
    return true;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - string date parsing: ");

    tests++;
    if (parse("2024-03-15", "%Y-%m-%d", 2024, 3, 15, 0, 0, 0, -1) &&
            parse("15/03/2024", "%d/%m/%Y", 2024, 3, 15, 0, 0, 0, -1) &&
            parse("2024-03-15 13:45:30", "%Y-%m-%d %H:%M:%s", 2024, 3, 15, 13, 45, 30, -1) &&
            parse("2024-03-15 13:45:30", "%Y-%m-%d %H:%M:%S", 2024, 3, 15, 13, 45, 30, -1) &&
            parse("2024-02-29", "%Y-%m-%d", 2024, 2, 29, 0, 0, 0, -1)) { correct++; }

    /* %a / %b: three letters or the full name, any case; %b is 1-based */
    tests++;
    if (parse("Fri 2024", "%a %Y", 2024, -1, -1, 0, 0, 0, 5) &&
            parse("friday 2024", "%a %Y", 2024, -1, -1, 0, 0, 0, 5) &&
            parse("SUN", "%a", -1, -1, -1, 0, 0, 0, 0) &&
            parse("Mar 15 2024", "%b %d %Y", 2024, 3, 15, 0, 0, 0, -1) &&
            parse("15 December 2024", "%d %b %Y", 2024, 12, 15, 0, 0, 0, -1) &&
            parse("Tue, 01 jan 2030", "%a, %d %b %Y", 2030, 1, 1, 0, 0, 0, 2)) { correct++; }

    /* %p */
    tests++;
    if (parse("12:30 PM", "%H:%M %p", -1, -1, -1, 12, 30, 0, -1) &&
            parse("12:05 am", "%H:%M %p", -1, -1, -1, 0, 5, 0, -1) &&
            parse("01:30 PM", "%H:%M %p", -1, -1, -1, 13, 30, 0, -1) &&
            parse("11:59 AM", "%H:%M %p", -1, -1, -1, 11, 59, 0, -1)) { correct++; }

    /* rejected input */
    tests++;
    if (fails("", "%Y") && fails("garbage", "%Y-%m-%d") && fails("2024", "%Y-%m-%d") &&
            fails("2024-03", "%Y-%m-%d") && fails("2024-13-01", "%Y-%m-%d") &&
            fails("2024-00-10", "%Y-%m-%d") && fails("2023-02-29", "%Y-%m-%d") &&
            fails("2024-04-31", "%Y-%m-%d") && fails("2024-03-15 24:00", "%Y-%m-%d %H:%M") &&
            fails("10:60", "%H:%M") && fails("10:00:60", "%H:%M:%s") &&
            fails("13:00 PM", "%H:%M %p") && fails("Fx 2024", "%a %Y") &&
            fails("Mxr 1", "%b %d") && fails("2024/03/15", "%Y-%m-%d") &&
            fails("2024", "%Q") && fails("PM", "%p")) { correct++; }

    /* %% and %z, trailing text ignored */
    date.Parts t;
    tests++;
    if (date.parseParts("15% -0130", "%d%% %z", &t) && t.dayOfMon == 15 &&
            (i64)t.zoneDiff == -130 && date.parseParts("2024-01-02T", "%Y-%m-%d", &t) &&
            t.dayOfMon == 2) { correct++; }
    else { printf("%%/%%z/trailing wrong\n"); }

    /* StrToCDate: seconds default to 0, failure is 0 */
    date.Parts ds;
    date.Date c = date.parse("2024-03-15 10:20", "%Y-%m-%d %H:%M");
    date.toParts(&ds, c);
    tests++;
    if (ds.year == 2024 && ds.mon == 3 && ds.dayOfMon == 15 && ds.hour == 10 &&
            ds.min == 20 && ds.sec == 0 && date.parse("x", "%Y") == 0 &&
            date.parse("10:20", "%H:%M") == 0) { correct++; }
    else { printf("StrToCDate wrong\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
