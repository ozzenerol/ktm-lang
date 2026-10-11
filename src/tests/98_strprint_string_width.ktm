#include "testhelper.ktm"

import date, libc, mem, str;

/* StrPrint ignored the width of %s and used its precision as the width:
 * "[%5s][%-5s][%.2s]" gave "[ab][ab][abcd]". Right-justified padding was
 * also written through a copy of the destination pointer and then
 * overwritten by the string, and %-8d/%-8x padded on the left. Every case
 * here must print exactly what C's printf prints. */

extern "c" i64 snprintf(u8 *buf, u64 len, u8 *fmt, ...);

i64 gBad = 0;

void same(u8 *fmt, u8 *ours, u8 *want) {
    if (str.cmp(ours, want)) {
        gBad++;
        printf("\n  %s: got '%s', C printf gives '%s'", fmt, ours, want);
    }
}

void checkS(u8 *fmt, u8 *s) {
    u8 ours[512], want[512];
    str.formatTo(ours, fmt, s);
    libc.snprintf(want, sizeof(want), fmt, s);
    same(fmt, ours, want);
}

void checkI(u8 *fmt, i64 v) {
    u8 ours[512], want[512];
    str.formatTo(ours, fmt, v);
    libc.snprintf(want, sizeof(want), fmt, v);
    same(fmt, ours, want);
}

void checkStar(u8 *fmt, i64 a, i64 b, u8 *s) {
    u8 ours[512], want[512];
    str.formatTo(ours, fmt, a, b, s);
    libc.snprintf(want, sizeof(want), fmt, a, b, s);
    same(fmt, ours, want);
}

