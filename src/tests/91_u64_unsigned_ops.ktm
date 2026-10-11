import test;

import str;

/* `>>`, `/`, `%` and `<`/`>` on u64 behaved as signed (sarq/asr,
 * idiv/sdiv, signed compares): every integer binop was typed i64. An
 * operation is now unsigned when either operand is u64 (C's usual
 * arithmetic conversions with u64 = unsigned long). u8/u16/u32 promote
 * to i64, as they would to a 64-bit long in C. */

/* Globals are folded at compile time by the parser's evaluator. */
u64 gShr = (u64)0xC004000000000000 >> 63;
u64 gDiv = (u64)0x8000000000000000 / 3;
u64 gRem = (u64)0x8000000000000000 % 3;
i64 gGt  = (u64)0x8000000000000000 > 1;
i64 gSar = -8 >> 1;
u64 gLitDiv = 0xFFFFFFFFFFFFFFFF / 2;

/* Literals above I64_MAX are typed u64 (C does this for hex literals);
 * a #define keeps the type of the value it folds to. */
const u64 BIG_LIT = 0xC004000000000000;
const u64 BIG_EXPR = (0xFFFFFFFFFFFFFFFF / 2);

u64 gHi = 0xC004000000000000;
i64 gNeg = -8;

u64 shr(u64 a, i64 n) { return a >> n; }
u64 div(u64 a, u64 b) { return a / b; }
u64 rem(u64 a, u64 b) { return a % b; }
i64 gt(u64 a, u64 b) { return a > b; }
i64 sDiv(i64 a, i64 b) { return a / b; }
i64 sRem(i64 a, i64 b) { return a % b; }
i64 sar(i64 a, i64 n) { return a >> n; }

i32 main() {
    i64 correct = 0, tests = 38;

    printf("Test - u64 >>, /, %% and compares are unsigned: ");

    /* Runtime path through variables. */
    u64 w = gHi;
    u64 s = w >> 63;
    if (s == 1) { correct++; }
    u64 v = 1;
    v <<= 63;
    if (v / 3 == 3074457345618258602) { correct++; }
    if (v % 3 == 2) { correct++; }
    if (shr(w, 63) == 1) { correct++; }
    if (div(v, 3) == 3074457345618258602) { correct++; }
    if (rem(v, 3) == 2) { correct++; }
    if (gt(v, 1)) { correct++; }

    /* Compound assignments. */
    u64 t = w;
    t >>= 62;
    if (t == 3) { correct++; }
    t = v;
    t /= 3;
    if (t == 3074457345618258602) { correct++; }
    t = v;
    t %= 3;
    if (t == 2) { correct++; }

    /* Comparisons, including in a branch. */
    u64 big = v;
    if (big > 1) { correct++; }
    if (!(big < 1)) { correct++; }
    if (big >= 2 && 2 <= big) { correct++; }
    i64 m = -1;
    if ((m < big) == 0) { correct++; }  /* -1 converts to u64 max, like C */

    /* Mixing i64 and u64 is unsigned; the result is u64. */
    if (m / (u64)2 == 0x7FFFFFFFFFFFFFFF) { correct++; }
    if (m >> (u64)63 == 1) { correct++; }

    /* Narrow unsigned types promote to i64 and are zero-extended. */
    u32 x = 0xFFFFFFFF;
    if (x >> 31 == 1) { correct++; }
    if (x / 2 == 0x7FFFFFFF) { correct++; }
    u16 h = 0xFFFF;
    u8 c = 0xFF;
    if (h >> 15 == 1 && c >> 7 == 1) { correct++; }
    if (x > m) { correct++; }  /* u32 -> i64, signed compare as in C */

    /* Signed behaviour is unchanged. */
    i64 neg = gNeg;
    if (neg >> 1 == -4 && sar(neg, 1) == -4) { correct++; }
    i64 a = -7, b = 2;
    if (a / b == -3 && sDiv(a, b) == -3) { correct++; }
    if (a % b == -1 && sRem(a, b) == -1) { correct++; }
    neg >>= 1;
    if (neg == -4) { correct++; }

    /* Constant folding agrees with the runtime results. */
    if (gShr == 1) { correct++; }
    if (gDiv == 3074457345618258602 && gRem == 2) { correct++; }
    if (gGt == 1 && gSar == -4) { correct++; }
    if ((u64)0xC004000000000000 >> 63 == 1) { correct++; }
    if ((u64)0x8000000000000000 / 3 == 3074457345618258602 &&
            (u64)0x8000000000000000 % 3 == 2) { correct++; }
    if (-8 >> 1 == -4 && -7 / 2 == -3 && -7 % 2 == -1) { correct++; }

    /* Big literals are u64, literals that fit stay i64. */
    if (0xC004000000000000 >> 63 == 1) { correct++; }
    if (0xFFFFFFFFFFFFFFFF / 2 == 0x7FFFFFFFFFFFFFFF && gLitDiv == 0x7FFFFFFFFFFFFFFF) { correct++; }
    if (0x8000000000000000 > 1 && 18446744073709551615 % 10 == 5) { correct++; }
    if (BIG_LIT >> 63 == 1 && BIG_EXPR == 0x7FFFFFFFFFFFFFFF) { correct++; }
    if (U64_MAX > 1 && U64_MAX / 2 == I64_MAX) { correct++; }
    if (I64_MIN < 0 && I64_MIN / 2 == -4611686018427387904 && I64_MIN >> 62 == -2) { correct++; }
    if (-1 >> 1 == -1 && 0x7FFFFFFFFFFFFFFF > -1 && 9223372036854775807 > -1) { correct++; }
    if (!str.cmp(typeof(0xFFFFFFFFFFFFFFFF), "u64") &&
            !str.cmp(typeof(0x7FFFFFFFFFFFFFFF), "i64")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
