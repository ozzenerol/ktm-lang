import test;

import str;

/* The conditional operator `cond ? a : b`: right-associative, lower
 * precedence than ||, only the chosen branch evaluated, usual arithmetic
 * conversions between the branches, pointer and null branches, nested,
 * and usable in constant expressions (array sizes, global initialisers,
 * #if). Not an lvalue (that is a compile error, checked separately). */

i64 gArr[1 > 0 ? 4 : 9];      /* size folds to 4 */
i64 gInit = 3 > 2 ? 42 : 99;  /* global initialiser folds to 42 */
#if 1 ? 2 : 0
const i64 PP_TAKEN = 1;
#else
const i64 PP_TAKEN = 0;
#endif

i64 gCalls = 0;
i64 bump(i64 x) { gCalls++; return x; }
i64 dbl(i64 x) { return x * 2; }
i64 trp(i64 x) { return x * 3; }

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void basics() {
    i64 a = 5, b = 10;
    check((a > b ? a : b) == 10, "max");
    check((a < b ? a : b) == 5, "min");
    check((a > b ? 1 : 0) == 0, "false branch");
    check((true ? 7 : 8) == 7, "constant condition true");
    /* Lower precedence than ||: `a || b ? c : d` is `(a||b) ? c : d`. */
    check((0 || 1 ? 11 : 22) == 11, "binds looser than ||");
    check((1 && 0 ? 11 : 22) == 22, "binds looser than &&");
}

void nested() {
    i64 x = 2;
    /* Right-associative: a ? b : c ? d : e is a ? b : (c ? d : e). */
    check((x == 1 ? 10 : x == 2 ? 20 : 30) == 20, "right-assoc else chain");
    check((0 ? 1 : 0 ? 2 : 3) == 3, "all-false chain");
    /* Parenthesised middle. */
    check((x == 2 ? x == 2 ? 100 : 101 : 102) == 100, "nested in the middle");
}

void onlyOneBranch() {
    i64 a = 1;
    gCalls = 0;
    i64 r = a ? bump(7) : bump(9);
    check(r == 7 && gCalls == 1, "only the true branch ran");
    gCalls = 0;
    r = 0 ? bump(7) : bump(9);
    check(r == 9 && gCalls == 1, "only the false branch ran");
    /* A branch that would fault must not run when not chosen. */
    i64 *p = null;
    check((a ? 55 : *p) == 55, "unchosen branch (deref null) is skipped");
}

void conversions() {
    i64 i = 3;
    f64 f = 1.5;
    u8 c = 65;
    u64 big = 0xFFFFFFFFFFFFFFFF;
    /* int and float branches: result is float. */
    check((1 ? f : i) == 1.5, "int/float branch is float");
    check((0 ? f : i) == 3.0, "float result from the int branch");
    /* char stays its value. */
    check((1 ? c : 0) == 65, "char branch");
    /* unsigned 64-bit kept. */
    check((1 ? big : 0) == 0xFFFFFFFFFFFFFFFF, "u64 branch");
    /* both negative ints. */
    check((0 ? 1 : -7) == -7, "negative int branch");
}

void pointers() {
    i64 a = 1;
    u8 *s = a ? "yes" : "no";
    check(!str.cmp(s, "yes"), "string branches");
    check(!str.cmp(a > 1 ? "yes" : "no", "no"), "string false branch");
    /* pointer and null. */
    u8 *p = a ? s : null;
    check(p != null, "pointer / null, pointer chosen");
    u8 *q = a > 1 ? s : null;
    check(q == null, "pointer / null, null chosen");
}

void constExpr() {
    check(sizeof(gArr) / sizeof(i64) == 4, "array size from a ternary");
    check(gInit == 42, "global initialiser from a ternary");
    check(PP_TAKEN == 1, "#if with a ternary");
}

void fnPtrCallThrough() {
    i64 a = 1;
    /* Calls the chosen function. Needs the call-on-parenthesised-expr
     * feature (feat/call-paren-expr) in the rc. */
    check((a ? &dbl : &trp)(10) == 20, "call through (cond ? f : g)(x), true");
    check((a > 1 ? &dbl : &trp)(10) == 30, "call through, false");
}

i32 main() {
    printf("Test - conditional operator: ");
    basics();
    nested();
    onlyOneBranch();
    conversions();
    pointers();
    constExpr();
    fnPtrCallThrough();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
