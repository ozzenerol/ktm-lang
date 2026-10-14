import coro, date, test;

/* coro.park / coro.unpark (v0.20.0): hand-offs between coroutines of one
 * loop without a syscall. A ping-pong, the time limit (0), an unpark
 * before the park (returns at once), coro.stop (-1), and the errors. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

coro.Coroutine *gPing, *gPong;
i64 gRounds = 0;
bool gPongBad = false;

void *ping(void *arg) {
    for (i64 i = 0; i < 1000; i++) {
        coro.unpark(gPong);
        if (coro.park(1000) != 1) { return null; }
        gRounds++;
    }
    return null;
}

void *pong(void *arg) {
    for (i64 i = 0; i < 1000; i++) {
        if (coro.park(1000) != 1) { gPongBad = true; return null; }
        coro.unpark(gPing);
    }
    return null;
}

i64 gTimeout = -9, gEarly = -9, gElapsed = 0, gStopped = -9, gLate = -9;
coro.Coroutine *gLateC;

/* parks 1 ms; lateWaker unparks it after the time limit made it ready,
 * before it ran: the unpark still counts */
void *latePark(void *arg) {
    gLate = coro.park(1);
    return null;
}

void *lateWaker(void *arg) {
    i64 t0 = date.monoMs();
    while (date.monoMs() - t0 < 10) { }      /* past latePark's limit, no yield */
    coro.yield();                            /* the turn readies latePark (0) */
    coro.unpark(gLateC);                     /* runs before it resumes */
    return null;
}

void *timesOut(void *arg) {
    i64 t0 = date.monoMs();
    gTimeout = coro.park(30);
    gElapsed = date.monoMs() - t0;
    return null;
}

void *early(void *arg) {
    coro.unpark(coro.current());       /* before the park */
    gEarly = coro.park(5000);
    return null;
}

void *parksForever(void *arg) {
    gStopped = coro.park();
    return null;
}

void *stopper(void *arg) {
    coro.wait(-1, 0, 20);
    coro.stop();
    return null;
}

i32 main() {
    printf("Test - coro.park: ");
    gPing = coro.go(&ping);
    gPong = coro.go(&pong);
    coro.run();
    check(gRounds == 1000 && !gPongBad, "1000 hand-offs back and forth");

    coro.go(&timesOut);
    coro.go(&early);
    coro.run();
    check(gTimeout == 0 && gElapsed >= 25, "the time limit returns 0");
    check(gEarly == 1, "an unpark before the park returns at once");

    gLateC = coro.go(&latePark);
    coro.go(&lateWaker);
    coro.run();
    check(gLate == 1, "an unpark after the time limit, before the resume, still counts");

    coro.go(&parksForever);
    coro.go(&stopper);
    coro.run();
    check(gStopped == -1, "coro.stop ends a park with -1");

    bool threw = false;
    try { coro.park(); } catch (Error e) { threw = e.code == coro.ERR_LOOP; }
    check(threw, "park outside a loop coroutine throws");
    threw = false;
    try { coro.unpark(null); } catch (Error e) { threw = e.code == coro.ERR_LOOP; }
    check(threw, "unpark of no coroutine throws");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
