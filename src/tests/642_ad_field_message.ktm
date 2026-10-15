import cstr, fs, libc, mem, proc, test;

/* `ad` on a struct field said "Unexpected type ad while parsing class R".
 * It now says ad goes on a local variable, and every struct/union body
 * error names a struct or a union, not a class (v0.13.3). A field that is
 * itself named ad still compiles.
 * v0.24.0: the keyword is spelled `scoped`; v0.25.0 removed `ad` (test
 * 895). */

#define AD_FILE "__642_ad_field.ktm"
#define AD_OUT  "__642_ad_field.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void cleanup() {
    fs.remove(AD_FILE);
    fs.remove(AD_OUT);
    fs.remove("./a.out");
}

/* compile `source`: the exit status is `status` and the output has `msg` */
bool compiles(u8 *source, i64 status, u8 *msg) {
    cleanup();
    if (!fs.writeAll(AD_FILE, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AD_FILE " > " AD_OUT " 2>&1");
    u8 *out = fs.readAll(AD_OUT).take();
    if (!out) { cleanup(); return false; }
    bool ok = res == status && (!msg || libc.strstr(out, msg) != null);
    if (!ok) { printf("\n    status %d\n%s\n", res, out); }
    mem.free(out);
    cleanup();
    return ok;
}

i32 main() {
    printf("Test - scoped field message: ");
    check(compiles("struct R { u8 major; scoped String name; };\ni32 main() { return 0; }\n", 1,
                   "`scoped` goes on a local variable, not a struct field: free the field where the struct is freed"),
          "scoped on a struct field");
    check(compiles("struct R { u8 major; scoped String name; };\ni32 main() { return 0; }\n", 1,
                   "1:22"),
          "the error points at scoped");
    check(compiles("union U { i64 a; scoped String s; };\ni32 main() { return 0; }\n", 1,
                   "`scoped` goes on a local variable, not a union field"),
          "scoped on a union field");
    check(compiles("struct R { i64 x; Nope y; };\ni32 main() { return 0; }\n", 1,
                   "Unexpected type `Nope` while parsing struct R"),
          "struct body errors say struct");
    check(compiles("union U { i64 x; Nope y; };\ni32 main() { return 0; }\n", 1,
                   "Unexpected type `Nope` while parsing union U"),
          "union body errors say union");
    check(compiles("struct R { i64 ad; };\ni32 main() { R r; r.ad = 1; return r.ad - 1; }\n", 0, null),
          "a field named ad still compiles");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
