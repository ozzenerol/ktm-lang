import fs, mem, proc, str, test;

/* ktm wrote its intermediate assembly to the fixed path /tmp/ktm-asm.s,
 * so compiles running at the same time assembled each other's code (or a
 * half-written file). Each compile now gets its own temporary file in
 * $TMPDIR, removed afterwards. */

#define CC_DIR "__360_concurrent"
const i64 CC_JOBS = 6;
const i64 CC_FUNCS = 150;
#define CC_KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

/* Program `id`: CC_FUNCS functions, Main prints "prog <id> <sum>". */
u8 *programSource(i64 id) {
    i64 len = 0, i;
    u8 *src = null;
    for (i = 0; i < CC_FUNCS; i++) {
        src = str.appendFormatLen(src, &len, "i64 Fn%d(i64 x) { return x * 3 + %d; }\n",
                                            i, i + id * 1000);
    }
    src = str.appendFormatLen(src, &len, "void main()\n{\n  i64 s = 0;\n");
    for (i = 0; i < CC_FUNCS; i++) {
        src = str.appendFormatLen(src, &len, "  s += Fn%d(1);\n", i);
    }
    src = str.appendFormatLen(src, &len, "  printf(\"prog %d %%d\\n\", s);\n}\n", id);
    return src;
}

i64 expectedSum(i64 id) {
    return CC_FUNCS * (3 + id * 1000) + CC_FUNCS * (CC_FUNCS - 1) / 2;
}

bool writeSources() {
    for (i64 id = 0; id < CC_JOBS; id++) {
        u8 *path = str.format(CC_DIR "/p%d.ktm", id);
        u8 *src = programSource(id);
        bool ok = fs.writeAll(path, src, str.len(src));
        mem.free(src);
        mem.free(path);
        if (!ok) { return false; }
    }
    return true;
}

/* Number of ktm temporary files left in dir */
i64 leftoverTmpFiles(u8 *dir) {
    u8 *out = proc.shellOutput("ls -A '%s' | grep -c '^ktm-'", dir);
    i64 n = -1;
    if (out) {
        n = str.toInt(out);
        mem.free(out);
    }
    return n;
}

/* Compile every program at once (`flags` before the source file), then
 * run each binary and check what it printed. */
bool compileAllAtOnce(u8 *flags) {
    u8 *cmd = null;
    i64 len = 0, id;
    bool ok = true;

    for (id = 0; id < CC_JOBS; id++) {
        cmd = str.appendFormatLen(cmd, &len,
                "TMPDIR=\"$PWD/" CC_DIR "/tmp\" " CC_KTM "%s " CC_DIR "/p%d.ktm -o "
                CC_DIR "/p%d > " CC_DIR "/p%d.log 2>&1 &\n", flags, id, id, id);
    }
    cmd = str.appendFormatLen(cmd, &len, "wait\n");
    proc.shell("rm -f " CC_DIR "/p? " CC_DIR "/p?.log " CC_DIR "/p?.out");
    proc.shell(cmd);
    mem.free(cmd);

    /* Run them at once too: macOS checks every new binary on its first
     * run, slowly, so one at a time would make this the slowest test. */
    cmd = null;
    len = 0;
    for (id = 0; id < CC_JOBS; id++) {
        cmd = str.appendFormatLen(cmd, &len, "./" CC_DIR "/p%d > " CC_DIR "/p%d.out 2>&1 &\n",
                                            id, id);
    }
    cmd = str.appendFormatLen(cmd, &len, "wait\n");
    proc.shell(cmd);
    mem.free(cmd);

    for (id = 0; id < CC_JOBS; id++) {
        u8 *path = str.format(CC_DIR "/p%d.out", id);
        u8 *out = fs.readAll(path);
        u8 *want = str.format("prog %d %d\n", id, expectedSum(id));
        if (!out || str.cmp(out, want)) {
            u8 *log = fs.readAll(str.format(CC_DIR "/p%d.log", id));
            printf("program %d: got '%s', want '%s'\n%s\n", id, out, want, log);
            ok = false;
        }
        mem.free(out);
        mem.free(want);
        mem.free(path);
    }
    return ok;
}

i32 main() {
    printf("Test - concurrent compiles use their own temporary files: ");
    i64 tests = 7, correct = 0;

    fs.removeDir(CC_DIR, fs.REMOVE_RECURSE);
    fs.makeDir(CC_DIR);
    fs.makeDir(CC_DIR "/tmp");

    if (writeSources()) { correct++; }
    else { printf("Failed - writing the test programs\n"); }

    /* Two rounds: the old shared file failed most of the time, not always */
    if (compileAllAtOnce("")) { correct++; }
    if (compileAllAtOnce("-DKTM_360")) { correct++; }

    /* the temporary files are in $TMPDIR and removed on success */
    if (leftoverTmpFiles(CC_DIR "/tmp") == 0) { correct++; }
    else { printf("Failed - temporary files left after successful compiles\n"); }

    /* ... and when linking fails (ktm exits through its panic path) */
    u8 *bad = "extern \"c\" void Ktm360NoSuchFn();\nvoid main() { Ktm360NoSuchFn(); }\n";
    fs.writeAll(CC_DIR "/bad.ktm", bad, str.len(bad));
    if (proc.shell("TMPDIR=\"$PWD/" CC_DIR "/tmp\" " CC_KTM CC_DIR "/bad.ktm -o " CC_DIR "/bad > /dev/null 2>&1") != 0) { correct++; }
    else { printf("Failed - linking an undefined function succeeded\n"); }
    if (leftoverTmpFiles(CC_DIR "/tmp") == 0) { correct++; }
    else { printf("Failed - temporary files left after a failed link\n"); }

    /* TMPDIR is honoured: an unusable one is an error, not /tmp */
    if (proc.shell("TMPDIR=\"$PWD/" CC_DIR "/missing\" " CC_KTM CC_DIR "/p0.ktm -o " CC_DIR "/p0 > /dev/null 2>&1") != 0) { correct++; }
    else { printf("Failed - compile with a missing TMPDIR succeeded\n"); }

    fs.removeDir(CC_DIR, fs.REMOVE_RECURSE);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
