import coro, cstr, test;

/* A loop coroutine that throws (v0.5.0): run() throws the error on after
 * releasing that coroutine, the others stay and a second run() finishes
 * them; a try in a loop coroutine spans its waits while others run; a
 * loop where every coroutine waits forever throws ERR_LOOP. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gDone = 0;

void *thrower(void *arg) {
    coro.sleep((i64)arg);
    throw(42, "boom after %d ms", (i64)arg);
    return null;
}

void *worker(void *arg) {
    coro.sleep((i64)arg);
    gDone++;
    return null;
}

void *throwsAtOnce(void *arg) {
    throw(43, "at once");
    return null;
}

/* each catches its own throw, with the other's wait in between */
i64 gCaught[2];

void *catcher(void *arg) {
    i64 me = (i64)arg;
    try {
        coro.sleep(10 + me * 10);
        throw(100 + me);
    } catch (Error e) {
        gCaught[me] = e.code;
    }
    return null;
}

void *stuck(void *arg) {
    coro.waitIdle(-1, 0);
    gDone++;
    return null;
}

i32 main() {
    printf("Test - coro loop throw: ");
    i64 code = 0;
    u8 msg[64];
    msg[0] = 0;

    coro.go(&worker, (void *)40);
    coro.go(&thrower, (void *)10);
    coro.go(&worker, (void *)0);
    try { coro.run(); } catch (Error e) {
        code = e.code;
        cstr.copy(msg, e.message);
    }
    check(code == 42 && !cstr.cmp(msg, "boom after 10 ms"), "run() throws the coroutine's error on");
    check(gDone == 1, "the ones that ended before it did end");
    code = 0;
    try { coro.run(); } catch (Error e) { code = e.code; }
    check(code == 0 && gDone == 2, "a second run() finishes the others");

    gDone = 0;
    coro.go(&throwsAtOnce);
    coro.go(&worker, (void *)0);
    code = 0;
    try { coro.run(); } catch (Error e) { code = e.code; }
    check(code == 43, "a throw before any wait");
    coro.run();
    check(gDone == 1, "the one queued after it still runs");

    coro.go(&catcher, (void *)0);
    coro.go(&catcher, (void *)1);
    coro.run();
    check(gCaught[0] == 100 && gCaught[1] == 101, "a try in a loop coroutine spans its waits");

    gDone = 0;
    coro.go(&stuck);
    msg[0] = 0;
    try { coro.run(); } catch (Error e) {
        if (e.code == coro.ERR_LOOP) { cstr.copy(msg, e.message); }
    }
    check(!cstr.cmp(msg, "coro.run: every coroutine waits with no fd and no time limit"),
          "every coroutine waiting forever throws ERR_LOOP");
    coro.stop();
    coro.run();
    check(gDone == 1, "stop() frees it and run() ends");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
