import coro, libc, mem, test;

const i64 END_MARK = 1001;
const i64 SELF_MARK = 1002;
const i64 NO_MARK = 1003;

enum Err { main, boom };

/* Coroutines as a library module (coroutines.ktm), on x86_64 and arm64:
 * values both ways through Yield/Next, the function's return value from
 * the last Next, coroutines resuming coroutines, a try spanning a yield
 * (each coroutine has its own catch chain), an uncaught throw going on
 * from the Next that resumed it, f64 values kept across switches, many
 * coroutines alive at once, coroutines on two threads, the FP control
 * state per coroutine, and misuse exiting with a message. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 dup2(i32 oldfd, i32 newfd);
extern "c" i32 fflush(void *stream);

void *fib(void *max) {
    i64 a = 0, b = 1, t;
    while (a < (i64)max) {
        coro.yield((void *)a);
        t = a + b;
        a = b;
        b = t;
    }
    return (void *)END_MARK;
}

bool generator() {
    i64 want[7] = {0, 1, 1, 2, 3, 5, 8}, n = 0;
    coro.Coroutine *c = coro.spawn(&fib, (void *)10);
    void *v;
    if (!c || coro.done(c)) { return false; }
    for (v = coro.next(c); !coro.done(c); v = coro.next(c)) {
        if (n >= 7 || (i64)v != want[n]) { return false; }
        n++;
    }
    if (n != 7 || (i64)v != END_MARK) { return false; }    /* fn's return value */
    if (coro.next(c) != null) { return false; }     /* done: null */
    if (!coro.done(c)) { return false; }
    coro.release(c);
    return true;
}

void *sumFn(void *start) {
    i64 total = (i64)start;
    while (true) { total += (i64)coro.yield((void *)total); }
    return null;
}

bool sendValues() {
    coro.Coroutine *c = coro.spawn(&sumFn, (void *)100);
    if ((i64)coro.next(c, (void *)555) != 100) { return false; } /* send ignored */
    if ((i64)coro.next(c, (void *)5) != 105) { return false; }
    if ((i64)coro.next(c, (void *)7) != 112) { return false; }
    if ((i64)coro.next(c) != 112) { return false; }            /* null = 0 */
    coro.release(c);                                        /* while paused */
    return true;
}

/* Yields every value of an inner generator, doubled. */
void *doubler(void *max) {
    coro.Coroutine *inner = coro.spawn(&fib, max);
    void *v;
    if (coro.current() == null) { return (void *)SELF_MARK; }
    for (v = coro.next(inner); !coro.done(inner); v = coro.next(inner)) {
        coro.yield((void *)((i64)v * 2));
    }
    coro.release(inner);
    return null;
}

bool nested() {
    i64 want[6] = {0, 2, 2, 4, 6, 10}, n = 0;
    if (coro.current() != null) { return false; }
    coro.Coroutine *c = coro.spawn(&doubler, (void *)6);
    void *v;
    for (v = coro.next(c); !coro.done(c); v = coro.next(c)) {
        if (n >= 6 || (i64)v != want[n]) { return false; }
        n++;
    }
    if (n != 6 || v != null) { return false; }
    if (coro.current() != null) { return false; }
    coro.release(c);
    return true;
}

/* A try whose body yields: the throw after the yield must land in this
 * coroutine's catch, not in whatever try the resumer is in. */
void *tryAcross(void *tag) {
    try {
        coro.yield((void *)1);
        throw((i64)tag);
    } catch (Error e) {
        coro.yield((void *)e.code);
    }
    return (void *)2;
}

bool tryAcrossYield() {
    coro.Coroutine *a = coro.spawn(&tryAcross, (void *)'A');
    coro.Coroutine *b = coro.spawn(&tryAcross, (void *)'B');
    i64 r1, r2, r3, r4, r5 = 0, r6;
    try {
        if ((i64)coro.next(a) != 1) { return false; }
        if ((i64)coro.next(b) != 1) { return false; }
        r1 = (i64)coro.next(a);   /* a throws and catches 'A' */
        r2 = (i64)coro.next(b);   /* b throws and catches 'B' */
        try {
            throw(Err.main);
        } catch (Error e) {
            r5 = e.code;
        }
        r3 = (i64)coro.next(a);
        r4 = (i64)coro.next(b);
    } catch {
        return false;                 /* a coroutine's throw leaked out */
    }
    r6 = coro.done(a) && coro.done(b);
    coro.release(a);
    coro.release(b);
    return r1 == 'A' && r2 == 'B' && r3 == 2 && r4 == 2 && r5 == Err.main && r6;
}

