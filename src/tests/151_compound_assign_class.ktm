import cstr, date, fs, libc, mem, proc, test;

/* A compound assignment whose operands can't be combined, like
 * `y += p` with `p` a class, is the same type error as `y = y + p`.
 * It used to hang the compiler: the error rewound the tokens looking
 * for a `+` that isn't there (the source has `+=`), the per-statement
 * recovery restarted before the statement and parsed it again, forever. */

#define CA_TEST_FILE "__151_compound_assign_class.ktm"
#define CA_TEST_OUT  "__151_compound_assign_class.out"

/* ktm is run with a memory cap and a timeout where one exists, so a
 * regression fails the test instead of hanging the suite. */
#define CA_TIMEOUT "ulimit -v 4000000 2>/dev/null; $(command -v timeout >/dev/null 2>&1 && echo timeout 10) "

#define CA_DECLS "struct P { i64 a, b; };\nunion U { i64 i; f64 f; };\n"

void cleanupCompoundAssignTest(void) {
    fs.remove(CA_TEST_FILE);
    fs.remove(CA_TEST_OUT);
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

/* Compile `source`: ktm must exit with status 1 having printed `errors`
 * errors, the first being `expected` at `where`. */
bool compileFailsWith(u8 *source, u8 *expected, u8 *where, i64 errors=1) {
    u8 *out, *err;
    i64 res;
    bool ok;

    cleanupCompoundAssignTest();

    if (!fs.writeAll(CA_TEST_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell(CA_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " CA_TEST_FILE " > " CA_TEST_OUT " 2>&1");

    out = fs.readAll(CA_TEST_OUT);
    if (!out) {
        printf("status %d, no output\n", res);
        cleanupCompoundAssignTest();
        return false;
    }

    ok = res == 1 && countErrors(out) == errors;
    err = libc.strstr(out, "error: ");
    if (!err || cstr.cmpLen(err + 7, expected, cstr.len(expected))) { ok = false; }
    if (!libc.strstr(out, where)) { ok = false; }
    if (!ok) { printf("status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupCompoundAssignTest();
    return ok;
}

/* Every compound operator with a class on the right, then on the left */
bool testEveryOperator(void) {
    u8 *ops[10] = {"+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>="};
    u8 src[512], msg[256];
    i64 i;
    bool ok = true;

    for (i = 0; i < 10; ++i) {
        libc.snprintf(src, sizeof(src),
                         CA_DECLS "void main()\n{\n  P p;\n  i64 y = 1;\n  y %s p;\n}\n",
                         ops[i]);
        libc.snprintf(msg, sizeof(msg),
                         "The `%s` operator cannot be applied to a integer type with a local variable of class",
                         ops[i]);
        if (!compileFailsWith(src, msg, CA_TEST_FILE ":7:5")) {
            printf("  y %s p\n", ops[i]);
            ok = false;
        }

        libc.snprintf(src, sizeof(src),
                         CA_DECLS "void main()\n{\n  P p;\n  p %s 1;\n}\n", ops[i]);
        libc.snprintf(msg, sizeof(msg),
                         "The `%s` operator cannot be applied to a class with a literal of integer type",
                         ops[i]);
        if (!compileFailsWith(src, msg, CA_TEST_FILE ":6:5")) {
            printf("  p %s 1\n", ops[i]);
            ok = false;
        }
    }
    return ok;
}

bool testSameAsBinaryForm(void) {
    return compileFailsWith(CA_DECLS
"void main()
{
  P p;
  i64 y = 1;
  y = y + p;
}
",
        "The `+` operator cannot be applied to a integer type with a local variable of class",
        CA_TEST_FILE ":7:9");
}

bool testUnion(void) {
    return compileFailsWith(CA_DECLS
"void main()
{
  U u;
  i64 y = 1;
  y -= u;
}
",
        "The `-=` operator cannot be applied to a integer type with a local variable of union",
        CA_TEST_FILE ":7:5");
}

bool testThroughPointer(void) {
    return compileFailsWith(CA_DECLS
"void main()
{
  P p, *pp = &p;
  pp->a *= *pp;
}
",
        "The `*=` operator cannot be applied to a integer type with a unary op of class",
        CA_TEST_FILE ":6:9");
}

bool testClassAndClass(void) {
    return compileFailsWith(CA_DECLS
"void main()
{
  P p, q;
  p += q;
}
",
        "The `+=` operator cannot be applied to a class with a local variable of class",
        CA_TEST_FILE ":6:5");
}

/* Each bad statement is reported and the parser carries on */
bool testRecoveryContinues(void) {
    return compileFailsWith(CA_DECLS
"void main()
{
  P p;
  i64 y = 1;
  y += p;
  y <<= p;
  y = y + p;
}
",
        "The `+=` operator cannot be applied to a integer type with a local variable of class",
        CA_TEST_FILE ":8:5", 3);
}

/* Compound assignments that are valid keep working */
struct CaPoint {
    i64 x, y;
};

bool testValid(void) {
    i64 arr[4] = {10, 20, 30, 40};
    i64 *ptr = arr;
    f64 f = 1.5;
    i64 i = 7;
    date.Date d = 100;
    CaPoint pt, *ppt = &pt;
    bool ok = true;

    ptr += 2;
    if (*ptr != 30) { ok = false; }
    ptr -= 1;
    if (*ptr != 20) { ok = false; }

    f += i;
    if (f != 8.5) { ok = false; }
    f *= 2;
    if (f != 17.0) { ok = false; }

    d += 1;
    if (d != 101) { ok = false; }

    pt.x = 3;
    pt.y = 4;
    pt.x += 5;
    ppt.y <<= 2;
    if (pt.x != 8 || pt.y != 16) { ok = false; }

    i += 3;  if (i != 10) { ok = false; }
    i -= 4;  if (i != 6) { ok = false; }
    i *= 5;  if (i != 30) { ok = false; }
    i /= 4;  if (i != 7) { ok = false; }
    i %= 4;  if (i != 3) { ok = false; }
    i <<= 3; if (i != 24) { ok = false; }
    i >>= 1; if (i != 12) { ok = false; }
    i &= 10; if (i != 8) { ok = false; }
    i |= 3;  if (i != 11) { ok = false; }
    i ^= 15; if (i != 4) { ok = false; }

    return ok;
}

i32 main() {
    printf("Test - compound assignment with a class: ");
    i64 correct = 0, tests = 0;

    tests++; if (testEveryOperator())     { correct++; }
    tests++; if (testSameAsBinaryForm())  { correct++; }
    tests++; if (testUnion())             { correct++; }
    tests++; if (testThroughPointer())    { correct++; }
    tests++; if (testClassAndClass())     { correct++; }
    tests++; if (testRecoveryContinues()) { correct++; }
    tests++; if (testValid())             { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
