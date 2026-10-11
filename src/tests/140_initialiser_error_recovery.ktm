#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* An error inside a brace initialiser is reported once. The parser's
 * recovery used to stop at the `}` of the innermost list and report the
 * rest of the initialiser, or the next statement, as a second error
 * (e.g. `Identifier expected: got TK_STR "ok\n"`). Each source below has
 * a second, independent error further down, which must be reported too:
 * that shows the following code is parsed normally. */

#define INIT_RECOVERY_FILE "__140_initialiser_error_recovery.ktm"
#define INIT_RECOVERY_OUT  "__140_initialiser_error_recovery.out"

#define MSG_FLOAT_STR "The `+` operator cannot be applied to a float type with a string literal of array type"

void cleanupInitRecoveryTest(void) {
    fs.remove(INIT_RECOVERY_FILE);
    fs.remove(INIT_RECOVERY_OUT);
    fs.remove("./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p += 7;
    }
    return n;
}

/* Compile `source`: ktm must exit with status 1 and print exactly two
 * errors, `m1` then `m2`, at `loc1` and `loc2`. */
bool compileFailsTwice(u8 *source, u8 *m1, u8 *loc1, u8 *m2, u8 *loc2) {
    u8 *out, *first, *second;
    i64 res;
    bool ok;

    cleanupInitRecoveryTest();

    if (!fs.writeAll(INIT_RECOVERY_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " INIT_RECOVERY_FILE " > " INIT_RECOVERY_OUT " 2>&1");

    out = fs.readAll(INIT_RECOVERY_OUT);
    if (!out) {
        cleanupInitRecoveryTest();
        return false;
    }

    ok = res == 1 && countErrors(out) == 2;
    first = libc.strstr(out, "error: ");
    second = null;
    if (first) { second = libc.strstr(first + 7, "error: "); }
    if (!first || !second) {
        ok = false;
    } else {
        if (str.cmpLen(first + 7, m1, str.len(m1))) { ok = false; }
        if (str.cmpLen(second + 7, m2, str.len(m2))) { ok = false; }
        /* Each location is in its own error's header. */
        u8 *loc = libc.strstr(first, loc1);
        if (!loc || loc > second) { ok = false; }
        if (!libc.strstr(second, loc2)) { ok = false; }
    }
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupInitRecoveryTest();
    return ok;
}

bool testClassTooManyThenString(void) {
    /* The reported repro: the next statement is a string. */
    return compileFailsTwice(
"struct Foo { i64 a; i64 b; };
void main() { Foo f = {1, 2, 3}; printf(\"ok\\n\"); }
void Other() { i64 z = 2.5 + \"t\"; }
", "More initialisers than class fields for class: Foo", ":2:29",
     MSG_FLOAT_STR, ":3:26");
}

bool testLocalLastElement(void) {
    /* The reported repro: the next statement is a declaration. */
    return compileFailsTwice(
"void main() {
  i64 a[2] = {1, 2.5 + \"s\"};
  i64 u = 3;
  i64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:22", MSG_FLOAT_STR, ":4:15");
}

bool testLocalNested(void) {
    return compileFailsTwice(
"void main() {
  i64 m[2][2] = {{1, 2}, {3, 2.5 + \"s\"}};
  i64 k = 1;
  i64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:34", MSG_FLOAT_STR, ":4:15");
}

bool testLocalNestedFirstRow(void) {
    /* The error is in the first inner list; the rest is skipped. */
    return compileFailsTwice(
"void main() {
  i64 c[2][2][2] = {{{1, 2.5 + \"s\"}, {3, 4}}, {{5, 6}, {7, 8}}};
  printf(\"ok\\n\");
  i64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:30", MSG_FLOAT_STR, ":4:15");
}

bool testGlobalNested(void) {
    return compileFailsTwice(
"i64 g[2][2] = {{1, 2.5 + \"s\"}, {3, 4}};
i64 h = 5;
void main() { i64 z = 2.5 + \"t\"; }
", MSG_FLOAT_STR, ":1:24", MSG_FLOAT_STR, ":3:25");
}

bool testGlobalClass(void) {
    return compileFailsTwice(
"struct Foo { i64 a; i64 b; };
Foo f = {1, 2, 3};
i64 h = 5;
i64 z = 2.5 + \"t\";
", "More initialisers than class fields for class: Foo", ":2:17",
     MSG_FLOAT_STR, ":4:13");
}

bool testClassArrayField(void) {
    /* Error inside the nested list of an array field. */
    return compileFailsTwice(
"struct Bar { i64 x; i64 y[2]; };
void main() {
  Bar b = {1, {2, .y}};
  i64 k = 1;
  i64 z = 2.5 + \"t\";
}
", "Designated initialisers", ":3:20", MSG_FLOAT_STR, ":5:15");
}

bool testMissingOperand(void) {
    /* The bad expression runs into the closing `}`. */
    return compileFailsTwice(
"void main() {
  i64 a[3] = {1, 2 + };
  i64 k = 1;
  i64 z = 2.5 + \"t\";
}
", "Second operand missing", ":2:", MSG_FLOAT_STR, ":4:15");
}

bool testMoreDeclaratorsAfter(void) {
    /* The rest of the statement (`, b = 4`) is skipped with it. */
    return compileFailsTwice(
"void main() {
  i64 a[2] = {1, 2.5 + \"s\"}, b = 4;
  i64 k = 1;
  i64 z = 2.5 + \"t\";
}
", MSG_FLOAT_STR, ":2:22", MSG_FLOAT_STR, ":4:15");
}

bool testRowTooLong(void) {
    /* The overflow is found after the row's `}`; the rest of the
     * initialiser (`, {4, 5}}`) is skipped too. */
    return compileFailsTwice(
"void main() {
  i64 m[2][2] = {{1, 2, 3}, {4, 5}};
  i64 k = 1;
  i64 z = 2.5 + \"t\";
}
", "Invalid array initializer: expected 2 items but got 3", ":2:26",
     MSG_FLOAT_STR, ":4:15");
}

bool testStringTooLong(void) {
    return compileFailsTwice(
"u8 s[2][3] = {printf(\"abcdef\", \"x\"});
i64 h = 5;
void main() { i64 z = 2.5 + \"t\"; }
", "Invalid array initializer: expected 3 items but got 7", ":1:15",
     MSG_FLOAT_STR, ":3:25");
}

i32 main() {
    printf("Test - Initialiser error recovery: ");
    i64 tests = 11;
    i64 correct = 0;

    if (testClassTooManyThenString()) { correct++; }
    else { printf("Failed - TestClassTooManyThenString()\n"); }

    if (testLocalLastElement()) { correct++; }
    else { printf("Failed - TestLocalLastElement()\n"); }

    if (testLocalNested()) { correct++; }
    else { printf("Failed - TestLocalNested()\n"); }

    if (testLocalNestedFirstRow()) { correct++; }
    else { printf("Failed - TestLocalNestedFirstRow()\n"); }

    if (testGlobalNested()) { correct++; }
    else { printf("Failed - TestGlobalNested()\n"); }

    if (testGlobalClass()) { correct++; }
    else { printf("Failed - TestGlobalClass()\n"); }

    if (testClassArrayField()) { correct++; }
    else { printf("Failed - TestClassArrayField()\n"); }

    if (testMissingOperand()) { correct++; }
    else { printf("Failed - TestMissingOperand()\n"); }

    if (testMoreDeclaratorsAfter()) { correct++; }
    else { printf("Failed - TestMoreDeclaratorsAfter()\n"); }

    if (testRowTooLong()) { correct++; }
    else { printf("Failed - TestRowTooLong()\n"); }

    if (testStringTooLong()) { correct++; }
    else { printf("Failed - TestStringTooLong()\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
