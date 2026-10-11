import cstr, fs, libc, mem, proc, test;

/* Modules (M5): the files of a module are read in name order, but every
 * name of every file is visible in any order, types included. The
 * fixture src/tests/modules/fileorder has module shop over three files:
 * a_order.ktm's struct holds a pointer to b_types.ktm's Box (which points
 * back), b_types.ktm's Item by value (which holds c_price.ktm's Price by
 * value), b_types.ktm's enum and its const as an array size; a_order.ktm
 * also has a global sized by that const, a method of Item and functions
 * using Box's method. Before, a_order.ktm's struct failed with "Unexpected
 * type `Box`". AOT, JIT and built with -lib. A cycle of structs holding
 * each other by value is an error; a type that doesn't exist still gives
 * the error it gave. */

#define FO_OUT "./ktm-429-fileorder.out"
#define FO_BIN "./ktm-429-fileorder.bin"
#define FO_DIR "./fo429"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/fileorder"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "132 5 6 7\n10 6 1\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " FO_OUT " " FO_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(FO_OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " FO_OUT " " FO_BIN);
    return ok;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* Module m from the files a.ktm and b.ktm, imported by a program that
 * doesn't use it: compiling it gives `errors` errors, and `want` if not
 * null */
bool compiles(u8 *a, u8 *b, i64 errors, u8 *want) {
    proc.shell("rm -rf " FO_DIR " && mkdir -p " FO_DIR "/m");
    u8 *main = "import m;\ni32 main() { return 0; }\n";
    fs.writeAll(FO_DIR "/main.ktm", cstr.view(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.writeAll(FO_DIR "/m/a.ktm", cstr.view(a), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.writeAll(FO_DIR "/m/b.ktm", cstr.view(b), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell(KTM "-S " FO_DIR "/main.ktm -o /dev/null > " FO_OUT " 2>&1");
    u8 *out = fs.readAll(FO_OUT).take();
    bool ok = out && count(out, "error:") == errors && (!want || libc.strstr(out, want));
    if (!ok) { printf("\n%s\n%s\n----\n%s\n", a, b, out); }
    mem.free(out);
    proc.shell("rm -rf " FO_DIR " " FO_OUT);
    return ok;
}

i32 main() {
    printf("Test - modules: types of any file, in any order: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE "/main.ktm -o " FO_BIN " > " FO_OUT " 2>&1 && "
                         FO_BIN " >> " FO_OUT " 2>&1", gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FIXTURE "/main.ktm > " FO_OUT " 2>&1", gWant)) { correct++; }

    /* precompiled: built into a library, then imported */
    proc.shell("rm -rf " FO_DIR " && mkdir -p " FO_DIR " && cp -r " FIXTURE "/. " FO_DIR "/");
    tests++;
    if (output(KTM "-lib shop " FO_DIR "/shop > " FO_OUT " 2>&1; "
                         "test -f " FO_DIR "/shop/.ktmlib && echo built > " FO_OUT, "built\n")) {
        correct++;
                         }
    tests++;
    if (output(KTM FO_DIR "/main.ktm -o " FO_BIN " > " FO_OUT " 2>&1 && "
                         FO_BIN " >> " FO_OUT " 2>&1", gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FO_DIR "/main.ktm > " FO_OUT " 2>&1", gWant)) { correct++; }
    proc.shell("rm -rf " FO_DIR);

    /* structs holding each other by value: one error, at the first */
    tests++;
    if (compiles("module m;\npub struct A { B b; };\n", "module m;\npub struct B { A a; };\n",
                             1, "field `b` of m.A holds a m.B, which is not complete here")) {
        correct++;
                             }
    /* the same with pointers is fine */
    tests++;
    if (compiles("module m;\npub struct A { B *b; };\n",
                             "module m;\npub struct B { A *a; };\n", 0, null)) {
        correct++;
                             }
    /* a type that doesn't exist, used by an earlier file's struct */
    tests++;
    if (compiles("module m;\npub struct A { Missing x; B *b; };\n",
                             "module m;\npub struct B { i64 v; };\n",
                             1, "Unexpected type `Missing` while parsing class m.A")) {
        correct++;
                             }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
