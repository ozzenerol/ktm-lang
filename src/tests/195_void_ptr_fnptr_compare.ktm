import cstr, fs, libc, mem, proc, test;

/* `void *w = &F; if (w == &F)` was rejected with "The `==` operator
 * cannot be applied to a pointer type with a unary op of function".
 * As in C, a `void *` holding a function address compares equal / not
 * equal with a function address or function pointer, in both orders.
 * Other mixes (an `i64 *`, an f64, `<`, `-`) are still errors. */

#define FNCMP_FILE "__195_fncmp.ktm"
#define FNCMP_OUT  "__195_fncmp.out"

void F() {}
void G() {}

struct Holder {
    void *any;
};

void *gW = &F;

void cleanupFnCmpTest(void) {
    fs.remove(FNCMP_FILE);
    fs.remove(FNCMP_OUT);
    fs.remove("./a.out");
}

/* `expr` must fail to compile with `expected` in the output */
bool compileFailsWith(u8 *expr, u8 *expected) {
    u8 *src, *cmd, *out;
    i64 res;
    bool ok = false;

    cleanupFnCmpTest();
    src = cstr.format(
"void F() {}
void main() {
  void *w = &F; fn() void fp = &F; i64 *q = null; f64 d = 1.0;
  printf(\"%%d\\n\", %s);
}
", expr);
    if (!fs.writeAll(FNCMP_FILE, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        mem.free(src);
        return false;
    }
    mem.free(src);
    cmd = cstr.format(
            "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
            FNCMP_FILE " > " FNCMP_OUT " 2>&1");
    res = proc.shell(cmd);
    mem.free(cmd);
    if (res != 0) {
        out = fs.readAll(FNCMP_OUT);
        if (out) {
            if (libc.strstr(out, expected)) {
                ok = true;
            } else {
                printf("%s\n", out);
            }
            mem.free(out);
        }
    }
    cleanupFnCmpTest();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 18;
    void *w = &F;
    void *n = null;
    fn() void fp = &F;
    fn() void gp = &G;
    Holder h;
    h.any = &G;

    printf("Test - compare a void pointer with a function pointer: ");

    if (w == &F) { correct++; }
    if (!(w != &F)) { correct++; }
    if (w != &G) { correct++; }
    if (&F == w) { correct++; }
    if (&G != w) { correct++; }
    if (w == fp && fp == w) { correct++; }
    if (w != gp && gp != w) { correct++; }
    if (n != fp && fp != n) { correct++; }
    if (fp != null && fp != 0 && !(fp == null)) { correct++; }
    if (gW == &F && gW != gp) { correct++; }
    if (h.any == &G && h.any != fp) { correct++; }
    /* The result is a 0/1 value */
    i64 eq = w == &F, ne = w == &G;
    if (eq == 1 && ne == 0) { correct++; }

    /* Still errors */
    if (compileFailsWith("fp == 1.5", "cannot be applied to a function")) { correct++; }
    if (compileFailsWith("fp == d", "cannot be applied to a function")) { correct++; }
    if (compileFailsWith("q == &F", "`i64 * == fn() void` is invalid")) { correct++; }
    if (compileFailsWith("w < &F", "`void * < fn() void` is invalid")) { correct++; }
    if (compileFailsWith("w - &F", "`void * - fn() void` is invalid")) { correct++; }
    if (compileFailsWith("w * fp", "is invalid")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
