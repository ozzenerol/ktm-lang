import fs, libc, mem, proc, str, test;

/* `case 0x...3:` was accepted as `case 0...3:`: the lexer ended a number
 * at a `..` range operator by returning early, skipping the "hex literal
 * has no digits" check that `0x;` gets. A bare prefix before `...` must
 * be the same error, and real ranges must keep working. */

#define HEX_RANGE_TEST_FILE "__128_hex_range.ktm"
#define HEX_RANGE_TEST_OUT  "__128_hex_range.out"
#define HEX_RANGE_TEST_BIN  "__128_hex_range.bin"

void cleanupHexRangeTest(void) {
    fs.remove(HEX_RANGE_TEST_FILE);
    fs.remove(HEX_RANGE_TEST_OUT);
    fs.remove(HEX_RANGE_TEST_BIN);
}

/* Compile `source` with ktm: it must fail normally (exit status 1),
 * print `expected` and then "malformed numeric literal". */
bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out, *reason, *err;
    i64 res;
    bool ok = true;

    cleanupHexRangeTest();

    if (!fs.writeAll(HEX_RANGE_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " HEX_RANGE_TEST_FILE " -o " HEX_RANGE_TEST_BIN " > " HEX_RANGE_TEST_OUT " 2>&1");
    if (res != 1) {
        printf("ktm exit status %d, expected a normal compile failure\n", res);
        ok = false;
    } else {
        out = fs.readAll(HEX_RANGE_TEST_OUT);
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

    cleanupHexRangeTest();
    return ok;
}

u8 *classify(i64 i) {
    switch (i) {
        case 0x4...0x6:   return "hex";
        case 1...3:       return "dec";
        case 0b111...0b1001: return "bin";
        case 0xA...0xF:   return "hexletters";
        case 0x1E...0x20: return "hexe";
        case 0x21...40:   return "mixed";
        default:          return "none";
    }
}

i32 main() {
    i64 correct = 0, tests = 13;

    printf("Test - hex prefix before a case range: ");

    if (compileFailsWith(
"void main() { i64 i = 2; switch (i) { case 0x...3: \"in\\n\"; break; default: \"out\\n\"; } }\n",
                "line 1: hex literal has no digits")) { correct++; }
    if (compileFailsWith(
"void main() { i64 i = 2; switch (i) { case 0X...3: break; } }\n",
                "hex literal has no digits")) { correct++; }
    if (compileFailsWith(
"void main() { i64 i = 2; switch (i) { case 1...0x: break; } }\n",
                "hex literal has no digits")) { correct++; }
    if (compileFailsWith(
"void main() { i64 i = 2; switch (i) { case 0b...3: break; } }\n",
                "binary literal has no digits")) { correct++; }
    /* The same message as `0x` on its own. */
    if (compileFailsWith("void main() { i64 i = 0x; }\n",
                "hex literal has no digits")) { correct++; }

    if (!str.cmp(classify(2), "dec") && !str.cmp(classify(0), "none")) { correct++; }
    if (!str.cmp(classify(4), "hex") && !str.cmp(classify(6), "hex")) { correct++; }
    if (!str.cmp(classify(7), "bin") && !str.cmp(classify(9), "bin")) { correct++; }
    if (!str.cmp(classify(10), "hexletters") && !str.cmp(classify(15), "hexletters")) { correct++; }
    if (!str.cmp(classify(30), "hexe") && !str.cmp(classify(32), "hexe")) { correct++; }
    if (!str.cmp(classify(33), "mixed") && !str.cmp(classify(40), "mixed")) { correct++; }
    if (!str.cmp(classify(16), "none") && !str.cmp(classify(41), "none")) { correct++; }
    /* Ranges stop at the `...`, not at a float: `1.5` still lexes. */
    if (1.5 + 0x4 == 5.5) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
