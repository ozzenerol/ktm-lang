module date;
import cstr, libc, mem, os, str;

/* Clocks, sleeping, Unix times as text (strftime) and HTTP dates */

#ifdef IS_MACOS
const i32 DATE_CLOCK_MONOTONIC = 6;
#else
const i32 DATE_CLOCK_MONOTONIC = 1;
#endif

extern "c" i32 clock_gettime(i32 id, libc.timespec *ts);
extern "c" i32 nanosleep(libc.timespec *req, libc.timespec *rem);
extern "c" libc.tm *gmtime_r(i64 *t, libc.tm *out);
extern "c" libc.tm *localtime_r(i64 *t, libc.tm *out);
extern "c" u64 strftime(u8 *s, u64 max, u8 *fmt, libc.tm *tm);

/* Milliseconds on a clock that only goes forward (not the time of day:
 * changing the system clock doesn't move it). For timeouts and deadlines:
 * `i64 until = date.monoMs() + 5000;` */
pub i64 monoMs()
{
    libc.timespec ts;
    clock_gettime(DATE_CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* The same clock in nanoseconds, for measuring short things */
pub i64 monoNs()
{
    libc.timespec ts;
    clock_gettime(DATE_CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000000000 + ts.tv_nsec;
}

/* Sleep ms milliseconds (a signal doesn't cut it short); 0 or less
 * returns at once */
pub void sleepMs(i64 ms)
{
    if (ms <= 0) { return; }
    libc.timespec req, rem;
    req.tv_sec = ms / 1000;
    req.tv_nsec = ms % 1000 * 1000000;
    while (nanosleep(&req, &rem) == -1 && os.errno() == libc.EINTR) { req = rem; }
}

/* Seconds since 1970-01-01 00:00:00 UTC (the Unix time) */
pub i64 unixNow()
{
    libc.timeval tv;
    libc.gettimeofday(&tv, null);
    return tv.tv_sec;
}

/* The Unix time t as text by strftime's format (%Y-%m-%d %H:%M:%S, %a,
 * %b, %z, ...), in UTC or (utc false) in local time */
pub String formatUnix(i64 t, u8[] fmt, bool utc = true)
{
    String out;
    if (!fmt.len) { return out; }
    libc.tm parts;
    if (utc) { gmtime_r(&t, &parts); }
    else { localtime_r(&t, &parts); }
    String f = str.from(fmt);
    defer f.free();
    f.appendByte(' ');   /* so a result is never empty: 0 then means "too small" */
    for (i64 cap = 128; cap <= 1 << 20; cap *= 2) {
        u8 *buf = mem.alloc(cap);
        u64 n = strftime(buf, cap, f.ptr, &parts);
        if (n > 0) {
            buf[n - 1] = 0;   /* the added space */
            return str.adopt(buf, n - 1);
        }
        mem.free(buf);
    }
    return out;
}

/* the length of an HTTP date: "Sun, 06 Nov 1994 08:49:37 GMT" */
pub const i64 HTTP_DATE_LEN = 29;

u8 *gDateDays[7] = { "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat" };

u8 *gDateMonths[12] = { "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec" };

/* Days since 1970-01-01 of a date (y, m 1-12, d 1-31), the inverse of
 * civilFromDays */
static i64 daysFromCivil(i64 y, i64 m, i64 d)
{
    if (m <= 2) { y--; }
    i64 era = (y >= 0 ? y : y - 399) / 400;
    i64 yoe = y - era * 400;
    i64 mp = m > 2 ? m - 3 : m + 9;
    i64 doy = (153 * mp + 2) / 5 + d - 1;
    i64 doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return era * 146097 + doe - 719468;
}

/* two digits of v at p */
static void date2(u8 *p, i64 v)
{
    p[0] = (u8)('0' + v / 10 % 10);
    p[1] = (u8)('0' + v % 10);
}

/* The Unix time t as an HTTP date written to out (room for
 * HTTP_DATE_LEN bytes, no NUL), without allocating: what httpDate
 * returns, for servers that write one per reply. Returns HTTP_DATE_LEN. */
pub i64 httpDateTo(i64 t, u8 *out)
{
    i64 days = t / 86400, rem = t % 86400;
    if (rem < 0) {
        rem += 86400;
        days--;
    }
    i64 y, m, d;
    civilFromDays(days, &y, &m, &d);
    i64 wday = (days % 7 + 11) % 7;   /* 1970-01-01 was a Thursday */
    mem.copy(out, gDateDays[wday], 3);
    out[3] = ',';
    out[4] = ' ';
    date2(out + 5, d);
    out[7] = ' ';
    mem.copy(out + 8, gDateMonths[m - 1], 3);
    out[11] = ' ';
    date2(out + 12, y / 100);
    date2(out + 14, y % 100);
    out[16] = ' ';
    date2(out + 17, rem / 3600);
    out[19] = ':';
    date2(out + 20, rem % 3600 / 60);
    out[22] = ':';
    date2(out + 23, rem % 60);
    mem.copy(out + 25, " GMT", 4);
    return HTTP_DATE_LEN;
}

/* The Unix time t as an HTTP date (RFC 9110 IMF-fixdate, always UTC and
 * in English whatever the locale): "Sun, 06 Nov 1994 08:49:37 GMT" */
pub String httpDate(i64 t)
{
    u8 buf[HTTP_DATE_LEN];
    httpDateTo(t, buf);
    return str.from(buf[0:HTTP_DATE_LEN]);
}

/* ---- parsing an HTTP date ---- */

static bool dateDigits(u8[] s, i64 *at, i64 n, i64 *v)
{
    *v = 0;
    for (i64 i = 0; i < n; i++) {
        if (*at >= s.len || s[*at] < '0' || s[*at] > '9') { return false; }
        *v = *v * 10 + s[(*at)++] - '0';
    }
    return true;
}

static bool dateLit(u8[] s, i64 *at, u8[] lit)
{
    if (*at + lit.len > s.len || !str.equals(s[*at:*at + lit.len], lit)) { return false; }
    *at += lit.len;
    return true;
}

static bool dateMonth(u8[] s, i64 *at, i64 *m)
{
    for (i64 i = 0; i < 12; i++) {
        if (dateLit(s, at, cstr.view(gDateMonths[i]))) {
            *m = i + 1;
            return true;
        }
    }
    return false;
}

/* hh:mm:ss */
static bool dateClock(u8[] s, i64 *at, i64 *secs)
{
    i64 h, mi, se;
    if (!dateDigits(s, at, 2, &h) || !dateLit(s, at, ":") || !dateDigits(s, at, 2, &mi)
        || !dateLit(s, at, ":") || !dateDigits(s, at, 2, &se)) { return false; }
    if (h > 23 || mi > 59 || se > 60) { return false; }
    *secs = h * 3600 + mi * 60 + se;
    return true;
}

/* An HTTP date as a Unix time in *t: the IMF-fixdate httpDate writes and
 * the two older forms RFC 9110 says to accept ("Sunday, 06-Nov-94
 * 08:49:37 GMT" and asctime's "Sun Nov  6 08:49:37 1994"). False, *t
 * untouched, when s is none of them. */
pub bool parseHttpDate(u8[] s, i64 *t)
{
    i64 at = 0, y, m, d, secs;
    s = str.trim(s);
    i64 comma = str.find(s, ",");
    if (comma == 3) {                       /* Sun, 06 Nov 1994 08:49:37 GMT */
        if (s.len < 5 || s[4] != ' ') { return false; }
        at = 5;
        if (!dateDigits(s, &at, 2, &d) || !dateLit(s, &at, " ") || !dateMonth(s, &at, &m)
            || !dateLit(s, &at, " ") || !dateDigits(s, &at, 4, &y) || !dateLit(s, &at, " ")
            || !dateClock(s, &at, &secs) || !dateLit(s, &at, " GMT")) { return false; }
    } else if (comma > 3) {                 /* Sunday, 06-Nov-94 08:49:37 GMT */
        at = comma + 1;
        if (!dateLit(s, &at, " ") || !dateDigits(s, &at, 2, &d) || !dateLit(s, &at, "-")
            || !dateMonth(s, &at, &m) || !dateLit(s, &at, "-") || !dateDigits(s, &at, 2, &y)
            || !dateLit(s, &at, " ") || !dateClock(s, &at, &secs) || !dateLit(s, &at, " GMT")) {
            return false;
        }
        y += y < 70 ? 2000 : 1900;          /* RFC 9110: 50+ years ahead means the past */
    } else if (comma == -1 && s.len >= 4 && s[3] == ' ') {   /* Sun Nov  6 08:49:37 1994 */
        at = 4;
        if (!dateMonth(s, &at, &m) || !dateLit(s, &at, " ")) { return false; }
        if (at < s.len && s[at] == ' ') { at++; }
        if (at + 1 < s.len && s[at + 1] == ' ') {
            if (!dateDigits(s, &at, 1, &d)) { return false; }
        } else if (!dateDigits(s, &at, 2, &d)) {
            return false;
        }
        if (!dateLit(s, &at, " ") || !dateClock(s, &at, &secs) || !dateLit(s, &at, " ")
            || !dateDigits(s, &at, 4, &y)) { return false; }
    } else {
        return false;
    }
    if (at != s.len || d < 1 || d > 31) { return false; }
    i64 days = daysFromCivil(y, m, d);
    i64 back, bm, bd;
    civilFromDays(days, &back, &bm, &bd);
    if (bm != m || bd != d) { return false; }   /* 31 Feb */
    *t = days * 86400 + secs;
    return true;
}
