import cstr, fs, libc, mem, proc, test;

/* A syntax error inside a class/union body is reported where it is,
 * the rest of the body is still parsed, and the class keeps the fields
 * that did parse. ktm used to leave the class registered without a
 * field map and segfault (exit 139, no diagnostic) on the first use. */

#define CLS_ERR_FILE "__121_class_body_error.ktm"
#define CLS_ERR_OUT  "__121_class_body_error.out"

#define MSG_TOKEN_C   "Unexpected token 'c' while parsing class Foo, perhaps you meant to terminate the field with `;`?"
#define MSG_NO_C      "Property: c does not exist on class"

void cleanupClassErrTest(void) {
    fs.remove(CLS_ERR_FILE);
    fs.remove(CLS_ERR_OUT);
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

    cleanupClassErrTest();

    if (!fs.writeAll(CLS_ERR_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CLS_ERR_FILE " > " CLS_ERR_OUT " 2>&1");

    out = fs.readAll(CLS_ERR_OUT).take();
    if (!out) {
        cleanupClassErrTest();
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
    cleanupClassErrTest();
    return ok;
}

bool testFieldAccess(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; };
void main() { Foo f; f.a = 1; f.b = 2; }
", 1, MSG_TOKEN_C, "1:27");
}

/* Fields after the bad one are still parsed; the bad name is not a field */
bool testFieldsAfterError(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; i64 d; };
void main() { Foo f; f.d = 1; f.c = 2; }
", 2, MSG_TOKEN_C, MSG_NO_C);
}

bool testThroughPointer(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; };
void main() { Foo *p = null; p->a = 1; }
", 1, MSG_TOKEN_C, null);
}

bool testSizeof(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; };
void main() { i64 n = sizeof(Foo); }
", 1, MSG_TOKEN_C, null);
}

bool testUnion(void) {
    return compileFailsExactly(
"union U { i64 a; i64 b c; };
void main() { U u; u.a = 1; }
", 1, "Unexpected token 'c' while parsing class U", null);
}

/* The anonymous nested class has no name; this used to crash in the
 * error path itself */
bool testNestedAnonymousClass(void) {
    return compileFailsExactly(
"struct Foo { i64 x; struct { i64 a; i64 b c; }; i64 y; };
void main() { Foo f; f.a = 1; f.x = 2; f.y = 3; }
", 1, "Unexpected token 'c' while parsing class <anonymous>", null);
}

bool testNestedAnonymousUnion(void) {
    return compileFailsExactly(
"struct Foo { i64 x; union { i64 a; i64 5; }; i64 y; };
void main() { Foo f; f.a = 1; f.y = 2; }
", 1, "Unexpected character `5` while parsing class <anonymous>", null);
}

bool testAsFieldOfAnotherClass(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; };
struct Bar { Foo f; i64 z; };
void main() { Bar b; b.f.a = 1; b.z = 2; }
", 1, MSG_TOKEN_C, null);
}

bool testInherited(void) {
    return compileFailsExactly(
"struct Foo { i64 a; i64 b c; };
struct B : Foo { i64 z; };
void main() { B b; b.a = 1; b.z = 2; }
", 1, MSG_TOKEN_C, null);
}

bool testUnknownFieldType(void) {
    return compileFailsExactly(
"struct Foo { i64 a; Nope b; i64 c; };
void main() { Foo f; f.c = 1; }
", 1, "Unexpected type `Nope` while parsing class Foo", null);
}

/* No body at all: the member access is an error, not a crash */
bool testForwardDeclared(void) {
    return compileFailsExactly(
"struct Foo;
void main() { Foo *p; p->a = 1; }
", 1, "Cannot access `a`, Foo is incomplete", null);
}

i32 main() {
    printf("Test - Class body errors: \n");
    i64 tests = 11;
    i64 correct = 0;

    if (testFieldAccess()) { correct++; }
    else { printf("Failed - TestFieldAccess()\n"); }

    if (testFieldsAfterError()) { correct++; }
    else { printf("Failed - TestFieldsAfterError()\n"); }

    if (testThroughPointer()) { correct++; }
    else { printf("Failed - TestThroughPointer()\n"); }

    if (testSizeof()) { correct++; }
    else { printf("Failed - TestSizeof()\n"); }

    if (testUnion()) { correct++; }
    else { printf("Failed - TestUnion()\n"); }

    if (testNestedAnonymousClass()) { correct++; }
    else { printf("Failed - TestNestedAnonymousClass()\n"); }

    if (testNestedAnonymousUnion()) { correct++; }
    else { printf("Failed - TestNestedAnonymousUnion()\n"); }

    if (testAsFieldOfAnotherClass()) { correct++; }
    else { printf("Failed - TestAsFieldOfAnotherClass()\n"); }

    if (testInherited()) { correct++; }
    else { printf("Failed - TestInherited()\n"); }

    if (testUnknownFieldType()) { correct++; }
    else { printf("Failed - TestUnknownFieldType()\n"); }

    if (testForwardDeclared()) { correct++; }
    else { printf("Failed - TestForwardDeclared()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
