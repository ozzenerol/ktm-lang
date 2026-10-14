import cstr, fs, libc, mem, proc, test;

/* Stores into a module's global whose symbol is long (v0.20.0): the
 * x86_64 backend built the operand `symbol(%rip)` in a 32-byte buffer and
 * cut it short (`_K11hooksmodule16gClientTransport(`), so the assembler
 * refused the program; net.https's client hooks first hit it. AOT and
 * JIT, from a module compiled with the program. */

#define DIR "./ktm-799"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

bool prints(u8 *cmd, u8 *want, u8 *what) {
    u8 *out = proc.shellOutput("K=\"$(pwd)/../../ktm\"; %s", cmd).take();
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - stores to globals with long symbols: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/hooksmodule");
    put(DIR "/hooksmodule/h.ktm",
"module hooksmodule;
pub struct Hooks { fn(i64) i64 twice; fn(i64) i64 inc; };
static i64 twice(i64 x) { return 2 * x; }
static i64 inc(i64 x) { return x + 1; }
Hooks gClientTransport;
static i64 registerHooks() { gClientTransport.twice = &twice; gClientTransport.inc = &inc; return 1; }
i64 gRegistered = registerHooks();
pub i64 run(i64 x) { return gClientTransport.twice(x) + gClientTransport.inc(0); }
");
    put(DIR "/main.ktm", "import hooksmodule;\nvoid main() { printf(\"%d\\n\", hooksmodule.run(20)); }\n");
    prints("cd " DIR " && " KTM "main.ktm -o app 2>&1 && ./app", "41", "compiled");
    prints("cd " DIR " && " KTM "-jit main.ktm 2>&1", "41", "in the JIT");
    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
