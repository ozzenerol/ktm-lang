import libc, mem, str, test;

/* str.cmp / str.cmpLen / str.cmpLenNoCase must order strings like C's strcmp /
 * strncmp / strncasecmp: negative if s1 < s2, 0 if equal, positive if
 * s1 > s2, comparing bytes as unsigned. On x86_64 str.cmp had the sign
 * reversed, str.cmpLenNoCase returned the distance between the two pointers
 * (even for equal strings shorter than n) and str.cmpLen kept comparing
 * past the terminating NUL. */

extern "c" i32 strncasecmp(u8 *s1, u8 *s2, u64 n);

i64 sign(i64 x) {
    if (x < 0) { return -1; }
    if (x > 0) { return 1; }
    return 0;
}

bool cmpOk(u8 *a, u8 *b, i64 want) {
    i32 c = libc.strcmp(a, b);
    return sign(str.cmp(a, b)) == want && sign(c) == want;
}

bool nCmpOk(u8 *a, u8 *b, i64 n, i64 want) {
    i32 c = libc.strncmp(a, b, n);
    return sign(str.cmpLen(a, b, n)) == want && sign(c) == want;
}

bool niCmpOk(u8 *a, u8 *b, i64 n, i64 want) {
    i32 c = libc.strncasecmp(a, b, n);
    return sign(str.cmpLenNoCase(a, b, n)) == want && sign(c) == want;
}

i32 main() {
    i64 correct = 0, tests = 30;

    printf("Test - StrCmp/StrNCmp/StrNICmp sign matches C: ");

    /* High bytes compare as unsigned: "\xff" > "a" */
    u8 hi[2], lo[2];
    hi[0] = 0xFF; hi[1] = 0;
    lo[0] = 'a';  lo[1] = 0;

    /* Equal up to the NUL, different garbage after it */
    u8 g1[8], g2[8];
    mem.set(g1, 0, 8);
    mem.set(g2, 0, 8);
    g1[0] = 'a'; g1[1] = 'b'; g1[3] = 'X';
    g2[0] = 'a'; g2[1] = 'b'; g2[3] = 'Y';

    /* str.cmp */
    if (cmpOk("abc", "abd", -1)) { correct++; }
    if (cmpOk("abd", "abc", 1)) { correct++; }
    if (cmpOk("abc", "abc", 0)) { correct++; }
    if (cmpOk("ab", "abc", -1)) { correct++; }
    if (cmpOk("abc", "ab", 1)) { correct++; }
    if (cmpOk("", "", 0)) { correct++; }
    if (cmpOk("", "a", -1)) { correct++; }
    if (cmpOk("a", "", 1)) { correct++; }
    if (cmpOk(hi, lo, 1) && cmpOk(lo, hi, -1)) { correct++; }

    /* str.cmpLen */
    if (nCmpOk("abc", "abd", 3, -1)) { correct++; }
    if (nCmpOk("abd", "abc", 3, 1)) { correct++; }
    if (nCmpOk("abc", "abd", 2, 0)) { correct++; }  /* n stops before the difference */
    if (nCmpOk("abc", "abc", 10, 0)) { correct++; }
    if (nCmpOk("ab", "abc", 3, -1)) { correct++; }
    if (nCmpOk("abc", "ab", 3, 1)) { correct++; }
    if (nCmpOk("", "a", 1, -1)) { correct++; }
    if (nCmpOk("abc", "xyz", 0, 0)) { correct++; }
    if (nCmpOk(hi, lo, 1, 1) && nCmpOk(lo, hi, 1, -1)) { correct++; }
    if (nCmpOk(g1, g2, 8, 0)) { correct++; }        /* stops at the NUL */

    /* str.cmpLenNoCase */
    if (niCmpOk("abc", "ABD", 3, -1)) { correct++; }
    if (niCmpOk("ABD", "abc", 3, 1)) { correct++; }
    if (niCmpOk("Hello", "hELLO", 5, 0)) { correct++; }
    if (niCmpOk("Hello", "hELLO", 10, 0)) { correct++; } /* equal, shorter than n */
    if (niCmpOk("abc", "ABD", 2, 0)) { correct++; }
    if (niCmpOk("ab", "ABC", 3, -1)) { correct++; }
    if (niCmpOk("ABC", "ab", 3, 1)) { correct++; }
    if (niCmpOk("_", "A", 1, -1)) { correct++; }    /* lowercases, like C */
    if (niCmpOk("", "", 5, 0) && niCmpOk("x", "y", 0, 0)) { correct++; }
    if (niCmpOk(hi, lo, 1, 1) && niCmpOk(lo, hi, 1, -1)) { correct++; }
    if (niCmpOk(g1, g2, 8, 0)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
