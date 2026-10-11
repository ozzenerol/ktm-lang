import cstr, fs, libc, mem, proc, test;

/* An intrinsic class whose fields don't fit its base type, or whose base
 * type is not an integer, is reported once. ktm used to unwind before
 * the class got its field map, so every later member access added a
 * "Cannot access `a`, X is incomplete" error. A class body cut off by
 * the end of input is reported as that, not as an unexpected token. */

#define INTR_FILE "__126_intrinsic_class_size.ktm"
#define INTR_OUT  "__126_intrinsic_class_size.out"

#define MSG_EOF "Unexpected end of input while parsing class Foo"

u16 struct Pair126 {
    u8 lo;
    u8 hi;
};

i64 struct Full126 {
    i32 a;
    u16 b;
    u8 c;
    u8 d;
};

void cleanupIntrinsicTest(void) {
    fs.remove(INTR_FILE);
    fs.remove(INTR_OUT);
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

/* Compile `source`: ktm must exit with status 1 (not crash) and print
 * exactly `n_expected` errors, `m1`/`m2` (when not null) among them. */
bool compileFailsExactly(u8 *source, i64 nExpected, u8 *m1, u8 *m2) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupIntrinsicTest();

    if (!fs.writeAll(INTR_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " INTR_FILE " > " INTR_OUT " 2>&1");

    out = fs.readAll(INTR_OUT);
    if (!out) {
        cleanupIntrinsicTest();
        return false;
    }

    /* System gives the exit code: 1 for a compile error; a segfault
     * would be 139 (from the shell) or -1 (killed by a signal). */
    ok = res == 1;
    if (countErrors(out) != nExpected) { ok = false; }
    if (m1 && !libc.strstr(out, m1)) { ok = false; }
    if (m2 && !libc.strstr(out, m2)) { ok = false; }
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupIntrinsicTest();
    return ok;
}

bool testTooBig(void) {
    return compileFailsExactly(
"i8 struct X { i64 a; };
void main() { X x; x.a = 1; }
", 1, "Fields of intrinsic class X take 8 bytes, more than the 1 bytes of its base type i8", null);
}

bool testOneByteOver(void) {
    return compileFailsExactly(
"u16 struct Z { u8 a; u8 b; u8 c; };
void main() { Z z; z.a = 1; z.c = 3; printf(\"%d\\n\", z.b); }
", 1, "Fields of intrinsic class Z take 3 bytes, more than the 2 bytes of its base type u16", null);
}

/* Each bad class is reported, and nothing else */
bool testTwoClasses(void) {
    return compileFailsExactly(
"u16 struct Z { u8 a; u8 b; u8 c; };
i32 struct W { i64 a; };
void main() { Z z; W w; z.a = 1; w.a = 2; }
", 2, "intrinsic class Z take 3 bytes", "intrinsic class W take 8 bytes");
}

bool testNonIntegerBase(void) {
    return compileFailsExactly(
"f64 struct Y { i64 a; };
void main() { Y y; y.a = 1; }
", 1, "Can only make intrinsic types from integer types, got f64", null);
}

/* A class body cut off by the end of input, after a field name, an
 * array dimension, a pointer field, a `,` and a field type. */
bool testEofAfterName(void) {
    return compileFailsExactly("struct Foo { i64 a; i64 b", 1, MSG_EOF, null);
}

bool testEofAfterDimension(void) {
    return compileFailsExactly("struct Foo { i64 a; i64 b[2]", 1, MSG_EOF, null);
}

bool testEofAfterPointer(void) {
    return compileFailsExactly("struct Foo { i64 a; i64 *b", 1, MSG_EOF, null);
}

bool testEofAfterComma(void) {
    return compileFailsExactly("struct Foo { i64 a; i64 b,", 1, MSG_EOF, null);
}

bool testEofAfterType(void) {
    return compileFailsExactly("struct Foo { i64 a; i64", 1, MSG_EOF, null);
}

bool testEofAfterField(void) {
    return compileFailsExactly("struct Foo { i64 a; i64 b;", 1,
            "Unexpected end of input in class/union body", null);
}

/* Intrinsic classes whose fields fit still work */
bool testFits(void) {
    Pair126 p;
    Full126 f;
    p.lo = 1;
    p.hi = 2;
    f.a = -5;
    f.b = 600;
    f.c = 7;
    f.d = 8;
    return sizeof(Pair126) == 2 && p == 0x0201 && p.lo == 1 && p.hi == 2 &&
                 sizeof(Full126) == 8 && f.a == -5 && f.b == 600 &&
                 f.c == 7 && f.d == 8;
}

i32 main() {
    printf("Test - Intrinsic class size errors: \n");
    i64 tests = 11;
    i64 correct = 0;

    if (testTooBig()) { correct++; }
    else { printf("Failed - TestTooBig()\n"); }

    if (testOneByteOver()) { correct++; }
    else { printf("Failed - TestOneByteOver()\n"); }

    if (testTwoClasses()) { correct++; }
    else { printf("Failed - TestTwoClasses()\n"); }

    if (testNonIntegerBase()) { correct++; }
    else { printf("Failed - TestNonIntegerBase()\n"); }

    if (testEofAfterName()) { correct++; }
    else { printf("Failed - TestEofAfterName()\n"); }

    if (testEofAfterDimension()) { correct++; }
    else { printf("Failed - TestEofAfterDimension()\n"); }

    if (testEofAfterPointer()) { correct++; }
    else { printf("Failed - TestEofAfterPointer()\n"); }

    if (testEofAfterComma()) { correct++; }
    else { printf("Failed - TestEofAfterComma()\n"); }

    if (testEofAfterType()) { correct++; }
    else { printf("Failed - TestEofAfterType()\n"); }

    if (testEofAfterField()) { correct++; }
    else { printf("Failed - TestEofAfterField()\n"); }

    if (testFits()) { correct++; }
    else { printf("Failed - TestFits()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
