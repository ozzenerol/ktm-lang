import test;

/* A float used as a truth value must be compared against 0.0, as in C
 * (-0.0 is false, NaN is true), and `&&` / `||` / `!` must give 0/1.
 * The right operand of `&&` / `||` went into the result raw (`0 || 0.5`
 * was 0, `0 || 1e-300` was 89) and `!f` was converted back from a float
 * (`!0.0` was 0, `!!0.5` was 0). */

i64 correct = 0, tests = 0;
i64 calls = 0;

void check(i64 got, i64 want) {
    tests++;
    if (got == want) { correct++; }
    else { printf("\n  got %d want %d (test %d)", got, want, tests); }
}

f64 side(f64 v) { calls++; return v; }

f64 makeNan() { f64 z = 0.0; return z / z; }

/* t: the C truth value of g (and of f, its f32 copy, given as tf). */
void truth(f64 g, i64 t, i64 tf) {
    i64 z = 0, o = 1, w = 0, c;
    f32 f = g;
    f64 h;

    c = z || g; check(c, t);
    c = g || z; check(c, t);
    c = o && g; check(c, t);
    c = g && o; check(c, t);
    c = g && g; check(c, t);
    c = g || g; check(c, t);
    c = z && g; check(c, 0);
    c = o || g; check(c, 1);
    c = !g; check(c, !t);
    c = !!g; check(c, t);
    c = (z || g) + (o && g) * 2; check(c, t * 3);
    if (g) { c = 1; } else { c = 0; } check(c, t);
    if (!g) { c = 1; } else { c = 0; } check(c, !t);
    if (z || g) { c = 1; } else { c = 0; } check(c, t);
    if (g && o) { c = 1; } else { c = 0; } check(c, t);
    while (g) { w++; break; } check(w, t);
    h = g;
    for (w = 0; h; w++) { h = 0.0; } check(w, t);

    c = z || f; check(c, tf);
    c = f || z; check(c, tf);
    c = o && f; check(c, tf);
    c = f && o; check(c, tf);
    c = !f; check(c, !tf);
    c = !!f; check(c, tf);
    if (f) { c = 1; } else { c = 0; } check(c, tf);
}

i32 main() {
    i64 c, z = 0, o = 1;

    printf("Test - float operands of && || ! and conditions: ");

    truth(0.5, 1, 1);
    truth(0.0, 0, 0);
    truth(-0.0, 0, 0);
    truth(makeNan(), 1, 1);
    truth(1e-300, 1, 0); /* (f32)1e-300 is 0.0 */
    truth(256.0, 1, 1);
    truth(-2.5, 1, 1);

    /* Literal operands. */
    c = 0 || 0.5; check(c, 1);
    c = 0.25 && 1; check(c, 1);
    c = !0.0; check(c, 1);

    /* Short-circuit still skips the right operand. */
    c = z || side(0.0); check(c, 0);
    c = o || side(1.0); check(c, 1);
    c = z && side(1.0); check(c, 0);
    c = o && side(0.5); check(c, 1);
    check(calls, 2);

    /* do-while on a float counts down to exactly 0.0. */
    f64 g = 0.5;
    c = 0;
    do { c++; g -= 0.25; } while (g);
    check(c, 2);

    /* `!` on a pointer and an int is still 0/1. */
    u8 *p = null;
    c = !p; check(c, 1);
    p = "x";
    c = !p; check(c, 0);
    c = !256; check(c, 0);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
