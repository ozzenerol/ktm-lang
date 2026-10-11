import cstr, fs, libc, mem, proc, test;

/* Returning a struct (a String, a slice, any struct) from a function
 * declared to return something else, a pointer for instance, or a
 * different struct, was only a warning ("unexpected return value"), and
 * the caller got garbage: `u8 *f() { return proc.shellOutput(...); }`
 * compiled after proc.shellOutput began returning a String. It is an
 * error now, as for an argument or an assignment. The conversions that
 * exist still work: a literal, an array or a String returned as a u8[]. */

#define SRC_FILE "__500_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *ktmOut(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1; echo \"exit=$?\"");
    fs.remove(SRC_FILE);
    return out;
}

void errors(u8 *src, u8 *want, u8 *what) {
    u8 *out = ktmOut(src);
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
}

struct P { i64 x; };
P mk(i64 x) { P p; p.x = x; return p; }
P pass(i64 x) { return mk(x); }
u8[] lit() { return "lit"; }
u8[] whole(u8 *buf) { return buf[0:2]; }

i32 main() {
    printf("Test - returning a struct for something else: ");
    errors("import str;\nu8 *f() { return str.from(\"x\"); }\nvoid main() {}\n",
           "error: cannot return a String from a function returning u8 *", "a String for a pointer");
    errors("u8 *p;\nString f() { return p; }\nvoid main() {}\n",
           "error: cannot return a u8 * from a function returning String", "a pointer for a String");
    errors("struct P { i64 x; };\nstruct Q { i64 y; };\nP f() { Q q; return q; }\nvoid main() {}\n",
           "error: cannot return a Q from a function returning P", "another struct");
    errors("u8[] f() { return 5; }\nvoid main() {}\n",
           "error: cannot return a i64 from a function returning u8[]", "a number for a slice");

    check(pass(7).x == 7 && lit().equals("lit") && whole("abc").equals("ab"),
          "the returns that are right still work");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
