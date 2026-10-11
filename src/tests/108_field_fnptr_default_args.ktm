import fs, libc, mem, proc, str, test;

/* Default arguments declared on a class field function pointer were
 * ignored: `c.f(1)` with `fn(i64 a, i64 b=10) i64 f` passed whatever was
 * in the register for `b` instead of 10. Plain and local function
 * pointers already filled them in. */

#define FIELD_FNPTR_TEST_FILE "__108_field_fnptr_fail.ktm"
#define FIELD_FNPTR_TEST_OUT  "__108_field_fnptr_fail.out"

struct C {
    fn(i64 a, i64 b=10) i64 f;
    fn(i64 a=1, i64 b=2) i64 all;
    fn(f64 x, f64 y=2.5, f64 z=1) f64 fl;
    fn(u8 *a, u8 *b="world") u8 *s;
    i64 v;
};

struct Outer {
    C in;
    C *pin;
};

i64 add(i64 a, i64 b) { return a + b; }
i64 sub(i64 a, i64 b) { return a - b; }
f64 fAdd(f64 x, f64 y, f64 z) { return x + y + z; }
u8 *join(u8 *a, u8 *b) { return str.format("%s %s", a, b); }

bool compileFailsWith(u8 *source, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok = false;

    if (!fs.writeAll(FIELD_FNPTR_TEST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " FIELD_FNPTR_TEST_FILE " > " FIELD_FNPTR_TEST_OUT " 2>&1");
    if (res != 0) {
        out = fs.readAll(FIELD_FNPTR_TEST_OUT);
        if (out) {
            if (libc.strstr(out, expected)) { ok = true; }
            else { printf("%s\n", out); }
            mem.free(out);
        }
    }
    fs.remove(FIELD_FNPTR_TEST_FILE);
    fs.remove(FIELD_FNPTR_TEST_OUT);
    fs.remove("./a.out");
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 14;
    u8 *strVal;

    printf("Test - default arguments through class field function pointers: ");

    C c;
    c.f = &add;
    c.all = &sub;
    c.fl = &fAdd;
    c.s = &join;

    /* the original report */
    if (c.f(1) == 11) { correct++; }
    /* explicit argument overrides the default */
    if (c.f(1, 2) == 3) { correct++; }
    /* every argument defaulted, with and without a skipped slot */
    if (c.all() == -1) { correct++; }
    if (c.all(5) == 3) { correct++; }
    if (c.all(, 7) == -6) { correct++; }

    /* f64 defaults, including an integer literal default for an f64 */
    if (c.fl(1.0) == 4.5) { correct++; }
    if (c.fl(1.0, 0.5) == 2.5) { correct++; }

    /* string default */
    strVal = c.s("hello");
    if (!str.cmp(strVal, "hello world")) { correct++; }
    mem.free(strVal);

    /* through a pointer to the class */
    C *cp = &c;
    if (cp.f(2) == 12) { correct++; }
    if (cp.all() == -1) { correct++; }

    /* nested class fields, by value and by pointer */
    Outer o;
    o.in.f = &add;
    o.pin = &c;
    if (o.in.f(3) == 13) { correct++; }
    if (o.pin.f(4) == 14) { correct++; }
    if (o.pin.fl(0.0, 1.0) == 2.0) { correct++; }

    /* a field without defaults still rejects too few arguments */
    if (compileFailsWith(
"
i64 Add(i64 a, i64 b) { return a + b; }
struct C { fn(i64 a, i64 b) i64 f; };
void main() { C c; c.f = &Add; c.f(1); }
",
            "Unexpected number of arguments 1 in call to f(), expected 2 args")) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
