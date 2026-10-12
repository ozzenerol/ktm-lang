import coro, cstr, date, libc, test;

/* coro.go/run (v0.5.0): a loop of coroutines over poll. Waits on an fd
 * return its revents, timeouts return 0, sleeps wake in deadline order
 * (also when several end in one turn), new coroutines run in the order
 * they were started, and the loop calls throw ERR_LOOP where they can't
 * work. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 gLog[64];
i64 gLogLen = 0;

void logCh(u8 ch) {
    if (gLogLen < 63) { gLog[gLogLen++] = ch; }
    gLog[gLogLen] = 0;
}

void logReset() {
    gLogLen = 0;
    gLog[0] = 0;
}

/* sleep (i64)arg ms, then log a letter for it */
void *sleeper(void *arg) {
    i64 ms = (i64)arg;
    coro.sleep(ms);
    logCh('a' + ms / 10 - 1);
    return null;
}

/* blocks the thread, so the sleepers' deadlines all pass in one turn */
void *hog(void *arg) {
    i64 until = date.monoMs() + 60;
    while (date.monoMs() < until) {}
    return null;
}

i32 gPipe[2];
i64 gRevents = -2, gTimeout = -2, gWaitedMs = 0;

void *reader(void *arg) {
    i64 t0 = date.monoMs();
    gRevents = coro.wait(gPipe[0], libc.POLLIN);
    gWaitedMs = date.monoMs() - t0;
    u8 b;
    libc.read(gPipe[0], &b, 1);
    logCh(b);
    return null;
}

void *writer(void *arg) {
    coro.sleep(30);
    libc.write(gPipe[1], "w", 1);
    return null;
}

void *timesOut(void *arg) {
    gTimeout = coro.wait(gPipe[0], libc.POLLIN, 20);
    return null;
}

void *letter(void *arg) {
    logCh((u8)(i64)arg);
    return null;
}

/* starts two more from inside the loop */
void *parent(void *arg) {
    logCh('p');
    coro.go(&letter, (void *)'x');
    coro.go(&letter, (void *)'y');
    logCh('q');
    return null;
}

/* plain coro.yield: the others get their turn in between */
void *yielder(void *arg) {
    u8 ch = (u8)(i64)arg;
    for (i64 i = 0; i < 3; i++) {
        logCh(ch);
        coro.yield();
    }
    return null;
}

/* the ERR_LOOP message of fn, "" when it doesn't throw */
u8 gMsg[160];

void *runInside(void *arg) {
    gMsg[0] = 0;
    try { coro.run(); } catch (Error e) {
        if (e.code == coro.ERR_LOOP) { cstr.copy(gMsg, e.message); }
    }
    return null;
}

bool waitThrows(i64 which) {
    try {
        if (which == 0) { coro.wait(0, libc.POLLIN); }
        if (which == 1) { coro.sleep(1); }
        if (which == 2) { coro.accept(0); }
        if (which == 3) { coro.waitIdle(0, libc.POLLIN); }
    } catch (Error e) {
        return e.code == coro.ERR_LOOP && libc.strstr(e.message, "not inside a coroutine that coro.go started") != null;
    }
    return false;
}

/* a coroutine made with coro.spawn is not a loop coroutine */
void *plainSleeper(void *arg) {
    return (void *)waitThrows(1);
}

i32 main() {
    printf("Test - coro loop: ");

    coro.run();
    check(true, "run with no coroutines returns");

    logReset();
    coro.go(&sleeper, (void *)30);
    coro.go(&sleeper, (void *)10);
    coro.go(&sleeper, (void *)20);
    check(gLogLen == 0, "go doesn't run anything before run");
    coro.run();
    check(!cstr.cmp(gLog, "abc"), "sleeps wake in deadline order");

    logReset();
    coro.go(&sleeper, (void *)30);
    coro.go(&sleeper, (void *)10);
    coro.go(&sleeper, (void *)20);
    coro.go(&hog);
    coro.run();
    check(!cstr.cmp(gLog, "abc"), "timeouts of one turn resume earliest deadline first");

    logReset();
    libc.pipe(gPipe);
    coro.go(&reader);
    coro.go(&timesOut);
    coro.go(&writer);
    coro.run();
    check(gRevents > 0 && (gRevents & libc.POLLIN), "wait returns revents when ready");
    check(gWaitedMs >= 25, "wait paused until the fd was ready");
    check(gTimeout == 0, "wait returns 0 when ms passed");
    check(!cstr.cmp(gLog, "w"), "the reader read what the writer wrote");

    logReset();
    coro.go(&parent);
    coro.go(&letter, (void *)'z');
    coro.run();
    check(!cstr.cmp(gLog, "pqzxy"), "go inside the loop: runs after the ones before it");

    logReset();
    coro.go(&yielder, (void *)'1');
    coro.go(&yielder, (void *)'2');
    coro.run();
    check(!cstr.cmp(gLog, "121212"), "a plain yield lets the others run");

    coro.go(&runInside);
    coro.run();
    check(!cstr.cmp(gMsg, "coro.run: called inside a coroutine"), "run inside a coroutine throws");

    check(waitThrows(0), "wait outside a loop coroutine throws");
    check(waitThrows(1), "sleep outside a loop coroutine throws");
    check(waitThrows(2), "accept outside a loop coroutine throws");
    check(waitThrows(3), "waitIdle outside a loop coroutine throws");
    coro.Coroutine *c = coro.spawn(&plainSleeper);
    check((bool)(i64)coro.next(c), "wait in a coroutine coro.go didn't start throws");
    coro.release(c);

    check(coro.go(&letter, (void *)'k') != null, "go returns the coroutine");
    check(!coro.stopping(), "not stopping");
    coro.run();

    libc.close(gPipe[0]);
    libc.close(gPipe[1]);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
