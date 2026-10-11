import cstr, fs, libc, mem, proc, test;

#define IF_TEST_FILE "__90_if_expr_fail.ktm"
#define IF_TEST_OUT  "__90_if_expr_fail.out"

/* Valid conditions must keep working. */
#define IF_THREE 3
#define IF_FLAG
#define IF_STR "abc"

void cleanupIfTest(void) {
    fs.remove(IF_TEST_FILE);
    fs.remove(IF_TEST_OUT);
    fs.remove("./a.out");
}

/* A bad #if/#elif condition used to exit from the macro processor with
 * "Unexpected end of input Parsing macro:1" - no file, no line. It must
 * be a normal compile failure (exit status 1) reporting `expected` and
 * the directive's file:line. */
bool compileFailsWith(u8 *source, u8 *expected, u8 *where) {
    u8 *out;
    i64 res;

    cleanupIfTest();

    if (!fs.writeAll(IF_TEST_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " IF_TEST_FILE " > " IF_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        cleanupIfTest();
        return false;
    }

    out = fs.readAll(IF_TEST_OUT);
    if (!out) {
        cleanupIfTest();
        return false;
    }

    if (!libc.strstr(out, expected) || !libc.strstr(out, where) ||
            libc.strstr(out, "Parsing macro")) {
        printf("%s\n", out);
        mem.free(out);
        cleanupIfTest();
        return false;
    }

    mem.free(out);
    cleanupIfTest();
    return true;
}

bool testIfTrailingOp(void) {
    return compileFailsWith(
"void Main() { }
#if 1 +
#endif
",
        "#if: the condition must be a constant expression",
        IF_TEST_FILE ":2:");
}

bool testIfOpenParen(void) {
    return compileFailsWith(
"void Main() { }

#if (
#endif
",
        "#if: the condition must be a constant expression",
        IF_TEST_FILE ":3:");
}

bool testIfCall(void) {
    return compileFailsWith(
"#if foo(
#endif
void Main() { }
",
        "#if: the condition must be a constant expression",
        IF_TEST_FILE ":1:");
}

bool testElifTrailingOp(void) {
    return compileFailsWith(
"void main()
{
#if 0
  printf(\"a\\n\");
#elif 1 *
  printf(\"b\\n\");
#endif
}
",
        "#elif: the condition must be a constant expression",
        IF_TEST_FILE ":5:");
}

/* A string is not a number: this used to panic in the evaluator. */
bool testIfString(void) {
    return compileFailsWith(
"#if \"s\"
#endif
void Main() { }
",
        "#if: the condition must be a constant expression",
        IF_TEST_FILE ":1:");
}

bool testValidConditions(void) {
    i64 hits = 0;
#if 1
    hits++;
#endif
#if IF_THREE * 2 - 6
    hits = -100;
#elif IF_THREE > 2 && defined(IF_FLAG)
    hits++;
#endif
#if !defined(IF_NOT_DEFINED) && (IF_THREE + 1) == 4
    hits++;
#endif
#if defined(IF_STR)
    hits++;
#endif
#if UNDEFINED_NAME
    hits = -100;
#else
    hits++;
#endif
#if 0
    hits = -100;
#elif 2 / 2
    hits++;
#endif
    return hits == 6;
}

i32 main() {
    printf("Test - #if expression errors: \n");
    i64 tests = 6;
    i64 correct = 0;

    if (testIfTrailingOp()) { correct++; }
    else { printf("Failed - TestIfTrailingOp()\n"); }

    if (testIfOpenParen()) { correct++; }
    else { printf("Failed - TestIfOpenParen()\n"); }

    if (testIfCall()) { correct++; }
    else { printf("Failed - TestIfCall()\n"); }

    if (testElifTrailingOp()) { correct++; }
    else { printf("Failed - TestElifTrailingOp()\n"); }

    if (testIfString()) { correct++; }
    else { printf("Failed - TestIfString()\n"); }

    if (testValidConditions()) { correct++; }
    else { printf("Failed - TestValidConditions()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
