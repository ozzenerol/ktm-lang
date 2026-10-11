#include "testhelper.ktm"

import mem, str;

/* StrNPrint: StrPrint into a buffer of a known size, with C's snprintf
 * rules - at most size-1 characters, always a '\0' within size, nothing
 * written for size <= 0, longer output cut short, returns dst. */

u8 *show(u8 *buf, i64 n)
{ // the n bytes, '\0' as '0' and others as is
    u8 *s = mem.alloc(n + 1);
    for (i64 i = 0; i < n; i++) {
        if (buf[i]) { s[i] = buf[i]; }
        else        { s[i] = '0'; }
    }
    s[n] = '\0';
    return s;
}

bool bufIs(u8 *buf, i64 n, u8 *want) {
    u8 *s = show(buf, n);
    bool ok = !str.cmp(s, want);
    if (!ok) { printf("got [%s] want [%s]\n", s, want); }
    mem.free(s);
    return ok;
}

i32 main() {
    printf("Test - StrNPrint: ");
    i64 correct = 0, tests = 0, i;
    u8 buf[16];

    /* Fits */
    tests++;
    mem.set(buf, '#', 16);
    if (str.formatToSize(buf, 16, "%d-%s", 42, "ab") == buf &&
            bufIs(buf, 16, "42-ab0##########")) { correct++; }
    /* Cut short, '\0' at size-1, nothing after it */
    tests++;
    mem.set(buf, '#', 16);
    str.formatToSize(buf, 6, "%s|%05d", "hello", 7);
    if (bufIs(buf, 16, "hello0##########")) { correct++; }
    /* Exactly fills */
    tests++;
    mem.set(buf, '#', 16);
    str.formatToSize(buf, 4, "abc");
    if (bufIs(buf, 16, "abc0############")) { correct++; }
    /* size 1: only the '\0'; size 0 and negative: nothing */
    tests++;
    mem.set(buf, '#', 16);
    str.formatToSize(buf, 1, "abc");
    str.formatToSize(buf + 2, 0, "abc");
    str.formatToSize(buf + 4, -5, "abc");
    if (bufIs(buf, 16, "0###############")) { correct++; }
    /* An empty format, and no format arguments */
    tests++;
    mem.set(buf, '#', 16);
    str.formatToSize(buf, 16, "");
    if (bufIs(buf, 3, "0##")) { correct++; }
    /* Same text as StrPrint for any size */
    tests++;
    bool ok = true;
    u8 *full = str.formatTo(null, "%x|%5.2f|%-4s|", 48879, 3.14159, "z");
    i64 flen = str.len(full);
    for (i = 1; i <= 16; i++) {
        mem.set(buf, '#', 16);
        str.formatToSize(buf, i, "%x|%5.2f|%-4s|", 48879, 3.14159, "z");
        i64 n = flen;
        if (n > i - 1) { n = i - 1; }
        if (str.cmpLen(buf, full, n) || buf[n] != '\0') { ok = false; }
        if (i < 16 && buf[i] != '#') { ok = false; }
    }
    if (ok) { correct++; }
    mem.free(full);
    /* null dst is left alone */
    tests++;
    if (str.formatToSize(null, 8, "x") == null) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
