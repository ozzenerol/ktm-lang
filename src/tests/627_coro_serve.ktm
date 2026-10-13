import atomic, coro, cstr, libc, net, os, signal, test;

/* coro.serve (v0.11.0): a TCP server on several coroutine loops, one per
 * thread. Many clients at once get their own bytes back, the connections
 * spread over more than one loop (Linux: a SO_REUSEPORT listener each;
 * macOS: one shared listener), serveStop works from another thread and
 * from a handler, an in-flight handler still finishes, SIGTERM through
 * signal.stopInit() and a coroutine started before serve stops it, and a
 * bad port, a bad address or a port in use gives -1. */

extern "c" void *pthread_self();
extern "c" i32 getpid();
extern "c" i32 kill(i32 pid, i32 sig);
extern "c" i32 usleep(u32 us);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* A port nobody listens on: bind port 0, read it, close it */
i32 freePort() {
    i32 fd = net.listen(0, "127.0.0.1", 1);
    i32 port = net.localPort(fd);
    libc.close(fd);
    return port;
}

/* ---- the echo server: every connection's thread is recorded ---- */

i64 gConns = 0;
i64 gThreadIds[64];
i64 gNthreads = 0;

void noteThread() {
    i64 me = (i64)pthread_self();
    for (i64 i = 0; i < 64; i++) {
        i64 seen = atomic.load(&gThreadIds[i]);
        if (seen == me) { return; }
        if (seen == 0 && atomic.cas(&gThreadIds[i], 0, me)) {
            atomic.add(&gNthreads, 1);
            return;
        }
        if (atomic.load(&gThreadIds[i]) == me) { return; }
    }
}

void echo(i32 fd, void *arg) {
    u8 buf[256];
    atomic.add(&gConns, 1);
    noteThread();
    while (coro.wait(fd, libc.POLLIN, 5000) > 0) {
        i64 n = libc.recv(fd, buf, sizeof(buf), 0);
        if (n <= 0) { return; }
        if (n == 4 && !libc.memcmp(buf, "STOP", 4)) {
            coro.serveStop();
            net.send(fd, "stopping");
            return;
        }
        if (n == 4 && !libc.memcmp(buf, "slow", 4)) {
            coro.sleep(300);          /* still running when the stop comes */
            net.send(fd, "done");
            continue;
        }
        net.send(fd, buf[0:n]);
    }
}

/* ---- blocking clients, on threads of their own ---- */

i32 gPort;

i32 connect() {
    for (i64 i = 0; i < 100; i++) {
        i32 fd = net.dial("127.0.0.1", gPort);
        if (fd >= 0) {
            net.setRecvTimeout(fd, 5000);
            return fd;
        }
        usleep(20000);
    }
    return -1;
}

/* send msg, read back exactly want.len bytes; whether they match */
bool ask(i32 fd, u8[] msg, u8[] want) {
    u8 buf[256];
    i64 got = 0;
    if (net.send(fd, msg) != msg.len) { return false; }
    while (got < want.len) {
        i64 n = libc.recv(fd, buf + got, want.len - got, 0);
        if (n <= 0) { return false; }
        got += n;
    }
    return !libc.memcmp(buf, want.ptr, want.len);
}

const i64 CLIENTS = 8, ROUNDS = 25, MSGS = 3;
i64 gGood = 0, gBad = 0;

void *client(void *arg) {
    i64 id = (i64)arg;
    u8 msg[64];
    for (i64 r = 0; r < ROUNDS; r++) {
        i32 fd = connect();
        bool ok = fd >= 0;
        for (i64 m = 0; ok && m < MSGS; m++) {
            libc.snprintf(msg, sizeof(msg), "client %d round %d message %d", id, r, m);
            u8[] text = cstr.view(msg);
            ok = ask(fd, text, text);
        }
        if (fd >= 0) { libc.close(fd); }
        atomic.add(ok ? &gGood : &gBad, 1);
    }
    return null;
}

/* phase 1: CLIENTS threads at once, then serveStop from this thread */
void *driveMany(void *unused) {
    libc.pthread_t *th[CLIENTS];
    for (i64 i = 0; i < CLIENTS; i++) { libc.pthread_create(&th[i], null, &client, (void *)i); }
    for (i64 i = 0; i < CLIENTS; i++) { libc.pthread_join(th[i]); }
    coro.serveStop();
    return null;
}

