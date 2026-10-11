import test;

/* Float -> integer conversions across every integer width and both
 * signednesses, for f32 and f64. Truncation is toward zero (C semantics):
 * -100.9 -> -100, not -101. f32 values are chosen to be exactly
 * representable in single precision so the truncated result is exact. */

bool f32ToInt() {
    i64 tests = 8, correct = 0;
    f32 f;

    f = -100.5;     i8  a = f;  if (a == -100)     { correct++; }
    f =  200.5;     u8  b = f;  if (b ==  200)     { correct++; }
    f = -5000.5;    i16 c = f;  if (c == -5000)    { correct++; }
    f =  50000.5;   u16 d = f;  if (d ==  50000)   { correct++; }
    f = -1000000.25; i32 e = f; if (e == -1000000) { correct++; }
    f =  1000000.75; u32 g = f; if (g ==  1000000) { correct++; }
    f = -2000000.5; i64 h = f;  if (h == -2000000) { correct++; }
    f =  2000000.5; u64 i = f;  if (i ==  2000000) { correct++; }

    return correct == tests;
}

bool f64ToInt() {
    i64 tests = 8, correct = 0;
    f64 f;

    f = -100.9;            i8  a = f; if (a == -100)            { correct++; }
    f =  250.9;            u8  b = f; if (b ==  250)            { correct++; }
    f = -30000.9;          i16 c = f; if (c == -30000)          { correct++; }
    f =  65000.9;          u16 d = f; if (d ==  65000)          { correct++; }
    f = -2000000000.9;     i32 e = f; if (e == -2000000000)     { correct++; }
    /* 4e9 is > I32_MAX: exercises fptoui, not fptosi. */
    f =  4000000000.9;     u32 g = f; if (g ==  4000000000)     { correct++; }
    f = -9000000000000.9;  i64 h = f; if (h == -9000000000000)  { correct++; }
    f =  9000000000000.9;  u64 i = f; if (i ==  9000000000000)  { correct++; }

    return correct == tests;
}

/* The reverse direction: integer -> float, signed and unsigned. */
bool intToFloat() {
    i64 tests = 4, correct = 0;

    i32 si = -2000000000;  f64 a = si; if (a == -2000000000.0)  { correct++; }
    u32 ui =  4000000000;  f64 b = ui; if (b ==  4000000000.0)  { correct++; }
    i64 sl = -9;           f32 c = sl; if (c == -9.0)           { correct++; }
    u8  ub =  200;         f32 d = ub; if (d ==  200.0)         { correct++; }

    return correct == tests;
}

i64 wideRet() { return 0x1234567890ABCDEF; }

/* Identity passthroughs: the coercion happens at the call site (the
 * argument is converted to the parameter's type) and again at the return. */
u8  takeU8(u8 p)   { return p; }
i64 takeI64(i64 p) { return p; }
f64 takeF64(f64 p) { return p; }
f32 takeF32(f32 p) { return p; }

/* Arguments are coerced to the parameter's type at the call site: a wider
 * int truncates to a narrow param, a narrow signed int promotes (sign
 * extends) to a wide one, an int promotes to a float param and a float
 * truncates (toward zero) to an int param, and f32<->f64 convert. */
bool fnCallConversions() {
    i64 tests = 9, correct = 0;

    if (takeU8(300)        == 44)  { correct++; }   /* wide int -> u8 param    */
    if (takeU8(0x1FF)      == 255) { correct++; }   /* 511 & 0xFF              */
    i8 n = -7;
    if (takeI64(n)         == -7)  { correct++; }   /* i8 -> i64 (sign extend) */
    if (takeF64(5)         == 5.0) { correct++; }   /* int -> f64 param        */
    if (takeI64(7.9)       == 7)   { correct++; }   /* f64 -> i64 param (trunc)*/
    if (takeI64(-7.9)      == -7)  { correct++; }   /* trunc toward zero       */
    f32 g = 2.5;
    if (takeF64(g)         == 2.5) { correct++; }   /* f32 arg -> f64 param    */
    if (takeF32(3.5)       == 3.5) { correct++; }   /* f64 arg -> f32 param    */
    if (takeU8(wideRet())  == 239) { correct++; }   /* truncate a call result  */

    return correct == tests;
}

/* Assigning a wider integer to a narrower type truncates to the
 * destination width; the surviving low bits are then interpreted per the
 * destination's signedness (so a high bit becomes a sign bit for a signed
 * target). */
bool intTruncation() {
    i64 tests = 10, correct = 0;
    i64 big = 0x1234567890ABCDEF;

    u8  a = big;  if (a == 239)         { correct++; }   /* 0xEF             */
    u16 b = big;  if (b == 52719)       { correct++; }   /* 0xCDEF           */
    u32 c = big;  if (c == 2427178479)  { correct++; }   /* 0x90ABCDEF       */
    i8  d = big;  if (d == -17)         { correct++; }   /* 0xEF  as signed  */
    i16 e = big;  if (e == -12817)      { correct++; }   /* 0xCDEF as signed */
    i32 f = big;  if (f == -1867788817) { correct++; }   /* 0x90ABCDEF signed*/

    u8  g = 300;  if (g == 44)          { correct++; }   /* 300 - 256        */
    i8  h = 200;  if (h == -56)         { correct++; }   /* 200 wraps signed */

    /* truncation of a (mismatched) wider value through a function return  */
    u8  i = wideRet(); if (i == 239)    { correct++; }
    /* explicit narrowing cast in an expression (ktm postfix cast)       */
    if ((u8)big == 239)                 { correct++; }

    return correct == tests;
}

/* Same coercions, but the argument is a *variable* (not a literal) and the
 * parameter is mutated in the body then returned - the case that has been
 * a source of bugs (the value must be truncated/promoted into the param's
 * type at the call site, and the in-body modification must survive). */
u64 addU16(u16 arg)  { arg += 3;   return arg; }
i64 decI8(i8 arg)    { arg -= 1;   return arg; }
f64 bumpF32(f32 arg) { arg += 0.5; return arg; }
i64 bumpI64(i64 arg) { arg += 1;   return arg; }

bool varArgCoercion() {
    i64 tests = 6, correct = 0;

    i64 x = 23;          if (addU16(x)   == 26)   { correct++; } /* i64 var -> u16 */
    i64 big = 0x12345;   if (addU16(big) == 9032) { correct++; } /* 0x2345 + 3     */
    i64 neg = -7;        if (decI8(neg)  == -8)   { correct++; } /* signed narrow  */
    f64 d = 2.0;         if (bumpF32(d)  == 2.5)  { correct++; } /* f64 var -> f32  */
    i64 i = 10;          if (bumpF32(i)  == 10.5) { correct++; } /* int var -> f32  */
    f64 f = 7.9;         if (bumpI64(f)  == 8)    { correct++; } /* f64 var -> i64  */

    return correct == tests;
}

i32 main() {
    i64 tests = 6, correct = 0;
    if (f32ToInt())         { correct++; }
    if (f64ToInt())         { correct++; }
    if (intToFloat())       { correct++; }
    if (intTruncation())    { correct++; }
    if (fnCallConversions()) { correct++; }
    if (varArgCoercion())   { correct++; }
    printf("Test - float<->int conversions (all widths, signed + unsigned):\n");
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
