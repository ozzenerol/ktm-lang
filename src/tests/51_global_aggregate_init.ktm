import test;

/* Global aggregate initialisers: class/struct + arrays.
 *
 * Regression coverage for several bugs that all lived in the static-data
 * path for globals:
 *   1. `class`/`struct` globals with a `{...}` initialiser failed to parse
 *      (the global `=` path used parseExpr, which can't read `{`).
 *   2. sub-8-byte array/field element literals were emitted at the *literal's*
 *      width (always 8 bytes), so `u32 a[]={7,8,9}` was written with an
 *      8-byte stride and read back as `7 0 8`.
 *   3. expression elements (`-1`, `2+3`, `1<<4`) were not constant-folded, so
 *      the emitter read garbage / wrote 0 bytes.
 *   4. ieee754_64 zeroed the mantissa of negative doubles, so `f64 f[]={-1.5}`
 *      was stored as -1.0.
 *
 * Note: narrow *signed* global reads (i8/i16) do not yet sign-extend on load
 * - a separate, pre-existing bug in the read path - so signed-negative cases
 * here use i32/i64, and the narrow-width/stride cases use the unsigned types
 * (positive values still exercise the stride fix). */

struct ColorI { i32 r,g,b; };
struct ColorF { f32 r,g,b; };

ColorI gCi = {11, 22, 33};
ColorF gCf = {-2.5, 1.25, 4.75};

u8  gU8[3]  = {7, 8, 9};
u16 gU16[3] = {300, 4000, 500};
u32 gU32[3] = {7, 8, 9};
i32 gI32[3] = {-100000, 200000, -300000};
i64 gI64[3] = {-1, -2, -3};
i32 gExpr[3] = {1000 * 1000, 7 + 8, 5 << 3};
f64 gF64[3] = {-1.5, 2.5, -3.5};
f32 gF32[3] = {-1.5, 2.5, -3.5};

bool globalClassInt() {
    return gCi.r == 11 && gCi.g == 22 && gCi.b == 33;
}

bool globalClassFloat() {
    return gCf.r == -2.5 && gCf.g == 1.25 && gCf.b == 4.75;
}

bool globalClassWrite() {
    gCi.g = 99;
    bool ok = gCi.r == 11 && gCi.g == 99 && gCi.b == 33;
    gCi.g = 22;     /* restore so re-runs are stable */
    return ok;
}

bool globalNarrowArrays() {
    /* The stride bug: each element must occupy its own width, not 8 bytes. */
    i64 c = 0, t = 3;
    if (gU8[0]  == 7   && gU8[1]  == 8    && gU8[2]  == 9)   { c++; }
    if (gU16[0] == 300 && gU16[1] == 4000 && gU16[2] == 500) { c++; }
    if (gU32[0] == 7   && gU32[1] == 8    && gU32[2] == 9)   { c++; }
    return c == t;
}

bool globalSignedArrays() {
    i64 c = 0, t = 2;
    if (gI32[0] == -100000 && gI32[1] == 200000 && gI32[2] == -300000) { c++; }
    if (gI64[0] == -1 && gI64[1] == -2 && gI64[2] == -3) { c++; }
    return c == t;
}

bool globalExprFold() {
    return gExpr[0] == 1000000 && gExpr[1] == 15 && gExpr[2] == 40;
}

bool globalFloatArrays() {
    i64 c = 0, t = 2;
    if (gF64[0] == -1.5 && gF64[1] == 2.5 && gF64[2] == -3.5) { c++; }
    if (gF32[0] == -1.5 && gF32[1] == 2.5 && gF32[2] == -3.5) { c++; }
    return c == t;
}

i32 main() {
    printf("Test - global aggregate initialisers (class/struct + arrays):\n");
    i64 tests = 7, correct = 0;
    if (globalClassInt())    { correct++; }
    if (globalClassFloat())  { correct++; }
    if (globalClassWrite())  { correct++; }
    if (globalNarrowArrays()) { correct++; }
    if (globalSignedArrays()) { correct++; }
    if (globalExprFold())    { correct++; }
    if (globalFloatArrays()) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
