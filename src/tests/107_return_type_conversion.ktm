import test;

/* A return value must be converted to the function's return type like an
 * assignment is: `f64 Conv(i64 u) { return u; }` returned the integer's
 * raw bits in the float register (Conv(3) printed 0.000000), and an
 * integer function returning a float expression returned garbage instead
 * of the truncated value. */

f64 conv(i64 u) { return u; }
f64 conv2(i64 u) { return u * 2; }
f64 lit() { return 3; }
f64 negLit() { return -4; }
f64 fromU8(u8 b) { return b; }
f64 fromI32(i32 b) { return b; }
f64 fromU32(u32 b) { return b; }
f32 f32Conv(i64 u) { return u; }
f64 same(f64 f) { return f * 2.0; }
i64 truncFn(f64 f) { return f; }
i64 truncExpr(f64 f) { return f * 2.0; }
i64 truncF32(f32 f) { return f; }
u8 toU8(f64 f) { return f; }
i32 toI32(f64 f) { return f; }
u32 toU32(f64 f) { return f; }
i64 mixed(i64 u, f64 f) { if (u > 0) { return f; } return u; }

i32 main() {
    i64 correct = 0, tests = 20;
    i64 assign;
    f64 neg = -3.7;

    printf("Test - return value converted to the return type: ");

    if (conv(3) == 3.0) { correct++; }
    if (conv2(3) == 6.0) { correct++; }
    if (lit() == 3.0) { correct++; }
    if (negLit() == -4.0) { correct++; }
    if (fromU8(250) == 250.0 && fromI32(-7) == -7.0) { correct++; }
    if (fromU32(4000000000) == 4000000000.0) { correct++; }
    if (f32Conv(5) == 5.0) { correct++; }
    if (same(1.25) == 2.5) { correct++; }

    /* Same truncation an assignment `i64 x = f;` does. */
    assign = neg;
    if (truncFn(3.7) == 3) { correct++; }
    if (truncFn(neg) == assign && assign == -3) { correct++; }
    if (truncExpr(1.75) == 3) { correct++; }
    if (truncF32(9.5) == 9) { correct++; }
    if (toU8(200.5) == 200) { correct++; }
    if (toI32(-5.5) == -5) { correct++; }
    if (toU32(4000000000.0) == 4000000000) { correct++; }
    if (mixed(1, 2.9) == 2 && mixed(-6, 2.9) == -6) { correct++; }

    /* Through function pointers and nested calls. */
    fn(i64) f64 fp = &conv;
    fn(f64) i64 tp = &truncFn;
    if (fp(9) == 9.0) { correct++; }
    if (tp(8.9) == 8) { correct++; }
    if (conv(truncFn(conv2(2))) == 4.0) { correct++; }
    if (conv(conv2(1)) == 2.0 && truncFn(conv(truncFn(lit()))) == 3) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
