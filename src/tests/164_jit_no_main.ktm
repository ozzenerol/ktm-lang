import fs, libc, mem, proc, str, test;

/* `ktm -jit` on a file with no Main and no code at file scope looked
 * up a `main` that was never made and exited 255 with nothing printed.
 * It now says there is nothing to run. Files with statements at file
 * scope get a synthetic main and must keep running. */

#define NM_FILE "__164_jit_no_main.ktm"
#define NM_OUT  "__164_jit_no_main.out"

void cleanupNoMainTest(void) {
    fs.remove(NM_FILE);
    fs.remove(NM_OUT);
}

/* Run `ktm -jit` on `source`; true if the exit status is zero exactly
 * when `want_ok` is, and the output contains `expected` (or, with
 * `expected` "", is empty) */
bool jitRuns(u8 *source, bool wantOk, u8 *expected) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupNoMainTest();
    if (!fs.writeAll(NM_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    res = proc.shell("T=; command -v timeout >/dev/null 2>&1 && T='timeout 30'; "
                             "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                             "-jit " NM_FILE " > " NM_OUT " 2>&1");
    out = fs.readAll(NM_OUT);
    if (!out) {
        cleanupNoMainTest();
        return false;
    }
    if ((res == 0) != wantOk) { ok = false; }
    if (*expected && !libc.strstr(out, expected)) { ok = false; }
    if (!*expected && str.len(out) != 0) { ok = false; }
    if (!ok) { printf("\nstatus %d, want ok %d, output:\n%s\n", res, wantOk, out); }
    mem.free(out);
    cleanupNoMainTest();
    return ok;
}

#define NO_MAIN_MSG "ktm: __164_jit_no_main.ktm: nothing to run: no main function"

i32 main() {
    i64 correct = 0, tests = 0;

    printf("Test - ktm -jit with no main: ");

    /* nothing to run: reported */
    tests++; if (jitRuns("i64 foo() { return 1; }\n", false, NO_MAIN_MSG)) { correct++; }
    tests++; if (jitRuns("", false, NO_MAIN_MSG)) { correct++; }
    tests++; if (jitRuns("// only a comment\n", false, NO_MAIN_MSG)) { correct++; }
    tests++; if (jitRuns("struct C { i64 a; };\ni64 g;\n", false, NO_MAIN_MSG)) { correct++; }
    /* code at file scope: an error (ktm syntax v0.2 has none) */
    tests++; if (jitRuns("printf(\"top\\n\");\n", false, "code at file scope: put it in `main`")) { correct++; }
    tests++; if (jitRuns("i64 two() { return 2; }\n\nvoid main()\n{\n  printf(\"%d\\n\", two());\n}\n", true, "2\n")) { correct++; }
    /* a constant initialiser is static data, like `i64 g;` (no code); one
     * that calls a function is code at file scope */
    tests++; if (jitRuns("i64 x = 3;\ni64 foo() { return x; }\n", false, NO_MAIN_MSG)) { correct++; }
    tests++; if (jitRuns("import str;\n\ni64 x;\ni64 foo() { return x; }\n\nvoid main()\n{\n  x = str.len(\"ab\");\n}\n", true, "")) { correct++; }
    /* a main: runs */
    tests++; if (jitRuns("void main() { printf(\"main\\n\"); }\n", true, "main\n")) { correct++; }
    tests++; if (jitRuns("i32 main() { return 3; }\n", false, "")) { correct++; }

    cleanupNoMainTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
