import coro, cstr, date, str, test;

/* coro.offload (v0.20.0): CPU-heavy work runs on a worker thread while
 * the other coroutines of the loop keep running (a ticker ticks through
 * 300 ms of computing); outside a loop it runs inline; what the work
 * throws comes back to the caller; many offloads at once. */

extern "c" u64 pthread_self();

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

struct Busy {
    i64 ms;
    u64 thread;
    i64 result;
};

void busy(void *arg) {
    Busy *b = arg;
    b.thread = pthread_self();
    i64 end = date.monoNs() + b.ms * 1000000;
    i64 x = 0;
    while (date.monoNs() < end) { x++; }
    b.result = x > 0 ? b.ms : -1;
}

void boom(void *arg) {
    throw(77, "boom %d", 42);
}

i64 gTicks = 0;
bool gDone = false;

void *ticker(void *arg) {
    while (!gDone) {
        coro.sleep(5);
        gTicks++;
    }
    return null;
}

u64 gLoopThread;
Busy gBusy;
i64 gTicksDuring = 0;
bool gThrew = false;

void *worker(void *arg) {
    gLoopThread = pthread_self();
    coro.sleep(10);
    i64 before = gTicks;
    gBusy.ms = 300;
    coro.offload(&busy, &gBusy);
    gTicksDuring = gTicks - before;
    try {
        coro.offload(&boom);
    } catch (Error e) {
        gThrew = e.code == 77 && str.equals(cstr.view(e.message), "boom 42");
    }
    gDone = true;
    return null;
}

i64 gFinished = 0;
void *many(void *arg) {
    Busy b;
    b.ms = 20 + (i64)arg;
    for (i64 i = 0; i < 3; i++) {
        coro.offload(&busy, &b);
        if (b.result == b.ms) { gFinished++; }
    }
    return null;
}

i32 main() {
    printf("Test - coro.offload: ");
    Busy here;
    here.ms = 1;
    coro.offload(&busy, &here);
    check(here.thread == pthread_self() && here.result == 1, "outside a loop: inline");

    coro.go(&ticker);
    coro.go(&worker);
    coro.run();
    check(gBusy.result == 300 && gBusy.thread != gLoopThread, "inside a loop: on a worker thread");
    /* a 5 ms ticker through 300 ms of work: about 60 ticks; a blocked
     * loop gets 0 or 1. A loaded machine's timers run late, so a few are
     * enough to show the loop kept going. */
    check(gTicksDuring >= 5, "the loop keeps running during the work");
    if (gTicksDuring < 5) { printf("  ticks during 300 ms: %d\n", gTicksDuring); }
    check(gThrew, "what the work throws comes back");

    for (i64 i = 0; i < 16; i++) { coro.go(&many, (void *)i); }
    coro.run();
    check(gFinished == 48, "48 offloads from 16 coroutines");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
