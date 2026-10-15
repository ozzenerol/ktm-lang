import coro, libc, test;

/* `threadlocal T x;`: a global with one copy per thread, each starting
 * from the declared value. AOT reaches it through the thread pointer
 * (fs: on x86_64, tpidr_el0 on arm64 Linux, the TLV thunk on macOS), the
 * JIT through a copy per thread. Here: scalars, narrow ints, floats, a
 * struct, an array, a pointer to a global, `static` and `a, b` lists, &x
 * per thread, threads running at once, a first use in a new thread with
 * arguments live around it, and a coroutine that yields on one thread and
 * is resumed on another, where it must see the second thread's copies and
 * its try/catch must still work (the address is never kept across a call). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct Pair { i64 a; i64 b; };

threadlocal i64 tCount = 5;
threadlocal i32 tSmall = -7;
threadlocal u8 tByte = 200;
threadlocal f64 tReal = 1.5;
threadlocal f32 tFloat = 2.5;
threadlocal Pair tPair = {10, 20};
threadlocal i64 tArr[4] = {1, 2, 3, 4};
threadlocal u8 *tName = "start";
i64 gTarget = 99;
threadlocal i64 *tPtr = &gTarget;
static threadlocal i64 tHidden = 3;
threadlocal i64 tFirst, tSecond = 4;
threadlocal i64 tZero;

i64 *gAddr[2];

/* what a second thread saw */
i64 gT[16];
f64 gTReal;
f32 gTFloat;
u8 *gTName;

void *seeFresh(void *arg) {
    gAddr[1] = &tCount;
    gT[0] = tCount;
    gT[1] = tSmall;
    gT[2] = tByte;
    gTReal = tReal;
    gTFloat = tFloat;
    gT[3] = tPair.a + tPair.b;
    gT[4] = tArr[0] + tArr[3];
    gTName = tName;
    gT[5] = *tPtr;
    gT[6] = tHidden;
    gT[7] = tFirst;
    gT[8] = tSecond;
    gT[9] = tZero;
    /* this thread's writes stay in this thread */
    tCount = 1000;
    tSmall -= 1;
    tPair.b += 1;
    tArr[2] = 33;
    tName = "worker";
    tHidden++;
    return null;
}

/* many threads at once: each counts in its own copy */
void *countAlone(void *arg) {
    for (i64 i = 0; i < 100000; i++) { tCount++; }
    gT[(i64)arg] = tCount;
    return null;
}

/* a first use in a new thread with integer and float arguments live
 * around it (the macOS TLV thunk allocates on a thread's first use) */
f64 mixed(i64 a, f64 b, i64 c, f64 d) {
    i64 v = tSecond;
    return (f64)(a + c + v) + b * d;
}

void *firstUse(void *arg) {
    gTReal = mixed(1, 2.0, 3, 4.0);
    return null;
}

/* ---- a coroutine moved between threads ---- */

const i64 ERR_T = 0x850;

i64 gBefore = 0, gAfter = 0, gCaught = 0, gAfterAddr = 0;

void *body(void *arg) {
    gBefore = tCount;
    try {
        coro.yield();           /* resumed on the second thread */
        gAfter = tCount;
        gAfterAddr = (i64)&tCount;
        throw(ERR_T, "after the move");
    } catch (Error e) {
        if (e.code == ERR_T) { gCaught++; }
    }
    return null;
}

coro.Coroutine *gCo;
i64 gOtherAddr = 0;

void *resumeHere(void *unused) {
    tCount = 777;
    gOtherAddr = (i64)&tCount;
    coro.next(gCo);
    return null;
}

void run(fn(void *arg) void *f, void *arg) {
    libc.pthread_t *th;
    libc.pthread_create(&th, null, f, arg);
    libc.pthread_join(th);
}

i32 main() {
    printf("Test - threadlocal globals: ");

    /* the main thread starts from the declared values too */
    check(tCount == 5 && tSmall == -7 && tByte == 200, "initial ints");
    check(tReal == 1.5 && tFloat == 2.5, "initial floats");
    check(tPair.b == 20 && tArr[3] == 4 && *tPtr == 99, "initial struct, array, pointer");
    tCount = 100;
    tSmall = 1;
    tPair.b = 0;
    tArr[2] = 0;
    tName = "main";
    gAddr[0] = &tCount;

    run(&seeFresh, null);
    check(gT[0] == 5 && gT[1] == -7 && gT[2] == 200, "a new thread starts from the declared ints");
    check(gTReal == 1.5 && gTFloat == 2.5, "and floats");
    check(gT[3] == 30 && gT[4] == 5, "and struct and array");
    check(!libc.strcmp(gTName, "start") && gT[5] == 99, "and pointers");
    check(gT[6] == 3 && gT[7] == 0 && gT[8] == 4 && gT[9] == 0, "static, list and zeroed ones");
    check(gAddr[0] != gAddr[1] && gAddr[0] == &tCount, "&x is the calling thread's copy");
    check(tCount == 100 && tSmall == 1 && tPair.b == 0 && tArr[2] == 0, "its writes didn't reach main");
    check(!libc.strcmp(tName, "main") && tHidden == 3, "nor the pointer or the static one");

    libc.pthread_t *ths[4];
    for (i64 i = 0; i < 4; i++) {
        libc.pthread_create(&ths[i], null, &countAlone, (void *)(10 + i));
    }
    for (i64 i = 0; i < 4; i++) { libc.pthread_join(ths[i]); }
    check(gT[10] == 100005 && gT[11] == 100005 && gT[12] == 100005 && gT[13] == 100005,
          "threads running at once each count in their own copy");
    check(tCount == 100, "main's copy untouched");

    gTReal = 0.0;
    run(&firstUse, null);
    check(gTReal == 16.0, "arguments live around a thread's first use");

    gCo = coro.spawn(&body);
    coro.next(gCo);
    run(&resumeHere, null);
    check(gBefore == 100, "the coroutine read main's copy before the move");
    check(gAfter == 777 && gAfterAddr == gOtherAddr, "and the second thread's after it");
    check(gCaught == 1, "its try/catch works after the move");
    check(coro.done(gCo), "it ended on the second thread");
    coro.release(gCo);

    try {
        throw(ERR_T, "main again");
    } catch (Error e) {
        if (e.code == ERR_T) { gCaught++; }
    }
    check(gCaught == 2, "main's own try/catch still works");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
