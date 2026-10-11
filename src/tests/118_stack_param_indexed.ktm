#include "testhelper.ktm"

/* Parameters past the argument registers (the 7th integer argument on
 * x86_64, the 9th on arm64, the 9th float on x86_64) arrive on the
 * stack. When such a param was read only as the index of `p[i]` or as
 * a returned value, it got no stack slot and the compiler panicked
 * with `ir-regalloc: no slot for var.id=.. kind=5`:
 * `i64 X(i64 a, ..., i64 f, i64 *p, i64 i) { return p[i]; }`. */

/* x86_64: p and i on the stack */
i64 ld8(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 *p, i64 i)
{ return p[i]; }
i64 ldCopy8(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 *p, i64 i)
{ i64 *q = p; return q[i]; }
/* Register pointer, stack index */
i64 ldMix7(i64 *p, i64 b, i64 c, i64 d, i64 e, i64 f, i64 i)
{ return p[i]; }
/* Stack pointer, register index */
i64 ldMixR7(i64 i, i64 b, i64 c, i64 d, i64 e, i64 f, i64 *p)
{ return p[i]; }
/* Plain use of a stack param */
i64 ret7(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 i) { return i; }
i64 ret10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                    i64 j, i64 k) { return k; }
i64 sum10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                    i64 j, i64 k) { return a + b + c + d + e + f + g + h + j + k; }
/* arm64: p and i on the stack (9th and 10th) */
i64 ld10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                 i64 *p, i64 i) { return p[i]; }
i64 ldCopy10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                         i64 *p, i64 i) { i64 *q = p; return q[i]; }
/* Stack param reassigned before use */
i64 bump9(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                    i64 i) { i = i + 1; return i; }
u8 ldU810(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                     u8 *p, i64 i) { return p[i]; }
f64 ldF6410(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                         f64 *p, i64 i) { return p[i]; }

/* Stores and compound assignment */
void st8(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 *p, i64 i)
{ p[i] = 77; }
void stV10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                 i64 *p, i64 i, i64 v) { p[i] = v; }
void add10(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                 i64 *p, i64 i) { p[i] += 5; }
void stF6410(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                        f64 *p, i64 i, f64 v) { p[i] = v; }
void addF649(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                        f64 *p, i64 i) { p[i] += 1.0; }

/* Float params past the 8 float registers */
f64 retF9(f64 a, f64 b, f64 c, f64 d, f64 e, f64 f, f64 g, f64 h, f64 k)
{ return k; }

i32 main() {
    i64 correct = 0, tests = 22;

    printf("Test - indexed access with stack-passed parameters: ");

    i64 v[4] = {10, 20, 30, 40};
    u8 b[4] = {1, 2, 3, 4};
    f64 fv[4] = {0.5, 1.5, 2.5, 3.5};

    /* Loads */
    if (ld8(0, 0, 0, 0, 0, 0, v, 2) == 30) { correct++; }
    if (ldCopy8(0, 0, 0, 0, 0, 0, v, 3) == 40) { correct++; }
    if (ldMix7(v, 0, 0, 0, 0, 0, 1) == 20) { correct++; }
    if (ldMixR7(2, 0, 0, 0, 0, 0, v) == 30) { correct++; }
    if (ld10(0, 0, 0, 0, 0, 0, 0, 0, v, 1) == 20) { correct++; }
    if (ldCopy10(0, 0, 0, 0, 0, 0, 0, 0, v, 0) == 10) { correct++; }
    if (ldU810(0, 0, 0, 0, 0, 0, 0, 0, b, 3) == 4) { correct++; }
    if (ldF6410(0, 0, 0, 0, 0, 0, 0, 0, fv, 2) == 2.5) { correct++; }

    /* Plain stack params */
    if (ret7(0, 0, 0, 0, 0, 0, 5) == 5) { correct++; }
    if (ret10(0, 0, 0, 0, 0, 0, 0, 0, 0, 9) == 9) { correct++; }
    if (sum10(1, 2, 3, 4, 5, 6, 7, 8, 9, 10) == 55) { correct++; }
    if (bump9(0, 0, 0, 0, 0, 0, 0, 0, 41) == 42) { correct++; }
    if (retF9(0, 0, 0, 0, 0, 0, 0, 0, 1.25) == 1.25) { correct++; }

    /* Stores, +=, f64 elements */
    st8(0, 0, 0, 0, 0, 0, v, 0);
    if (v[0] == 77) { correct++; }
    stV10(0, 0, 0, 0, 0, 0, 0, 0, v, 1, -3);
    if (v[1] == -3) { correct++; }
    add10(0, 0, 0, 0, 0, 0, 0, 0, v, 2);
    if (v[2] == 35) { correct++; }
    if (v[3] == 40) { correct++; }
    stF6410(0, 0, 0, 0, 0, 0, 0, 0, fv, 0, 9.5);
    if (fv[0] == 9.5) { correct++; }
    addF649(0, 0, 0, 0, 0, 0, 0, 0, fv, 3);
    if (fv[3] == 4.5) { correct++; }
    if (fv[1] == 1.5) { correct++; }

    /* Through a function pointer */
    fn(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
                        i64 *p, i64 i) i64 fp = &ld10;
    if (fp(0, 0, 0, 0, 0, 0, 0, 0, v, 3) == 40) { correct++; }
    if (ld8(0, 0, 0, 0, 0, 0, v, 1) == -3) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
