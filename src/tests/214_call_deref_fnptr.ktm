import fs, libc, mem, proc, str, test;

/* `(*fp)()` was an error ("The `*` operator cannot be applied to a
 * function pointer"). As in C, `*fp` of a function pointer is now the
 * function pointer itself, so `(*fp)()`, `(**fp)()`, `(*s.cb)(x)` and
 * `(*arr[i])(x)` all call it. `*` on an integer or a float is still an
 * error. */

#define FNDEREF_FILE "__214_fnderef.ktm"
#define FNDEREF_OUT  "__214_fnderef.out"
#define FNDEREF_BIN  "__214_fnderef.bin"

i64 gHits;
void hit() { gHits++; }
i64 addFn(i64 a, i64 b) { return a + b; }
i64 mul(i64 a, i64 b) { return a * b; }
f64 halfFn(f64 x) { return x / 2.0; }
u8 *nameFn(i64 i) { if (i) { return "one"; } return "zero"; }
i64 sum3Fn(i64 a, i64 b=20, i64 c=300) { return a + b + c; }

struct Ops {
    fn(i64 a, i64 b) i64 cb;
    i64 k;
};

fn(i64 a, i64 b) i64 gOp = &mul;

i64 callParam(fn(i64 a, i64 b) i64 f, i64 x) {
    return (*f)(x, x) + (f)(1, 1);
}

void cleanupFiles() {
    fs.remove(FNDEREF_FILE);
    fs.remove(FNDEREF_OUT);
    fs.remove(FNDEREF_BIN);
}

bool compileFailsWith(u8 *source, u8 *expected) {
    cleanupFiles();
    if (!fs.writeAll(FNDEREF_FILE, source, str.len(source),
                                 libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                     FNDEREF_FILE " -o " FNDEREF_BIN " > " FNDEREF_OUT " 2>&1");
    bool ok = res != 0;
    if (ok) {
        u8 *out = fs.readAll(FNDEREF_OUT);
        ok = out && libc.strstr(out, expected) != null;
        if (!ok && out) { printf("%s\n", out); }
        mem.free(out);
    }
    cleanupFiles();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - call through a dereferenced function pointer: ");

    fn() void fp = &hit;
    gHits = 0;
    (*fp)();
    (**fp)();
    (***fp)();
    (fp)();
    tests++; if (gHits == 4) { correct++; }
    else { printf("(*fp)() did not call\n"); }

    fn(i64 a, i64 b) i64 add = &addFn;
    tests++; if ((*add)(2, 3) == 5 && (**add)(4, 5) == 9 && add(1, 1) == 2) { correct++; }
    else { printf("arguments/return value wrong\n"); }

    fn(f64 x) f64 half = &halfFn;
    fn(i64 i) u8 *name = &nameFn;
    tests++; if ((*half)(5.0) == 2.5 && !str.cmp((*name)(1), "one") &&
                             !str.cmp((*name)(0), "zero")) { correct++; }
    else { printf("float/pointer return wrong\n"); }

    Ops ops;
    ops.cb = &mul;
    Ops *op = &ops;
    tests++; if ((*ops.cb)(6, 7) == 42 && (*op.cb)(2, 8) == 16) { correct++; }
    else { printf("(*s.cb)(x) wrong\n"); }

    fn(i64 a, i64 b) i64 arr[2];
    arr[0] = &addFn;
    arr[1] = &mul;
    i64 total = 0;
    for (i64 i = 0; i < 2; ++i) { total += (*arr[i])(3, 4); }
    tests++; if (total == 7 + 12) { correct++; }
    else { printf("(*arr[i])(x) wrong\n"); }

    /* In larger expressions, through a global and a parameter */
    i64 x = (*add)(1, 2) * 10 + (*arr[1])(2, 2) - (*gOp)(3, 3);
    tests++; if (x == 30 + 4 - 9 && callParam(&addFn, 5) == 12) { correct++; }
    else { printf("expression/global/param call wrong\n"); }

    /* `*fp` as a value is the function pointer */
    fn(i64 a, i64 b) i64 copy = *add;
    fn(i64 a, i64 b=20, i64 c=300) i64 sum3 = &sum3Fn;
    tests++; if (copy(10, 20) == 30 && (*sum3)(1) == 321 && (*sum3)(1, 2) == 303) { correct++; }
    else { printf("*fp as a value / default args wrong\n"); }

    /* A cast after a parenthesised function pointer is still a cast */
    tests++; if ((i64)(add) == (i64)(&addFn)) { correct++; }
    else { printf("(fp)(i64) is no longer a cast\n"); }

    tests++;
    if (compileFailsWith("void main() { i64 n = 5; i64 m = *n; }\n",
                                             "The `*` operator cannot be applied")) { correct++; }
    else { printf("`*` on an integer accepted\n"); }

    tests++;
    if (compileFailsWith("void main() { f64 f = 1.5; f64 g = *f; }\n",
                                             "The `*` operator cannot be applied")) { correct++; }
    else { printf("`*` on a float accepted\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
