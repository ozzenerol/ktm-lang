import test;

import fs, libc, mem, proc, str;

#define DEF_AFTER_CLASS_FILE "__110_define_after_class.ktm"
#define DEF_AFTER_CLASS_OUT  "__110_define_after_class.out"

#define DEF_MSG_CONST   "#define X: the value must be a constant expression or a string, made of literals and other macros"
#define DEF_MSG_NUM     "Error while parsing #define X; #define must be a numerical expression or a string"
#define DEF_MSG_FNLIKE  "function-like macros are not supported: #define X(1)"
#define DEF_MSG_UNDEF   "Variable or function `X` has not been defined"

/* A successful #define right after a class must keep working */
struct DefAfterClassOk { i64 a; };
#define DEF_AFTER_CLASS_OK (2+1)

void cleanupDefAfterClassTest(void) {
    fs.remove(DEF_AFTER_CLASS_FILE);
    fs.remove(DEF_AFTER_CLASS_OUT);
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

/* Compile `source`, which must fail with exactly `n_expected` errors,
 * each of `m1`/`m2` (when not null) among them. */
bool compileFailsExactly(u8 *source, i64 nExpected, u8 *m1, u8 *m2) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupDefAfterClassTest();

    if (!fs.writeAll(DEF_AFTER_CLASS_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DEF_AFTER_CLASS_FILE " > " DEF_AFTER_CLASS_OUT " 2>&1");
    if (res == 0) {
        cleanupDefAfterClassTest();
        return false;
    }

    out = fs.readAll(DEF_AFTER_CLASS_OUT);
    if (!out) {
        cleanupDefAfterClassTest();
        return false;
    }

    ok = countErrors(out) == nExpected;
    if (m1 && !libc.strstr(out, m1)) { ok = false; }
    if (m2 && !libc.strstr(out, m2)) { ok = false; }
    if (!ok) { printf("%s\n", out); }

    mem.free(out);
    cleanupDefAfterClassTest();
    return ok;
}

bool testNoPreceding(void) {
    return compileFailsExactly(
"#define X (zz+1)
void main() { i64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

bool testAfterClass(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#define X (zz+1)
void main() { i64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

bool testAfterUnion(void) {
    return compileFailsExactly(
"union Foo { i64 a; u8 b; };
#define X (zz+1)
void main() { i64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

bool testAfterGlobal(void) {
    return compileFailsExactly(
"i64 g = 5;
#define X (zz+1)
void main() { i64 y = X; }
", 2, DEF_MSG_CONST, DEF_MSG_UNDEF);
}

/* The class must be complete: using it after the failing define used
 * to crash the compiler. */
bool testClassStillUsable(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#define X (zz+1)
void main() { Foo f; f.a = 1; }
", 1, DEF_MSG_CONST, null);
}

bool testNonConstAfterClass(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#define X zz
void main() { i64 y = X; }
", 2, DEF_MSG_NUM, DEF_MSG_UNDEF);
}

bool testFnLikeAfterClass(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
const i64 X = (1);
void main() { i64 y = 1; }
", 1, DEF_MSG_FNLIKE, null);
}

bool testTwoBadDefines(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#define X (zz+1)
#define Y (yy+1)
struct Bar { i64 b; };
void main() { Bar b; b.b = 1; }
", 2, DEF_MSG_CONST, "#define Y: the value must be a constant expression");
}

i32 main() {
    printf("Test - #define error after a class: \n");
    i64 tests = 9;
    i64 correct = 0;

    if (testNoPreceding()) { correct++; }
    else { printf("Failed - TestNoPreceding()\n"); }

    if (testAfterClass()) { correct++; }
    else { printf("Failed - TestAfterClass()\n"); }

    if (testAfterUnion()) { correct++; }
    else { printf("Failed - TestAfterUnion()\n"); }

    if (testAfterGlobal()) { correct++; }
    else { printf("Failed - TestAfterGlobal()\n"); }

    if (testClassStillUsable()) { correct++; }
    else { printf("Failed - TestClassStillUsable()\n"); }

    if (testNonConstAfterClass()) { correct++; }
    else { printf("Failed - TestNonConstAfterClass()\n"); }

    if (testFnLikeAfterClass()) { correct++; }
    else { printf("Failed - TestFnLikeAfterClass()\n"); }

    if (testTwoBadDefines()) { correct++; }
    else { printf("Failed - TestTwoBadDefines()\n"); }

    DefAfterClassOk ok;
    ok.a = DEF_AFTER_CLASS_OK;
    if (ok.a == 3) { correct++; }
    else { printf("Failed - successful #define after a class\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