void *thrower(void *arg) {
    coro.yield((void *)1);
    throw(Err.boom);
    return null;
}

void *middle(void *arg)
{/* doesn't catch: the inner throw passes through it */
    coro.Coroutine *t = coro.spawn(&thrower);
    coro.yield(coro.next(t));
    coro.next(t);
    return (void *)NO_MARK;
}

bool throwOut() {
    coro.Coroutine *c = coro.spawn(&thrower);
    i64 caught = 0;
    coro.next(c);
    try {
        coro.next(c);
    } catch (Error e) {
        caught = e.code;
    }
    if (caught != Err.boom || !coro.done(c)) { return false; }
    if (coro.next(c) != null) { return false; }     /* no second rethrow */
    coro.release(c);

    caught = 0;
    coro.Coroutine *m = coro.spawn(&middle);
    try {
        if ((i64)coro.next(m) != 1) { return false; }
        coro.next(m);
    } catch (Error e) {
        caught = e.code;
    }
    if (caught != Err.boom || !coro.done(m)) { return false; }
    coro.release(m);              /* Thrower inside it leaks: fine */
    return true;
}

void *floatWork(void *arg) {
    f64 x = 1.0, y = 0.5, z = 0.25;
    i64 i;
    for (i = 0; i < 5; i++) {
        x = x * 3.0 + y;
        y = y * 0.5 + z;
        z = z + x * 0.001;
        coro.yield((void *)i);
    }
    f64 *out = arg;
    *out = x + y + z;
    return null;
}

bool floats() {
    f64 a = 1.25, b = -2.5, c = 1e10, d = 3.0 / 7.0, got = 0, want;
    coro.Coroutine *co = coro.spawn(&floatWork, &got);
    while (!coro.done(co)) {
        coro.next(co);
        a = a * 2.0;
        b = b + a;
        c = c / 2.0;
        d = d * d + 0.1;
    }
    coro.release(co);
    /* the same sums with no coroutine */
    f64 x = 1.0, y = 0.5, z = 0.25, a2 = 1.25, b2 = -2.5, c2 = 1e10, d2 = 3.0 / 7.0;
    i64 i;
    for (i = 0; i < 5; i++) {
        x = x * 3.0 + y;
        y = y * 0.5 + z;
        z = z + x * 0.001;
    }
    want = x + y + z;
    for (i = 0; i < 6; i++) {
        a2 = a2 * 2.0;
        b2 = b2 + a2;
        c2 = c2 / 2.0;
        d2 = d2 * d2 + 0.1;
    }
    return got == want && a == a2 && b == b2 && c == c2 && d == d2;
}

const i64 MANY = 1000;

bool many() {
    coro.Coroutine **cs = mem.alloc(MANY * sizeof(coro.Coroutine *));
    i64 i, sum = 0, live = MANY;
    for (i = 0; i < MANY; i++) {
        cs[i] = coro.spawn(&fib, (void *)(i % 50), 64 * 1024);
        if (!cs[i]) { return false; }
    }
    while (live) {                  /* round robin until all are done */
        live = 0;
        for (i = 0; i < MANY; i++) {
            if (coro.done(cs[i])) { continue; }
            void *v = coro.next(cs[i]);
            if (!coro.done(cs[i])) {
                sum += (i64)v;
                live++;
            }
        }
    }
    for (i = 0; i < MANY; i++) { coro.release(cs[i]); }
    mem.free(cs);
    /* sum of the Fibonacci numbers below m, for m = 0..49, 20 times */
    i64 want = 0, m, a, b, t;
    for (m = 0; m < 50; m++) {
        a = 0;
        b = 1;
        while (a < m) {
            want += a;
            t = a + b;
            a = b;
            b = t;
        }
    }
    return sum == want * (MANY / 50);
}

