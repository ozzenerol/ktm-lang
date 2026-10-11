import cstr, fs, libc, mem, proc, test;

/* As in C, `(` calls any expression whose type is a function pointer.
 * A cast to a function pointer type (`((i64 (*)(i64))fv)(5)`,
 * `fv(i64 (*)(i64))(5)`), a call that returns one (`GetH()(x)`) and an
 * assignment (`(fp = &F)(x)`) could not be called; variables, fields and
 * array elements could. Postfix casts did not take the nameless
 * `T (*)(params)` type at all. Arguments are checked against the
 * pointer's parameters, and `(x)(3)` on a non-function is a clear error. */

i64 twice(i64 x) { return x * 2; }
i64 add(i64 a, i64 b) { return a + b; }
f64 half(f64 x) { return x / 2.0; }
f64 mix(i64 a, f64 b, i64 c) { return a + b * c; }
i64 sum(i64 n, ...args) {
    i64 s = n;
    for (i64 i = 0; i < args.len; i++) { s += args[i]; }
    return s;
}

struct Pt { i64 x, y; };
i64 sumPt(Pt *p) { return p.x + p.y; }

struct S { fn(i64) i64 cb; fn(i64) i64 cbs[2]; };

void *getV() { return &twice; }
auto getH() {
    fn(i64, i64) i64 fp = &add;
    return fp;
}

#define CE_SRC "408-err.ktm"
#define CE_OUT "408-err.out"

/* compile source; true if it fails and prints want */
bool failsWith(u8 *source, u8 *want) {
    u8 *out;
    bool ok;
    proc.shell("rm -f " CE_SRC " " CE_OUT);
    fs.writeAll(CE_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    ok = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-S " CE_SRC " -o /dev/null > " CE_OUT " 2>&1") != 0;
    out = fs.readAll(CE_OUT).take();
    ok = ok && out && libc.strstr(out, want);
    if (!ok) { printf("got: '%s'\n", out); }
    mem.free(out);
    proc.shell("rm -f " CE_SRC " " CE_OUT);
    return ok;
}

i32 main() {
    printf("Test - calling function pointer expressions: ");
    i64 correct = 0, tests = 0;
    void *fv = &twice, *fadd = &add, *fhalf = &half, *fmix = &mix, *fsum = &sum;
    void *fpt = &sumPt;
    fn(i64) i64 fp;
    fn(i64) i64 tab[2];
    i64 i = 1;
    S s;
    S *sp = &s;
    Pt pt;

    /* prefix casts */
    tests++; if (((fn(i64) i64)fv)(5) == 10) { correct++; }
    tests++; if (((fn(i64, i64) i64)fadd)(5, 6) == 11) { correct++; }
    tests++; if (((fn(i64) i64)getV())(7) == 14) { correct++; }
    /* postfix casts, bare and in parentheses */
    tests++; if (((fn(i64) i64)fv)(3) == 6) { correct++; }
    tests++; if (((fn(i64) i64)fv)(4) == 8) { correct++; }
    tests++; if (((fn(i64) i64)(fv))(9) == 18) { correct++; }
    tests++; if (((fn(i64) i64)getV())(21) == 42) { correct++; }
    tests++; if (1 + ((fn(i64, i64) i64)fadd)(2, 3) * 2 == 11) { correct++; }
    /* floats, int to float conversion, varargs, a class pointer */
    tests++; if (((fn(f64) f64)fhalf)(5.0) == 2.5) { correct++; }
    tests++; if (((fn(f64) f64)fhalf)(7) == 3.5) { correct++; }
    tests++; if (((fn(i64, f64, i64) f64)fmix)(1, 2.5, 4) == 11.0) { correct++; }
    tests++; if (((fn(i64, ...args) i64)fsum)(100, 1, 2, 3) == 106) { correct++; }
    pt.x = 3;
    pt.y = 4;
    tests++; if (((fn(Pt *) i64)fpt)(&pt) == 7) { correct++; }
    /* the result of a call that returns a function pointer */
    tests++; if (getH()(8, 9) == 17) { correct++; }
    /* the result of an assignment */
    tests++; if ((fp = &twice)(9) == 18 && fp(1) == 2) { correct++; }

    /* the forms that already worked */
    tests++; if ((fp)(3) == 6 && (*fp)(4) == 8 && (&twice)(5) == 10) { correct++; }
    tab[0] = &twice;
    tab[1] = (fn(i64) i64)fadd;
    tab[1] = &twice;
    tests++; if (tab[i](6) == 12 && (tab[i])(7) == 14 && (*tab[0])(8) == 16) { correct++; }
    s.cb = &twice;
    s.cbs[1] = &twice;
    tests++;
    if (s.cb(1) == 2 && sp.cb(2) == 4 && (sp.cb)(3) == 6 && (*sp.cb)(4) == 8 &&
            s.cbs[1](5) == 10 && sp.cbs[i](6) == 12) {
        correct++;
            }

    /* argument checking and errors */
    tests++;
    if (failsWith("i64 Add(i64 a, i64 b) { return a + b; }\n"
                                "i32 main() { void *f = &Add; return ((i64 (*)(i64, i64))f)(1); }\n",
                                "Unexpected number of arguments 1")) {
        correct++;
                                }
    tests++;
    if (failsWith("i64 Add(i64 a, i64 b) { return a + b; }\n"
                                "i32 main() { void *f = &Add; return f(i64 (*)(i64, i64))(1, 2, 3); }\n",
                                "Unexpected number of arguments 3")) {
        correct++;
                                }
    tests++;
    if (failsWith("i32 main() { i64 x = 5; return (x)(3); }\n",
                                "Cannot call integer type `x`")) {
        correct++;
                                }
    /* a nameless `(*)` in a declaration is still an error */
    tests++;
    if (failsWith("i32 main() { i64 (*)(i64); return 0; }\n",
                                "Expected function pointer name")) {
        correct++;
                                }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
