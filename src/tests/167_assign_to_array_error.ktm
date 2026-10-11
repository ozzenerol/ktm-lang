import fs, libc, mem, proc, str, test;

/* Assigning to an array (`arr = p`, `arr += 1`, `arr++`) was only an
 * "Incompatible types" warning, and `arr++` compiled silently. As in C
 * it is an error, reported at the operator. */

#define ARR_TEST_FILE "__167_assign_to_array.ktm"
#define ARR_TEST_OUT  "__167_assign_to_array.out"

void cleanupArrTest() {
    fs.remove(ARR_TEST_FILE);
    fs.remove(ARR_TEST_OUT);
    fs.remove("./a.out");
}

/* Compiles `source`; true if the result matches `should_fail` and, when
 * `expected` is given, the output contains it. */
bool compile(u8 *source, bool shouldFail, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupArrTest();
    if (!fs.writeAll(ARR_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ARR_TEST_FILE " > " ARR_TEST_OUT " 2>&1");
    out = fs.readAll(ARR_TEST_OUT);
    if (!out) {
        cleanupArrTest();
        return false;
    }
    ok = (res != 0) == shouldFail;
    if (ok && expected && !libc.strstr(out, expected)) { ok = false; }
    if (!ok) { printf("%s\n", out); }
    mem.free(out);
    cleanupArrTest();
    return ok;
}

u8 *wrap(u8 *stmt) {
    return str.format("struct S { i64 a[2]; };\ni64 g[2];\n"
                                     "void main()\n{\n  i64 arr[2];\n  i64 *p = arr;\n  S s;\n"
                                     "  %s\n}\n", stmt);
}

bool fails(u8 *stmt, u8 *expected) {
    u8 *src = wrap(stmt);
    bool ok = compile(src, true, expected);
    mem.free(src);
    return ok;
}

i32 main() {
    printf("Test - assigning to an array is an error: ");
    i64 correct = 0, tests = 0;
    u8 *src;

    tests++; if (fails("arr = arr + 1;", "Cannot assign to array `arr`")) { correct++; }
    tests++; if (fails("arr += 1;", "Cannot assign to array `arr`")) { correct++; }
    tests++; if (fails("arr = p;", "Cannot assign to array `arr`")) { correct++; }
    tests++; if (fails("arr++;", "Cannot assign to array `arr`")) { correct++; }
    tests++; if (fails("--arr;", "Cannot assign to array `arr`")) { correct++; }
    tests++; if (fails("s.a = p;", "Cannot assign to array `s.a`")) { correct++; }
    tests++; if (fails("g -= 1;", "Cannot assign to array `g`")) { correct++; }
    /* Reported at the operator, not as a type warning */
    tests++; if (fails("arr = p;", ":8:7")) { correct++; }
    tests++; if (fails("i64 x = 1;\n  arr = p;", ":9:7")) { correct++; }

    /* Elements, pointers and an array of arrays' rows are fine */
    src = wrap("arr[0] = 1; arr[1] += 2; arr[0]++; p = arr; p += 1; s.a[1] = 3;\n"
                         "  i64 grid[2][2]; grid[1][0] = 4; *arr = 5;");
    tests++; if (compile(src, false, null)) { correct++; }
    mem.free(src);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
