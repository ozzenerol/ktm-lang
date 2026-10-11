import cstr, fs, libc, mem, proc, test;

/* A scalar global's initialiser (`i32 g = 7;`) was a store in the
 * generated main(): on arm64 that code ran over x0/x1 before Main read
 * argc/argv (and on x86_64 too once an initialiser called a function),
 * and in a -lib library, which has no main, the global stayed 0. Constant
 * initialisers are now static data, and the generated main passes argc
 * and argv on to Main. */

#define GI_SRC  "__385_gi.ktm"
#define GI_OUT  "__385_gi.out"
#define GI_BIN  "__385_gi.bin"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *slurp(u8 *cmd) {
    proc.shell("rm -f " GI_OUT);
    if (proc.shell(cmd) != 0) { return null; }
    return fs.readAll(GI_OUT);
}

/* `src` built with -jit and as a binary, run with the arguments
 * "one two", prints `want` both ways */
void runsAs(u8 *src, u8 *want, u8 *what) {
    u8 *out, label[128];
    fs.writeAll(GI_SRC, src, cstr.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    out = slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " GI_SRC " one two > " GI_OUT " 2>&1");
    cstr.formatTo(label, "%s (JIT)", what);
    check(out && !cstr.cmp(out, want), label);
    if (out && cstr.cmp(out, want)) { printf("  got: %s", out); }
    mem.free(out);
    out = slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " GI_SRC " -o " GI_BIN " && ./" GI_BIN " one two > " GI_OUT " 2>&1");
    cstr.formatTo(label, "%s (AOT)", what);
    check(out && !cstr.cmp(out, want), label);
    if (out && cstr.cmp(out, want)) { printf("  got: %s", out); }
    mem.free(out);
    proc.shell("rm -f " GI_SRC " " GI_OUT " " GI_BIN);
}

i32 main() {
    printf("Test - scalar global initialisers: ");

    /* every scalar type, converted the way an assignment converts (a bool
     * is 0 or 1) */
    runsAs(
"i8   a = -5;
i16  b = 300 * 100;
i32  c = 7;
i64  d = -3000000000;
u8   e = 300;
bool f = true;
f64  g = 1.5;
f32  h = 2.25;
i64  i = 2.9;
u8  *j = null;
u64  k = 0xFFFFFFFFFFFFFFFF;
bool l = 5;
bool m = 0.5;
i32 main(i32 argc, u8 **argv)
{
  printf(\"%d %s %s|%d %d %d %d %d %d %.2f %.2f %d %d %x %d %d\\n\", argc, argv[1], argv[2],
    a, b, c, d, e, f, g, h, i, j == null, k, l, m);
  return 0;
}
",
"3 one two|-5 30000 7 -3000000000 44 1 1.50 2.25 2 1 ffffffffffffffff 1 1\n",
        "argc/argv with initialised globals");

    /* initialisers that run at startup, in order, around constant ones */
    runsAs(
"i64 calls;
i64 next() { return ++calls; }
i64 first = next();
i32 seven = 7;
i64 second = next() * 10 + seven;
i64 third = next();
i32 main(i64 argc, u8 **argv)
{
  printf(\"calls %d\\n\", calls);
  printf(\"%d %s|%d %d %d\\n\", argc, argv[2], first, second, third);
  return 0;
}
",
"calls 3\n3 two|1 27 3\n",
        "non-constant initialisers keep their order");

    /* Main without parameters still runs after the initialisers */
    runsAs(
"import math;

i64 n = math.abs(-4);
void main() { printf(\"%d\\n\", n); }
",
"4\n", "Main without parameters");

    /* a -lib library's initialised globals have their values */
    proc.shell("rm -rf ./ktm-385-prefix ./ktm-385-work ./ktm-385-use.ktm ./ktm-385-use");
    check(proc.shell("D=${KTM_INSTALL_DIR:-../../build/prefix} && H=$PWD/../../ktm && P=$PWD && "
                     "mkdir -p ./ktm-385-prefix/lib ./ktm-385-work && "
                     "cp -R $D/modules ./ktm-385-prefix/ && "
                     "cp -P $D/lib/libktm.* ./ktm-385-prefix/lib/ && "
                     "printf 'static i32 hidden = -1;\\npub i64 shown = 42;\\n"
                     "pub f64 ratio = 0.5;\\n"
                     "pub i64 Ktm385Sum() { return hidden + shown; }\\n'"
                     " > ./ktm-385-work/ktm385.ktm && "
                     "cd ./ktm-385-work && "
                     "$H -lib ktm385 --install-dir=$P/ktm-385-prefix ktm385.ktm > /dev/null 2>&1") == 0,
                "build the -lib library");
    proc.shell("printf '#link <ktm385>\\n"
                 "extern i64 shown;\\nextern f64 ratio;\\ni64 Ktm385Sum();\\n"
                 "i32 main() { if (Ktm385Sum() == 41 && shown == 42 && ratio == 0.5) { return 0; } return 1; }\\n'"
                 " > ./ktm-385-use.ktm");
    check(proc.shell("../../ktm -jit --install-dir=./ktm-385-prefix ./ktm-385-use.ktm") == 0,
                "-lib globals (JIT)");
    check(proc.shell("../../ktm --install-dir=./ktm-385-prefix ./ktm-385-use.ktm -o ./ktm-385-use && "
                             "./ktm-385-use") == 0,
                "-lib globals (AOT)");
    proc.shell("rm -rf ./ktm-385-prefix ./ktm-385-work ./ktm-385-use.ktm ./ktm-385-use");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
