import cstr, fs, libc, mem, proc, test;

/* v0.23.2: a crash in C code called from ktm (printf given a bad %s
 * through a computed format) reported a wrong caller on x86_64, where C
 * code keeps no frame pointer: the first word on the stack that pointed
 * into ktm code was taken as the return into it, and deep C code like
 * printf leaves old words there (a function pointer, the return of an
 * earlier call, rt.crash's own from start-up). The report then named
 * rt.crash, or a function that had already returned, and the walk went
 * on from the C code's frame pointer, losing main. The report now names
 * the ktm code that called the C function, and every caller up to main,
 * compiled and in the JIT. On Linux arm64 the report stopped at "in C
 * code": glibc keeps no frame records we can follow and signs the
 * return addresses it saves (pointer authentication), so the stack scan
 * now runs there too and strips the signature. */

#define CC_SRC "__882_cc.ktm"
#define CC_BIN "./__882_cc.out"
#define CC_ERR "__882_cc.stderr"
#define CC_ST  "__882_cc.status"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* The program `src` crashes in C code: its report must say so, then
 * have each line of `want` (in order, "called from __882_cc.ktm:<line>
 * in <function>"), never mention rt.crash, and the program die of a
 * SIGSEGV */
bool reports(u8 *src, u8 **want, i64 n, bool jit) {
    fs.writeAll(CC_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.remove(CC_ERR);
    fs.remove(CC_ST);
    if (jit) {
        proc.shell("(../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " CC_SRC
                   " 2> " CC_ERR "; echo $? > " CC_ST ") > /dev/null 2>&1");
    } else if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          CC_SRC " -o " CC_BIN) == 0) {
        proc.shell("(" CC_BIN " 2> " CC_ERR "; echo $? > " CC_ST ") > /dev/null 2>&1");
    }
    u8 *err = fs.readAll(CC_ERR).take();
    u8 *st = fs.readAll(CC_ST).take();
    bool ok = err && st && !cstr.cmpLen(st, "139\n", 4) && !libc.strstr(err, "rt.crash") &&
              !libc.strstr(err, "rt/crash");
    u8 *at = ok ? libc.strstr(err, "  in C code\n") : null;
    ok = at != null;
    for (i64 i = 0; ok && i < n; i++) {
        at = libc.strstr(at, want[i]);
        ok = at != null;
    }
    if (!ok) { printf("\n%s: status %s stderr [%s]\n", jit ? "jit" : "aot", st, err); }
    mem.free(err);
    mem.free(st);
    fs.remove(CC_SRC);
    fs.remove(CC_BIN);
    fs.remove(CC_ERR);
    fs.remove(CC_ST);
    return ok;
}

/* printf straight from main */
u8 *gDirect =
    "void main() {\n"
    "    u8 *f = \"%s\\n\";\n"
    "    printf(f, (u8 *)42);\n"
    "}\n";

/* from a function main calls */
u8 *gShow =
    "void show(u8 *bad) {\n"
    "    u8 *f = \"%s\\n\";\n"
    "    printf(f, bad);\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    show((u8 *)42);\n"
    "}\n";

/* an earlier printf from another function left its return below */
u8 *gStale =
    "void tell() {\n"
    "    printf(\"before %d\\n\", 1);\n"
    "}\n"
    "\n"
    "void show(u8 *bad) {\n"
    "    u8 *f = \"%s\\n\";\n"
    "    printf(f, bad);\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    tell();\n"
    "    show((u8 *)42);\n"
    "}\n";

/* several ktm frames above the C code */
u8 *gDeep =
    "void leaf(u8 *bad) {\n"
    "    u8 *f = \"%s\\n\";\n"
    "    printf(f, bad);\n"
    "}\n"
    "\n"
    "void down(i64 n, u8 *bad) {\n"
    "    if (n == 0) {\n"
    "        leaf(bad);\n"
    "        return;\n"
    "    }\n"
    "    down(n - 1, bad);\n"
    "    printf(\"\");\n"
    "}\n"
    "\n"
    "void main() {\n"
    "    down(3, (u8 *)42);\n"
    "}\n";

i32 main() {
    printf("Test - a crash in C code names its ktm callers: ");
    u8 *direct[] = {"called from " CC_SRC ":3 in main"};
    u8 *show[] = {"called from " CC_SRC ":3 in show", "called from " CC_SRC ":7 in main"};
    u8 *stale[] = {"called from " CC_SRC ":7 in show", "called from " CC_SRC ":12 in main"};
    u8 *deep[] = {"called from " CC_SRC ":3 in leaf", "called from " CC_SRC ":8 in down",
                  "called from " CC_SRC ":11 in down", "called from " CC_SRC ":16 in main"};
    i64 k;
    for (k = 0; k < 2; k++) {
        bool jit = k == 1;
        check(reports(gDirect, direct, 1, jit), "from main");
        check(reports(gShow, show, 2, jit), "from a function");
        check(reports(gStale, stale, 2, jit), "after an earlier call");
        check(reports(gDeep, deep, 4, jit), "several frames up");
    }
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
