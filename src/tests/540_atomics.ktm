import atomic, cstr, fs, libc, proc, test;

/* module atomic (v0.7.0): every operation and overload on one thread
 * (old values returned, cas true and false), then threads: exact counts
 * from atomic.add, a cas spin lock around a plain counter, a flag that
 * publishes data. And `atomic` as a declaration keyword is an error. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

const i64 THREADS = 8;
const i64 ROUNDS = 100000;

i64 gCount64 = 0;
i32 gCount32 = 0;
u64 gCountU = 0;
i64 gDown = THREADS * ROUNDS;

void *adder(void *arg) {
    for (i64 i = 0; i < ROUNDS; i++) {
        atomic.add(&gCount64, 1);
        atomic.add(&gCount32, 1);
        atomic.add(&gCountU, 2);
        atomic.sub(&gDown, 1);
    }
    return null;
}

i64 gLock = 0;
i64 gPlain = 0;      /* only touched under gLock */

void *locker(void *arg) {
    for (i64 i = 0; i < ROUNDS / 4; i++) {
        while (!atomic.cas(&gLock, 0, 1)) {}
        gPlain = gPlain + 1;
        atomic.store(&gLock, 0);
    }
    return null;
}

i64 gData[64];
i32 gReady = 0;
bool gSeen = false;

void *producer(void *arg) {
    for (i64 i = 0; i < 64; i++) { gData[i] = i * 3; }
    atomic.store(&gReady, 1);
    return null;
}

void *consumer(void *arg) {
    while (!atomic.load(&gReady)) {}
    bool ok = true;
    for (i64 i = 0; i < 64; i++) { if (gData[i] != i * 3) { ok = false; } }
    gSeen = ok;
    return null;
}

void runThreads(fn(void *) void * body, i64 n) {
    libc.pthread_t *th[THREADS];
    for (i64 i = 0; i < n; i++) { libc.pthread_create(&th[i], null, body, null); }
    for (i64 i = 0; i < n; i++) { libc.pthread_join(th[i]); }
}

/* `atomic i64 n;` doesn't compile any more, with this message */
bool keywordRejected(u8 *source) {
    u8 *src = "__540_atomic_kw.ktm", *out = "__540_atomic_kw.out";
    fs.writeAll(cstr.view(src), cstr.view(source));
    i64 res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "__540_atomic_kw.ktm -o __540_atomic_kw.bin > __540_atomic_kw.out 2>&1");
    String text = fs.readAll(cstr.view(out));
    bool ok = res != 0 && text.ptr &&
              libc.strstr(text.ptr, "`atomic` is not a keyword") != null;
    if (!ok && text.ptr) { printf("\n%s", text); }
    text.free();
    fs.remove(cstr.view(src));
    fs.remove(cstr.view(out));
    fs.remove("__540_atomic_kw.bin");
    return ok;
}

