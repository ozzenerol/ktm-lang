#include "testhelper.ktm"

import fs, libc, mem, proc, str;

#define NUM_MSG_TEST_FILE "__124_number_messages.ktm"
#define NUM_MSG_TEST_OUT  "__124_number_messages.out"
#define NUM_MSG_TEST_BIN  "__124_number_messages.bin"

void cleanupNumMsgTest(void) {
    fs.remove(NUM_MSG_TEST_FILE);
    fs.remove(NUM_MSG_TEST_OUT);
    fs.remove(NUM_MSG_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1),
 * print `expected` as the reason and then "malformed numeric literal". */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out, *reason, *err;
    i64 res;
    bool ok = true;

    cleanupNumMsgTest();

    if (!fs.writeAll(NUM_MSG_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NUM_MSG_TEST_FILE " -o " NUM_MSG_TEST_BIN " > " NUM_MSG_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        ok = false;
    } else {
        out = fs.readAll(NUM_MSG_TEST_OUT);
        reason = null;
        err = null;
        if (out) {
            reason = libc.strstr(out, expected);
            err = libc.strstr(out, "malformed numeric literal");
        }
        if (!reason || !err || reason > err) {
            if (out) { printf("%s\n", out); }
            ok = false;
        }
        mem.free(out);
    }

    cleanupNumMsgTest();
    return ok;
}

/* A second `.` used to give no reason at all. */
bool testTwoDecimalPoints(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { f64 x = 1.2.3; }\n",
                                                "line 1: a number can only have one decimal point")) {
        printf("  1.2.3\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = .5.3; }\n",
                                                "a number can only have one decimal point")) {
        printf("  .5.3\n"); ok = false;
    }
    if (!compileFailsWith("void main() {\n  f64 x = 0;\n  x = 10.25.5;\n}\n",
                                                "line 3: a number can only have one decimal point")) {
        printf("  10.25.5 on line 3\n"); ok = false;
    }
    return ok;
}

/* A `.` after the exponent used to give no reason either. */
bool testFractionalExponent(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { f64 x = 1e3.5; }\n",
                                                "the exponent of a number must be a whole number")) {
        printf("  1e3.5\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 1.5e-3.2; }\n",
                                                "the exponent of a number must be a whole number")) {
        printf("  1.5e-3.2\n"); ok = false;
    }
    return ok;
}

/* `0xx` said "seen x", `12x5` "invalid character 'x' in number". */
bool testStrayX(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { i64 x = 0xx; }\n",
                                                "'x' can only appear in the 0x prefix of a hex literal")) {
        printf("  0xx\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0x1x; }\n",
                                                "'x' can only appear in the 0x prefix of a hex literal")) {
        printf("  0x1x\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0X1X; }\n",
                                                "'X' can only appear in the 0x prefix of a hex literal")) {
        printf("  0X1X\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 12x5; }\n",
                                                "'x' can only appear in the 0x prefix of a hex literal")) {
        printf("  12x5\n"); ok = false;
    }
    return ok;
}

/* `0x.5` said "LEX error is float and ishex", `0x1.5.3` gave no reason,
 * `0b1.1` "invalid binary digit: '.'". */
bool testFractionalHexBinary(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { f64 x = 0x.5; }\n",
                                                "hex literals can't have a fractional part")) {
        printf("  0x.5\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 0x1.5; }\n",
                                                "hex literals can't have a fractional part")) {
        printf("  0x1.5\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 0x1.5.3; }\n",
                                                "hex literals can't have a fractional part")) {
        printf("  0x1.5.3\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 0b1.1; }\n",
                                                "binary literals can't have a fractional part")) {
        printf("  0b1.1\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 0b.1; }\n",
                                                "binary literals can't have a fractional part")) {
        printf("  0b.1\n"); ok = false;
    }
    return ok;
}

i64 rangeCase(i64 i) {
    switch (i) {
        case 1...3: return 1;
        case 0x4...0x6: return 2;
        case 0b111...0b1000: return 3;
        default: return 0;
    }
}

/* The valid neighbours still compile with their values. */
bool testValidLiterals(void) {
    bool ok = true;
    f64 a = 1.5, b = 1e10, c = 1.5e-3, d = 1f32, e = 2f64, f = .5;
    if (a != 1.5) { printf("  1.5 = %f\n", a); ok = false; }
    if (b != 10000000000.0) { printf("  1e10 = %f\n", b); ok = false; }
    if (c != 0.0015) { printf("  1.5e-3 = %f\n", c); ok = false; }
    if (d != 1.0) { printf("  1f32 = %f\n", d); ok = false; }
    if (e != 2.0) { printf("  2f64 = %f\n", e); ok = false; }
    if (f != 0.5) { printf("  .5 = %f\n", f); ok = false; }
    if (0x1e5 != 485) { printf("  0x1e5 = %d\n", 0x1e5); ok = false; }
    if (0X1E5 != 485) { printf("  0X1E5 = %d\n", 0X1E5); ok = false; }
    if (0xABC != 2748) { printf("  0xABC = %d\n", 0xABC); ok = false; }
    if (0b101 != 5) { printf("  0b101 = %d\n", 0b101); ok = false; }
    if (0x10U != 16) { printf("  0x10U = %d\n", 0x10U); ok = false; }
    if (rangeCase(0) != 0 || rangeCase(1) != 1 || rangeCase(3) != 1 ||
            rangeCase(4) != 2 || rangeCase(6) != 2 || rangeCase(7) != 3 ||
            rangeCase(8) != 3 || rangeCase(9) != 0) {
        printf("  case ranges wrong\n"); ok = false;
    }
    return ok;
}

i32 main() {
    printf("Test - number literal error messages: \n");
    i64 tests = 5;
    i64 correct = 0;

    if (testTwoDecimalPoints()) { correct++; }
    else { printf("Failed - TestTwoDecimalPoints()\n"); }

    if (testFractionalExponent()) { correct++; }
    else { printf("Failed - TestFractionalExponent()\n"); }

    if (testStrayX()) { correct++; }
    else { printf("Failed - TestStrayX()\n"); }

    if (testFractionalHexBinary()) { correct++; }
    else { printf("Failed - TestFractionalHexBinary()\n"); }

    if (testValidLiterals()) { correct++; }
    else { printf("Failed - TestValidLiterals()\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
