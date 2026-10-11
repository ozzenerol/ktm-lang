import cstr, fs, libc, mem, proc, test;

/* A binary op with only its right operand a pointer had its operands
 * swapped (meant to turn `1 + p` into `p + 1`), so `z < p` ran as
 * `p < z` and `5 - p` as `p - 5`. Only `+` may be reordered; `int - ptr`
 * is now a compile error, as in C. */

#define PTR_CMP_TEST_FILE "__113_ptr_compare_fail.ktm"
#define PTR_CMP_TEST_OUT  "__113_ptr_compare_fail.out"

void cleanupPtrCmpTest(void) {
    fs.remove(PTR_CMP_TEST_FILE);
    fs.remove(PTR_CMP_TEST_OUT);
    fs.remove("./a.out");
}

bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupPtrCmpTest();
    if (!fs.writeAll(PTR_CMP_TEST_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PTR_CMP_TEST_FILE " > " PTR_CMP_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupPtrCmpTest();
        return false;
    }
    out = fs.readAll(PTR_CMP_TEST_OUT);
    if (!out) {
        cleanupPtrCmpTest();
        return false;
    }
    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupPtrCmpTest();
        return false;
    }
    mem.free(out);
    cleanupPtrCmpTest();
    return true;
}

i64 gOrder = 0;
i64 mark(i64 v) { gOrder = gOrder * 10 + v; return v; }

i32 main() {
    printf("Test - Pointer comparison operand order: \n");
    i64 tests = 22;
    i64 correct = 0;

    u8 buf[4];
    u8 *q = buf;
    i64 z = 0;
    i64 a = q;
    i64 hi = q + 100;

    /* int on the left, pointer on the right */
    if ((z < q) == 1 && (z > q) == 0) { correct++; } else { printf("Failed - z < q / z > q\n"); }
    if ((z <= q) == 1 && (z >= q) == 0) { correct++; } else { printf("Failed - z <= q / z >= q\n"); }
    if ((z == q) == 0 && (z != q) == 1) { correct++; } else { printf("Failed - z == q / z != q\n"); }
    if ((hi > q) == 1 && (hi < q) == 0) { correct++; } else { printf("Failed - hi > q\n"); }
    if ((hi >= q) == 1 && (hi <= q) == 0) { correct++; } else { printf("Failed - hi >= q\n"); }

    /* pointer on the left (unchanged) */
    if ((q > z) == 1 && (q < z) == 0 && (q >= z) == 1 && (q <= z) == 0) { correct++; }
    else { printf("Failed - q OP z\n"); }

    /* i64 holding the same address */
    if ((a == q) && !(a != q) && (a <= q) && (a >= q) && !(a < q) && !(a > q)) { correct++; }
    else { printf("Failed - a OP q\n"); }
    if ((a < q + 1) && !(a > q + 1) && (a + 1 > q) && !(a + 1 < q)) { correct++; }
    else { printf("Failed - a OP q +- 1\n"); }

    /* null / 0 on either side */
    u8 *n = null;
    if ((null == n) && (0 == n) && (n == null) && (n == 0)) { correct++; } else { printf("Failed - null == n\n"); }
    if ((null != q) && (0 != q) && (0 < q) && !(0 > q)) { correct++; } else { printf("Failed - 0 OP q\n"); }

    /* two pointers */
    u8 *q2 = q + 2;
    if ((q < q2) && !(q > q2) && (q2 > q) && (q != q2) && (q2 - q == 2)) { correct++; }
    else { printf("Failed - q OP q2\n"); }

    /* chained range comparison with the pointer in the middle */
    if (z < q < hi) { correct++; } else { printf("Failed - z < q < hi\n"); }
    if (!(hi < q < hi)) { correct++; } else { printf("Failed - hi < q < hi\n"); }

    /* an array on the right */
    i64 arr[4];
    arr[0] = 10; arr[1] = 11; arr[2] = 12; arr[3] = 13;
    if ((z < arr) && !(z > arr)) { correct++; } else { printf("Failed - z < arr\n"); }

    /* `+` is still reordered and scaled whichever side the pointer is on */
    i64 *w = arr;
    i64 *w1 = 1 + w;
    i64 *w2 = w + 1;
    if (w1 == w2 && (i64)w1 - (i64)w == 8 && *w1 == 11) { correct++; } else { printf("Failed - 1 + w\n"); }
    i64 i = 3;
    if (*(i + arr) == 13 && *(arr + i) == 13) { correct++; } else { printf("Failed - i + arr\n"); }
    if (*(w2 - 1) == 10 && (w2 - w) == 1) { correct++; } else { printf("Failed - w2 - 1\n"); }

    /* `&&` / `||` keep their evaluation order with a pointer on the right */
    gOrder = 0;
    if (mark(1) && mark(2) + q) { correct++; }
    if (gOrder == 12) { correct++; } else { printf("Failed - && order %d\n", gOrder); }

    /* int - pointer is rejected, as in C */
    bool ok1 = compileFailsWith(
"void main() { i64 *w = null; i64 *r = 5 - w; printf(\"%d\\n\", r); }
", "`i64 - i64 *` is invalid");
    bool ok2 = compileFailsWith(
"void main() { i64 arr[4]; i64 i = 1; i64 *r = i - arr; printf(\"%d\\n\", r); }
", "is invalid");
    if (ok1) { correct++; } else { printf("Failed - 5 - w compiled\n"); }
    if (ok2) { correct++; } else { printf("Failed - i - arr compiled\n"); }

    /* pointer - int still compiles */
    if (q2 - 2 == q) { correct++; } else { printf("Failed - q2 - 2\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