i32 main() {
    i64 correct = 0, tests = 14, i, j;
    u8 *sfmts[13] = {
        "[%s]", "[%5s]", "[%-5s]", "[%.2s]", "[%5.2s]", "[%-5.2s]", "[%.0s]",
        "[%.10s]", "[%1s]", "[%12s]", "[%-12s]", "[%3.1s]", "[%-1.3s]"
    };
    u8 *strs[5] = { "", "a", "ab", "abcd", "hello world" };
    u8 *ifmts[15] = {
        "[%d]", "[%8d]", "[%-8d]", "[%08d]", "[%-08d]", "[%1d]", "[%u]",
        "[%8u]", "[%-8u]", "[%x]", "[%8x]", "[%-8x]", "[%08x]", "[%X]",
        "[%-6X]"
    };
    i64 ivals[5] = { 0, 7, 42, 123456789, 0x7fffffff };
    i64 nvals[3] = { -1, -42, -123456 };
    u8 buf[64], ours[2048], want[2048], *m, *c;

    printf("Test - StrPrint width, '-' and precision of %%s match C printf: ");

    /* 1: %s width, '-' and precision */
    for (i = 0; i < 5; ++i) {
        for (j = 0; j < 13; ++j) {
            checkS(sfmts[j], strs[i]);
        }
    }
    if (!gBad) { correct++; }

    /* 2: %d %u %x %X width, '-' and '0' */
    gBad = 0;
    for (i = 0; i < 5; ++i) {
        for (j = 0; j < 15; ++j) {
            checkI(ifmts[j], ivals[i]);
        }
    }
    for (i = 0; i < 3; ++i) {
        for (j = 0; j < 6; ++j) {
            checkI(ifmts[j], nvals[i]);
        }
    }
    if (!gBad) { correct++; }

    /* 3: '*' width and precision, a negative '*' width left-justifies */
    gBad = 0;
    checkStar("[%*.*s]", 6, 2, "abcd");
    checkStar("[%-*.*s]", 6, 3, "abcd");
    checkStar("[%*.*s]", -6, 1, "abcd");
    checkStar("[%*.*s]", 2, 10, "abcd");
    checkStar("[%*.*s]", 0, 0, "abcd");
    checkStar("[%*.*s]", 3, -1, "abcd"); /* negative precision: none */
    if (!gBad) { correct++; }

    /* 4: the bug report, into a caller's stack buffer */
    if (!str.cmp(str.formatTo(buf, "[%5s][%-5s][%.2s]", "ab", "ab", "abcd"),
                            "[   ab][ab   ][ab]")) { correct++; }

    /* 5: into a buffer in the middle of an allocation, which is never grown */
    u8 *mid = mem.allocZero(128);
    if (!str.cmp(str.formatTo(mid + 16, "%8s|%-8s|%.1s|%-6d|%6d", "x", "y", "zz", 7, -7),
                            "       x|y       |z|7     |    -7")) { correct++; }
    mem.free(mid);

    /* 6: StrPrint(null, ...) grows its buffer past STR_LEN while padding */
    m = str.formatTo(null, "%300s|%-300s|%.3s", "right", "left", "truncated");
    libc.snprintf(want, sizeof(want), "%300s|%-300s|%.3s", "right", "left", "truncated");
    if (!str.cmp(m, want)) { correct++; }
    mem.free(m);

    /* 7: MStrPrint, several regrowths, padding straddles each one */
    m = str.format("%255s%-257s%511s%d", "a", "b", "c", 42);
    libc.snprintf(want, sizeof(want), "%255s%-257s%511s%d", "a", "b", "c", 42);
    if (!str.cmp(m, want) && str.len(m) == 255 + 257 + 511 + 2) { correct++; }
    mem.free(m);

    /* 8: CatPrint */
    c = str.format("[%3s]", "a");
    m = str.appendFormat(c, "[%-4s][%4.2s]", "bc", "def"); /* takes over c */
    if (!str.cmp(m, "[  a][bc  ][  de]")) { correct++; }
    mem.free(m);

    /* 9: a null string prints nothing, but still takes its width */
    if (!str.cmp(str.formatTo(buf, "[%s][%4s][%-4s][%.2s]", null, null, null, null),
                            "[][    ][    ][]")) { correct++; }

    /* 10: %c and %C take a width and '-' too */
    str.formatTo(ours, "[%3c][%-3c][%c]", 'a', 'b', 'c');
    libc.snprintf(want, sizeof(want), "[%3c][%-3c][%c]", 'a', 'b', 'c');
    if (!str.cmp(ours, want) &&
            !str.cmp(str.formatTo(buf, "[%4C][%-4C]", 'ab', 'cd'), "[  AB][CD  ]")) {
        correct++;
    }

    /* 11, 12: %Q and %p with widths */
    if (!str.cmp(str.formatTo(buf, "[%6Q][%-6Q]", "a\"b", "a\nb"),
                            "[  a\\\"b][a\\nb  ]")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "[%p]", 0x1234), "[0x0000000000001234]") &&
            !str.cmp(str.formatTo(buf, "[%20p][%-20p]", 0xab, 0xab),
                            "[  0x00000000000000AB][0x00000000000000AB  ]")) { correct++; }

    /* 13: %D and %T with widths */
    date.Date cdt = date.now();
    u8 *d = date.formatDate(cdt), *t = date.formatTime(cdt);
    libc.snprintf(want, sizeof(want), "[%12s][%-12s][%10s][%-10s]", d, d, t, t);
    str.formatTo(ours, "[%12D][%-12D][%10T][%-10T]", cdt, cdt, cdt, cdt);
    if (!str.cmp(ours, want)) { correct++; }
    mem.free(d);
    mem.free(t);

    /* 14: floats and mixed formats are unchanged */
    str.formatTo(ours, "%-6s=%8.3f|%5s|%-3d|%%|%s", "pi", 3.14159, "ok", 9, "end");
    libc.snprintf(want, sizeof(want), "%-6s=%8.3f|%5s|%-3d|%%|%s",
                     "pi", 3.14159, "ok", 9, "end");
    if (!str.cmp(ours, want)) { correct++; }
    else { printf("\n  mixed: got '%s', C printf gives '%s'", ours, want); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
