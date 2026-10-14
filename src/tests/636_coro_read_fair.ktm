import coro, date, libc, net, test;

/* A busy stream doesn't starve its loop (v0.13.0): coro.read goes on
 * reading without a turn of the loop while its reads fill their buffer,
 * but for 4 ms at most, then waits once. One loop here has a stream (a
 * thread writes a counting byte pattern into it as fast as it can, and a
 * coroutine reads it with a small buffer, so every read is full) and a
 * request/response client (round trips to an echo thread). The client's
 * worst round trip stays near the budget (a generous margin for slow
 * machines; a stream that never gave way would starve it outright), and
 * every byte of the stream arrives in order. On every backend. */

extern "c" i32 socketpair(i32 domain, i32 type, i32 protocol, i32 *sv);

i64 gCorrect = 0, gTests = 0;
u8[] gBackend;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed on %s: %s\n", gBackend.ptr, what); }
}

const i64 RUN_MS = 1500;
/* The budget is 4 ms: a wait of about 8 ms. A machine running other test
 * suites at once preempted the loop for 67 ms once, so the margin is wide;
 * a stream that never gave way would hold the client for the whole run
 * (1.5 s), far past it. */
const i64 MARGIN_MS = 250;

i32 gStream[2];                  /* [0] the loop reads, [1] the writer thread writes */
i32 gPing[2];                    /* [0] the loop's client, [1] the echo thread */
bool gStop;
i64 gStreamBytes, gStreamBad, gRounds, gWorstUs, gPingBad;

/* writes 0, 1, 2, ... 255, 0, ... until the reader is gone, faster than
 * the reader can take it (one block, made once: 65536 is a multiple of
 * 256), so the reader's every read is full */
void *writer(void *unused) {
    u8 block[65536];
    for (i64 i = 0; i < 65536; i++) { block[i] = (u8)i; }
    while (true) {
        i64 off = 0;
        while (off < 65536) {
            i64 w = libc.send(gStream[1], block + off, 65536 - off, libc.MSG_NOSIGNAL);
            if (w <= 0) { return null; }
            off += w;
        }
    }
    return null;
}

/* the echo end of the request/response pair, blocking */
void *echoer(void *unused) {
    u8 buf[64];
    i64 n;
    while ((n = libc.recv(gPing[1], buf, 64, 0)) > 0) {
        libc.send(gPing[1], buf, n, libc.MSG_NOSIGNAL);
    }
    return null;
}

void *streamReader(void *unused) {
    u8 buf[4096];
    i64 expect = 0, n;
    while (!gStop && (n = coro.read(gStream[0], buf[0:4096], 5000)) > 0) {
        for (i64 i = 0; i < n; i++) {
            if (buf[i] != (u8)(expect + i)) { gStreamBad++; }
        }
        expect = (expect + n) & 0xFF;
        gStreamBytes += n;
    }
    return null;
}

void *client(void *unused) {
    u8 got[8];
    i64 end = date.monoMs() + RUN_MS;
    while (date.monoMs() < end) {
        i64 t0 = date.monoNs();
        if (coro.write(gPing[0], "ping", 2000) != 4) { gPingBad++; break; }
        i64 have = 0;
        while (have < 4) {
            i64 n = coro.read(gPing[0], got[have:8], 2000);
            if (n <= 0) { gPingBad++; break; }
            have += n;
        }
        i64 us = (date.monoNs() - t0) / 1000;
        if (us > gWorstUs) { gWorstUs = us; }
        gRounds++;
        coro.sleep(1);
    }
    gStop = true;
    return null;
}

void *scenario(void *unused) {
    gBackend = coro.backend();
    gStop = false;
    gStreamBytes = gStreamBad = gRounds = gWorstUs = gPingBad = 0;
    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, gStream);
    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, gPing);
    net.setNonBlocking(gStream[0]);
    net.setNonBlocking(gPing[0]);
    libc.pthread_t *w, *e;
    libc.pthread_create(&w, null, &writer, null);
    libc.pthread_create(&e, null, &echoer, null);
    coro.go(&streamReader);
    coro.go(&client);
    coro.run();
    coro.close(gStream[0]);
    coro.close(gPing[0]);
    libc.pthread_join(w);
    libc.pthread_join(e);
    libc.close(gStream[1]);
    libc.close(gPing[1]);
    return null;
}

void on(u8 *backend) {
    libc.setenv("KTM_CORO_BACKEND", backend, 1);
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &scenario, null);
    libc.pthread_join(th);
    check(gStreamBytes > 1000000, "the stream moved data (over 1 MB)");
    check(gStreamBad == 0, "every byte of the stream arrived in order");
    check(gPingBad == 0, "the client's round trips all came back");
    /* each round can wait up to about 8 ms (two 4 ms bursts) plus its 1 ms
     * sleep: about 160 rounds in 1.5 s at worst, a shut-out client none */
    check(gRounds >= 20, "the client kept getting its turns");
    check(gWorstUs < MARGIN_MS * 1000, "its worst round trip stayed near the 4 ms budget, the stream gave way");
    if (gWorstUs >= MARGIN_MS * 1000) { printf("(worst %d us)", gWorstUs); }
}

i32 main() {
    printf("Test - coro.read gives way: ");
#ifdef IS_MACOS
    on("kqueue");
#else
    on("uring");
    on("epoll");
#endif
    on("poll");
    libc.setenv("KTM_CORO_BACKEND", "", 1);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
