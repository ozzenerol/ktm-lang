import cstr, fs, libc, mem, proc, test;

/* A brace initialiser for a class that was only forward declared
 * (`class Foo;`) is reported as an error. ktm used to read the missing
 * field map and segfault (exit 139, no diagnostic). */

#define FWD_INIT_FILE "__125_forward_class_init.ktm"
#define FWD_INIT_OUT  "__125_forward_class_init.out"

#define MSG_INCOMPLETE "Cannot use an initialiser list, Foo is incomplete"

/* Declared here, defined below: an initialiser after the definition
 * works as usual. */
struct Fwd125;
Fwd125 *fwd125Ptr = null;
struct Fwd125 {
    i64 a;
    i64 b;
};
Fwd125 fwd125Global = {3, 4};

void cleanupFwdInitTest(void) {
    fs.remove(FWD_INIT_FILE);
    fs.remove(FWD_INIT_OUT);
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

    cleanupFwdInitTest();

    if (!fs.writeAll(FWD_INIT_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FWD_INIT_FILE " > " FWD_INIT_OUT " 2>&1");

    out = fs.readAll(FWD_INIT_OUT);
    if (!out) {
        cleanupFwdInitTest();
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
    cleanupFwdInitTest();
    return ok;
}

bool testGlobal(void) {
    return compileFailsExactly(
"struct Foo;
Foo f = {1};
void main() {}
", 1, MSG_INCOMPLETE, "2:9");
}

bool testLocal(void) {
    return compileFailsExactly(
"struct Foo;
void main() { Foo f = {1, 2}; }
", 1, MSG_INCOMPLETE, "2:23");
}

/* Reported once, at the outer `{`, not once per element */
bool testArray(void) {
    return compileFailsExactly(
"struct Foo;
void main() { Foo a[2] = {{1},{2}}; }
", 1, MSG_INCOMPLETE, "2:26");
}

bool testArrayUnsized(void) {
    return compileFailsExactly(
"struct Foo;
Foo a[] = {{1},{2},{3}};
void main() {}
", 1, MSG_INCOMPLETE, null);
}

/* Each bad initialiser is reported and the code after it still parses */
bool testSeveral(void) {
    return compileFailsExactly(
"struct Foo;
Foo g = {1};
void main() {
  Foo a[2] = {{1},{2}};
  Foo b = {3, 4};
  i64 ok = 5;
}
", 3, MSG_INCOMPLETE, "5:11");
}

bool testDefinedLater(void) {
    Fwd125 l = {5, 6};
    return fwd125Global.a == 3 && fwd125Global.b == 4 &&
                 l.a == 5 && l.b == 6 && sizeof(Fwd125) == 16 &&
                 fwd125Ptr == null;
}

i32 main() {
    printf("Test - Forward declared class initialiser: \n");
    i64 tests = 6;
    i64 correct = 0;

    if (testGlobal()) { correct++; }
    else { printf("Failed - TestGlobal()\n"); }

    if (testLocal()) { correct++; }
    else { printf("Failed - TestLocal()\n"); }

    if (testArray()) { correct++; }
    else { printf("Failed - TestArray()\n"); }

    if (testArrayUnsized()) { correct++; }
    else { printf("Failed - TestArrayUnsized()\n"); }

    if (testSeveral()) { correct++; }
    else { printf("Failed - TestSeveral()\n"); }

    if (testDefinedLater()) { correct++; }
    else { printf("Failed - TestDefinedLater()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
