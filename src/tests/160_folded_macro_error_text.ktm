import fs, libc, mem, proc, str, test;

/* A #define whose value is folded from several tokens (`(N + 1)`) got
 * an empty spelling, so an error on a use of it read "Unexpected
 * integer ``". The folded value is now spelled out. */

#define FM_TEST_FILE "__160_folded_macro_error_text.ktm"
#define FM_TEST_OUT  "__160_folded_macro_error_text.out"

void cleanupFoldedTest(void) {
    fs.remove(FM_TEST_FILE);
    fs.remove(FM_TEST_OUT);
}

/* Run `ktm -jit` on `source`: does it fail (or, with `ok`, pass) and
 * print `expected`? */
bool jitOutputHas(u8 *source, bool ok, u8 *expected) {
    u8 *out;
    i64 res;
    bool good = true;

    cleanupFoldedTest();
    if (!fs.writeAll(FM_TEST_FILE, source, str.len(source),
                                 libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " FM_TEST_FILE " > " FM_TEST_OUT " 2>&1");
    if ((res == 0) != ok) { good = false; }
    out = fs.readAll(FM_TEST_OUT);
    if (!out) {
        cleanupFoldedTest();
        return false;
    }
    if (!libc.strstr(out, expected)) { good = false; }
    if (!good) { printf("%s\n", out); }
    mem.free(out);
    cleanupFoldedTest();
    return good;
}

/* the values are still right */
#define V_N 3
#define V_I (V_N + 1)
#define V_F (1.5 * 2)
#define V_NEG (0 - 5)
#define V_U (0xFFFFFFFFFFFFFFFF + 0)
#define V_C ('a' + 1)
#define V_S ("ab" "cd")

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - error text of a folded macro: ");

    tests++;
    if (jitOutputHas("#define N 3\n#define T (N + 1)\nvoid main() { i64 x = 2 T; }\n",
                                     false, "Unexpected integer `4`")) { correct++; }
    tests++;
    if (jitOutputHas("#define T (1.5 * 2)\nvoid main() { f64 x = 2 T; }\n",
                                     false, "Unexpected float `3.0`")) { correct++; }
    tests++;
    if (jitOutputHas("#define T (1.0 / 4)\nvoid main() { f64 x = 2 T; }\n",
                                     false, "Unexpected float `0.25`")) { correct++; }
    tests++;
    if (jitOutputHas("#define T (0 - 5)\nvoid main() { i64 x = 2 T; }\n",
                                     false, "Unexpected integer `-5`")) { correct++; }
    tests++;
    if (jitOutputHas("#define T (0xFFFFFFFFFFFFFFFF + 0)\nvoid main() { i64 x = 2 T; }\n",
                                     false, "`18446744073709551615`")) { correct++; }
    /* the spelling stays where it was right already */
    tests++;
    if (jitOutputHas("#define T 7\nvoid main() { i64 x = 2 T; }\n",
                                     false, "Unexpected integer `7`")) { correct++; }

    tests++;
    if (V_I == 4 && V_NEG == -5 && V_C == 'b') { correct++; }
    tests++;
    if (V_F == 3.0) { correct++; }
    tests++;
    if ((u64)V_U == 0xFFFFFFFFFFFFFFFF) { correct++; }
    tests++;
    if (!str.cmp(V_S, "abcd")) { correct++; }

    cleanupFoldedTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
