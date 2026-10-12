import cstr, fs, mem, proc, test;

/* Generics across modules and next to plain functions (v0.10.0): module
 * user (src/tests/modules/genericx) makes and calls instances of module
 * box's Box<T> in a field, a local, over its own type and nested (their
 * methods weren't found from another module, and an instance named after
 * another module's type, `Box<user.Term *>`, was taken for module
 * `box.Box<user`). A plain function and a generic of one name: the plain
 * ones that take the arguments win, the generic is the fallback. */

#define GX_OUT "./ktm-610-gx.out"
#define GX_BIN "./ktm-610-gx.bin"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/genericx/main.ktm"

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " GX_OUT " " GX_BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(GX_OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " GX_OUT " " GX_BIN);
    return ok;
}

u64 h<K>(K k) { return 1; }
u64 h(u8[] s) { return 2; }
u64 h(i64 x) { return 3; }

/* type arguments written on a name that has plain functions too */
i64 f(i64 a, u8 *b) { return a; }
T f<T>(i64 a) { T x; x = a; return x; }

i32 main() {
    printf("Test - generics across modules: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " GX_BIN " > " GX_OUT " 2>&1 && " GX_BIN " >> " GX_OUT " 2>&1",
               "24\n")) { correct++; }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " GX_OUT " 2>&1", "24\n")) { correct++; }

    tests++; if (h("x") == 2 && h(5) == 3 && h(2.5) == 1 && h((u8)1) == 3) { correct++; }
    i64 f2 = 3;
    tests++; if (f<i64>(5) == 5 && f<f64>(2) == 2.0 && f(6, "x") == 6 && (f2 < 4)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
