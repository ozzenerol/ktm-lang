import test;

import json, libc, mem, str;

/* StrPrint and friends printed "Floats not supported" and exited on %f,
 * so JsonToString exited on any JSON containing a float. %f, %e and %g
 * must now print exactly what C's printf prints for the same double. */

extern "c" i64 snprintf(u8 *buf, u64 len, u8 *fmt, ...);
extern "c" f64 strtod(u8 *nptr, u8 **endptr);

i64 gChecks = 0;
i64 gBad = 0;

void check(u8 *fmt, f64 v) {
    u8 ours[512], want[512];
    str.formatTo(ours, fmt, v);
    libc.snprintf(want, sizeof(want), fmt, v);
    gChecks++;
    if (str.cmp(ours, want)) {
        gBad++;
        printf("\n  %s: got '%s', C printf gives '%s'", fmt, ours, want);
    }
}

i32 main() {
    i64 correct = 0, tests = 13, i, j;
    u8 *fmts[14] = {
        "%f", "%.2f", "%.0f", "%.1f", "%.10f", "%8.2f", "%-8.2f|", "%08.2f",
        "%e", "%.3e", "%.0e", "%g", "%.3g", "%10.4g"
    };
    f64 vals[24];
    f64 negZero = 0.0;
    negZero = negZero * -1.0;

    printf("Test - StrPrint %%f/%%e/%%g match C printf: ");

    vals[0] = 0.0;
    vals[1] = negZero;
    vals[2] = 1.5;
    vals[3] = -1.5;
    vals[4] = 2.675;
    vals[5] = 0.125;
    vals[6] = 0.5;
    vals[7] = 2.5;
    vals[8] = 3.14159265358979;
    vals[9] = 1.0e15;
    vals[10] = 1.0e300;
    vals[11] = libc.strtod("1e-7", null); /* ktm lexes 1.0e-7 wrong */
    vals[12] = -123456.789;
    vals[13] = -0.0001234;
    vals[14] = 9.9999999;
    vals[15] = 0.05;
    vals[16] = libc.strtod("1e-300", null);
    vals[17] = libc.strtod("4.9406564584124654e-324", null); /* smallest denormal */
    vals[18] = libc.strtod("1.7976931348623157e308", null);  /* largest f64 */
    vals[19] = 100.0;
    vals[20] = 1.0e21;
    vals[21] = 0.000099999;
    vals[22] = 999999.5;
    vals[23] = 1.0 / 3.0;

    for (i = 0; i < 24; ++i) {
        for (j = 0; j < 14; ++j) {
            check(fmts[j], vals[i]);
        }
    }
    if (!gBad) { correct++; }

    /* inf and nan, with width and flags */
    f64 inf = libc.strtod("inf", null), nan = libc.strtod("nan", null);
    gBad = 0;
    for (j = 0; j < 14; ++j) {
        check(fmts[j], inf);
        check(fmts[j], -inf);
        check(fmts[j], nan);
    }
    if (!gBad) { correct++; }

    /* the exact strings, not just "same as C" */
    u8 buf[64];
    if (!str.cmp(str.formatTo(buf, "%f", 1.5), "1.500000")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "%.2f", 2.675), "2.67")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "[%8.2f][%-8.2f][%08.2f]", -1.5, 1.5, -1.5),
                            "[   -1.50][1.50    ][-0001.50]")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "%f %f", vals[11], negZero),
                            "0.000000 -0.000000")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "%f|%f|%5f", inf, -inf, nan),
                            "inf|-inf|  nan")) { correct++; }
    if (!str.cmp(str.formatTo(buf, "%d %.3f %s", 7, 0.0005, "x"), "7 0.001 x")) {
        correct++; /* 0.0005 is just above the tie, so it rounds up */
    }

    /* %s into a caller's buffer must not MSize/ReAlloc it: mid-array the
     * size header OutStr read is 0, so it "grew" and freed a bad pointer */
    u8 *mid = mem.allocZero(64);
    if (!str.cmp(str.formatTo(mid + 16, "%s|%c", "ab", 'z'), "ab|z")) {
        correct++;
    }
    mem.free(mid);

    /* MStrPrint and CatPrint go through the same formatter */
    u8 *m = str.format("pi=%.4f e=%.3e", 3.14159265358979, 2.718281828);
    if (!str.cmp(m, "pi=3.1416 e=2.718e+00")) { correct++; }
    mem.free(m);

    /* JsonParse -> JsonToString with floats no longer exits */
    json.Value *js = json.parse("{\"pi\": 3.5, \"neg\": -0.25, \"arr\": [1.5, 2, 0.1]}");
    u8 *s = json.toStr(js);
    if (!str.cmp(s, "{\"pi\": 3.5,\"neg\": -0.25,\"arr\": [1.5,2,0.1]}")) {
        correct++;
    } else {
        printf("\n  JsonToString gave: %s", s);
    }
    mem.free(s);
    json.release(js);

    /* floats print so they read back as the same float (with 15, 16 or 17
     * digits, so the smallest denormal is not the shortest -5e-324) */
    js = json.parse("[2.0, 1.0e21, 0.30000000000000004, 1.0e-7, -4.9406564584124654e-324]");
    s = json.toStr(js);
    if (!str.cmp(s, "[2.0,1.0e+21,0.30000000000000004,1.0e-07,-4.94065645841247e-324]")) {
        correct++;
    } else {
        printf("\n  JsonToString gave: %s", s);
    }
    json.Value *back = json.parse(s);
    if (back.array.type == json.FLOAT &&
            back.array.next.floatVal == 1.0e21 &&
            back.array.next.next.floatVal == 0.30000000000000004) {
        correct++;
    }
    mem.free(s);
    json.release(back);
    json.release(js);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
