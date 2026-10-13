import coro, cstr, libc, net, test;

/* coro's loop backends (v0.13.0): io_uring is Linux's default where the
 * kernel allows it, then epoll, then poll(2); macOS has kqueue, then
 * poll. coro.backend() names the calling thread's; KTM_CORO_BACKEND
 * picks one when a thread's loop is made, a backend that can't be had
 * gives the next one down, and an unknown value is ignored. Each thread
 * here makes its loop under another setting and runs a coroutine on it. */

extern "c" i32 socketpair(i32 domain, i32 type, i32 protocol, i32 *sv);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8[] gGot;
bool gRan = false;

void *echoOnce(void *arg) {
    i32 *sv = arg;
    u8 buf[8];
    coro.write(sv[1], "ping");
    gRan = coro.read(sv[0], buf[0:8], 2000) == 4 && !libc.memcmp(buf, "ping", 4);
    coro.sleep(5);
    return null;
}

/* in a new thread: its loop's backend, and whether a coroutine ran on it */
void *probe(void *unused) {
    gGot = coro.backend();
    i32 sv[2];
    socketpair(libc.AF_UNIX, libc.SOCK_STREAM, 0, sv);
    net.setNonBlocking(sv[0]);
    net.setNonBlocking(sv[1]);
    coro.go(&echoOnce, sv);
    coro.run();
    libc.close(sv[0]);
    libc.close(sv[1]);
    return null;
}

u8[] on(u8 *value) {
    libc.setenv("KTM_CORO_BACKEND", value, 1);
    gGot = "";
    gRan = false;
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &probe, null);
    libc.pthread_join(th);
    return gGot;
}

bool is(u8[] got, u8 *name) {
    return got.len == cstr.len(name) && !libc.memcmp(got.ptr, name, got.len);
}

i32 main() {
    printf("Test - coro backend: ");
    /* the default, from a thread whose loop is made with no setting (the
     * suite also runs this test with KTM_CORO_BACKEND=epoll) */
    bool forced = libc.getenv("KTM_CORO_BACKEND") != null;
    u8[] main = coro.backend();
    u8[] def = on("");
#ifdef IS_MACOS
    check(is(def, "kqueue"), "macOS: kqueue by default");
#else
    check(is(def, "uring") || is(def, "epoll"), "Linux: io_uring, or epoll where it can't be had");
#endif
    check(forced || is(main, "uring") == is(def, "uring"), "the main thread's loop has the default too");
    check(gRan, "a coroutine runs on the default");
    check(is(on("bogus"), "uring") == is(def, "uring") && gRan, "an unknown value is ignored");
    u8[] p = on("poll");
    check(is(p, "poll") && gRan, "KTM_CORO_BACKEND=poll: poll(2), and it runs");
#ifdef IS_MACOS
    check(is(on("epoll"), "kqueue") && gRan, "macOS: epoll means the kernel queue, kqueue");
    check(is(on("kqueue"), "kqueue") && gRan, "KTM_CORO_BACKEND=kqueue");
    check(is(on("uring"), "kqueue") && gRan, "macOS: uring falls back to kqueue");
#else
    check(is(on("epoll"), "epoll") && gRan, "KTM_CORO_BACKEND=epoll, and it runs");
    check(is(on("kqueue"), "epoll") && gRan, "Linux: kqueue means the kernel queue, epoll");
    u8[] u = on("uring");
    check((is(def, "uring") ? is(u, "uring") : is(u, "epoll")) && gRan,
          "KTM_CORO_BACKEND=uring: io_uring, or epoll where it can't be had");
#endif
    libc.setenv("KTM_CORO_BACKEND", "", 1);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
