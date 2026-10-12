import libc, math, os, test, vec;

/* Small helpers (v0.5.0): os.eprintf (format-checked, to stderr),
 * os.flush, math.min/max/clamp, removeAt on PtrVec, IntVec and FloatVec. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - small helpers: ");

    check(math.min(3, -4) == -4 && math.max(3, -4) == 3, "min and max");
    check(math.min(I64_MIN, 0) == I64_MIN && math.max(I64_MAX, 0) == I64_MAX, "min/max at the ends");
    check(math.clamp(5, 0, 10) == 5 && math.clamp(-5, 0, 10) == 0 && math.clamp(50, 0, 10) == 10,
          "clamp");

    vec.PtrVec *pv = vec.ptrNew(4);
    u8 *names[5] = { "a", "b", "c", "d", "e" };
    for (i64 i = 0; i < 5; i++) { pv.push(names[i]); }
    u8 *gone = pv.removeAt(1);
    check(gone == names[1] && pv.size == 4 && pv.entries[0] == names[0] && pv.entries[1] == names[2]
          && pv.entries[3] == names[4], "PtrVec.removeAt keeps the order");
    check(pv.removeAt(3) == names[4] && pv.size == 3, "PtrVec.removeAt of the last");
    check(pv.removeAt(0) == names[0] && pv.entries[0] == names[2] && pv.size == 2, "PtrVec.removeAt(0)");
    pv.release();

    vec.IntVec *iv = vec.intNew(2);
    for (i64 i = 0; i < 100; i++) { iv.push(i * 10); }
    bool ok = iv.removeAt(50) == 500 && iv.size == 99;
    for (i64 i = 0; ok && i < 99; i++) { ok = iv.entries[i] == (i < 50 ? i : i + 1) * 10; }
    check(ok, "IntVec.removeAt in the middle of 100");
    iv.release();

    vec.FloatVec *fv = vec.floatNew(2);
    fv.push(1.5);
    fv.push(2.5);
    check(fv.removeAt(0) == 1.5 && fv.size == 1 && fv.entries[0] == 2.5, "FloatVec.removeAt");
    check(fv.removeAt(0) == 2.5 && fv.size == 0, "removeAt down to empty");
    fv.release();

    /* eprintf writes to fd 2: point it at a pipe and read it back */
    os.flush();
    i32 p[2], saved = libc.dup(2);
    libc.pipe(p);
    libc.dup2(p[1], 2);
    os.eprintf("err %d %s|", 42, "x");
    os.eprintf("100%%|");
    libc.dup2(saved, 2);
    libc.close(saved);
    libc.close(p[1]);
    u8 got[64];
    i64 n = libc.read(p[0], got, sizeof(got));
    libc.close(p[0]);
    check(n == 14 && !libc.memcmp(got, "err 42 x|100%|", 14), "eprintf goes to stderr");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
