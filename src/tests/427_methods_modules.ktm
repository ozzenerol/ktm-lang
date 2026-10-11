import cstr, fs, libc, mem, proc, test;

/* Methods (Y10) and modules: the fixture src/tests/modules/methods has
 * module bank with `pub struct Account` and its methods over two files
 * (a method calls one of the other file). Outside, a pub method is called
 * on a `bank.Account` value or pointer with no prefix, its address is
 * `&bank.Account.deposit`, and `bank.Account.deposit(p, x)` calls it
 * explicitly. A method that isn't pub is private to bank; only bank can
 * define methods of its types (not the program, not another module).
 * Also built with -lib: AOT and JIT. */

#define M_OUT "./ktm-427-methods.out"
#define M_BIN "./ktm-427-methods.bin"
#define M_DIR "./m427"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "ann:115:6 bob:4:7\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " M_OUT " " M_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(M_OUT);
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " M_OUT " " M_BIN);
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

/* A program `main` (and the module `shop` when given) next to a copy of
 * bank fails with exactly one error, `want` */
bool fails(u8 *main, u8 *shop, u8 *want) {
    proc.shell("rm -rf " M_DIR " && mkdir -p " M_DIR " && "
                 "cp -r ${KTM_TEST_SRC:-.}/modules/methods/bank " M_DIR "/");
    fs.writeAll(M_DIR "/main.ktm", main, cstr.len(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (shop) {
        proc.shell("mkdir -p " M_DIR "/shop");
        fs.writeAll(M_DIR "/shop/shop.ktm", shop, cstr.len(shop), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    }
    proc.shell(KTM "-S " M_DIR "/main.ktm -o /dev/null > " M_OUT " 2>&1");
    u8 *out = fs.readAll(M_OUT);
    bool ok = out && count(out, "error:") == 1 && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", main, out); }
    mem.free(out);
    proc.shell("rm -rf " M_DIR " " M_OUT);
    return ok;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/methods/main.ktm"

i32 main() {
    printf("Test - methods: modules: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " M_BIN " > " M_OUT " 2>&1 && " M_BIN " >> " M_OUT " 2>&1",
                         gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " M_OUT " 2>&1", gWant)) { correct++; }

    tests++;
    if (fails("import bank;\ni32 main() { bank.Account *a = bank.open(\"x\"); a.audit(); return 0; }\n",
                        null, "the method `audit` of `bank.Account` is private to module bank")) {
        correct++;
                        }
    tests++;
    if (fails("import bank;\nvoid bank.Account.steal() {}\ni32 main() { return 0; }\n", null,
                        "methods of `bank.Account` can only be defined in the module that defines it")) {
        correct++;
                        }
    tests++;
    if (fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
                        "module shop;\nimport bank;\nvoid bank.Account.steal() {}\npub void go() {}\n",
                        "methods of `bank.Account` can only be defined in the module that defines it")) {
        correct++;
                        }
    tests++;
    if (fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
                        "module shop;\npub struct Cart { i64 n; };\nvoid Cart.n() {}\npub void go() {}\n",
                        "`shop.Cart` has a field named `n`")) {
        correct++;
                        }

    /* precompiled */
    proc.shell("rm -rf " M_DIR " && mkdir -p " M_DIR " && "
                 "cp -r ${KTM_TEST_SRC:-.}/modules/methods/. " M_DIR "/");
    tests++;
    if (output(KTM "-lib bank " M_DIR "/bank > " M_OUT " 2>&1; "
                         "test -f " M_DIR "/bank/.ktmlib && echo built > " M_OUT, "built\n")) {
        correct++;
                         }
    tests++;
    if (output(KTM M_DIR "/main.ktm -o " M_BIN " > " M_OUT " 2>&1 && " M_BIN " >> " M_OUT " 2>&1",
                         gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " M_DIR "/main.ktm > " M_OUT " 2>&1", gWant)) { correct++; }
    proc.shell("rm -rf " M_DIR);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
