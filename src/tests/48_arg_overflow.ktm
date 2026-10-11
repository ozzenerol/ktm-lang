#include "testhelper.ktm"

/* Argument register overflow: when a call has more arguments than there
 * are parameter registers, the excess spills onto the stack (caller) and
 * is read back from the incoming argument area (callee). Covers overflow
 * of plain scalars (int + float) and of by-value structs (INTEGER, HFA,
 * INDIRECT), including unused overflow params. */

struct Color { u8 r; u8 g; u8 b; u8 a; };          /* INTEGER agg */
struct Vec2  { f32 x; f32 y; };                     /* HFA agg     */
struct Big   { i64 a; i64 b; i64 c; };              /* INDIRECT    */

i64 sum9(i64 a,i64 b,i64 c,i64 d,i64 e,i64 f,i64 g,i64 h,i64 i) {
    return a + b + c + d + e + f + g + h + i;
}
f64 sumF10(f64 a,f64 b,f64 c,f64 d,f64 e,f64 f,f64 g,f64 h,f64 i,f64 j) {
    return a + b + c + d + e + f + g + h + i + j;
}
/* g and h overflow on x86 (6 GP regs) and are UNUSED - exercises the
 * unreferenced-overflow-param path. */
i64 unused(i64 a,i64 b,i64 c,i64 d,i64 e,i64 f,i64 g,i64 h,i64 i,i64 j) {
    return i * 10 + j;
}
/* Struct after 8 integer args -> stack on aarch64; on x86 the trailing
 * scalars and the struct both spill. */
i64 intStructOv(i64 a,i64 b,i64 c,i64 d,i64 e,i64 f,i64 g,i64 h, Color c2) {
    return h * 1000 + c2.r + c2.g * 10;
}
f64 hfaOv(f64 a,f64 b,f64 c,f64 d,f64 e,f64 f,f64 g,f64 h, Vec2 v) {
    return h + v.x + v.y;
}
i64 bigOv(i64 a,i64 b,i64 c,i64 d,i64 e,i64 f,i64 g,i64 h, Big bg) {
    return h * 100 + bg.a + bg.b * 10 + bg.c;
}

i32 main() {
    i64 tests = 6, correct = 0;

    if (sum9(1,2,3,4,5,6,7,8,9) == 45) { correct++; }
    if (sumF10(1.0,2,3,4,5,6,7,8,9,10) == 55.0) { correct++; }
    if (unused(1,2,3,4,5,6,7,8,9,10) == 100) { correct++; }

    Color c; c.r=9; c.g=1; c.b=0; c.a=0;
    if (intStructOv(1,2,3,4,5,6,7,8,c) == 8019) { correct++; }

    Vec2 v; v.x=2.0; v.y=3.0;
    if (hfaOv(1,2,3,4,5,6,7,8,v) == 13.0) { correct++; }

    Big bg; bg.a=1; bg.b=2; bg.c=3;
    if (bigOv(1,2,3,4,5,6,7,8,bg) == 824) { correct++; }

    printf("Test - argument register overflow (stack spill):\n");
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
