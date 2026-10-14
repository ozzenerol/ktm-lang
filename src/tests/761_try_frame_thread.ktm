import coro, libc, test;

/* try and defer frames keep their thread's state, so leaving them needs
 * no lookup of it (v0.19.0: 12 ns a try before, under 7 after). A
 * coroutine that is resumed on another thread gets its open frames moved
 * to that thread: here one yields inside a try and a defer on the main
 * thread, is resumed on a second thread, throws, catches, leaves its
 * frames there and ends, and both threads' try chains still work after. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

const i64 ERR_T = 0x761;

i64 gCaughtInside = 0, gDeferRan = 0, gCaughtOnThread = 0;

void inner() {
    defer gDeferRan++;
    coro.yield();                   /* resumed on the other thread */
    throw(ERR_T, "thrown after the move");
}

void *body(void *arg) {
    try {
        inner();
    } catch (Error e) {
        if (e.code == ERR_T) { gCaughtInside++; }
    }
    try {
        coro.yield();               /* a frame open across a second resume */
    } catch (Error e) {
    }
    return null;
}

coro.Coroutine *gCo;

void *other(void *unused) {
    coro.next(gCo);                 /* throws, catches, yields in the 2nd try */
    coro.next(gCo);                 /* leaves it and ends */
    /* this thread's own chain */
    try {
        throw(ERR_T, "on the second thread");
    } catch (Error e) {
        if (e.code == ERR_T) { gCaughtOnThread++; }
    }
    return null;
}

i32 main() {
    printf("Test - try frames across threads: ");
    i64 caughtOuter = 0, caughtAfter = 0;
    try {
        gCo = coro.spawn(&body);
        coro.next(gCo);             /* yields inside inner()'s defer and body's try */
        libc.pthread_t *th;
        libc.pthread_create(&th, null, &other, null);
        libc.pthread_join(th);
        check(coro.done(gCo), "the coroutine ended on the second thread");
        coro.release(gCo);
        /* the main thread's chain: this try, then the outer one */
        try {
            throw(ERR_T, "on the main thread");
        } catch (Error e) {
            if (e.code == ERR_T) { caughtAfter++; }
        }
        throw(ERR_T, "to the outer try");
    } catch (Error e) {
        if (e.code == ERR_T) { caughtOuter++; }
    }
    check(gCaughtInside == 1, "the throw after the move was caught in the coroutine");
    check(gDeferRan == 1, "the defer ran once, on the second thread");
    check(gCaughtOnThread == 1, "the second thread's own try still works");
    check(caughtAfter == 1, "the main thread's inner try still works");
    check(caughtOuter == 1, "the main thread's outer try still works");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
