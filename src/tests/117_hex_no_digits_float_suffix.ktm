import test;

import fs, libc, mem, proc, str;

#define HEX_NO_DIGITS_TEST_FILE "__117_hex_no_digits.ktm"
#define HEX_NO_DIGITS_TEST_OUT  "__117_hex_no_digits.out"
#define HEX_NO_DIGITS_TEST_BIN  "__117_hex_no_digits.bin"

void cleanupHexNoDigitsTest(void) {
    fs.remove(HEX_NO_DIGITS_TEST_FILE);
    fs.remove(HEX_NO_DIGITS_TEST_OUT);
    fs.remove(HEX_NO_DIGITS_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1) and
 * print `expected`. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupHexNoDigitsTest();

    if (!fs.writeAll(HEX_NO_DIGITS_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " HEX_NO_DIGITS_TEST_FILE " -o " HEX_NO_DIGITS_TEST_BIN " > " HEX_NO_DIGITS_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        ok = false;
    } else {
        out = fs.readAll(HEX_NO_DIGITS_TEST_OUT);
        if (!out || !libc.strstr(out, expected) || !libc.strstr(out, "malformed numeric literal")) {
            if (out) { printf("%s\n", out); }
            ok = false;
        }
        mem.free(out);
    }

    cleanupHexNoDigitsTest();
    return ok;
}

/* `0x` with no digits used to be read as 0. */
bool testHexNoDigits(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { i64 x = 0x; printf(\"%d\\n\", x); }\n",
                                                "hex literal has no digits")) {
        printf("  0x;\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0X; }\n",
                                                "hex literal has no digits")) {
        printf("  0X;\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = (0x); }\n",
                                                "hex literal has no digits")) {
        printf("  (0x)\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0x+1; }\n",
                                                "hex literal has no digits")) {
        printf("  0x+1\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0xg; }\n",
                                                "hex literal has no digits")) {
        printf("  0xg\n"); ok = false;
    }
    if (!compileFailsWith("void main() { i64 x = 0xU; }\n",
                                                "hex literal has no digits")) {
        printf("  0xU\n"); ok = false;
    }
    return ok;
}

/* `1f325` used to be read as 1.0, silently dropping the `5`. */
bool testDigitsAfterFloatSuffix(void) {
    bool ok = true;
    if (!compileFailsWith("void main() { f64 x = 1f325; printf(\"%f\\n\", x); }\n",
                                                "invalid suffix 'f325' on number")) {
        printf("  1f325\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 2f640; }\n",
                                                "invalid suffix 'f640' on number")) {
        printf("  2f640\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 1.5f321; }\n",
                                                "invalid suffix 'f321' on number")) {
        printf("  1.5f321\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 1f32.5; }\n",
                                                "invalid suffix 'f32.5' on number")) {
        printf("  1f32.5\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 1f32e3; }\n",
                                                "invalid suffix 'f32e3' on number")) {
        printf("  1f32e3\n"); ok = false;
    }
    if (!compileFailsWith("void main() { f64 x = 1f32g; }\n",
                                                "invalid suffix 'g' on number")) {
        printf("  1f32g\n"); ok = false;
    }
    return ok;
}

/* The valid neighbours keep their values. */
bool testValidLiterals(void) {
    bool ok = true;
    f64 a = 1f32, b = 2f64, c = 1.5f32, d = 1e3f64, e = -1f32*2;
    if (a != 1.0) { printf("  1f32 = %f\n", a); ok = false; }
    if (b != 2.0) { printf("  2f64 = %f\n", b); ok = false; }
    if (c != 1.5) { printf("  1.5f32 = %f\n", c); ok = false; }
    if (d != 1000.0) { printf("  1e3f64 = %f\n", d); ok = false; }
    if (e != -2.0) { printf("  -1f32*2 = %f\n", e); ok = false; }
    if (1f32+1 != 2.0) { ok = false; }
    if (0xff32 != 65330) { printf("  0xff32 = %d\n", 0xff32); ok = false; }
    if (0xff64 != 65380) { printf("  0xff64 = %d\n", 0xff64); ok = false; }
    if (0x1f32 != 7986) { ok = false; }
    if (0x0 != 0 || 0X0 != 0 || 0x00 != 0) { ok = false; }
    if (0xF != 15 || 0x10U != 16) { ok = false; }
    return ok;
}

i32 main() {
    printf("Test - hex literal without digits, float suffix: \n");
    i64 tests = 3;
    i64 correct = 0;

    if (testHexNoDigits()) { correct++; }
    else { printf("Failed - TestHexNoDigits()\n"); }

    if (testDigitsAfterFloatSuffix()) { correct++; }
    else { printf("Failed - TestDigitsAfterFloatSuffix()\n"); }

    if (testValidLiterals()) { correct++; }
    else { printf("Failed - TestValidLiterals()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
