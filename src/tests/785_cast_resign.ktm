import test;

/* A cast to an integer of the same width and the other signedness
 * ((i32) of a u32, (u16) of an i16...) re-extends the value: before
 * v0.20.0, (i64)(i32)0xFFFFFFFEu was 4294967294, not -2. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

u32 f32() { return 0xFFFFFFFE; }
u16 f16() { return 0xFFFE; }
u8 f8() { return 0xFE; }
i64 ret32() { return (i32)f32(); }
i32 neg() { return -2; }

i32 main() {
    printf("Test - same-width casts change the sign: ");
    u32 u = 0xFFFFFFFE;
    u16 w = 0xFFFE;
    u8 b = 0xFE;
    i64 a = (i32)u;
    check(a == -2, "(i32) of a u32 variable");
    check(ret32() == -2, "(i32) of a call, returned as i64");
    check((i64)(i32)f32() == -2, "(i64)(i32) of a call");
    check((i32)u < 0, "compared");
    check((i16)w == -2 && (i64)(i16)f16() == -2, "(i16) of a u16");
    check((i8)b == -2 && (i64)(i8)f8() == -2, "(i8) of a u8");
    i32 n = -2;
    i64 m = (u32)n;
    check(m == 4294967294 && (i64)(u32)neg() == 4294967294, "(u32) of an i32");
    check((i64)(u16)(i16)-2 == 65534, "(u16) of an i16");
    check((i64)(i32)(u32)-1 == -1 && (i64)(u32)(i32)-1 == 4294967295, "round trips");
    i64 sum = 0;
    for (i64 i = 0; i < 3; i++) { sum += (i32)u; }
    check(sum == -6, "in arithmetic");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
