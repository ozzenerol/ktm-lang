import cstr, test;

/* `#define F (1.5 * N)` with `#define N 3` folded to the i64 3: the
 * define took the type of the last macro (or the first literal) in its
 * value, so the float was truncated. A numeric define has the type of
 * its value, as C's arithmetic conversions give it: any float operand
 * makes it an f64, otherwise it is an i64. */

#define N          3
#define HALF       0.5
#define F_FIRST    (1.5 * N)
#define F_LAST     (N * 1.5)
#define F_MID      (N + 1.5 + N)
#define F_MACROS   (N * HALF)
#define F_MIX      (1 + 2.5)
#define F_ONLY     (1.5 * 2.0)
#define F_DIV      (N / 2.0)
#define F_NEG      (-N * 0.5)
#define F_NESTED   (F_FIRST + N)
#define I_ONLY     (N * 4 + 1)
#define I_DIV      (7 / 2)
#define I_NESTED   (I_ONLY - N)
#define I_CAST     ((i64)2.7 * N)
#define I_CMP_F    (1.5 > 1)
#define I_CMP_M    (N > 2.5)
#define I_CMP_EQ   (N == 3.0)
#define C_PLUS     ('a' + 1)
#define C_HALF     ('d' * 0.5)
#define S_VAL      "str"
#define S_COPY     (S_VAL)

i32 main() {
    i64 correct = 0, tests = 20;

    printf("Test - #define with mixed int and float operands: ");

    if (F_FIRST == 4.5 && !cstr.cmp(typeof(F_FIRST), "f64")) { correct++; }
    if (F_LAST == 4.5 && !cstr.cmp(typeof(F_LAST), "f64")) { correct++; }
    if (F_MID == 7.5 && !cstr.cmp(typeof(F_MID), "f64")) { correct++; }
    if (F_MACROS == 1.5 && !cstr.cmp(typeof(F_MACROS), "f64")) { correct++; }
    if (F_MIX == 3.5 && !cstr.cmp(typeof(F_MIX), "f64")) { correct++; }
    if (F_ONLY == 3.0 && !cstr.cmp(typeof(F_ONLY), "f64")) { correct++; }
    if (F_DIV == 1.5 && !cstr.cmp(typeof(F_DIV), "f64")) { correct++; }
    if (F_NEG == -1.5 && !cstr.cmp(typeof(F_NEG), "f64")) { correct++; }
    if (F_NESTED == 7.5 && !cstr.cmp(typeof(F_NESTED), "f64")) { correct++; }

    if (I_ONLY == 13 && !cstr.cmp(typeof(I_ONLY), "i64")) { correct++; }
    if (I_DIV == 3 && !cstr.cmp(typeof(I_DIV), "i64")) { correct++; }
    if (I_NESTED == 10 && !cstr.cmp(typeof(I_NESTED), "i64")) { correct++; }
    if (I_CAST == 6 && !cstr.cmp(typeof(I_CAST), "i64")) { correct++; }

    /* A comparison is an integer, but compares its operands as floats. */
    if (I_CMP_F == 1 && !cstr.cmp(typeof(I_CMP_F), "i64")) { correct++; }
    if (I_CMP_M == 1 && I_CMP_EQ == 1) { correct++; }

    if (C_PLUS == 'b') { correct++; }
    if (C_HALF == 50.0 && !cstr.cmp(typeof(C_HALF), "f64")) { correct++; }
    if (!cstr.cmp(S_VAL, "str") && !cstr.cmp(S_COPY, "str")) { correct++; }

    /* The same values outside a macro. */
    if (F_FIRST == 1.5 * N && F_LAST == N * 1.5) { correct++; }
    if (F_MIX == 1 + 2.5 && I_DIV == 7 / 2) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
