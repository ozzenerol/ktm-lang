module date;
import libc, mem, str;

#define TIMESTAMP_FMT "%d-%02d-%02d %02d:%02d:%02d"

pub u8 *formatTime(Date cdt) {
        Parts ds;
        toParts(&ds,cdt);
        return str.format("%02d:%02d:%02d",ds.hour,ds.min,ds.sec);
}

pub u8 *formatDate(Date cdt) {
        Parts ds;
        toParts(&ds,cdt);
        return str.format("%02d/%02d/%04d",ds.mon,ds.dayOfMon,ds.year);
}

pub u8 *timestampNow()
{// Get a timestamp
        i64 seconds = nowMs() / 1000; 
        u8 *timestamp = mem.alloc(sizeof(u8)*32);
        auto ptm = libc.localtime(&seconds);
        auto len = libc.snprintf(timestamp,sizeof(u8)*32,TIMESTAMP_FMT,
                                        ptm.tm_year+1900,ptm.tm_mon+1,ptm.tm_mday,
                                        ptm.tm_hour,ptm.tm_min,ptm.tm_sec);
        timestamp[len] = '\0';
        return timestamp;
}

static u8 *strDateDays[7] = {
        "sunday", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday"
};

static u8 *strDateMonths[12] = {
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
};

/* Read up to `max_digits` decimal digits (after any spaces) */
static bool strDateNum(u8 **_s, i64 max_digits, i64 *_out) {
        u8 *s = *_s;
        i64 v = 0, n = 0;
        while (*s == ' ' || *s == '\t') { s++; }
        while (n < max_digits && *s >= '0' && *s <= '9') {
                v = v * 10 + (*s - '0');
                s++;
                n++;
        }
        if (n == 0) { return false; }
        *_s = s;
        *_out = v;
        return true;
}

/* A day or month name: the full name or its first three letters, any
    * case. Returns the index or -1. */
static i64 strDateName(u8 **_s, u8 **names, i64 count) {
        u8 *s = *_s;
        for (i64 i = 0; i < count; ++i) {
                i64 len = str.len(names[i]);
                if (str.cmpLenNoCase(s, names[i], len) == 0) {
                        *_s = s + len;
                        return i;
                }
        }
        for (i64 i = 0; i < count; ++i) {
                if (str.cmpLenNoCase(s, names[i], 3) == 0) {
                        *_s = s + 3;
                        return i;
                }
        }
        return -1;
}

/* similar to strptime. Conversions: %Y year, %m month (1-12), %d day,
    * %H hour (0-23, or 1-12 with %p), %M minutes, %s or %S seconds, %a day
    * name, %b month name (full or 3 letters), %p AM/PM, %z zone offset, %%.
    * A space in the format matches any run of spaces. Fields the format
    * doesn't set stay -1, except the time fields, which default to 0, and
    * zone_diff, which is CDATE_NO_ZONE without a %z.
    * Returns false for empty input, input that ends before the format, text
    * that doesn't match the format, an unknown conversion or a field out of
    * range; text after the end of the format is ignored. */
pub bool parseParts(u8 *strdate, u8 *format, Parts *t) {
        i64 year = -1, mon = -1, day = -1, hour = 0, min = 0, sec = 0;
        i64 dow = -1, zone = NO_ZONE, v, pm = -1, sign;
        bool hasHour = false;

        /* initialise the date struct's field to -1, error state */
        mem.set(t, -1, sizeof(Parts));
        t.sec10000 = 0;
        t.sec100 = 0;
        t.sec = 0;
        t.min = 0;
        t.hour = 0;
        t.zoneDiff = NO_ZONE;

        if (!strdate || !format || !*strdate) {
                return false;
        }

        while (*format) {
                if (*format == '%') {
                        format++;
                        switch (*format) {
                                case 'a': /* day name */
                                        if ((dow = strDateName(&strdate, strDateDays, 7)) < 0) {
                                                return false;
                                        }
                                case 'b': /* month name */
                                        if ((v = strDateName(&strdate, strDateMonths, 12)) < 0) {
                                                return false;
                                        }
                                        mon = v + 1;
                                case 'd': if (!strDateNum(&strdate, 2, &day)) { return false; }
                                case 'm': if (!strDateNum(&strdate, 2, &mon)) { return false; }
                                case 'Y': if (!strDateNum(&strdate, 4, &year)) { return false; }
                                case 'H':
                                        if (!strDateNum(&strdate, 2, &hour)) { return false; }
                                        hasHour = true;
                                case 'M': if (!strDateNum(&strdate, 2, &min)) { return false; }
                                case 's':
                                case 'S': if (!strDateNum(&strdate, 2, &sec)) { return false; }
                                case 'p': /* am or pm */
                                        while (*strdate == ' ') { strdate++; }
                                        if (str.cmpLenNoCase(strdate, "AM", 2) == 0) {
                                                pm = 0;
                                        } else if (str.cmpLenNoCase(strdate, "PM", 2) == 0) {
                                                pm = 1;
                                        } else {
                                                return false;
                                        }
                                        strdate += 2;
                                case 'z': /* timezone difference, e.g. +0100 */
                                        while (*strdate == ' ') { strdate++; }
                                        sign = 1;
                                        if (*strdate == '+') {
                                                strdate++;
                                        } else if (*strdate == '-') {
                                                sign = -1;
                                                strdate++;
                                        }
                                        if (!strDateNum(&strdate, 4, &zone)) { return false; }
                                        if (zone / 100 > 23 || zone % 100 > 59) { return false; }
                                        zone *= sign;
                                case '%':
                                        if (*strdate != '%') { return false; }
                                        strdate++;
                                default:
                                        return false;
                        }
                        format++;
                } else if (*format == ' ') {
                        while (*format == ' ') { format++; }
                        while (*strdate == ' ' || *strdate == '\t') { strdate++; }
                } else {
                        if (*strdate != *format) {
                                return false;
                        }
                        strdate++;
                        format++;
                }
        }

        if (pm != -1) {
                if (!hasHour || hour < 1 || hour > 12) {
                        return false;
                }
                if (pm == 0 && hour == 12) {
                        hour = 0;
                } else if (pm == 1 && hour != 12) {
                        hour += 12;
                }
        }
        if (hour > 23 || min > 59 || sec > 59) {
                return false;
        }
        if (mon != -1 && (mon < 1 || mon > 12)) {
                return false;
        }
        if (day != -1) {
                i64 maxDay = 31;
                if (mon == 4 || mon == 6 || mon == 9 || mon == 11) {
                        maxDay = 30;
                } else if (mon == 2) {
                        maxDay = 29;
                        if (year != -1 &&
                                        !((year % 4 == 0 && year % 100 != 0) || year % 400 == 0)) {
                                maxDay = 28;
                        }
                }
                if (day < 1 || day > maxDay) {
                        return false;
                }
        }

        t.year = year;
        t.mon = mon;
        t.dayOfMon = day;
        t.dayOfWeek = dow;
        t.hour = hour;
        t.min = min;
        t.sec = sec;
        t.zoneDiff = zone;
        return true;
}

pub Date parse(u8 *_str, u8 *fmt)
{// Parse with Str2CDateStruct into a CDate; 0 if that fails or the
    // format doesn't give the year, month and day
        Parts ds;
        if (!parseParts(_str,fmt,&ds)) {
                return 0;
        }
        if ((i64)ds.year == -1 || (i64)ds.mon == -1 || (i64)ds.dayOfMon == -1) {
                return 0;
        }
        return fromParts(&ds);
}
