import str, test;

/* The parser reads tokens from a 16-token ring that the lexer fills
 * ahead of it, and macros were expanded only as the parser took each
 * token: by then a later `#undef` or `#define` of the same name had
 * already run, so a use just before it saw the new value (or none). */

#define N 3
i64 gA = N;
#undef N
#define N 4
i64 gB = N;

#define S "one"
u8 *gS1 = S;
#undef S
#define S "two"
u8 *gS2 = S;

#define V 10
i64 gC = V;
#undef V
i64 V = 7;

#define K (2 * 3)
i64 gK1 = K;
#undef K
#define K (N * 10)

#define F 1.5
f64 gF1 = F;
#undef F
#define F 2.5
f64 gF2 = F;

i64 inFunc() {
    i64 x = 0;
#define M 100
    x += M;
#undef M
#define M 1
    x += M;
#undef M
    return x;
}

i32 main() {
    i64 correct = 0, tests = 9;

    printf("Test - #undef and redefinitions apply in source order: ");

    if (gA == 3) { correct++; }
    if (gB == 4) { correct++; }
    if (!str.cmp(gS1, "one")) { correct++; }
    if (!str.cmp(gS2, "two")) { correct++; }
    if (gC == 10) { correct++; }
    if (V == 7) { correct++; }
    if (gK1 == 6 && K == 40) { correct++; }
    if (gF1 == 1.5 && gF2 == 2.5) { correct++; }
    if (inFunc() == 101) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
