import fs, libc, mem, proc, str, test;

/* An inner list (or string) in a multi-dimensional array initialiser
 * must fit its dimension. ktm used to accept `i64 m[2][2] = {{1,2,3},..}`
 * silently and write the extra item over the next row. */

#define NESTED_INIT_FILE "__139_nested_array_init_overflow.ktm"
#define NESTED_INIT_OUT  "__139_nested_array_init_overflow.out"

#define MSG_2_3 "Invalid array initializer: expected 2 items but got 3"

/* Initialisers that fit are still accepted, globally and locally. */
i64 g139M[2][3] = {{1,2,3},{4,5,6}};
i64 g139Unsized[][2] = {{1,2},{3,4},{5,6}};
i64 g139Cube[2][2][2] = {{{1,2},{3,4}},{{5,6},{7,8}}};

void cleanupNestedInitTest(void) {
    fs.remove(NESTED_INIT_FILE);
    fs.remove(NESTED_INIT_OUT);
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

/* Compile `source`: ktm must exit with status 1 and print exactly one
 * error, `msg` at `loc`; the recovery skips the rest of the initialiser. */
bool compileFailsWith(u8 *source, u8 *msg, u8 *loc) {
    u8 *out, *first;
    i64 res;
    bool ok;

    cleanupNestedInitTest();

    if (!fs.writeAll(NESTED_INIT_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NESTED_INIT_FILE " > " NESTED_INIT_OUT " 2>&1");

    out = fs.readAll(NESTED_INIT_OUT);
    if (!out) {
        cleanupNestedInitTest();
        return false;
    }

    ok = res == 1 && countErrors(out) == 1;
    first = libc.strstr(out, "error: ");
    if (!first || str.cmpLen(first + 7, msg, str.len(msg))) { ok = false; }
    else if (!libc.strstr(first, loc)) { ok = false; }
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupNestedInitTest();
    return ok;
}

bool testLocalRowTooLong(void) {
    return compileFailsWith(
"void main() {
  i64 m[2][2] = {{1,2,3},{4,5}};
}
", MSG_2_3, "2:24");
}

bool testLocalLastRowTooLong(void) {
    return compileFailsWith(
"void main() {
  i64 m[2][2] = {{1,2},{3,4,5}};
}
", MSG_2_3, "2:30");
}

bool testGlobalRowTooLong(void) {
    return compileFailsWith(
"i64 g[2][2] = {{1,2,3},{4,5}};
void main() {}
", MSG_2_3, "1:22");
}

bool testTooManyRows(void) {
    return compileFailsWith(
"void main() {
  i64 m[2][2] = {{1},{2},{3}};
}
", MSG_2_3, "2:17");
}

bool testUnsizedOuterRowTooLong(void) {
    return compileFailsWith(
"void main() {
  i64 m[][2] = {{1,2},{3,4,5}};
}
", MSG_2_3, "2:29");
}

bool testCubeInnerTooLong(void) {
    return compileFailsWith(
"void main() {
  i64 c[2][2][2] = {{{1,2},{3,4}},{{5,6,7},{8,9}}};
}
", MSG_2_3, "2:42");
}

bool testCubeMiddleTooLong(void) {
    return compileFailsWith(
"i64 c[2][2][2] = {{{1,2},{3,4},{5,6}},{{7,8},{9,10}}};
void main() {}
", MSG_2_3, "1:37");
}

bool testStringTooLong(void) {
    /* "abc" needs 4 bytes with its NUL, like a top-level u8 buf[3]. */
    return compileFailsWith(
"void main() {
  u8 s[2][3] = {printf(\"ab\",\"abc\"});
}
", "Invalid array initializer: expected 3 items but got 4", "2:22");
}

bool testGlobalStringTooLong(void) {
    return compileFailsWith(
"u8 s[2][3] = {printf(\"abcdef\",\"x\"});
void main() {}
", "Invalid array initializer: expected 3 items but got 7", "1:15");
}

bool testValidInits(void) {
    i64 m[2][3] = {{1,2,3},{4,5,6}};
    i64 u[][2] = {{1,2},{3,4},{5,6}};
    i64 c[2][2][2] = {{{1,2},{3,4}},{{5,6},{7,8}}};
    u8 s[2][4] = {"abc","de"};
    bool ok = true;

    if (m[0][2] != 3 || m[1][0] != 4 || m[1][2] != 6) { ok = false; }
    if (sizeof(u) != 48 || u[2][0] != 5 || u[2][1] != 6) { ok = false; }
    if (c[0][1][1] != 4 || c[1][0][0] != 5 || c[1][1][1] != 8) { ok = false; }
    if (str.cmp(s[0], "abc") || str.cmp(s[1], "de")) { ok = false; }
    if (g139M[0][2] != 3 || g139M[1][0] != 4) { ok = false; }
    if (sizeof(g139Unsized) != 48 || g139Unsized[2][1] != 6) { ok = false; }
    if (g139Cube[1][0][1] != 6 || g139Cube[1][1][1] != 8) { ok = false; }
    return ok;
}

i32 main() {
    printf("Test - Nested array initialiser overflow: ");
    i64 tests = 10;
    i64 correct = 0;

    if (testLocalRowTooLong()) { correct++; }
    else { printf("Failed - TestLocalRowTooLong()\n"); }

    if (testLocalLastRowTooLong()) { correct++; }
    else { printf("Failed - TestLocalLastRowTooLong()\n"); }

    if (testGlobalRowTooLong()) { correct++; }
    else { printf("Failed - TestGlobalRowTooLong()\n"); }

    if (testTooManyRows()) { correct++; }
    else { printf("Failed - TestTooManyRows()\n"); }

    if (testUnsizedOuterRowTooLong()) { correct++; }
    else { printf("Failed - TestUnsizedOuterRowTooLong()\n"); }

    if (testCubeInnerTooLong()) { correct++; }
    else { printf("Failed - TestCubeInnerTooLong()\n"); }

    if (testCubeMiddleTooLong()) { correct++; }
    else { printf("Failed - TestCubeMiddleTooLong()\n"); }

    if (testStringTooLong()) { correct++; }
    else { printf("Failed - TestStringTooLong()\n"); }

    if (testGlobalStringTooLong()) { correct++; }
    else { printf("Failed - TestGlobalStringTooLong()\n"); }

    if (testValidInits()) { correct++; }
    else { printf("Failed - TestValidInits()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
