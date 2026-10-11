#include "testhelper.ktm"

import fs, libc, mem, proc, str;

#define IF_EXTRA_TEST_FILE "__102_if_extra_tokens.ktm"
#define IF_EXTRA_TEST_OUT  "__102_if_extra_tokens.out"
#define IF_EXTRA_TEST_BIN  "__102_if_extra_tokens.bin"

#define IF_EXTRA_X 3
#define IF_EXTRA_Y 1

void cleanupIfExtraTest(void) {
    fs.remove(IF_EXTRA_TEST_FILE);
    fs.remove(IF_EXTRA_TEST_OUT);
    fs.remove(IF_EXTRA_TEST_BIN);
}

/* Compile `source` with ktm; return true if the result matches
 * `should_fail` and, when failing, the output contains `expected`. */
bool compileCheck(u8 *source, bool shouldFail, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupIfExtraTest();

    if (!fs.writeAll(IF_EXTRA_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " IF_EXTRA_TEST_FILE " -o " IF_EXTRA_TEST_BIN " > " IF_EXTRA_TEST_OUT " 2>&1");

    if (shouldFail) {
        if (res == 0) {
            ok = false;
        } else {
            out = fs.readAll(IF_EXTRA_TEST_OUT);
            if (!out || !libc.strstr(out, expected)) {
                if (out) { printf("%s\n", out); }
                ok = false;
            }
            mem.free(out);
        }
    } else if (res != 0) {
        out = fs.readAll(IF_EXTRA_TEST_OUT);
        if (out) { printf("%s\n", out); }
        mem.free(out);
        ok = false;
    }

    cleanupIfExtraTest();
    return ok;
}

bool testTwoLiterals(void) {
    return compileCheck(
"#if 0 1
i32 main() { return 1; }
#else
i32 main() { return 0; }
#endif
", true, "#if: unexpected '1' after the condition");
}

bool testLineNumber(void) {
    return compileCheck(
"i64 a = 1;

#if 1 2
#endif
i32 main() { return 0; }
", true, ":3:");
}

bool testAfterDefined(void) {
    return compileCheck(
"#define FOO
#if defined(FOO) 1
#endif
i32 main() { return 0; }
", true, "#if: unexpected '1' after the condition");
}

bool testAfterExpression(void) {
    return compileCheck(
"#define A 3
#if A > 2 && A 7
#endif
i32 main() { return 0; }
", true, "#if: unexpected '7' after the condition");
}

bool testElif(void) {
    return compileCheck(
"#if 0
#elif 1 1
#endif
i32 main() { return 0; }
", true, "#elif: unexpected '1' after the condition");
}

bool testStraySemicolon(void) {
    return compileCheck(
"#if 1;
#endif
i32 main() { return 0; }
", true, "#if: unexpected ';' after the condition");
}

bool testValidConditionsCompile(void) {
    return compileCheck(
"#define A 3
#define B 1
#define FLAG
#if 1 // note
#endif
#if 1 /* block */
#endif
#if A > 2 && B
#endif
#if defined(FLAG) && !defined(NOPE)
#endif
#ifdef FLAG
#endif
#if 0
#elif (A + 1) * 2 == 8 // trailing
#endif
#if 1
#if B
#endif
#endif
i32 main() { return 0; }
", false, null);
}

/* The same conditions in this file: the test only builds if they are
 * still accepted, and each must pick the right branch. */
bool testValidConditionsInline(void) {
    i64 hits = 0;
#if 1 // note
    hits++;
#endif
#if 1 /* block */
    hits++;
#endif
#if IF_EXTRA_X > 2 && IF_EXTRA_Y
    hits++;
#endif
#if defined(IF_EXTRA_X) && !defined(IF_EXTRA_NOPE)
    hits++;
#endif
#if 0
    hits += 100;
#elif (IF_EXTRA_X + 1) * 2 == 8 // trailing
    hits++;
#endif
#if 1
#if IF_EXTRA_Y
    hits++;
#endif
#endif
#if 0
    hits += 100;
#endif
    return hits == 6;
}

i32 main() {
    printf("Test - #if extra tokens: \n");
    i64 tests = 8;
    i64 correct = 0;

    if (testTwoLiterals()) { correct++; }
    else { printf("Failed - TestTwoLiterals()\n"); }

    if (testLineNumber()) { correct++; }
    else { printf("Failed - TestLineNumber()\n"); }

    if (testAfterDefined()) { correct++; }
    else { printf("Failed - TestAfterDefined()\n"); }

    if (testAfterExpression()) { correct++; }
    else { printf("Failed - TestAfterExpression()\n"); }

    if (testElif()) { correct++; }
    else { printf("Failed - TestElif()\n"); }

    if (testStraySemicolon()) { correct++; }
    else { printf("Failed - TestStraySemicolon()\n"); }

    if (testValidConditionsCompile()) { correct++; }
    else { printf("Failed - TestValidConditionsCompile()\n"); }

    if (testValidConditionsInline()) { correct++; }
    else { printf("Failed - TestValidConditionsInline()\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
