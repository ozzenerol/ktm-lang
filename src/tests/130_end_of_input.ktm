import fs, libc, mem, proc, str, test;

/* A file that ends in the middle of a construct is reported with an
 * "unexpected end of input" error and exit status 1. ktm used to loop
 * forever on `i64 g[3] = {1` (rewinding past the end of input handed
 * the previous token back again) and segfault on `i64 g[3] =`,
 * `extern`, `class C1 :` and `class N { i64 (*`, among others. */

#define EOF_TEST_FILE "__130_end_of_input.ktm"
#define EOF_TEST_OUT  "__130_end_of_input.out"

#define MSG_EOF "Unexpected end of input"

void cleanupEofTest(void) {
    fs.remove(EOF_TEST_FILE);
    fs.remove(EOF_TEST_OUT);
    fs.remove("./a.out");
}

/* Compile `source` (written without a trailing newline): ktm must exit
 * with status 1, not hang or crash, and print `expected`. `timeout`
 * guards against a hang where it exists. */
bool compileFailsAtEnd(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupEofTest();

    if (!fs.writeAll(EOF_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("T=; command -v timeout >/dev/null 2>&1 && T='timeout 10'; "
                             "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                             EOF_TEST_FILE " > " EOF_TEST_OUT " 2>&1");

    out = fs.readAll(EOF_TEST_OUT);
    if (!out) {
        cleanupEofTest();
        return false;
    }

    /* System gives the exit code: 1 for a compile error; a hang is
     * 124 and a segfault 139 (from the shell). */
    ok = res == 1 && libc.strstr(out, expected) != null;
    if (!ok) { printf("`%s`: status %d\n%s\n", source, res, out); }

    mem.free(out);
    cleanupEofTest();
    return ok;
}

i32 main() {
    printf("Test - End of input inside a construct: \n");
    i64 tests = 0;
    i64 correct = 0;

    /* The reported cases */
    u8 *cases[] = {
        "i64 g[3] = {1",
        "i64 g[3] = {1,",
        "i64 g[3] =",
        "extern",
        "struct B {i64 x;}; struct C1 :",
        "struct N { i64 (*",
        /* After a type */
        "i64",
        "static i64 *",
        "void main() { i64",
        "void main() { i64 *",
        /* Statements */
        "void main() { for (i64",
        "void main() { i64 i; for (i = 0; i",
        "void main() { if (1) {} else",
        "void main() { do {}",
        "void main() { printf(\"%s\", typeof",
        "void F() { static u8",
        "inline",
        "inline i64",
        /* Calls */
        "i64 F(i64 a) { return a; } void main() { F(",
        "void main() { \"%d\", 1,",
        /* Classes and initialisers */
        "struct U { union",
        "struct P { i64 a; i64 b; }; P p = {1, 2",
        "void main() { i64 a[2] = {{1}, {2"
    };
    i64 i, n = sizeof(cases) / sizeof(cases[0]);

    for (i = 0; i < n; i++) {
        tests++;
        if (compileFailsAtEnd(cases[i], MSG_EOF)) { correct++; }
        else { printf("Failed - case %d\n", i); }
    }

    /* A string literal or #include path cut off by the end of input */
    tests++;
    if (compileFailsAtEnd("void main() { \"abc", "Unterminated string")) { correct++; }
    else { printf("Failed - unterminated string\n"); }

    tests++;
    if (compileFailsAtEnd("#include \".", "Unterminated string")) { correct++; }
    else { printf("Failed - unterminated #include path\n"); }

    /* `defined(` cut off inside an #if condition */
    tests++;
    if (compileFailsAtEnd("#if defined(", "#if: the condition must be")) { correct++; }
    else { printf("Failed - #if defined(\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
