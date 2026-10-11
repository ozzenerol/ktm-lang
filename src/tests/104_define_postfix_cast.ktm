import str, test;

/* A postfix cast in a #define's value, `#define X (5(u64) % 3)`, crashed
 * ktm: the stub parser that folds a define had no type table to look `u64`
 * up in. The cast must fold the way it runs, and set the define's type. */

#define CAST_MOD    ((u64)5 % 3)
#define CAST_ONLY   (u64)5
#define CAST_END    (1 + (u8)2)
#define CAST_U8     (u8)300
#define CAST_I8     ((i8)200)
#define CAST_U32    (u32)(-1)
#define CAST_PTR    (u8*)0x1000
#define CAST_PTR_OP ((u8*)0 + 3)
#define CAST_F64    (f64)5
#define CAST_F64_OP ((f64)5 / 2)
#define CAST_TO_INT (i64)2.7
#define CAST_INT_OP ((i64)2.7 * 2)
#define CAST_NESTED (CAST_ONLY % 3)
#define CAST_TWICE  ((u8)CAST_NESTED + (i16)1)
#define CAST_FMAC   (CAST_F64 * (f64)CAST_ONLY)
#define PLAIN_INT   (1 + 2)
#define PLAIN_MIX   (1 + 2.5)

#if (u64)7 % 4 == 3
#define CAST_IF 1
#else
#define CAST_IF 0
#endif

i32 main() {
    i64 correct = 0, tests = 20;

    printf("Test - postfix cast inside a #define: ");

    if (CAST_MOD == 2) { correct++; }
    if (CAST_ONLY == 5 && !str.cmp(typeof(CAST_ONLY), "u64")) { correct++; }
    if (!str.cmp(typeof(CAST_MOD), "u64")) { correct++; }
    if (CAST_END == 3) { correct++; }
    if (CAST_U8 == 44 && CAST_U8 == (u8)300) { correct++; }
    if (CAST_I8 == -56) { correct++; }
    if (CAST_U32 == 4294967295) { correct++; }
    if (CAST_PTR == 4096) { correct++; }
    if (CAST_PTR_OP == 3) { correct++; }
    if (CAST_F64 == 5.0 && !str.cmp(typeof(CAST_F64), "f64")) { correct++; }
    if (CAST_F64_OP == 2.5) { correct++; }
    if (CAST_TO_INT == 2 && !str.cmp(typeof(CAST_TO_INT), "i64")) { correct++; }
    if (CAST_INT_OP == 4) { correct++; }
    if (CAST_NESTED == 2) { correct++; }
    if (CAST_TWICE == 3) { correct++; }
    if (CAST_FMAC == 25.0) { correct++; }
    if (CAST_IF == 1) { correct++; }

    /* The same expressions outside a macro. */
    if (((u64)5 % 3) == CAST_MOD && ((f64)5 / 2) == CAST_F64_OP) { correct++; }

    /* Without a cast a define has the type of its value: any float
     * operand makes it an f64 (test 127). */
    if (PLAIN_INT == 3 && !str.cmp(typeof(PLAIN_INT), "i64")) { correct++; }
    if (PLAIN_MIX == 3.5 && !str.cmp(typeof(PLAIN_MIX), "f64")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
