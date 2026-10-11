import test;

import fs, libc, mem, proc, str;

#define FN_MACRO_TEST_FILE "__72_fn_macro_fail.ktm"
#define FN_MACRO_TEST_OUT  "__72_fn_macro_fail.out"

/* Object-like macros must keep working: `#define NAME (expr)` with a
 * space before the paren is an ordinary define, not a function-like
 * macro. */
#define CALC (2 * 21)
#define CALC_BASE 40
#define CALC_REF  (CALC_BASE + 2)
#define CALC_STR  "forty two"
#define CALC_F64  (40.5 + 1.5)
#define CALC_CHAR 'A'
#define CALC_CHR  'a' + 1
#define CALC_SUFFIX 2048U
#define CALC_FLAG
#define CALC_GONE 1
#undef CALC_GONE

void cleanupFnMacroTest(void) {
    fs.remove(FN_MACRO_TEST_FILE);
    fs.remove(FN_MACRO_TEST_OUT);
    fs.remove("./a.out");
}

/* The compile must fail normally (exit status 1), not die on a signal,
 * and print `expected`. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupFnMacroTest();

    if (!fs.writeAll(FN_MACRO_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FN_MACRO_TEST_FILE " > " FN_MACRO_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        cleanupFnMacroTest();
        return false;
    }

    out = fs.readAll(FN_MACRO_TEST_OUT);
    if (!out) {
        cleanupFnMacroTest();
        return false;
    }

    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupFnMacroTest();
        return false;
    }

    mem.free(out);
    cleanupFnMacroTest();
    return true;
}

bool testFunctionLikeMacro(void) {
    return compileFailsWith(
"#define DOUBLE(x) ((x)*2)
void main() { printf(\"%d\\n\", DOUBLE(21)); }
",
        "function-like macros are not supported: #define DOUBLE(x) ((x)*2)");
}

bool testEmptyParamMacro(void) {
    return compileFailsWith(
"#define X()
void Main() { }
",
        "function-like macros are not supported: #define X()");
}

bool testUnclosedParamMacro(void) {
    return compileFailsWith(
"#define X(
void Main() { }
",
        "function-like macros are not supported: #define X(");
}

bool testDefineNoName(void) {
    return compileFailsWith(
"#define
void Main() { }
",
        "Syntax is: #define <TK_IDENT> <value>");
}

bool testDefineBadName(void) {
    /* `1 x`, not `1x`: that is now a malformed number literal. */
    return compileFailsWith(
"#define 1 x
void Main() { }
",
        "Syntax is: #define <TK_IDENT> <value>");
}

/* An unknown name inside a multi-token value used to crash the
 * macro evaluator. */
bool testDefineUnknownName(void) {
    return compileFailsWith(
"#define Y (a + 1)
void Main() { }
",
        "#define Y: the value must be a constant expression");
}

bool testDefineUnclosedValue(void) {
    return compileFailsWith(
"#define Y (1 + 2
void Main() { }
",
        "#define Y: the value must be a constant expression");
}

bool testObjectLikeMacros(void) {
    bool ok = true;
    if (CALC != 42) { ok = false; }
    if (CALC_REF != 42) { ok = false; }
    if (str.cmp(CALC_STR, "forty two")) { ok = false; }
    if (CALC_F64 != 42.0) { ok = false; }
    if (CALC_CHAR != 'A') { ok = false; }
    if (CALC_CHR != 'b') { ok = false; }
    if (CALC_SUFFIX != 2048) { ok = false; }
#ifndef CALC_FLAG
    ok = false;
#endif
#ifdef CALC_GONE
    ok = false;
#endif
    return ok;
}

i32 main() {
    printf("Test - Function-like macro errors: \n");
    i64 tests = 8;
    i64 correct = 0;

    if (testFunctionLikeMacro()) { correct++; }
    else { printf("Failed - TestFunctionLikeMacro()\n"); }

    if (testEmptyParamMacro()) { correct++; }
    else { printf("Failed - TestEmptyParamMacro()\n"); }

    if (testUnclosedParamMacro()) { correct++; }
    else { printf("Failed - TestUnclosedParamMacro()\n"); }

    if (testDefineNoName()) { correct++; }
    else { printf("Failed - TestDefineNoName()\n"); }

    if (testDefineBadName()) { correct++; }
    else { printf("Failed - TestDefineBadName()\n"); }

    if (testDefineUnknownName()) { correct++; }
    else { printf("Failed - TestDefineUnknownName()\n"); }

    if (testDefineUnclosedValue()) { correct++; }
    else { printf("Failed - TestDefineUnclosedValue()\n"); }

    if (testObjectLikeMacros()) { correct++; }
    else { printf("Failed - TestObjectLikeMacros()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
