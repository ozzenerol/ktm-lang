import coro, date, libc, mem, net, os, test;

/* Many TCP connections through one coroutine loop on every backend
 * (v0.13.0): an echo server (coro.accept, coro.read, coro.write) and 300
 * clients in the same loop, each sending four messages of 1 byte to 100
 * KB (partial reads and writes) and checking every byte of the echo,
 * then shutting down its side and reading on until the end (a short read
 * isn't the end of a stream). Each backend runs in a thread of its own. */

i64 gCorrect = 0, gTests = 0;
u8[] gBackend;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed on %s: %s\n", gBackend.ptr, what); }
}

const i64 CONNS = 300;
i64 SIZES[4] = {1, 1000, 30000, 100000};

i32 gListen;
i64 gGood, gBad, gServed, gEof;

void *echo(void *arg) {
    i32 fd = (i32)(i64)arg;
    u8 buf[16384];
    i64 n;
    while ((n = coro.read(fd, buf[0:16384], 10000)) > 0) {
        if (coro.write(fd, buf[0:n], 10000) < 0) { break; }
    }
    coro.close(fd);
    gServed++;
    return null;
}

void *acceptor(void *unused) {
    for (i64 i = 0; i < CONNS; i++) {
        i32 fd = coro.accept(gListen, 10000);
        if (fd < 0) { return null; }
        coro.go(&echo, (void *)(i64)fd);
    }
    return null;
}

/* A connection to the listener, started without blocking the loop:
 * net.dial connects before it returns, and on macOS (backlog 128) a
 * connect waits once the backlog is full, while the acceptor that would
 * empty it can't run */
i32 connectAsync(i32 port) {
    i32 fd = libc.socket(libc.AF_INET, libc.SOCK_STREAM, 0);
    if (fd < 0) { return -1; }
    net.setNonBlocking(fd);
    libc.sockaddr_in a;
    mem.set(&a, 0, sizeof(libc.sockaddr_in));
    a.sin_family = libc.AF_INET;
    a.sin_port = net.htons((u16)port);
    a.sin_addr.s_addr = net.htonl(0x7F000001);
    if (libc.connect(fd, (libc.sockaddr *)&a, sizeof(libc.sockaddr_in)) != 0 &&
        os.errno() != libc.EINPROGRESS) {
        libc.close(fd);
        return -1;
    }
    return fd;
}

/* one client: four messages, each a pattern of its own, echoed exactly */
void *client(void *arg) {
    i64 id = (i64)arg;
    i32 fd = connectAsync(net.localPort(gListen));
    bool ok = fd >= 0 && (coro.wait(fd, libc.POLLOUT, 15000) & libc.POLLOUT) != 0;
    for (i64 m = 0; ok && m < 4; m++) {
        i64 size = SIZES[(id + m) % 4];
        u8 *out = mem.alloc(size);
        u8 *in = mem.alloc(size);
        for (i64 i = 0; i < size; i++) { out[i] = (u8)(id * 7 + m * 13 + i); }
        ok = coro.write(fd, out[0:size], 10000) == size;
        i64 got = 0;
        while (ok && got < size) {
            i64 n = coro.read(fd, in[got:size], 10000);
            if (n <= 0) { ok = false; }
            else { got += n; }
        }
        ok = ok && !libc.memcmp(in, out, size);
        mem.free(out);
        mem.free(in);
    }
    if (fd >= 0) {
        /* the end: shut our side, then read until 0 */
        libc.shutdown(fd, libc.SHUT_WR);
        u8 tail[64];
        i64 n;
        while ((n = coro.read(fd, tail[0:64], 10000)) > 0) { ok = false; }
        if (n == 0) { gEof++; }
        coro.close(fd);
    }
    if (ok) { gGood++; } else { gBad++; }
    return null;
}

i64 gMs;

void *scenario(void *unused) {
    gBackend = coro.backend();
    gGood = gBad = gServed = gEof = 0;
    gListen = net.listen(0, "127.0.0.1", 512);
    i64 t0 = date.monoMs();
    coro.go(&acceptor);
    for (i64 i = 0; i < CONNS; i++) { coro.go(&client, (void *)i); }
    coro.run();
    gMs = date.monoMs() - t0;
    libc.close(gListen);
    return null;
}

void on(u8 *backend) {
    libc.setenv("KTM_CORO_BACKEND", backend, 1);
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &scenario, null);
    libc.pthread_join(th);
    check(gGood == CONNS && gBad == 0, "every client got every byte back");
    check(gServed == CONNS, "every connection was served and closed");
    check(gEof == CONNS, "every client read on to the end");
    check(gMs < 20000, "in good time");
}

i32 main() {
    printf("Test - coro many connections: ");
    /* 600 fds and more: more than macOS allows by default */
    libc.rlimit lim;
    libc.getrlimit(libc.RLIMIT_NOFILE, &lim);
    if (lim.rlim_cur < 2048) {
        lim.rlim_cur = lim.rlim_max < 2048 ? lim.rlim_max : 2048;
        libc.setrlimit(libc.RLIMIT_NOFILE, &lim);
    }
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
