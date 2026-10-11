import coro, libc, test;

/* coro.release keeps the stack for the next coro.spawn of the same
 * size on the same thread instead of unmapping it: at most 16 per
 * thread, the rest unmapped, and the cached ones unmapped when the
 * thread ends. A reused stack starts clean (its old data must not leak
 * into the new coroutine's first frame) and keeps its guard page. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 fflush(void *stream);
extern "c" i32 getpagesize();
extern "c" i32 msync(void *addr, u64 len, i32 flags);

const i64 MS_ASYNC = 1;  /* same value on Linux and macOS */

/* Is the page at addr still mapped? msync fails (ENOMEM) on unmapped memory. */
bool mappedFn(void *addr) {
    return msync(addr, libc.getpagesize(), MS_ASYNC) == 0;
}

/* The top page of a coroutine's stack, writable while it is mapped. */
void *topPage(coro.Coroutine *c) {
    return c.stackBase + c.stackLen - libc.getpagesize();
}

void *fib(void *max) {
    i64 a = 0, b = 1, t;
    while (a < (i64)max) {
        coro.yield((void *)a);
        t = a + b;
        a = b;
        b = t;
    }
    return (void *)'end';
}

/* Sum of what Fib(max) yields, and its return value must be 'end'. */
i64 runFib(coro.Coroutine *c) {
    i64 sum = 0;
    void *v;
    for (v = coro.next(c); !coro.done(c); v = coro.next(c)) {
        sum += (i64)v;
    }
    if ((i64)v != 'end') { return -1; }
    return sum;
}

bool reuse() {
    HCFs *fs = Fs();
    i64 before = fs.coroCached;
    coro.Coroutine *a = coro.spawn(&fib, (void *)100);
    void *base = a.stackBase;
    if (runFib(a) != 232) { return false; }
    coro.release(a);
    if (fs.coroCached != before + 1) { return false; }

    coro.Coroutine *b = coro.spawn(&fib, (void *)100);
    if (b.stackBase != base) { return false; }          /* the cached stack */
    if (fs.coroCached != before) { return false; }
    coro.next(b);
    coro.next(b);
    coro.release(b);                               /* while paused */

    coro.Coroutine *c = coro.spawn(&fib, (void *)100);
    if (c.stackBase != base) { return false; }
    if (runFib(c) != 232) { return false; }                /* dirty stack, clean start */
    coro.release(c);
    return fs.coroCached == before + 1;
}

bool sizeMatch() {
    HCFs *fs = Fs();
    coro.Coroutine *big = coro.spawn(&fib, (void *)10);
    void *bigBase = big.stackBase;
    coro.release(big);
    i64 cached = fs.coroCached;
    coro.Coroutine *small = coro.spawn(&fib, (void *)10, 64 * 1024);
    bool ok = small.stackBase != bigBase && fs.coroCached == cached;
    if (runFib(small) != 20) { ok = false; }
    coro.release(small);
    big = coro.spawn(&fib, (void *)10);               /* still there */
    if (big.stackBase != bigBase) { ok = false; }
    coro.release(big);
    return ok;
}

const i64 MANY = 40;

bool bounded() {
    HCFs *fs = Fs();
    coro.Coroutine *cs[MANY];
    void *tops[MANY];
    i64 i;
    for (i = 0; i < MANY; i++) {                       /* empties the cache */
        cs[i] = coro.spawn(&fib, (void *)10, 32 * 1024);
        tops[i] = topPage(cs[i]);
    }
    i64 start = fs.coroCached;
    for (i = 0; i < MANY; i++) { coro.release(cs[i]); }
    if (fs.coroCached != 16) { return false; }
    /* the first ones released filled the cache, the rest were unmapped */
    i64 mapped = 0;
    for (i = 0; i < MANY; i++) { if (mappedFn(tops[i])) { mapped++; } }
    if (mapped != 16 - start) { return false; }
    return !mappedFn(tops[MANY - 1]);
}

bool cycles() {
    i64 i, bad = 0;
    for (i = 0; i < 100000; i++) {
        coro.Coroutine *c = coro.spawn(&fib, (void *)30);
        if (runFib(c) != 54) { bad++; }
        coro.release(c);
    }
    return bad == 0 && Fs().coroCached <= 16;
}

void *gTops[8];

void *threadMain(void *arg) {
    coro.Coroutine *cs[8];
    i64 i;
    for (i = 0; i < 8; i++) {
        cs[i] = coro.spawn(&fib, (void *)10);
        gTops[i] = topPage(cs[i]);
        runFib(cs[i]);
    }
    for (i = 0; i < 8; i++) { coro.release(cs[i]); }
    *(i64 *)arg = Fs().coroCached;
    return null;
}

bool threadExit() {
    libc.pthread_t *t;
    i64 cached = 0, i, still = 0;
    libc.pthread_create(&t, null, &threadMain, &cached);
    libc.pthread_join(t);
    for (i = 0; i < 8; i++) { if (mappedFn(gTops[i])) { still++; } }
    return cached == 8 && still == 0;
}

i64 gFrame[2];

i64 deep(i64 n) {
    i64 pad[8];
    pad[0] = n;
    if (n < 2) { gFrame[n] = (i64)pad; }
    if (n == 0) { return 0; }
    return pad[0] - n + 1 + deep(n - 1);
}

void *overflow(void *arg) {
    coro.yield((void *)deep((i64)arg));
    return null;
}

i32 childGuard()
{// a reused stack must still die on its guard page
    deep(1);
    i64 frame = gFrame[1] - gFrame[0];
    if (frame <= 0) { frame = 64; }
    coro.Coroutine *a = coro.spawn(&overflow, (void *)1, 64 * 1024);
    void *base = a.stackBase;
    coro.release(a);
    a = coro.spawn(&overflow, (void *)((64 * 1024 + 65536) / frame), 64 * 1024);
    if (a.stackBase != base) { return 2; }
    coro.next(a);
    return 3;                       /* the overflow went unseen */
}

i32 runInChild(fn() i32 fn_)
{// fn's result, or 100 + the signal that killed it
    libc.fflush(null);
    i32 pid = libc.fork();
    if (pid == 0) {
        alarm(30);
        _exit(fn_());
    }
    i32 status = 0;
    libc.waitpid(pid, &status, 0);
    if (status & 0x7f) { return 100 + (status & 0x7f); }
    return (status >> 8) & 0xff;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - coroutine stack cache: ");

    tests++;
    if (reuse()) { correct++; }
    else { printf("\n  released stack reused\n"); }

    tests++;
    if (sizeMatch()) { correct++; }
    else { printf("\n  only a stack of the same size is reused\n"); }

    tests++;
    if (bounded()) { correct++; }
    else { printf("\n  at most 16 stacks cached, the rest unmapped\n"); }

    tests++;
    if (cycles()) { correct++; }
    else { printf("\n  100000 new/release cycles\n"); }

    tests++;
    if (threadExit()) { correct++; }
    else { printf("\n  cached stacks unmapped when the thread ends\n"); }

    tests++;
    i32 rc = runInChild(&childGuard);
    /* SIGSEGV (11) on Linux, SIGBUS (10) or SIGSEGV on macOS */
    if (rc == 111 || rc == 110) { correct++; }
    else { printf("\n  reused stack lost its guard page (%d)\n", rc); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
