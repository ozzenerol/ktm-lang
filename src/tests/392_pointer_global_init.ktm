import fs, libc, mem, proc, str, test;

/* A global pointer initialised with an address (`u8 *p = &g;`, `&arr[2]`,
 * `&Fn`, null) was a store in the generated main(), so in a -lib library
 * (no main) it stayed null. Constant addresses are now written as
 * relocated data (`.quad sym+off`), and the JIT fills them in once the
 * target has an address. */

#define PG_SRC "__392_pg.ktm"
#define PG_OUT "__392_pg.out"
#define PG_BIN "__392_pg.bin"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *slurp(u8 *cmd) {
    proc.shell("rm -f " PG_OUT);
    if (proc.shell(cmd) != 0) { return null; }
    return fs.readAll(PG_OUT);
}

/* `src` with -jit and as a binary prints `want` */
void runsAs(u8 *src, u8 *want, u8 *what) {
    u8 *out, label[128];
    fs.writeAll(PG_SRC, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    out = slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " PG_SRC " > " PG_OUT " 2>/dev/null");
    str.formatTo(label, "%s (JIT)", what);
    check(out && !str.cmp(out, want), label);
    if (out && str.cmp(out, want)) { printf("  got: %s", out); }
    mem.free(out);
    out = slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PG_SRC " -o " PG_BIN " 2>/dev/null && ./" PG_BIN " > " PG_OUT " 2>&1");
    str.formatTo(label, "%s (AOT)", what);
    check(out && !str.cmp(out, want), label);
    if (out && str.cmp(out, want)) { printf("  got: %s", out); }
    mem.free(out);
    proc.shell("rm -f " PG_SRC " " PG_OUT " " PG_BIN);
}

i32 main() {
    printf("Test - pointer global initialisers: ");

    runsAs(
"i64 g = 42;
i64 arr[5] = {10, 20, 30, 40, 50};
struct Pt { i64 x, y; };
Pt pt = {7, 8};
static i64 sg = 99;
i64 twice(i64 v) { return v * 2; }
i64 thrice(i64 v);
i64 *pg = &g;
i64 *pa = &arr[2];
i64 *pa2 = arr + 4;
i64 *pback = &arr[4] - 3;
u8 *pb = arr;
Pt *ppt = &pt;
i64 *psg = &sg;
fn(i64) i64 fp = &twice;
fn(i64) i64 later = &thrice;
u8 *np = null;
void *pf = &printf;
i64 thrice(i64 v) { return v * 3; }
void main()
{
  printf(\"%d %d %d %d %d %d %d %d %d %d %d\\n\", *pg, *pa, *pa2, *pback, pb[8], ppt.y,
    *psg, fp(21), later(5), np == null, pf == &printf);
}
",
"42 30 50 20 20 8 99 42 15 1 1\n", "pointer globals");

    /* a -lib library's pointer globals point at its own data and code */
    proc.shell("rm -rf ./ktm-392-prefix ./ktm-392-work ./ktm-392-use.ktm ./ktm-392-use");
    check(proc.shell("D=${KTM_INSTALL_DIR:-../../build/prefix} && H=$PWD/../../ktm && P=$PWD && "
                     "mkdir -p ./ktm-392-prefix/lib ./ktm-392-work && "
                     "cp -R $D/modules ./ktm-392-prefix/ && "
                     "cp -P $D/lib/libktm.* ./ktm-392-prefix/lib/ && "
                     "printf 'static i64 table[4] = {5, 6, 7, 8};\\n"
                     "i64 LibTriple(i64 x) { return x * 3; }\\n"
                     "pub i64 *third = &table[2];\\n"
                     "pub i64 (*triple)(i64) = &LibTriple;\\n"
                     "pub u8 *none = null;\\n"
                     "pub i64 Ktm392Check() { return *third + triple(2) + (none == null); }\\n'"
                     " > ./ktm-392-work/ktm392.ktm && "
                     "cd ./ktm-392-work && "
                     "$H -lib ktm392 --install-dir=$P/ktm-392-prefix ktm392.ktm > /dev/null 2>&1") == 0,
                "build the -lib library");
    /* 7 + 6 + 1 (triple is checked inside: `extern` of a function pointer
     * doesn't parse) */
    proc.shell("printf '#link <ktm392>\\n"
                 "extern i64 *third;\\ni64 Ktm392Check();\\n"
                 "i32 main() { if (Ktm392Check() == 14 && *third == 7) { return 0; } return 1; }\\n'"
                 " > ./ktm-392-use.ktm");
    check(proc.shell("../../ktm -jit --install-dir=./ktm-392-prefix ./ktm-392-use.ktm") == 0,
                "-lib pointer globals (JIT)");
    check(proc.shell("../../ktm --install-dir=./ktm-392-prefix ./ktm-392-use.ktm -o ./ktm-392-use && "
                             "./ktm-392-use") == 0,
                "-lib pointer globals (AOT)");
    proc.shell("rm -rf ./ktm-392-prefix ./ktm-392-work ./ktm-392-use.ktm ./ktm-392-use");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
