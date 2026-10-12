import coro, date, libc, net, test;

/* coro.accept out of fds (v0.5.0): with libc.RLIMIT_NOFILE lowered and every
 * fd taken, accept rests instead of spinning (little CPU time while it
 * waits), coro.close() lets it try again at once, its own timeout still
 * holds, and without a close it tries again after about 2 s. On macOS
 * a failed accept drops that client, so four clients are queued. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

struct RUsage {
    libc.timeval utime, stime;
    i64 rest[16];             /* the other fields: never read */
};

extern "c" i32 getrusage(i32 who, RUsage *ru);

i64 cpuMs() {
    RUsage ru;
    getrusage(0, &ru);
    return (ru.utime.tv_sec + ru.stime.tv_sec) * 1000 + (ru.utime.tv_usec + ru.stime.tv_usec) / 1000;
}

i32 gListen;
i32 gSpare[64];
i64 gNspare = 0;

/* take every fd that is left */
void fill() {
    i32 fd;
    while (gNspare < 64 && (fd = libc.dup(gListen)) >= 0) { gSpare[gNspare++] = fd; }
}

i32 gGot = -2;
i64 gElapsed = 0, gCpu = 0;

void *acceptor(void *arg) {
    i64 t0 = date.monoMs(), c0 = cpuMs();
    gGot = coro.accept(gListen, (i64)arg);
    gElapsed = date.monoMs() - t0;
    gCpu = cpuMs() - c0;
    return null;
}

i32 gGot1 = -2;
i64 gElapsed1 = 0, gCpu1 = 0;

void *timeoutThenTimer(void *arg) {
    acceptor((void *)300);
    gGot1 = gGot;
    gElapsed1 = gElapsed;
    gCpu1 = gCpu;
    libc.close(gSpare[--gNspare]);           /* two: one for the client, one spare */
    libc.close(gSpare[--gNspare]);
    acceptor((void *)5000);
    return null;
}

void *closer(void *arg) {
    coro.sleep(150);
    coro.close(gSpare[--gNspare]);
    coro.close(gSpare[--gNspare]);
    return null;
}

i32 main() {
    printf("Test - coro loop EMFILE: ");
    gListen = net.listen(0, "127.0.0.1", 16, libc.AF_INET, true);
    i32 port = net.localPort(gListen);
    i32 clients[4];
    for (i64 i = 0; i < 4; i++) { clients[i] = net.dial("127.0.0.1", port); }
    check(gListen >= 0 && clients[3] >= 0, "a listener with four clients queued");

    libc.rlimit old, low;
    libc.getrlimit(libc.RLIMIT_NOFILE, &old);
    i32 probe = libc.dup(gListen);
    libc.close(probe);
    low.rlim_cur = probe + 8;
    low.rlim_max = old.rlim_max;
    check(libc.setrlimit(libc.RLIMIT_NOFILE, &low) == 0, "RLIMIT_NOFILE lowered");
    fill();
    check(gNspare > 0 && libc.dup(gListen) == -1, "every fd taken");

    /* a close wakes it */
    coro.go(&acceptor, (void *)5000);
    coro.go(&closer);
    coro.run();
    check(gGot >= 0, "accept succeeds after coro.close freed an fd");
    check(gElapsed >= 120 && gElapsed < 1500, "it waited for the close, not the 2 s timer");
    check(gCpu < 75, "it rested instead of spinning");
    if (gGot >= 0) { libc.close(gGot); }
    fill();

    /* its own timeout, still out of fds; then a plain close(2), which
     * doesn't wake it: the timer retries */
    coro.go(&timeoutThenTimer);
    coro.run();
    check(gGot1 == -1 && gElapsed1 >= 250 && gElapsed1 < 1500, "accept's timeout holds while out of fds");
    check(gCpu1 < 75, "and it rested meanwhile");
    check(gGot >= 0, "accept succeeds again after the pause");
    check(gElapsed >= 1000 && gElapsed < 4000, "it tried again after about 2 s");
    check(gCpu < 75, "resting again");
    if (gGot >= 0) { libc.close(gGot); }

    while (gNspare > 0) { libc.close(gSpare[--gNspare]); }
    libc.setrlimit(libc.RLIMIT_NOFILE, &old);
    for (i64 i = 0; i < 4; i++) { libc.close(clients[i]); }
    libc.close(gListen);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
