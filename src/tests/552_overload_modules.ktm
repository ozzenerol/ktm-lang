import cstr, fs, libc, mem, proc, test;

/* Overloading in a module (v0.6.0): the fixture src/tests/modules/overload
 * has module geo over two files with overloaded functions (area,
 * describe, scale) and an overloaded method (Rect.grow), called by the
 * module itself across its files and by the program. pub is per
 * overload: geo.scale(i64) is pub, geo.scale(f64) is private. Also built
 * with -lib (each overload its own symbol in the library): AOT and JIT. */

#define OM_OUT "./ktm-552-ovl.out"
#define OM_BIN "./ktm-552-ovl.bin"
#define OM_DIR "./m552"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "6 12 13x14 n=7 what=box 12 8 15 196\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " OM_OUT " " OM_BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(OM_OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " OM_OUT " " OM_BIN);
    return ok;
}

/* `main` next to a copy of geo: one error, `want` */
bool fails(u8 *main, u8 *want) {
    proc.shell("rm -rf " OM_DIR " && mkdir -p " OM_DIR " && "
               "cp -r ${KTM_TEST_SRC:-.}/modules/overload/geo " OM_DIR "/");
    fs.writeAll(OM_DIR "/main.ktm", cstr.view(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell(KTM "-S " OM_DIR "/main.ktm -o /dev/null > " OM_OUT " 2>&1");
    u8 *out = fs.readAll(OM_OUT).take();
    u8 *first = out ? libc.strstr(out, "error:") : null;
    bool ok = first && !libc.strstr(first + 1, "error:") && libc.strstr(first, want);
    if (!ok) { printf("\n%s\n----\n%s\n", main, out); }
    mem.free(out);
    proc.shell("rm -rf " OM_DIR " " OM_OUT);
    return ok;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/overload/main.ktm"

i32 main() {
    printf("Test - overloading in modules: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " OM_BIN " > " OM_OUT " 2>&1 && " OM_BIN " >> " OM_OUT " 2>&1",
               gWant)) { correct++; }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " OM_OUT " 2>&1", gWant)) { correct++; }

    tests++;
    if (fails("import geo;\ni32 main() { return geo.scale(2.5); }\n",
              "`geo.scale(f64)` is private to module geo")) { correct++; }
    tests++;
    if (fails("import geo;\ni32 main() { return geo.scale(\"x\"); }\n",
              "no overload of `geo.scale` takes (u8[2]); there are `geo.scale(i64)`, "
              "`geo.scale(f64)`")) { correct++; }

    /* precompiled */
    proc.shell("rm -rf " OM_DIR " && mkdir -p " OM_DIR " && "
               "cp -r ${KTM_TEST_SRC:-.}/modules/overload/. " OM_DIR "/");
    tests++;
    if (output(KTM "-lib geo " OM_DIR "/geo > " OM_OUT " 2>&1; "
               "test -f " OM_DIR "/geo/.ktmlib && echo built > " OM_OUT, "built\n")) {
        correct++;
    }
    tests++;
    if (output(KTM OM_DIR "/main.ktm -o " OM_BIN " > " OM_OUT " 2>&1 && " OM_BIN " >> " OM_OUT " 2>&1",
               gWant)) { correct++; }
    tests++;
    if (output(KTM "-jit " OM_DIR "/main.ktm > " OM_OUT " 2>&1", gWant)) { correct++; }
    proc.shell("rm -rf " OM_DIR);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
