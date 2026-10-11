import test;

import fs, libc, mem, proc, str, vec;

/* New vector functions: IntVecSet/FloatVecSet/PtrVecSet (bounds checked
 * like Get: an index outside [0, size) prints an error and exits 1) and
 * IntVecLast/FloatVecLast/PtrVecLast (peek; sentinel -1 / -1.0 / null and
 * *_ok = false when empty). */

#define VS_FILE "__238_vecset.ktm"
#define VS_OUT  "__238_vecset.out"
#define VS_BIN  "__238_vecset.bin"

void cleanupFiles() {
    fs.remove(VS_FILE);
    fs.remove(VS_OUT);
    fs.remove(VS_BIN);
}

bool runExpectingExit(u8 *src, u8 *expected) {
    cleanupFiles();
    if (!fs.writeAll(VS_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    i64 res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                     "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                     VS_FILE " -o " VS_BIN " > " VS_OUT " 2>&1 && "
                                     "./" VS_BIN " 2> " VS_OUT " >/dev/null; exit $?");
    u8 *out = fs.readAll(VS_OUT);
    bool ok = out && res == 1 && libc.strstr(out, expected) != null &&
                        libc.strstr(out, "unreachable") == null;
    if (!ok) { printf("status %d, output: %s\n", res, out); }
    mem.free(out);
    cleanupFiles();
    return ok;
}

i64 gSeed = 3;
i64 nextRand() {
    gSeed = (gSeed * 6364136223846793005 + 1442695040888963407);
    return (gSeed >> 33) & 0x7FFFFFFF;
}

/* Random push/pop/set/last against an array model, all three types */
bool modelRun(i64 ops) {
    i64 model[2048];
    i64 n = 0;
    vec.IntVec *iv = vec.intNew(1);
    vec.FloatVec *fv = vec.floatNew(1);
    vec.PtrVec *pv = vec.ptrNew(1);
    bool ok = true, has;
    for (i64 op = 0; op < ops && ok; ++op) {
        i64 r = nextRand() % 10, x = nextRand() % 100000;
        if (r < 4 && n < 2048) {
            iv.push(x);
            fv.push((f64)x / 4.0);
            pv.push((void *)x);
            model[n++] = x;
        } else if (r < 6 && n > 0) {
            n--;
            iv.pop(&has);
            fv.pop(&has);
            pv.pop(&has);
        } else if (r < 9 && n > 0) {
            i64 idx = nextRand() % n;
            iv.set(idx, x);
            fv.set(idx, (f64)x / 4.0);
            pv.set(idx, (void *)x);
            model[idx] = x;
        }
        if (n > 0) {
            i64 last = model[n - 1];
            ok = iv.last(&has) == last && has &&
                     fv.last() == (f64)last / 4.0 && (i64)pv.last() == last;
            i64 idx = nextRand() % n;
            ok = ok && iv.get(idx) == model[idx] &&
                     fv.get(idx) == (f64)model[idx] / 4.0 &&
                     (i64)pv.get(idx) == model[idx];
        } else {
            ok = iv.last(&has) == -1 && !has;
            ok = ok && fv.last(&has) == -1.0 && !has;
            ok = ok && pv.last(&has) == null && !has;
        }
        ok = ok && iv.size == n && fv.size == n && pv.size == n;
    }
    iv.release();
    fv.release();
    pv.release();
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - vector set and last: ");

    tests++; if (modelRun(20000)) { correct++; }
    else { printf("model mismatch\n"); }

    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.IntVec *v = vec.intNew(); v.push(1);\n"
"  v.set(1, 5); printf(\"unreachable\\n\"); return 0; }\n",
            "IntVecSet: index 1 out of range (size 1)")) { correct++; }
    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.FloatVec *v = vec.floatNew();\n"
"  v.set(0, 1.0); printf(\"unreachable\\n\"); return 0; }\n",
            "FloatVecSet: index 0 out of range (size 0)")) { correct++; }
    tests++;
    if (runExpectingExit(
"import vec;\n\ni32 main() { vec.PtrVec *v = vec.ptrNew(); v.push(null);\n"
"  v.set(-3, null); printf(\"unreachable\\n\"); return 0; }\n",
            "PtrVecSet: index -3 out of range (size 1)")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
