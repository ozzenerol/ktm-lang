#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `p || T()` where p is a pointer and T() returns bool (or any other
 * char-kind operand: a bool/u8/i8 variable) failed to compile with
 * "The `||` operator cannot be applied to a pointer type with a function
 * call of character type". Logical ops only test against zero, so any
 * integer-like operand next to a pointer is fine. */

#define LOGPTR_TEST_FILE "__101_logical_ptr_bool_fail.ktm"
#define LOGPTR_TEST_OUT  "__101_logical_ptr_bool_fail.out"

i64 gCalls = 0;

bool T() { gCalls++; return true; }
bool F() { gCalls++; return false; }
u8 C0() { gCalls++; return 0; }
i8 cNeg() { gCalls++; return -1; }

i64 Id(i64 x) { return x; }
bool retOr(u8 *p) { return p || T(); }
bool retAnd(u8 *p) { return p && T(); }

void cleanupLogPtrTest(void) {
    fs.remove(LOGPTR_TEST_FILE);
    fs.remove(LOGPTR_TEST_OUT);
    fs.remove("./a.out");
}

/* A pointer next to a class value is still invalid. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupLogPtrTest();
    if (!fs.writeAll(LOGPTR_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " LOGPTR_TEST_FILE " > " LOGPTR_TEST_OUT " 2>&1");
    if (res == 0) {
        cleanupLogPtrTest();
        return false;
    }
    out = fs.readAll(LOGPTR_TEST_OUT);
    if (!out) {
        cleanupLogPtrTest();
        return false;
    }
    if (!libc.strstr(out, expected)) {
        printf("%s\n", out);
        mem.free(out);
        cleanupLogPtrTest();
        return false;
    }
    mem.free(out);
    cleanupLogPtrTest();
    return true;
}

i32 main() {
    i64 correct = 0, tests = 33, i, n;
    u8 *p = null;
    u8 buf[4];
    u8 *q = buf;
    i64 *ip = null;
    bool bt = true, bf = false;
    u8 u0 = 0;
    i8 i8_ = -3;
    i64 zero = 0, one = 1;
    bool b;
    i64 r;

    printf("Test - logical ops on a pointer next to a bool: ");

    /* The reported case. */
    gCalls = 0;
    if (p || T()) { correct++; }
    if (gCalls == 1) { correct++; }

    /* Short-circuit: T() must not run when the pointer decides. */
    gCalls = 0;
    if (q || T()) { correct++; }
    if (p && T()) {} else { correct++; }
    if (gCalls == 0) { correct++; }

    /* Both orders, both operators. */
    gCalls = 0;
    if (T() || p) { correct++; }
    if (F() || p) {} else { correct++; }
    if (T() && q) { correct++; }
    if (T() && p) {} else { correct++; }
    if (q && T()) { correct++; }
    if (gCalls == 5) { correct++; }

    /* Negated pointer, bool/u8/i8 variables, i64, two pointers. */
    if (!p || F()) { correct++; }
    if (!q || F()) {} else { correct++; }
    if ((p || bt) && !(p || bf)) { correct++; }
    if ((q && bt) && !(q && bf)) { correct++; }
    if (!(p || u0) && (q || u0) && (p || i8_)) { correct++; }
    if ((p || C0()) == 0 && (p || cNeg()) == 1) { correct++; }
    if (!(p || zero) && (p || one) && (q && one) && !(q && zero)) { correct++; }
    if ((q || ip) && !(p || ip) && !(q && ip)) { correct++; }

    /* Results are 0/1, also as values. */
    b = p || T();
    if (b == true) { correct++; }
    b = q && F();
    if (b == false) { correct++; }
    r = q || F();
    if (r == 1) { correct++; }
    r = (T() && q) + (q || T()) + (p || T());
    if (r == 3) { correct++; }
    if (Id(p || T()) == 1 && Id(q && F()) == 0) { correct++; }

    /* The right operand is tested as a whole, not truncated to a byte:
     * a pointer / i64 whose low byte is 0 is still true. */
    u8 *aligned = (u8 *)0x1000;
    i64 big = 256;
    if ((T() && aligned) && (p || aligned) && (zero || big)) { correct++; }
    if ((one && big) == 1 && (zero || 5) == 1) { correct++; }
    r = T() && aligned;
    if (r == 1) { correct++; }

    /* Return value, nested, while. */
    if (retOr(null) && retOr(q) && !retAnd(null) && retAnd(q)) { correct++; }
    if ((p || (q && T())) && !(p && (q || T()))) { correct++; }
    n = 0;
    i = 0;
    while (p || i < 3) { i++; n++; }
    if (n == 3) { correct++; }
    n = 0;
    while (q && n < 4) { n++; }
    if (n == 4) { correct++; }

    /* Still invalid: a pointer next to a class value. */
    if (compileFailsWith("
struct C { i64 a; };
void main() { C c; u8 *p = null; if (p || c) printf(\"x\\n\"); }
", "operator cannot be applied")) { correct++; }
    else { printf("Failed - p || class value\n"); }
    if (compileFailsWith("
struct C { i64 a; };
void main() { C c; u8 *p = null; if (c && p) printf(\"x\\n\"); }
", "operator cannot be applied")) { correct++; }
    else { printf("Failed - class value && p\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