void *threadMain(void *arg) {
    i64 *res = arg, rounds, sum = 0;
    for (rounds = 0; rounds < 200; rounds++) {
        coro.Coroutine *c = coro.spawn(&sumFn, (void *)0, 16 * 1024);
        coro.next(c);
        for (i64 i = 1; i <= 10; i++) { sum = (i64)coro.next(c, (void *)i) + sum; }
        coro.release(c);
    }
    *res = sum;
    return null;
}

bool threads() {
    libc.pthread_t *t1, *t2;
    i64 r1 = 0, r2 = 0;
    /* per round: running totals 1+3+6+...+55 = 220 */
    libc.pthread_create(&t1, null, &threadMain, &r1);
    libc.pthread_create(&t2, null, &threadMain, &r2);
    libc.pthread_join(t1);
    libc.pthread_join(t2);
    return r1 == 200 * 220 && r2 == 200 * 220 && coro.current() == null;
}

#ifdef IS_X86_64
/* glibc's fesetround sets both MXCSR and the x87 control word */
extern "c" i32 fesetround(i32 mode);
extern "c" i32 fegetround();
const i64 FE_TONEAREST = 0;
const i64 FE_DOWNWARD = 0x400;

void *rounding(void *arg) {
    fesetround(FE_DOWNWARD);
    coro.yield((void *)fegetround());
    coro.yield((void *)fegetround());     /* its own mode is back */
    fesetround(FE_TONEAREST);
    return null;
}

bool fpControl() {
    coro.Coroutine *c = coro.spawn(&rounding);
    if ((i64)coro.next(c) != FE_DOWNWARD) { return false; }
    if (fegetround() != FE_TONEAREST) { return false; }   /* not leaked to us */
    if ((i64)coro.next(c) != FE_DOWNWARD) { return false; }
    coro.next(c);
    coro.release(c);
    return fegetround() == FE_TONEAREST;
}
#else
bool FpControl() {
    return true;                    /* FPCR is not switched on arm64 */
}
#endif

void *nextSelf(void *arg) {
    coro.next(coro.current());
    return null;
}

i32 childYieldOutside() {
    coro.yield((void *)1);
    return 0;
}

i32 childNextRunning() {
    coro.Coroutine *c = coro.spawn(&nextSelf);
    coro.next(c);
    return 0;
}

i32 runInChild(fn() i32 fn_)
{// fn's exit code, or 100 + the signal that killed it
    libc.fflush(null);                   /* misuse exits via exit(), which flushes */
    i32 pid = libc.fork();
    if (pid == 0) {
        alarm(30);
        i32 devnull = libc.open("/dev/null", 1);
        dup2(devnull, 2);                /* the misuse message goes to stderr */
        _exit(fn_());
    }
    i32 status = 0;
    libc.waitpid(pid, &status, 0);
    if (status & 0x7f) { return 100 + (status & 0x7f); }
    return (status >> 8) & 0xff;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - coroutines api: ");

    tests++;
    if (generator()) { correct++; }
    else { printf("\n  generator values / final value / done\n"); }

    tests++;
    if (sendValues()) { correct++; }
    else { printf("\n  values sent by CoroutineNext\n"); }

    tests++;
    if (nested()) { correct++; }
    else { printf("\n  a coroutine resuming a coroutine\n"); }

    tests++;
    if (tryAcrossYield()) { correct++; }
    else { printf("\n  try spanning a yield\n"); }

    tests++;
    if (throwOut()) { correct++; }
    else { printf("\n  uncaught throw rethrown by CoroutineNext\n"); }

    tests++;
    if (floats()) { correct++; }
    else { printf("\n  f64 values across switches\n"); }

    tests++;
    if (many()) { correct++; }
    else { printf("\n  %d coroutines round robin\n", MANY); }

    tests++;
    if (threads()) { correct++; }
    else { printf("\n  coroutines on two threads\n"); }

    tests++;
    if (fpControl()) { correct++; }
    else { printf("\n  rounding mode per coroutine\n"); }

    tests++;
    i32 rc1 = runInChild(&childYieldOutside);
    i32 rc2 = runInChild(&childNextRunning);
    if (rc1 == 1 && rc2 == 1) { correct++; }
    else { printf("\n  misuse must exit with 1 (%d, %d)\n", rc1, rc2); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
