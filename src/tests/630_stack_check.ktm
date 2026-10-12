import coro, libc, test;

/* Big stack frames (v0.12.0): a function whose frame is bigger than a
 * page asks the runtime whether it still fits on its stack instead of
 * touching every page of it. So a 64 KiB buffer that is barely used
 * costs a page or two, its arguments arrive intact, and running off the
 * end of a coroutine's or a thread's stack is reported as a stack
 * overflow. */

extern "c" i32 fork();
extern "c" i32 waitpid(i32 pid, i32 *status, i32 options);
extern "c" i32 dup2(i32 from, i32 to);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* every argument register, through a big frame */
f64 manyArgs(i64 a, i64 b, i64 c, i64 d, i64 e, i64 f, i64 g, i64 h,
             f64 x, f64 y, f64 z, f64 w, f64 p, f64 q, f64 r, f64 s) {
    u8 big[20000];
    big[0] = (u8)a;
    big[19999] = (u8)h;
    return (f64)(a + b + c + d + e + f + g + h + big[0] + big[19999]) + x + y + z + w + p + q + r + s;
}

struct Pair { i64 a; i64 b; i64 c; };

/* a struct returned through the hidden pointer, from a big frame */
Pair bigPair(i64 v) {
    u8 big[9000];
    big[100] = (u8)v;
    Pair out;
    out.a = v;
    out.b = big[100];
    out.c = v * 3;
    return out;
}

struct Rusage { libc.timeval utime; libc.timeval stime; i64 maxrss; i64 rest[13]; };
extern "c" i32 getrusage(i32 who, Rusage *ru);

/* Peak resident memory in KB (ru_maxrss: KB on Linux, bytes on macOS);
 * the test only grows, so the peak is what it holds */
i64 rssKb() {
    Rusage ru;
    getrusage(0, &ru);
#ifdef IS_MACOS
    return ru.maxrss / 1024;
#else
    return ru.maxrss;
#endif
}

#ifdef IS_MACOS
const i32 LIMIT_PAGESIZE = 29;   /* _SC_PAGESIZE */
#else
const i32 LIMIT_PAGESIZE = 30;
#endif

i64 gPaused = 0;

/* a connection handler's shape: a 64 KiB buffer, one small use, then a
 * long pause */
void *bufferHolder(void *unused) {
    u8 buf[65536];
    buf[0] = 1;
    gPaused++;
    coro.sleep(300);
    return (void *)(i64)buf[0];
}

i64 gMeasured = -1;

/* runs after the holders paused: the memory they hold */
void *measure(void *unused) {
    coro.sleep(100);
    gMeasured = rssKb();
    return null;
}

i64 gDepth = 0;

void deep(i64 n) {
    u8 big[16384];
    big[n % 16384] = (u8)n;
    gDepth++;
    deep(n + 1);
    big[0] = big[1];
}

void *deepCoroutine(void *unused) {
    deep(0);
    return null;
}

/* Run `body` in a child with stderr in a pipe: its stderr, and whether
 * it died of SIGABRT */
bool overflowsIn(fn(void *arg) void *body, bool onCoroutine) {
    i32 fds[2];
    libc.pipe(fds);
    i32 pid = fork();
    if (pid == 0) {
        dup2(fds[1], 2);
        if (onCoroutine) {
            coro.Coroutine *c = coro.spawn(body);
            coro.next(c);
        } else {
            body(null);
        }
        libc.exit(0);
    }
    libc.close(fds[1]);
    u8 errbuf[256];
    i64 n = libc.read(fds[0], errbuf, 255);
    errbuf[n > 0 ? n : 0] = 0;
    libc.close(fds[0]);
    i32 status = 0;
    waitpid(pid, &status, 0);
    bool aborted = (status & 0x7F) == 6;    /* SIGABRT, the same on Linux and macOS */
    return aborted && libc.strstr(errbuf, "stack overflow") != null;
}

void *deepOnThread(void *unused) {
    deep(0);
    return null;
}

i32 main() {
    printf("Test - big stack frames: ");

    f64 got = manyArgs(1, 2, 3, 4, 5, 6, 7, 8, 0.5, 1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5);
    check(got == 36.0 + 1 + 8 + 32.0, "every argument register arrives through a big frame");
    Pair p = bigPair(7);
    check(p.a == 7 && p.b == 7 && p.c == 21, "a struct returned from a big frame");

    /* 200 coroutines, each with a 64 KiB buffer it barely uses */
    i64 before = rssKb();
    for (i64 i = 0; i < 200; i++) { coro.go(&bufferHolder); }
    coro.go(&measure);
    coro.run();
    check(gPaused == 200, "200 coroutines with a 64 KiB buffer each");
    /* about two pages each (the bottom of the frame, the top of the
     * stack), where touching the whole frame costs over 64 KiB */
    i64 per = (gMeasured - before) / 200;
    i64 limit = 3 * libc.sysconf(LIMIT_PAGESIZE) / 1024;
    if (limit < 16) { limit = 16; }
    check(gMeasured > 0 && per < limit && per < 60, "a few pages of memory each, not the whole 64 KiB frame");
    if (per >= limit) { printf("(%d KB each)", per); }

    check(overflowsIn(&deepCoroutine, true), "running off a coroutine's stack: stack overflow");
    check(overflowsIn(&deepOnThread, false), "running off the thread's stack: stack overflow");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
