import date, test;

/* `p[i]` where both p and i arrive as parameters. On arm64 AOT the
 * pointer was reloaded into x1, which still held the index, so
 * `i64 Ai(i64 *p, i64 i) { return p[i]; }` loaded from p + p*8
 * (`ldr x0, [x1, x1, lsl #3]`) and crashed. Stores and `+=` also
 * read the index out of its arg register after other code had
 * overwritten it. */

i64 gV[4] = {10, 20, 30, 40};

i64 ldI64(i64 *p, i64 i) { return p[i]; }
i64 ldIdxFirst(i64 i, i64 *p) { return p[i]; }
i64 ldX2(i64 a, i64 b, i64 *p, i64 i) { return p[i]; }
i64 ldSkip(i64 *p, i64 a, i64 i) { return p[i]; }
i64 ldX4(i64 a, i64 b, i64 c, i64 d, i64 *p, i64 i) { return p[i]; }
u8 ldU8(u8 *p, i64 i) { return p[i]; }
i32 ldI32(i32 *p, i64 i) { return p[i]; }
f64 ldF64(f64 *p, i64 i) { return p[i]; }
i32 ldDay(date.Date *p, i64 i) { return p[i].date; }
u32 ldTime(date.Date *p, i64 i) { return p[i].time; }
i64 ldSum(i64 *p, i64 i, i64 j) { return p[i + j]; }
i64 ldPlus1(i64 *p, i64 i) { return p[i + 1]; }
i64 ldNested(i64 *p, i64 i) { return ldI64(p, ldI64(p, i) - 18); }
i64 ldGlobal(i64 i) { return gV[i]; }

void stI64(i64 *p, i64 i, i64 v) { p[i] = v; }
void stX2(i64 a, i64 b, i64 *p, i64 i, i64 v) { p[i] = v; }
void stI32(i32 *p, i64 i, i32 v) { p[i] = v; }
void stF64(f64 *p, i64 i, f64 v) { p[i] = v; }
void stPlus1(i64 *p, i64 i, i64 v) { p[i + 1] = v; }

void addIdxFirst(i64 i, i64 *p) { p[i] += 5; }
void addI64(i64 *p, i64 i) { p[i] += 7; }
void orX2(i64 a, i64 b, i64 *p, i64 i) { p[i] |= 0x100; }
void addMul(i64 *p, i64 i, i64 a, i64 b) { p[i] += a * b; }
void addSum(i64 *p, i64 i, i64 j) { p[i + j] += 5; }
void incU8(u8 *p, i64 i) { p[i]++; }

i32 main() {
    i64 correct = 0, tests = 26;

    printf("Test - indexed access with pointer and index parameters: ");

    i64 v[4] = {10, 20, 30, 40};
    u8 b[4] = {1, 2, 3, 4};
    i32 w[4] = {-1, -2, -3, -4};
    f64 f[4] = {0.5, 1.5, 2.5, 3.5};
    date.Date d[3];
    d[0].date = 100; d[1].date = 200; d[2].date = 300;
    d[0].time = 1; d[1].time = 2; d[2].time = 3;

    /* Loads */
    if (ldI64(v, 2) == 30) { correct++; }
    if (ldIdxFirst(3, v) == 40) { correct++; }
    if (ldX2(0, 0, v, 3) == 40) { correct++; }
    if (ldSkip(v, 0, 1) == 20) { correct++; }
    if (ldX4(0, 0, 0, 0, v, 1) == 20) { correct++; }
    if (ldU8(b, 3) == 4) { correct++; }
    if (ldI32(w, 2) == -3) { correct++; }
    if (ldF64(f, 1) == 1.5) { correct++; }
    if (ldDay(d, 2) == 300 && ldTime(d, 1) == 2) { correct++; }
    if (ldSum(v, 1, 2) == 40) { correct++; }
    if (ldPlus1(v, 2) == 40) { correct++; }
    if (ldNested(v, 1) == 30) { correct++; }
    if (ldGlobal(2) == 30) { correct++; }

    /* Stores */
    stI64(v, 0, 11);
    if (v[0] == 11 && v[1] == 20) { correct++; }
    stX2(0, 0, v, 1, 21);
    if (v[1] == 21 && v[0] == 11) { correct++; }
    stI32(w, 1, 77);
    if (w[1] == 77 && w[0] == -1 && w[2] == -3) { correct++; }
    stF64(f, 2, 9.5);
    if (f[2] == 9.5 && f[1] == 1.5) { correct++; }
    stPlus1(v, 2, 99);
    if (v[3] == 99 && v[2] == 30) { correct++; }

    /* Read-modify-write */
    v[0] = 10; v[1] = 20; v[2] = 30; v[3] = 40;
    addIdxFirst(2, v);
    if (v[2] == 35 && v[0] == 10) { correct++; }
    addI64(v, 3);
    if (v[3] == 47 && v[1] == 20) { correct++; }
    orX2(0, 0, v, 0);
    if (v[0] == 0x10A) { correct++; }
    addMul(v, 1, 3, 4);
    if (v[1] == 32) { correct++; }
    addSum(v, 1, 1);
    if (v[2] == 40) { correct++; }
    incU8(b, 1);
    if (b[1] == 3 && b[0] == 1 && b[2] == 3) { correct++; }
    /* Nothing else was written through a wild pointer. */
    if (v[0] == 0x10A && v[1] == 32 && v[2] == 40 && v[3] == 47) { correct++; }
    if (b[0] == 1 && b[3] == 4 && w[3] == -4 && f[3] == 3.5) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
