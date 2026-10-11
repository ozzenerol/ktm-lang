import date, fs, libc, mem, proc, str, test;

/* `-x`, `+x` and `~x` need a number. On a string, pointer, array, class
 * or function they compiled and gave the negated/complemented address;
 * they must be a located type error. Valid uses keep their values, and
 * `-` on a u8/u16/i32.. is an i64 (it read back as 251 for `-(u8)5`). */

#define UNOP_TEST_FILE "__134_unary_op_type_fail.ktm"
#define UNOP_TEST_OUT  "__134_unary_op_type_fail.out"
const i64 NEG_DEFINE = -5;
const i64 NEG_EXPR_DEFINE = -(3 + 4);

struct Pt { i64 x, y; };
struct HasPtr { i64 *q; };

i64 twice(i64 a) { return a * 2; }

void cleanupUnopTest(void) {
    fs.remove(UNOP_TEST_FILE);
    fs.remove(UNOP_TEST_OUT);
    fs.remove("./a.out");
}

bool compileFailsWith(u8 *name, u8 *source, u8 *expected) {
    u8 *out;
    i64 res;

    cleanupUnopTest();
    if (!fs.writeAll(UNOP_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        printf("Failed - %s: could not write the source\n", name);
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNOP_TEST_FILE " > " UNOP_TEST_OUT " 2>&1");
    if (res == 0) {
        printf("Failed - %s: compiled\n", name);
        cleanupUnopTest();
        return false;
    }

    out = fs.readAll(UNOP_TEST_OUT);
    if (!out || !libc.strstr(out, expected)) {
        printf("Failed - %s: expected '%s' in:\n%s\n", name, expected, out);
        mem.free(out);
        cleanupUnopTest();
        return false;
    }

    mem.free(out);
    cleanupUnopTest();
    return true;
}

i64 testErrors(void) {
    i64 ok = 0;
    if (compileFailsWith("-string",
            "void main() { i64 u = -\"s\"; }\n",
            "The `-` operator cannot be applied to a string literal")) { ok++; }
    if (compileFailsWith("+string",
            "void main() { i64 u = +\"s\"; }\n",
            "The `+` operator cannot be applied to a string literal")) { ok++; }
    if (compileFailsWith("~string",
            "void main() { i64 u = ~\"s\"; }\n",
            "The `~` operator cannot be applied to a string literal")) { ok++; }
    /* Located on the operator: line 3, column of the `-`. */
    if (compileFailsWith("-pointer",
            "void main() {\n  i64 *p = 0;\n  i64 u = 1 + -p;\n}\n",
            ":3:15")) { ok++; }
    if (compileFailsWith("-pointer message",
            "void main() { i64 *p = 0; i64 u = -p; }\n",
            "`-` on `i64 *` is invalid")) { ok++; }
    if (compileFailsWith("-array",
            "void main() { i64 a[3]; i64 u = -a; }\n",
            "cannot be applied to a local variable of array type")) { ok++; }
    if (compileFailsWith("-class",
            "struct C { i64 x; };\nvoid main() { C c; i64 u = -c; }\n",
            "cannot be applied to a local variable of class")) { ok++; }
    if (compileFailsWith("-fnptr",
            "i64 F(i64 a) { return a; }\nvoid main() { fn(i64 a) i64 fp = &F; i64 u = -fp; }\n",
            "The `-` operator cannot be applied to a function pointer")) { ok++; }
    if (compileFailsWith("-&fn",
            "void F() {}\nvoid main() { i64 u = -&F; }\n",
            "The `-` operator cannot be applied")) { ok++; }
    /* A pointer expression, not just a variable. */
    if (compileFailsWith("-(ptr+1)",
            "struct S { i64 *q; };\nvoid main() { S s; S *sp = &s; i64 u = -(sp->q + 1); }\n",
            "cannot be applied to a binary op of pointer type")) { ok++; }
    if (compileFailsWith("~f64",
            "void main() { f64 f = 2.5; i64 u = ~f; }\n",
            "The `~` operator cannot be applied to a local variable of float type")) { ok++; }
    if (compileFailsWith("-void call",
            "void F() {}\nvoid main() { i64 u = -F(); }\n",
            "The `-` operator cannot be applied to a function call of void type")) { ok++; }
    return ok;
}

i32 main() {
    printf("Test - Unary operator types: \n");
    i64 tests = 12 + 14;
    i64 correct = testErrors();

    i64 i = 7, x = 4;
    u8 c = 5;
    i8 sc = -128;
    i16 h = 7;
    u32 w = 3;
    u64 big = 1;
    f64 f = 2.5, z = 0.0;
    i64 *p = &x, *np = null;
    i64 arr[2];
    arr[1] = 6;
    u8 *s = "ab";
    Pt pt;
    pt.x = 9;
    date.Date d = (date.Date)5;
    date.Date nd = -d;

    if (-i == -7 && +i == 7 && ~i == -8) { correct++; }
    if (-f == -2.5 && +f == 2.5 && -f * 2 == -5.0) { correct++; }
    /* Small integer types promote to i64. */
    i64 nc = -c;
    if (nc == -5 && -c == -5 && -c + 0 == -5 && ~c == -6 && +c == 5) { correct++; }
    if (-sc == 128 && -h == -7 && -w == -3 && ~w == -4) { correct++; }
    if (-big == 0xFFFFFFFFFFFFFFFF) { correct++; }
    if (-s[0] == -97 && -*s == -97) { correct++; }
    if (-*p == -4 && -arr[1] == -6 && -pt.x == -9 && - -x == 4) { correct++; }
    if (-(p - p) == 0) { correct++; }
    /* `!` takes anything. */
    if (!"s" == 0 && !p == 0 && !np == 1 && !arr == 0) { correct++; }
    if (!f == 0 && !z == 1) { correct++; }
    /* Intrinsic classes are integers. */
    if (nd == -5 && ~d == -6 && +d == 5) { correct++; }
    if (NEG_DEFINE == -5 && NEG_EXPR_DEFINE == -7) { correct++; }
    if (-twice(3) == -6) { correct++; }
    /* Passed straight to a vararg, not through a typed variable. */
    u8 *buf = str.format("%d %d", -c, -sc);
    if (!str.cmp(buf, "-5 128")) { correct++; }
    mem.free(buf);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
