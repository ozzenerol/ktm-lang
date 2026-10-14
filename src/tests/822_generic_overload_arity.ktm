import cstr, fs, libc, mem, proc, test;

/* Generic overloads told apart by their number of parameters, called with
 * string literals: each literal (an array) is its pointer for the type
 * argument, the second one too, so T is u8 * from both and the
 * two-parameter overload is picked. An overload that takes fewer
 * arguments than the call has is left out, so a conflict is reported as
 * itself. */

#define GOA_TEST_FILE "__822_goa_fail.ktm"
#define GOA_TEST_OUT  "__822_goa_fail.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 count<T>(T x) { return 1; }
i64 count<T>(T x, T y) { return 2; }

/* the other order of definition */
i64 later<T>(T x, T y) { return 2; }
i64 later<T>(T x) { return 1; }

T first<T>(T a, T b) { return a; }

bool compileFailsWith(u8 *source, u8 *expected) {
    fs.remove(GOA_TEST_FILE);
    fs.remove(GOA_TEST_OUT);
    if (!fs.writeAll(GOA_TEST_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __822_goa_fail " GOA_TEST_FILE " > " GOA_TEST_OUT " 2>&1");
    u8 *out = fs.readAll(GOA_TEST_OUT).take();
    bool ok = res != 0 && out && libc.strstr(out, expected);
    if (!ok && out) { printf("\n%s", out); }
    mem.free(out);
    fs.remove(GOA_TEST_FILE);
    fs.remove(GOA_TEST_OUT);
    fs.remove("__822_goa_fail");
    return ok;
}

i32 main()
{
    printf("Test - generic overloads by arity with string literals: ");
    u8 *p = "p";
    check(count("a", "b") == 2, "two literals of one length");
    check(count("abc", "b") == 2, "two literals of other lengths");
    check(count("a") == 1, "one literal");
    check(count(p, "b") == 2, "a u8 * and a literal");
    check(count(1, 2) == 2 && count(1) == 1, "integers");
    check(later("a", "b") == 2 && later("a") == 1, "defined the other way round");
    check(!libc.strcmp(first("one", "three"), "one"), "a single generic, two literals");
    check(compileFailsWith("i64 count<T>(T x) { return 1; }\ni64 count<T>(T x, T y) { return 2; }\n"
                           "void main() { count(1, 2.5); }\n",
                           "`T` is `i64` from one argument and `f64` from another"),
          "a conflict is reported, not the other overload's argument count");
    check(compileFailsWith("i64 one<T>(T x) { return 1; }\nvoid main() { one(1, 2); }\n",
                           "Unexpected number of arguments 2 in call to one(), expected 1 args"),
          "too many arguments for a single generic");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
