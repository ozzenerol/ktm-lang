import cstr, fs, libc, mem, proc, test;

/* Generics in a module (v0.9.0): the fixture src/tests/modules/generics
 * has module coll over two files with a generic struct Vec<T>, its
 * methods, generic functions (pub and private) and a function using an
 * instance itself; the program uses coll.Vec<i64>, coll.Vec<f64> (with
 * `ad`), coll.vecNew<T> and coll.maxOf. Also built with -lib (the
 * library's own instances are weak, so the program's link to one copy),
 * AOT and JIT. A private generic is an error from outside. */

#define GM_OUT "./ktm-602-gen.out"
#define GM_BIN "./ktm-602-gen.bin"
#define GM_DIR "./m602"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "3 9 1.5 2\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " GM_OUT " " GM_BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(GM_OUT).take();
    bool ok = out && (!want || !cstr.cmp(out, want));
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " GM_OUT " " GM_BIN);
    return ok;
}

bool fails(u8 *main, u8 *want) {
    proc.shell("rm -rf " GM_DIR " && mkdir -p " GM_DIR " && "
               "cp -r ${KTM_TEST_SRC:-.}/modules/generics/coll " GM_DIR "/");
    fs.writeAll(GM_DIR "/main.ktm", cstr.view(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell(KTM "-S " GM_DIR "/main.ktm -o /dev/null > " GM_OUT " 2>&1");
    u8 *out = fs.readAll(GM_OUT).take();
    bool ok = out && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", main, out); }
    mem.free(out);
    proc.shell("rm -rf " GM_DIR " " GM_OUT);
    return ok;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/generics/main.ktm"

i32 main() {
    printf("Test - generics in modules: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " GM_BIN " > " GM_OUT " 2>&1 && " GM_BIN " >> " GM_OUT " 2>&1",
               gWant)) { correct++; }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " GM_OUT " 2>&1", gWant)) { correct++; }

    tests++;
    if (fails("import coll;\ni32 main() { return (i32)coll.hidden(1); }\n",
              "`hidden` is private to module coll")) { correct++; }

    proc.shell("rm -rf " GM_DIR " && mkdir -p " GM_DIR " && "
               "cp -r ${KTM_TEST_SRC:-.}/modules/generics/. " GM_DIR "/");
    tests++;
    if (output(KTM "-lib coll " GM_DIR "/coll > " GM_OUT " 2>&1; "
               "test -f " GM_DIR "/coll/.ktmlib && echo built > " GM_OUT, "built\n")) { correct++; }
    tests++;
    if (output(KTM GM_DIR "/main.ktm -o " GM_BIN " > " GM_OUT " 2>&1 && " GM_BIN " >> " GM_OUT " 2>&1",
               gWant)) { correct++; }
    tests++;
    if (output(KTM "-jit " GM_DIR "/main.ktm > " GM_OUT " 2>&1", gWant)) { correct++; }
    proc.shell("rm -rf " GM_DIR);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
