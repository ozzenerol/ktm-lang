import cstr, fs, libc, mem, proc, test;

/* A generic function with @caller parameters: its type arguments are
 * worked out from the arguments written (the @caller ones are filled in,
 * not written), anywhere in the list and across generic overloads, and
 * for a method with type parameters of its own (`i64 Box.at<V>(...)`). */

#define GCI_TEST_FILE "__819_gci_fail.ktm"
#define GCI_TEST_OUT  "__819_gci_fail.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct Box { i64 n; };

bool same(i64 a, i64 b) { return a == b; }
bool same(u8 *a, u8 *b) { return !libc.strcmp(a, b); }

i64 gLine = 0;

/* the @caller parameter first, a plain one, then two type parameters */
bool eq<V, W>(@caller("line") i64 line, Box *b, V got, W want) {
    gLine = line;
    b.n++;
    return same(got, want);
}

/* @caller parameters between the others */
T pick<T>(@caller("file") u8 *file, T a, @caller("line") i64 line, T b) {
    gLine = line;
    return libc.strstr(file, "819_") ? a : b;
}

/* generic overloads */
i64 count<T>(@caller("line") i64 line, T x) { gLine = line; return 1; }
i64 Box.at<V>(@caller("line") i64 line, V x) { gLine = line; return (i64)x + self.n; }
i64 count<T>(@caller("line") i64 line, T x, T y) { gLine = line; return 2; }

bool compileFailsWith(u8 *source, u8 *expected) {
    fs.remove(GCI_TEST_FILE);
    fs.remove(GCI_TEST_OUT);
    if (!fs.writeAll(GCI_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __819_gci_fail " GCI_TEST_FILE " > " GCI_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(GCI_TEST_OUT).take();
    bool ok = res != 0 && out && libc.strstr(out, expected);
    if (!ok && out) { printf("\n%s", out); }
    mem.free(out);
    fs.remove(GCI_TEST_FILE);
    fs.remove(GCI_TEST_OUT);
    fs.remove("__819_gci_fail");
    return ok;
}

i32 main()
{
    printf("Test - generic functions with @caller parameters: ");
    Box b;
    b.n = 0;
    check(eq(&b, 1, 1) && gLine == 65, "inferred from the arguments written: i64");
    check(!eq(&b, "a", "b") && gLine == 66, "inferred: u8 *");
    check(eq<i64, i64>(&b, 2, 2) && gLine == 67, "type arguments written");
    check(b.n == 3, "the plain parameter gets its argument");
    check(pick(4, 5) == 4 && gLine == 69, "@caller parameters in the middle");
    check(count(1) == 1 && gLine == 70, "generic overloads: one argument");
    check(count(3, 4) == 2 && gLine == 71, "generic overloads: two");
    check(b.at(4) == 7 && gLine == 72, "a generic method with a @caller parameter");
    check(compileFailsWith("bool eq<V>(@caller(\"line\") i64 line, V a, V b) { return a == b; }\n"
                           "void main() { eq(1); }\n",
                           "Unexpected number of arguments"),
          "too few arguments still an error");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
