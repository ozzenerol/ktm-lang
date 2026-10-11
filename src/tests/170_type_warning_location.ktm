import cstr, fs, libc, mem, proc, test;

/* "Incompatible types" warnings for assignments, compound assignments
 * and declarations were placed by rewinding to the nearest `=`, so with
 * two statements on a line (or a long right hand side) the warning for
 * one landed on the other. They are reported at the statement's own
 * operator. */

#define TW_TEST_FILE "__170_type_warning_loc.ktm"
#define TW_TEST_OUT  "__170_type_warning_loc.out"

void cleanupTwTest() {
    fs.remove(TW_TEST_FILE);
    fs.remove(TW_TEST_OUT);
    fs.remove("./a.out");
}

/* Compiles `src`; true if the output has `what` at `where` (file:line:col)
 * and nothing at `not_where` (if given). */
bool diagAt(u8 *src, u8 *what, u8 *where, u8 *notWhere = null) {
    u8 *out, *at;
    bool ok = false;
    cleanupTwTest();
    if (!fs.writeAll(TW_TEST_FILE, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " TW_TEST_FILE " > " TW_TEST_OUT " 2>&1");
    out = fs.readAll(TW_TEST_OUT);
    if (out) {
        at = libc.strstr(out, what);
        /* The location line follows the message */
        ok = at && libc.strstr(at, where) && libc.strstr(at, where) - at < 200;
        if (ok && notWhere && libc.strstr(out, notWhere)) { ok = false; }
        if (!ok) { printf("%s\n", out); }
        mem.free(out);
    }
    cleanupTwTest();
    return ok;
}

#define DECLS "void main()\n{\n  i64 y = 1, *q = null;\n  f64 f = 1;\n  i64 arr[2];\n"
#define WARN "Incompatible types 'i64 *' is not assignable to type 'f64'"

i32 main() {
    printf("Test - type warning location: ");
    i64 correct = 0, tests = 0;

    /* Second statement on a line: its own `=`, not the first one's */
    tests++;
    if (diagAt(DECLS "  y = y + 1; f = q;\n}\n", WARN, ":6:16", ":6:5")) { correct++; }
    /* Declaration after an assignment */
    tests++;
    if (diagAt(DECLS "  y = 2; f64 g = q;\n}\n", WARN, ":6:16", ":6:5")) { correct++; }
    /* Right hand side over several lines */
    tests++;
    if (diagAt(DECLS "  y = 3;\n  f =\n      q;\n}\n", WARN, ":7:5")) { correct++; }
    /* Compound assignment after a plain one: the error is at its own `+=` */
    tests++;
    if (diagAt(DECLS "  f = 2; f += q;\n}\n", "The `+=` operator", ":6:12", ":6:5")) { correct++; }
    /* An array right hand side */
    tests++;
    if (diagAt(DECLS "  y = 4; f = arr;\n}\n", "Incompatible types 'i64[", ":6:12")) { correct++; }
    /* Statements after globals (they used to be top-level code) */
    tests++;
    if (diagAt("i64 *gq = null;\nf64 gf;\ni64 gy;\n \nvoid main() {\n  gy = 1;\n  gf = gq; }\n",
                         WARN, ":7:6", ":7:3")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
