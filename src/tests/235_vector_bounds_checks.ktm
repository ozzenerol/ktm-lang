import fs, libc, mem, proc, str, test, vec;

/* IntVecGet/FloatVecGet/PtrVecGet only checked the index under
 * `#ifdef DEBUG`, which the library is never built with: IntVecGet(v, -1)
 * read the malloc header. Out-of-range indexes now print
 * "<Fn>: index N out of range (size M)" on stderr and exit with 1. */

#define VB_FILE "__235_vecbounds.ktm"
#define VB_OUT  "__235_vecbounds.out"
#define VB_BIN  "__235_vecbounds.bin"

void cleanupFiles() {
    fs.remove(VB_FILE);
    fs.remove(VB_OUT);
    fs.remove(VB_BIN);
}

/* Compile and run `src`: true if it exits with 1, prints `expected` on
 * stderr and not "unreachable" */
bool runExpectingExit(u8 *src, u8 *expected) {
    cleanupFiles();
    if (!fs.writeAll(VB_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                     VB_FILE " -o " VB_BIN " > " VB_OUT " 2>&1 && "
                                     "./" VB_BIN " 2> " VB_OUT " >/dev/null; exit $?");
    u8 *out = fs.readAll(VB_OUT);
    bool ok = out && res == 1 && libc.strstr(out, expected) != null &&
                        libc.strstr(out, "unreachable") == null;
    if (!ok) { printf("status %d, output: %s\n", res, out); }
    mem.free(out);
    cleanupFiles();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - vector bounds checks: ");

    /* In range: unchanged */
    vec.IntVec *iv = vec.intNew();
    vec.FloatVec *fv = vec.floatNew();
    vec.PtrVec *pv = vec.ptrNew();
    for (i64 i = 0; i < 100; ++i) {
        iv.push(i * 2);
        fv.push(i * 0.5);
        pv.push((void *)(i + 1));
    }
    bool ok = true;
    for (i64 i = 0; i < 100; ++i) {
        if (iv.get(i) != i * 2 || fv.get(i) != i * 0.5 ||
                (i64)pv.get(i) != i + 1) { ok = false; }
    }
    tests++; if (ok) { correct++; }
    iv.release();
    fv.release();
    pv.release();

    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.IntVec *v = vec.intNew(); v.push(1);\n"
"  v.get(-1); printf(\"unreachable\\n\"); return 0; }\n",
            "IntVecGet: index -1 out of range (size 1)")) { correct++; }

    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.IntVec *v = vec.intNew(); v.push(1); v.push(2);\n"
"  v.get(2); printf(\"unreachable\\n\"); return 0; }\n",
            "IntVecGet: index 2 out of range (size 2)")) { correct++; }

    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.FloatVec *v = vec.floatNew();\n"
"  v.get(0); printf(\"unreachable\\n\"); return 0; }\n",
            "FloatVecGet: index 0 out of range (size 0)")) { correct++; }

    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.PtrVec *v = vec.ptrNew(); v.push(null);\n"
"  v.get(40); printf(\"unreachable\\n\"); return 0; }\n",
            "PtrVecGet: index 40 out of range (size 1)")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
