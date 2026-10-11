import cstr, fs, libc, proc, test;

/* Inside a module, calling one of its global function pointers by name
 * (`op(x)` of `pub fn(i64) i64 op`) gave "Function: op() not defined":
 * the call looked up the module's own functions, not its variables. A
 * local of that name still comes first. (F32) */

#define DIR "./ktm-479"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void put(u8 *path, u8 *src) {
    fs.writeAll(path, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

i32 main() {
    i64 correct = 0, tests = 2;
    printf("Test - a module's function pointer called by name: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/m");
    put(DIR "/m/a.ktm",
"module m;
i64 twice(i64 x) { return 2 * x; }
i64 thrice(i64 x) { return 3 * x; }
pub fn(i64) i64 op = &twice;
pub i64 run(i64 x) { return op(x); }
pub i64 shadowed(i64 x) { fn(i64) i64 op = &thrice; return op(x); }
pub void swap() { op = &thrice; }
");
    put(DIR "/main.ktm",
"import m;
i32 main() {
    printf(\"%d %d %d \", m.run(4), m.op(5), m.shadowed(2));
    m.swap();
    printf(\"%d\", m.run(4));
    return 0;
}
");
    u8 *out = proc.shellOutput(KTM "-jit " DIR "/main.ktm 2>&1");
    if (out && !cstr.cmp(out, "8 10 6 12")) { correct++; }
    else { printf("\n  JIT: [%s]\n", out); }
    out = proc.shellOutput(KTM DIR "/main.ktm -o " DIR "/bin 2>&1 && " DIR "/bin");
    if (out && !cstr.cmp(out, "8 10 6 12")) { correct++; }
    else { printf("\n  AOT: [%s]\n", out); }
    proc.shell("rm -rf " DIR);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