/* phase 2: a slow request in flight, then STOP from another client */
bool gSlowOk = false, gStopOk = false, gHelloOk = false;

void *slowClient(void *unused) {
    i32 fd = connect();
    if (fd < 0) { return null; }
    gSlowOk = ask(fd, "slow", "done");
    libc.close(fd);
    return null;
}

void *driveStop(void *unused) {
    i32 fd = connect();
    if (fd < 0) { return null; }
    gHelloOk = ask(fd, "hello", "hello");
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &slowClient, null);
    usleep(100000);              /* the slow one is being handled */
    i32 fd2 = connect();
    if (fd2 >= 0) {
        gStopOk = ask(fd2, "STOP", "stopping");
        libc.close(fd2);
    }
    libc.pthread_join(th);
    libc.close(fd);
    return null;
}

/* phase 3: SIGTERM, through signal.stopInit and a coroutine */
bool gSigEchoOk = false;

void *driveSignal(void *unused) {
    i32 fd = connect();
    if (fd < 0) { return null; }
    gSigEchoOk = ask(fd, "ping", "ping");
    libc.close(fd);
    kill(getpid(), libc.SIGTERM);
    return null;
}

void *onSignal(void *unused) {
    if (coro.waitIdle(signal.stopInit(), libc.POLLIN) > 0) { coro.stop(); }
    return null;
}

i32 main() {
    printf("Test - coro.serve: ");
    signal.handle(libc.SIGPIPE, libc.SIG_IGN);

    /* phase 1: 4 loops, 200 connections from 8 threads at once */
    gPort = freePort();
    libc.pthread_t *drv;
    libc.pthread_create(&drv, null, &driveMany, null);
    i32 r = coro.serve(gPort, &echo, null, 4, "127.0.0.1");
    libc.pthread_join(drv);
    check(r == 0, "serve returns 0 after serveStop from another thread");
    check(gGood == CLIENTS * ROUNDS && gBad == 0, "every client got its own bytes back");
    check(gConns == CLIENTS * ROUNDS, "the handler ran once per connection");
#ifdef IS_MACOS
    check(gNthreads >= 1, "the loops took the connections");
#else
    check(gNthreads > 1, "the connections spread over more than one loop");
#endif
    i32 again = net.dial("127.0.0.1", gPort);
    check(again < 0, "nothing listens after serve returned");
    if (again >= 0) { libc.close(again); }

    /* phase 2: one loop per CPU; STOP from a handler, a slow one finishes */
    gPort = freePort();
    libc.pthread_create(&drv, null, &driveStop, null);
    r = coro.serve(gPort, &echo);
    libc.pthread_join(drv);
    check(r == 0, "serve returns 0 after serveStop from a handler");
    check(gHelloOk, "echo on one loop per CPU");
    check(gStopOk, "the STOP handler answered");
    check(gSlowOk, "a handler in flight finished after the stop");

    /* phase 3: SIGTERM stops it through a coroutine started before serve.
     * The handler goes in before any thread, as stopInit asks: the
     * coroutine may run only after a client on another loop sent SIGTERM,
     * which then ended the test (seen once on arm64). */
    gPort = freePort();
    signal.stopInit();
    coro.go(&onSignal);
    libc.pthread_create(&drv, null, &driveSignal, null);
    r = coro.serve(gPort, &echo, null, 2, "127.0.0.1");
    libc.pthread_join(drv);
    check(r == 0, "serve returns 0 after SIGTERM");
    check(gSigEchoOk && signal.stopRequested(), "it served, then SIGTERM stopped it");

    /* phase 4: what can't be served */
    os.setErrno(0);
    check(coro.serve(70000, &echo, null, 2) == -1 && os.errno() == libc.EINVAL, "port 70000: -1, EINVAL");
    check(coro.serve(freePort(), &echo, null, 2, "1.2.3") == -1, "a bad address: -1");
    i32 busy = net.listen(0, "127.0.0.1", 4);
    check(coro.serve(net.localPort(busy), &echo, null, 2, "127.0.0.1") == -1 && os.errno() == libc.EADDRINUSE,
          "a port in use: -1, EADDRINUSE");
    libc.close(busy);
    coro.serveStop();                 /* none running: nothing happens */

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
