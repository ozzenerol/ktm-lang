import test;

/* A for loop's initialiser can be several comma-separated expressions,
 * as its step can: `for (k = 0, i = n; ...; k++, i--)`. Only declarations
 * could be before v0.22.0; `k = 0, i = 9` made `i = 9` the condition. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - for comma initialiser: ");
    i64 k, i, j, n = 0;

    for (k = 0, i = 9; k < 3; k++, i--) { n++; }
    check(k == 3 && i == 6 && n == 3, "two assignments, in parentheses");

    j = 1;
    for k = 0, i = 9, j = 1; k < 2; k++ { j *= 2; }
    check(k == 2 && i == 9 && j == 4, "three, without parentheses");

    i = 0;
    for (k = 0, i++; k < 1; k++) { }
    check(k == 1 && i == 1, "any expressions, run in order");

    for (k = 7; k < 8; k++) { }
    check(k == 8, "one assignment as before");

    for (i64 a = 0, b = 5; a < b; a++, b--) { n = a + b; }
    check(n == 5, "declarations as before");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