i32 main() {
    printf("Test - atomics: ");

    /* i64 */
    i64 a = 10;
    check(atomic.load(&a) == 10, "load i64");
    check(atomic.add(&a, 5) == 10 && a == 15, "add i64 returns the old value");
    check(atomic.sub(&a, 20) == 15 && a == -5, "sub i64");
    check(atomic.swap(&a, 0x7FFFFFFFFFFFFFFF) == -5 && a == 0x7FFFFFFFFFFFFFFF, "swap i64");
    check(atomic.add(&a, 1) == 0x7FFFFFFFFFFFFFFF && a == -0x7FFFFFFFFFFFFFFF - 1, "add i64 wraps");
    atomic.store(&a, 99);
    check(a == 99, "store i64");
    check(atomic.cas(&a, 99, 7) && a == 7, "cas i64 swaps");
    check(!atomic.cas(&a, 99, 8) && a == 7, "cas i64 fails, leaves it");
    a = 0x0F0;
    check(atomic.and(&a, 0x3C) == 0x0F0 && a == 0x030, "and i64");
    check(atomic.or(&a, 0x101) == 0x030 && a == 0x131, "or i64");
    check(atomic.xor(&a, 0x1FF) == 0x131 && a == 0x0CE, "xor i64");

    /* i32: only its 4 bytes change, results sign-extended */
    i32 pair[2];
    pair[0] = -3;
    pair[1] = 0x5A5A5A5A;
    check(atomic.load(&pair[0]) == -3, "load i32");
    check(atomic.add(&pair[0], 1) == -3 && pair[0] == -2, "add i32");
    check(atomic.swap(&pair[0], 0x7FFFFFFF) == -2, "swap i32");
    check(atomic.add(&pair[0], 1) == 0x7FFFFFFF && pair[0] == -0x7FFFFFFF - 1, "add i32 wraps at 32 bits");
    check(atomic.cas(&pair[0], -0x7FFFFFFF - 1, 5) && pair[0] == 5, "cas i32 with a negative expected");
    check(!atomic.cas(&pair[0], 6, 7) && pair[0] == 5, "cas i32 fails");
    atomic.store(&pair[0], -1);
    check(pair[0] == -1 && atomic.and(&pair[0], 0xFF) == -1 && pair[0] == 0xFF, "store, and i32");
    check(atomic.or(&pair[0], 0x100) == 0xFF && atomic.xor(&pair[0], 0x1) == 0x1FF && pair[0] == 0x1FE,
          "or, xor i32");
    check(pair[1] == 0x5A5A5A5A, "the i32 next to it is untouched");

    /* u64 and u32: unsigned results */
    u64 b = 0xFFFFFFFFFFFFFFFF;
    check(atomic.add(&b, 1) == 0xFFFFFFFFFFFFFFFF && b == 0, "add u64 wraps to 0");
    check(atomic.sub(&b, 1) == 0 && atomic.load(&b) == 0xFFFFFFFFFFFFFFFF, "sub u64");
    check(atomic.cas(&b, 0xFFFFFFFFFFFFFFFF, 3) && b == 3, "cas u64");
    u32 c = 0xFFFFFFF0;
    check(atomic.load(&c) == 0xFFFFFFF0, "load u32 is not sign-extended");
    check(atomic.add(&c, 0x20) == 0xFFFFFFF0 && c == 0x10, "add u32 wraps");
    check(atomic.cas(&c, 0x10, 0x80000000) && atomic.swap(&c, 1) == 0x80000000, "cas, swap u32");
    atomic.store(&c, 0xFFFFFFFF);
    check(c == 0xFFFFFFFF && atomic.xor(&c, 0xF) == 0xFFFFFFFF && c == 0xFFFFFFF0, "store, xor u32");

    /* pointers */
    i64 x, y;
    void *p = null;
    check(atomic.casPtr(&p, null, &x) && atomic.loadPtr(&p) == &x, "casPtr, loadPtr");
    check(!atomic.casPtr(&p, null, &y) && p == &x, "casPtr fails");
    check(atomic.swapPtr(&p, &y) == &x && p == &y, "swapPtr");
    atomic.storePtr(&p, null);
    check(p == null, "storePtr");
    atomic.fence();

    /* threads */
    runThreads(&adder, THREADS);
    check(gCount64 == THREADS * ROUNDS, "8 threads x 100000 add on an i64: exact");
    check(gCount32 == THREADS * ROUNDS, "the same on an i32");
    check(gCountU == 2 * THREADS * ROUNDS, "the same on a u64");
    check(gDown == 0, "sub down to 0");
    runThreads(&locker, 4);
    check(gPlain == ROUNDS, "a cas spin lock guards a plain counter");
    libc.pthread_t *pt, *ct;
    libc.pthread_create(&ct, null, &consumer, null);
    libc.pthread_create(&pt, null, &producer, null);
    libc.pthread_join(pt);
    libc.pthread_join(ct);
    check(gSeen, "data written before store(flag) is seen after load(flag)");

    check(keywordRejected("i32 main() {\n    atomic i64 n = 0;\n    return 0;\n}\n"),
          "atomic i64 n; in a function is an error");
    check(keywordRejected("atomic i64 gN;\ni32 main() { return 0; }\n"),
          "atomic at file scope is an error");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
