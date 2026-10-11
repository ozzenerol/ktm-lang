#include "testhelper.ktm"

import mem, str;

/* Calling a ktm `...` function through a function pointer passed the
 * implicit argc and the extra arguments in registers, while the callee
 * reads them from the stack: `sp("a", 1, 2, 3)` summed garbage on
 * x86_64 and arm64 (or crashed). The backends only recognised a
 * variadic callee by looking up the name of a direct call. */

i64 Sum(u8 *t, ...args) {
    i64 i, s = 0;
    for (i = 0; i < args.len; i++) { s += args[i]; }
    return s;
}

i64 Count(u8 *t, ...args) {
    return args.len;
}

/* Named args mixed with extras; weights the extras by position. */
i64 mix(i64 a, i64 b, ...args) {
    i64 i, s = a * 100 + b * 10;
    for (i = 0; i < args.len; i++) { s += args[i] * (i + 1); }
    return s + args.len * 100000;
}

f64 scale(f64 k, ...args) {
    i64 i, s = 0;
    for (i = 0; i < args.len; i++) { s += args[i]; }
    return k * s;
}

u8 *format(u8 *fmt, ...args) {
    if (args.len == 0) { return str.dup(fmt); }
    return str.formatArgs(null, fmt, args.len, args);
}

struct H {
    fn(u8 *t, ...args) i64 count;
    fn(u8 *t, ...args) i64 sum;
    i64 v;
};

H gH;

/* A function pointer parameter */
i64 via(fn(u8 *t, ...args) i64 f) {
    return f("v", 10, 20, 30, 40, 50, 60, 70, 80);
}

/* Leaves non-zero values where a following call's argc would go */
i64 junk(u8 *a, i64 b, i64 c, i64 d) {
    return b + c + d;
}

i32 main() {
    printf("Test - call a variadic function through a function pointer: ");
    i64 tests = 17, correct = 0;
    u8 *s;

    fn(u8 *t, ...args) i64 sp = &Sum;
    fn(u8 *t, ...args) i64 cp = &Count;
    fn(i64 a, i64 b, ...args) i64 mp = &mix;
    fn(f64 k, ...args) f64 kp = &scale;
    fn(u8 *fmt, ...args) u8 *fp = &format;

    if (sp("a", 1, 2, 3) == 6) { correct++; }
    else { printf("Failed - local pointer sum\n"); }
    junk("x", 77, 88, 99);
    if (cp("a") == 0) { correct++; }
    else { printf("Failed - local pointer, no extra arguments\n"); }
    junk("x", 77, 88, 99);
    if (sp("a") == 0) { correct++; }
    else { printf("Failed - local pointer sum, no extra arguments\n"); }
    if (sp("a", 1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55) { correct++; }
    else { printf("Failed - local pointer, many extra arguments\n"); }
    if (mp(1, 2, 3, 4, 5, 6, 7, 8) == 600253) { correct++; }
    else { printf("Failed - named arguments mixed with extras\n"); }
    if (mp(1, 2) == 120) { correct++; }
    else { printf("Failed - named arguments, no extras\n"); }
    if (mp(1, 2) + sp("x", 7) * 2 == 134) { correct++; }
    else { printf("Failed - results used in an expression\n"); }
    if (kp(0.5, 4, 6) == 5.0) { correct++; }
    else { printf("Failed - f64 named argument with extras\n"); }
    if (via(&Sum) == 360) { correct++; }
    else { printf("Failed - function pointer parameter\n"); }

    H h;
    h.count = &Count;
    h.sum = &Sum;
    H *p = &h;
    H *arr[2];
    arr[0] = &h;
    arr[1] = &h;
    gH.count = &Count;
    gH.sum = &Sum;

    if (h.count("a", 1, 2) == 2 && h.sum("a", 1, 2) == 3) { correct++; }
    else { printf("Failed - class field\n"); }
    junk("x", 77, 88, 99);
    if (p.count("a") == 0) { correct++; }
    else { printf("Failed - field through a pointer, no extra arguments\n"); }
    if (p.sum("a", 5, 6, 7) == 18) { correct++; }
    else { printf("Failed - field through a pointer\n"); }
    if (arr[1].count("a", 1, 2, 3, 4) == 4 &&
            arr[0].sum("a", 1, 2, 3, 4, 5, 6, 7, 8, 9) == 45) { correct++; }
    else { printf("Failed - field through an array element\n"); }
    junk("x", 77, 88, 99);
    if (arr[1].count("a") == 0) { correct++; }
    else { printf("Failed - field through an array element, no extra arguments\n"); }
    if (gH.sum("a", 4, 5) == 9 && gH.count("a") == 0) { correct++; }
    else { printf("Failed - global class field\n"); }

    s = fp("keep %s and 50%%");
    if (!str.cmp(s, "keep %s and 50%%")) { correct++; }
    else { printf("Failed - format through a pointer, no arguments: '%s'\n", s); }
    mem.free(s);
    s = fp("%s=%d", "x", 42);
    if (!str.cmp(s, "x=42")) { correct++; }
    else { printf("Failed - format through a pointer: '%s'\n", s); }
    mem.free(s);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
