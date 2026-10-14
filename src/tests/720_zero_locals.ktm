import test;

/* Every local starts zeroed (v0.18.0): integers, floats, pointers, bools,
 * structs, arrays (small and large) and function pointers declared
 * without a value read as 0, not as whatever the stack held; a local
 * declared in a loop is zero again on each pass. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s", what); }
}

struct Report {
    i64 lines;
    f64 ratio;
    u8 *name;
    i32 flags[4];
};

/* leaves 0xAB in the stack where the next call's locals go */
i64 dirty() {
    u8 junk[2048];
    for (i64 i = 0; i < 2048; i++) { junk[i] = 0xAB; }
    i64 sum = 0;
    for (i64 i = 0; i < 2048; i += 256) { sum += junk[i]; }
    return sum;
}

i64 one(i64 x) { return x + 1; }

void scalars() {
    i64 a;
    f64 f;
    u8 *p;
    bool b;
    i32 small;
    u8 c;
    check(a == 0 && small == 0 && c == 0, "integers");
    check(f == 0.0, "float");
    check(p == null, "pointer");
    check(!b, "bool");
}

void aggregates() {
    Report rep;
    i64 big[40];
    u8 buf[24];
    fn(i64) i64 cb;
    check(rep.lines == 0 && rep.ratio == 0.0 && rep.name == null &&
          rep.flags[0] == 0 && rep.flags[3] == 0, "struct");
    bool zero = true;
    for (i64 i = 0; i < 40; i++) { if (big[i]) { zero = false; } }
    for (i64 i = 0; i < 24; i++) { if (buf[i]) { zero = false; } }
    check(zero, "arrays, small and large");
    check(cb == null, "function pointer");
}

void loops() {
    i64 bad = 0;
    for (i64 i = 0; i < 3; i++) {
        i64 n;
        Report r;
        if (n != 0 || r.lines != 0) { bad++; }
        n = 7;
        r.lines = 9;
    }
    check(bad == 0, "zero again on each pass of a loop");
}

i32 main()
{
    printf("Test - every local is zeroed: ");
    dirty();
    scalars();
    dirty();
    aggregates();
    dirty();
    loops();
    check(one(1) == 2, "sanity");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
