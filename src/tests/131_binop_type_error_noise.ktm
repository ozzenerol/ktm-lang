import cstr, fs, libc, mem, proc, test;

#define BINOP_NOISE_FILE "__131_binop_type_error_noise.ktm"
#define BINOP_NOISE_OUT  "__131_binop_type_error_noise.out"

void cleanupBinopNoiseTest(void) {
    fs.remove(BINOP_NOISE_FILE);
    fs.remove(BINOP_NOISE_OUT);
    fs.remove("./a.out");
}

i64 countOccurrences(u8 *haystack, u8 *needle) {
    i64 count = 0;
    u8 *p = haystack;
    while ((p = libc.strstr(p, needle)) != null) {
        count++;
        p += cstr.len(needle);
    }
    return count;
}

/* Compile `source`. `want_status` is the expected exit status (0 or 1).
 * The output must contain each of the `nwant` strings in `want`, exactly
 * `nerrors` "error:" lines, and none of the location-less warnings that
 * the type inference used to print for an operator whose operand types
 * don't combine ("Binop has returned null type" / "Binary type is being
 * assigned as null"). */
bool compileCheck(u8 *source, i64 wantStatus, i64 nerrors, u8 **want, i64 nwant) {
    u8 *out;
    i64 res, i;
    bool ok = true;

    cleanupBinopNoiseTest();

    if (!fs.writeAll(BINOP_NOISE_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    /* 7 = exited 1, 8 = exited 0, 9 = anything else (e.g. a crash) */
    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " BINOP_NOISE_FILE " > " BINOP_NOISE_OUT " 2>&1; s=$?; exit $(( s == 1 ? 7 : (s == 0 ? 8 : 9) ))");
    if (wantStatus == 1 && res != 7) { ok = false; }
    if (wantStatus == 0 && res != 8) { ok = false; }

    out = fs.readAll(BINOP_NOISE_OUT).take();
    if (!out) {
        cleanupBinopNoiseTest();
        return false;
    }

    if (libc.strstr(out, "Binop has returned null type")) { ok = false; }
    if (libc.strstr(out, "being assigned as NULL")) { ok = false; }
    if (libc.strstr(out, "WARNING")) { ok = false; }
    if (countOccurrences(out, "error:") != nerrors) { ok = false; }
    for (i = 0; i < nwant; i++) {
        if (!libc.strstr(out, want[i])) { ok = false; }
    }
    if (!ok) { printf("status code %d\n%s\n", res, out); }

    mem.free(out);
    cleanupBinopNoiseTest();
    return ok;
}

/* The original report: a float or a string mixed with a string in a
 * #define printed both warnings (twice) before the located error. */
bool testDefineFloatString(void) {
    u8 *want[4];
    want[0] = "error: #define X";
    want[1] = " --> " BINOP_NOISE_FILE ":1:";
    want[2] = "error: #define Y";
    want[3] = " --> " BINOP_NOISE_FILE ":2:";
    return compileCheck(
"#define X (2.5 + \"s\")
#define Y (\"s\" * 2)
void main() {}
", 1, 2, want, 4);
}

/* The same expressions in a function: one located type error each. */
bool testLocalInit(void) {
    u8 *want[4];
    want[0] = "error: The `+` operator cannot be applied to a float type with a string literal of array type";
    want[1] = " --> " BINOP_NOISE_FILE ":2:15";
    want[2] = "error: The `*` operator cannot be applied to a array type with a literal of integer type";
    want[3] = " --> " BINOP_NOISE_FILE ":3:15";
    return compileCheck(
"void main() {
  i64 x = 2.5 + \"s\";
  i64 y = \"s\" * 2;
}
", 1, 2, want, 4);
}

/* Plain assignment, a comparison and a non-intrinsic class operand. */
bool testExprForms(void) {
    u8 *want[6];
    want[0] = " --> " BINOP_NOISE_FILE ":5:9";
    want[1] = "`f64 + u8[2]` is invalid";
    want[2] = " --> " BINOP_NOISE_FILE ":6:9";
    want[3] = "`f64 < u8[2]` is invalid";
    want[4] = " --> " BINOP_NOISE_FILE ":7:13";
    want[5] = "`Pt * i64` is invalid";
    return compileCheck(
"struct Pt { i64 x; };
void main() {
  Pt p;
  f64 z = 1.5;
  z = z + \"q\";
  if (z < \"s\") { printf(\"no\\n\"); }
  i64 k = p * 2;
}
", 1, 3, want, 6);
}

/* A global initialiser goes through the same path. */
bool testGlobalInit(void) {
    u8 *want[2];
    want[0] = "error: The `+` operator cannot be applied to a float type with a string literal of array type";
    want[1] = " --> " BINOP_NOISE_FILE ":1:13";
    return compileCheck(
"f64 g = 2.5 + \"s\";
void main() {}
", 1, 1, want, 2);
}

/* Valid mixed-type operations still compile, without warnings. */
bool testValidStillCompiles(void) {
    return compileCheck(
"#define OK_F (2.5 + 1)
#define OK_S \"a\" \"b\"
void main() {
  f64 f = 2 + 1.5;
  u8 *s = \"abc\" + 1;
  i64 c = f < 3.0;
  printf(\"%d %d\\n\", c, *s);
}
", 0, 0, null, 0);
}

i32 main() {
    printf("Test - Binop type error noise: \n");
    i64 tests = 5;
    i64 correct = 0;

    if (testDefineFloatString()) { correct++; }
    else { printf("Failed - TestDefineFloatString()\n"); }

    if (testLocalInit()) { correct++; }
    else { printf("Failed - TestLocalInit()\n"); }

    if (testExprForms()) { correct++; }
    else { printf("Failed - TestExprForms()\n"); }

    if (testGlobalInit()) { correct++; }
    else { printf("Failed - TestGlobalInit()\n"); }

    if (testValidStillCompiles()) { correct++; }
    else { printf("Failed - TestValidStillCompiles()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
