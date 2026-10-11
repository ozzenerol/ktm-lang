import fs, libc, mem, proc, str, test;

#define DW_TEST_FILE "__141_do_while_fail.ktm"
#define DW_TEST_OUT  "__141_do_while_fail.out"

/* ktm is run with a timeout where one exists: the undefined-function
 * error used to walk the token ring forever on long argument lists. */
#define DW_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 30) "

void cleanupDoWhileTest(void) {
    fs.remove(DW_TEST_FILE);
    fs.remove(DW_TEST_OUT);
    fs.remove("./a.out");
}

/* Compile `source`, expect a failure whose output contains both
 * `expected` and the location `where`. */
bool compileFailsWith(u8 *source, u8 *expected, u8 *where) {
    u8 *out;
    i64 res;

    cleanupDoWhileTest();

    if (!fs.writeAll(DW_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell(DW_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DW_TEST_FILE " > " DW_TEST_OUT " 2>&1");
    if (res == 0) {
        printf("  compiled, expected: %s\n", expected);
        cleanupDoWhileTest();
        return false;
    }

    out = fs.readAll(DW_TEST_OUT);
    if (!out) {
        cleanupDoWhileTest();
        return false;
    }

    if (!libc.strstr(out, expected) || !libc.strstr(out, where)) {
        printf("  expected `%s` at `%s`, got:\n%s\n", expected, where, out);
        mem.free(out);
        cleanupDoWhileTest();
        return false;
    }

    mem.free(out);
    cleanupDoWhileTest();
    return true;
}

bool testDoIf(void) {
    return compileFailsWith(
"void main()
{
  i64 i = 0;
  do { i++; } if (i < 3);
  printf(\"%d\\n\", i);
}
",
        "expected `while` after do block, got `if`",
        DW_TEST_FILE ":4:15");
}

bool testDoIdent(void) {
    return compileFailsWith(
"void main()
{
  i64 i = 0;
  do { i++; }
  i = 2;
}
",
        "expected `while` after do block, got `i`",
        DW_TEST_FILE ":5:3");
}

bool testDoFor(void) {
    return compileFailsWith(
"void main()
{
  i64 i = 0;
  do { i++; } for (;;);
}
",
        "expected `while` after do block, got `for`",
        DW_TEST_FILE ":4:15");
}

bool testInnerDoMissingWhile(void) {
    return compileFailsWith(
"void main()
{
  i64 i = 0, j = 0;
  do {
    do { j++; } if (j < 2);
    i++;
  } while (i < 2);
}
",
        "expected `while` after do block, got `if`",
        DW_TEST_FILE ":5:17");
}

/* An undefined function's error must point at its name, not at the
 * first argument token with the same length or prefix. */
bool testUndefinedFnSameLenArg(void) {
    return compileFailsWith(
"i64 abc = 1;
foo(abc);
",
        "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

bool testUndefinedFnPrefixArg(void) {
    return compileFailsWith(
"i64 foobar = 1;
foo(foobar);
",
        "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

bool testUndefinedFnMultiline(void) {
    return compileFailsWith(
"i64 abc = 1;
foo(1,
    abc);
",
        "Function: foo() not defined", DW_TEST_FILE ":2:1");
}

/* Name pushed out of the token ring: must still error, not hang. */
bool testUndefinedFnLongArgs(void) {
    return compileFailsWith(
"foo(1,2,3,4,5,6,7,8,9,10,11,12);
",
        "Function: foo() not defined", DW_TEST_FILE ":1:");
}

i64 doWhileBasic() {
    i64 i = 0;
    do { i++; } while (i < 3);
    return i;
}

i64 doWhileRunsOnce() {
    i64 i = 10;
    do { i++; } while (i < 3);
    return i;
}

i64 doWhileBodyIf() {
    i64 i = 0, odd = 0;
    do {
        if (i & 1) { odd++; }
        i++;
    } while (i < 7);
    return odd;
}

/* A do-while followed directly by an if. */
i64 doWhileThenIf() {
    i64 i = 0;
    do { i++; } while (i < 4);
    if (i == 4) { i = 40; }
    return i;
}

i64 doWhileNested() {
    i64 i = 0, j, n = 0;
    do {
        j = 0;
        do {
            n++;
            j++;
        } while (j < 3);
        i++;
    } while (i < 4);
    return n;
}

i64 doWhileStatementBody() {
    i64 i = 0;
    do { i += 2; } while (i < 9);
    return i;
}

void main() {
    printf("Test - do while requires while: ");
    i64 correct = 0, tests = 0, r;

    tests++; if ((r = doWhileBasic()) == 3) { correct++; } else { printf("\n  DoWhileBasic: %d\n", r); }
    tests++; if ((r = doWhileRunsOnce()) == 11) { correct++; } else { printf("\n  DoWhileRunsOnce: %d\n", r); }
    tests++; if ((r = doWhileBodyIf()) == 3) { correct++; } else { printf("\n  DoWhileBodyIf: %d\n", r); }
    tests++; if ((r = doWhileThenIf()) == 40) { correct++; } else { printf("\n  DoWhileThenIf: %d\n", r); }
    tests++; if ((r = doWhileNested()) == 12) { correct++; } else { printf("\n  DoWhileNested: %d\n", r); }
    tests++; if ((r = doWhileStatementBody()) == 10) { correct++; } else { printf("\n  DoWhileStatementBody: %d\n", r); }

    tests++; if (testDoIf()) { correct++; }
    tests++; if (testDoIdent()) { correct++; }
    tests++; if (testDoFor()) { correct++; }
    tests++; if (testInnerDoMissingWhile()) { correct++; }
    tests++; if (testUndefinedFnSameLenArg()) { correct++; }
    tests++; if (testUndefinedFnPrefixArg()) { correct++; }
    tests++; if (testUndefinedFnMultiline()) { correct++; }
    tests++; if (testUndefinedFnLongArgs()) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
}
