import fs, libc, mem, proc, str, test;

/* The `libc` module declares the C library: its files hold only extern "c"
 * functions, structs, unions, enums and constants, and `libc.write(...)`
 * calls C's write. An extern "c" in any module is emitted with its C
 * name, not a module symbol (_K...), whether pub or not, called or taken
 * the address of. In module libc a function with a body, a ktm prototype,
 * a method or a variable is an error. The fixture is
 * src/tests/modules/cmodule; AOT and JIT. */

#define MOD_OUT "./ktm-438-c.out"
#define MOD_BIN "./ktm-438-c.bin"
#define TMP_DIR "./ktm-438-c"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/cmodule/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "hello\n0 16 3 7\n";

bool output(u8 *cmd, u8 *want, u8 *not=null) {
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(MOD_OUT);
    bool ok = out && libc.strstr(out, want) != null && (!not || !libc.strstr(out, not));
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    return ok;
}

/* a `libc` module with `extra` added: the diagnostics have want */
bool cModule(u8 *extra, u8 *want) {
    proc.shell("rm -rf " TMP_DIR " && mkdir -p " TMP_DIR "/libc");
    u8 *mod = str.format("module libc;\npub extern \"c\" u64 strlen(u8 *s);\n%s\n", extra);
    u8 *main = "import libc;\ni32 main() { return libc.strlen(\"\"); }\n";
    fs.writeAll(TMP_DIR "/libc/libc.ktm", mod, str.len(mod), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.writeAll(TMP_DIR "/main.ktm", main, str.len(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    bool ok = output(KTM "-S " TMP_DIR "/main.ktm -o /dev/null > " MOD_OUT " 2>&1", want);
    proc.shell("rm -rf " TMP_DIR);
    mem.free(mod);
    return ok;
}

#define ONLY "error: module libc only declares C: extern \"c\" functions, structs, unions, enums and constants, not "

i32 main() {
    printf("Test - the libc module: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1",
                         gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1", gWant)) { correct++; }

    /* C's names: calls to write and abs (net.sock's private extern), the
     * address of strlen (x86_64 `strlen@GOTPCREL`, arm64 `:got:strlen`,
     * Mach-O with a `_`); no module symbol for any of them */
    tests++;
    if (output(KTM "-S " FIXTURE " -o " MOD_BIN " && "
                         "grep -qE '(call|bl)[a-z]*[[:space:]]+_?write$' " MOD_BIN " && "
                         "grep -qE '(call|bl)[a-z]*[[:space:]]+_?abs$' " MOD_BIN " && "
                         "grep -qE ':got:strlen|_?strlen@' " MOD_BIN " && "
                         "! grep -qE '_K[0-9]+libc[0-9]+(write|strlen|clock_gettime)|_K3net4sock3abs' " MOD_BIN " && "
                         "echo c-names > " MOD_OUT, "c-names")) {
        correct++;
                         }

    /* constants, structs, unions, enums are fine */
    tests++;
    if (cModule("pub const i64 K = 3;\npub union U { i64 a; f64 b; };\n", "") ) { correct++; }
    tests++; if (cModule("pub i64 f() { return 1; }", ONLY "a function with a body")) { correct++; }
    tests++; if (cModule("pub i64 h(i64 x);", ONLY "a ktm function's prototype")) { correct++; }
    tests++; if (cModule("i64 g;", ONLY "a variable")) { correct++; }
    tests++; if (cModule("pub struct S { i64 x; };\npub void S.f() { }", ONLY "a function with a body")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
