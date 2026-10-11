#include "testhelper.ktm"

/* A narrowing cast (to i8/u8/i16/u16/i32/u32, and bool) truncated its value
 * but did not sign or zero extend the result before it was used, so a
 * signed cast kept its raw low bits: `200(i8) == -56` was 0 although the
 * value prints as -56, and the same for a variable, a prefix cast, a
 * switch, an argument, arithmetic and a return. The cast now yields the
 * canonical full-width value. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 takeI64(i64 x) { return x; }
i64 retI8(i64 x) { return (i8)x; }
i64 retU8(i64 x) { return (u8)x; }
i64 retI16(i64 x) { return (i16)x; }
i64 retU32(i64 x) { return (u32)x; }

void signed() {
    i64 v = 200;                 /* low byte 0xC8 = -56 as i8 */
    i64 w = 0x1FF80;             /* low 16 bits 0xFF80 = -128 as i16 */
    i64 q = 0x1FFFFFF80;         /* low 32 bits = -128 as i32 */

    check((i8)200 == -56, "const i8 compare");
    check((i8)v == -56, "var i8 compare");
    check((i8)v < 0, "i8 is negative");
    check((i8)v + 0 == -56, "i8 in arithmetic");
    check((i8)v * 2 == -112, "i8 multiplied");
    check(takeI64((i8)v) == -56, "i8 as an argument");
    check(retI8(v) == -56, "i8 returned");

    check((i16)0x1FF80 == -128, "const i16 compare");
    check((i16)w == -128, "var i16 compare");
    check(retI16(w) == -128, "i16 returned");

    check((i32)q == -128, "const i32 compare");
    check((i32)q + 28 == -100, "i32 in arithmetic");

    printf("switch on v(i8): ");
    switch ((i8)v) {
        case -56: check(true, "i8 switch matches -56"); break;
        default:  check(false, "i8 switch matches -56");
    }

    /* Round trip through a narrow slot keeps the value. */
    i8 slot = (i8)v;
    check(slot == -56, "stored into an i8 slot and read back");
}

void unsigned() {
    i64 w = 0x1FF;               /* low byte 0xFF = 255 */
    i64 h = 0x1FFFF;             /* low 16 bits = 0xFFFF */
    i64 d = 0x1FFFFFFFF;         /* low 32 bits = 0xFFFFFFFF */

    check((u8)511 == 255, "const u8 compare");
    check((u8)w == 255, "var u8 compare");
    check((u8)w > 0, "u8 is positive");
    check((u8)w + 1 == 256, "u8 in arithmetic");
    check(retU8(w) == 255, "u8 returned");
    check((u16)0x1FFFF == 0xFFFF, "const u16 compare");
    check((u16)h == 0xFFFF, "var u16 compare");
    check((u32)d == 0xFFFFFFFF, "var u32 compare");
    check(retU32(d) == 0xFFFFFFFF, "u32 returned");
}

/* The rc has C prefix casts (T)x too; they lower the same way. */
void prefix() {
    i64 v = 200, w = 0x1FF;
    check((i8)v == -56, "prefix (i8) compare");
    check((i8)v + 0 == -56, "prefix (i8) in arithmetic");
    check((i8)200 == -56, "prefix (i8) on a constant");
    check((u8)w == 255, "prefix (u8) compare");
    check((i16)0x1FF80 == -128, "prefix (i16) compare");
    check((i32)0x1FFFFFF80 == -128, "prefix (i32) compare");
}

void bools() {
    i64 v = 200, z = 0;
    check((bool)200 == true, "const nonzero to bool is true");
    check((bool)v == true, "var nonzero to bool is true");
    check((bool)z == false, "zero to bool is false");
    check(takeI64((bool)v) == 1, "bool as an argument is 1");
}

i32 main() {
    printf("Test - narrowing cast then use: ");
    signed();
    unsigned();
    prefix();
    bools();
    PrintResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
