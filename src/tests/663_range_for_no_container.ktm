import cstr, fs, libc, mem, proc, test;

/* A range-for with no container after `:` (`for (auto x : )`, or an array
 * literal `["a", "b"]`, which ktm doesn't have) crashed the compiler; it
 * is a compile error that says what to write (v0.15.1). */

#define RANGE_TEST_FILE "__663_range_fail.ktm"
#define RANGE_TEST_OUT  "__663_range_fail.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool compileFailsWith(u8 *source, u8 *expected) {
    fs.remove(RANGE_TEST_FILE);
    fs.remove(RANGE_TEST_OUT);
    if (!fs.writeAll(RANGE_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    /* exit 1 is a reported error; a crash is 128 + the signal */
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __663_range_fail " RANGE_TEST_FILE " > " RANGE_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(RANGE_TEST_OUT).take();
    bool ok = res == 1 && out && libc.strstr(out, expected);
    if (!ok) { printf("\n  exit %d: %s", res, out ? out : ""); }
    mem.free(out);
    fs.remove(RANGE_TEST_FILE);
    fs.remove(RANGE_TEST_OUT);
    fs.remove("__663_range_fail");
    return ok;
}

i32 main()
{
    printf("Test - range-for with no container: ");

    check(compileFailsWith("void main() { for (auto name : [\"a\", \"b\"]) { printf(\"%s\\n\", name); } }\n",
                           "ktm has no array literals: declare the array first"),
          "an array literal");
    check(compileFailsWith("void main() { for (auto x : ) { } }\n",
                           "expected what to range over after `:`, as in `for (auto x : items)`"),
          "nothing before `)`");
    check(compileFailsWith("void main() { for (auto x : ;) { } }\n",
                           "expected what to range over after `:`"),
          "nothing before `;`");

    /* the workaround the error gives works */
    u8 *names[2] = { "a", "b" };
    i64 n = 0;
    for (auto name : names) { n += libc.strlen(name); }
    check(n == 2, "a named array still ranges");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
