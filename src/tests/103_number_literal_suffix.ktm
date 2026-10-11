import fs, libc, mem, proc, str, test;

#define NUM_SUFFIX_TEST_FILE "__103_number_literal_suffix.ktm"
#define NUM_SUFFIX_TEST_OUT  "__103_number_literal_suffix.out"
#define NUM_SUFFIX_TEST_BIN  "__103_number_literal_suffix.bin"

#define NUM_SUFFIX_HEX  0x1F
#define NUM_SUFFIX_EXPR (100+23)
#define NUM_SUFFIX_U    0x0800U

void cleanupNumSuffixTest(void) {
    fs.remove(NUM_SUFFIX_TEST_FILE);
    fs.remove(NUM_SUFFIX_TEST_OUT);
    fs.remove(NUM_SUFFIX_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1) and
 * print `expected`. */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupNumSuffixTest();

    if (!fs.writeAll(NUM_SUFFIX_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NUM_SUFFIX_TEST_FILE " -o " NUM_SUFFIX_TEST_BIN " > " NUM_SUFFIX_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        ok = false;
    } else {
        out = fs.readAll(NUM_SUFFIX_TEST_OUT);
        if (!out || !libc.strstr(out, expected) || !libc.strstr(out, "malformed numeric literal")) {
            if (out) { printf("%s\n", out); }
            ok = false;
        }
        mem.free(out);
    }

    cleanupNumSuffixTest();
    return ok;
}

bool testDecimalHexLetters(void) {
    return compileFailsWith(
"void main() {
  i64 x = 123abc;
  printf(\"%d\\n\", x);
}
", "invalid character 'a' in number");
}

bool testDecimalOtherLetter(void) {
    return compileFailsWith(
"void main() { i64 x = 123g; }
", "invalid suffix 'g' on number");
}

bool testHexBadSuffix(void) {
    return compileFailsWith(
"void main() { i64 x = 0x12G; }
", "invalid suffix 'G' on number");
}

bool testBinaryBadDigit(void) {
    return compileFailsWith(
"void main() { i64 x = 0b102; }
", "invalid binary digit: '2'");
}

bool testXNotAfterZero(void) {
    return compileFailsWith(
"void main() { i64 x = 12x5; }
", "'x' can only appear in the 0x prefix of a hex literal");
}

bool testFloatLetters(void) {
    return compileFailsWith(
"void main() { f64 x = 1.5abc; }
", "invalid character 'a' in number");
}

bool testInMacro(void) {
    return compileFailsWith(
"#define BAD 12ab
void main() { i64 x = BAD; }
", "invalid character 'a' in number");
}

/* Valid literals: every form the lexer accepts must keep its value. */
bool testValidLiterals(void) {
    i64 arr[3] = {1, 2, 3};
    bool ok = true;
    if (123 != 100 + 23) { ok = false; }
    if (0xABC != 2748) { ok = false; }
    if (0xabcdef != 11259375) { ok = false; }
    if (0XAB != 171) { ok = false; }
    if (0x1E3 != 483) { ok = false; }
    if (0xff32 != 65330) { ok = false; }   /* `f32` here is hex digits, not a suffix */
    if (0xff64 != 65380) { ok = false; }
    if (0b101 != 5) { ok = false; }
    if (0B11 != 3) { ok = false; }
    if (1.5 != 3.0 / 2) { ok = false; }
    if (1e10 != 10000000000.0) { ok = false; }
    if (1.5e-3 != 0.0015) { ok = false; }
    if (1E2 != 100.0) { ok = false; }
    if (1f32 != 1.0) { ok = false; }
    if (2f64 != 2.0) { ok = false; }
    if (2048U != 2048) { ok = false; }
    if (0b11u != 3) { ok = false; }
    if (NUM_SUFFIX_U != 2048) { ok = false; }
    if (NUM_SUFFIX_HEX != 31) { ok = false; }
    if (NUM_SUFFIX_EXPR != 123) { ok = false; }
    if ((123) != 123 || -123 + 123 != 0) { ok = false; }
    if (arr[2] != 3) { ok = false; }
    switch (2) {
        case 1...3: break;
        default: ok = false;
    }
    return ok;
}

i32 main() {
    printf("Test - number literal suffixes: \n");
    i64 tests = 8;
    i64 correct = 0;

    if (testDecimalHexLetters()) { correct++; }
    else { printf("Failed - TestDecimalHexLetters()\n"); }

    if (testDecimalOtherLetter()) { correct++; }
    else { printf("Failed - TestDecimalOtherLetter()\n"); }

    if (testHexBadSuffix()) { correct++; }
    else { printf("Failed - TestHexBadSuffix()\n"); }

    if (testBinaryBadDigit()) { correct++; }
    else { printf("Failed - TestBinaryBadDigit()\n"); }

    if (testXNotAfterZero()) { correct++; }
    else { printf("Failed - TestXNotAfterZero()\n"); }

    if (testFloatLetters()) { correct++; }
    else { printf("Failed - TestFloatLetters()\n"); }

    if (testInMacro()) { correct++; }
    else { printf("Failed - TestInMacro()\n"); }

    if (testValidLiterals()) { correct++; }
    else { printf("Failed - TestValidLiterals()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
