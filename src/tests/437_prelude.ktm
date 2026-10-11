import cstr, fs, libc, mem, proc, test;

/* The prelude (src/std/prelude, installed as modules/prelude): its pub
 * names, Error and the macros EXIT_OK/EXIT_FAIL and the integer limits
 * (I8_MIN, I8_MAX, U8_MAX ... I64_MIN, I64_MAX, U64_MAX),
 * are seen by every file, the program's and every module's, with no
 * import and no prefix, and it is read before std.ktmh. std.ktmh keeps
 * guarded copies for an install without a prelude. `import prelude;` is
 * an error, and a directory called prelude next to the program is not
 * the prelude. The fixture is src/tests/modules/preludeuse; AOT and JIT. */

#define MOD_OUT "./ktm-437-prelude.out"
#define MOD_BIN "./ktm-437-prelude.bin"
#define AT_SRC "./ktm-437-prelude.ktm"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/preludeuse/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "1 383 42\nError 5\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    proc.shell(cmd);
    u8 *out = fs.readAll(MOD_OUT);
    bool ok = out && libc.strstr(out, want) != null;
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " MOD_OUT " " MOD_BIN);
    return ok;
}

/* the diagnostics for `source` have want in them */
bool diagnose(u8 *source, u8 *want) {
    fs.writeAll(AT_SRC, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    bool ok = output(KTM "-S " AT_SRC " -o /dev/null > " MOD_OUT " 2>&1", want);
    proc.shell("rm -f " AT_SRC);
    return ok;
}

i32 main() {
    printf("Test - the prelude: ");
    i64 correct = 0, tests = 0;

    /* the program and a module, with a decoy prelude/ beside them */
    tests++;
    if (output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1",
                         gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1", gWant)) { correct++; }

    /* here too: no import, no prefix */
    Error e;
    e.code = I64_MIN;
    tests++;
    if (e.code == I64_MIN && U64_MAX == -1 && EXIT_OK == 0 && !cstr.cmp(typeof(e), "Error")) {
        correct++;
    }

    /* the limits of every integer type, in code and in #if */
    i8 small = I8_MIN;
    tests++;
    if (small == -128 && I8_MIN == -128 && I8_MAX == 127 && U8_MAX == 255 &&
            I16_MIN == -32768 && I16_MAX == 32767 && U16_MAX == 65535 &&
            I32_MIN == -2147483648 && I32_MAX == 2147483647 && U32_MAX == 4294967295 &&
            I64_MAX == 0x7fffffffffffffff && I64_MIN == -I64_MAX - 1) {
        correct++;
            }
#if I32_MIN < 0 && U16_MAX == 65535 && I8_MIN == -128
    tests++; correct++;
#else
    tests++;
#endif

    tests++;
    if (diagnose("import prelude;\ni32 main() { return 0; }\n",
                             "error: the prelude is always imported: its names need no import and no prefix")) {
        correct++;
                             }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
