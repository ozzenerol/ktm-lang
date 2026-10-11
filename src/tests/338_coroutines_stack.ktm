import test;

import coro, libc, mem, str;

/* The coroutine stacks (src/std/coroutines.ktm). The old x86_64-only
 * demo crashed on first use: mmap got MAP_SHARED|MAP_PRIVATE instead of
 * MAP_PRIVATE|MAP_ANONYMOUS and its result was not checked. The odd
 * STACK_CAPACITY misaligned every coroutine stack, which crashed f64
 * printing, and a stack overflow silently wrote over the stack mapped
 * below it. Each case runs in a forked child, so a crash fails the test
 * instead of the suite. Since the module is part of the library it runs
 * on every architecture. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 pipe(i32 *fds);
extern "c" i32 fflush(void *stream);

const i64 STACK_SIZE = (256 * 1024);

void *fib(void *arg) {
    i64 max = (i64)arg;
    i64 a = 0, b = 1, c;
    while (a < max) {
        coro.yield((void *)a);
        c = a + b;
        a = b;
        b = c;
    }
    return null;
}

i32 childFib() {
    i64 want[7] = {0, 1, 1, 2, 3, 5, 8};
    i64 n = 0;
    coro.Coroutine *c = coro.spawn(&fib, (void *)10);
    if (!c) { return 1; }
    for (void *it = coro.next(c); !coro.done(c); it = coro.next(c)) {
        if (n >= 7 || (i64)it != want[n]) { return 2; }
        n++;
    }
    if (n != 7) { return 3; }
    coro.release(c);
    return 0;
}

void *floats(void *arg) {
    f64 x = 1.5;
    for (i64 i = 0; i < 3; i++) {
        x *= 2.0;
        u8 *s = str.format("%.2f", x);
        coro.yield((void *)str.cmp(s, ((u8 **)arg)[i]));
        mem.free(s);
    }
    return null;
}

i32 childAlignment() {
    u8 *want[3] = {"3.00", "6.00", "12.00"};
    coro.Coroutine *c = coro.spawn(&floats, want);
    if (!c) { return 1; }
    /* the first frame sits on a 16-byte aligned stack pointer */
    if ((i64)c.sp % 16 != 0) { return 2; }
    for (void *it = coro.next(c); !coro.done(c); it = coro.next(c)) {
        if ((i64)it != 0) { return 3; }
    }
    coro.release(c);
    return 0;
}

i32 childMany() {
    coro.Coroutine *cs[64];
    i64 i, sum = 0;
    for (i = 0; i < 64; i++) {
        cs[i] = coro.spawn(&fib, (void *)100, STACK_SIZE);
        if (!cs[i]) { return 1; }
    }
    for (i = 0; i < 64; i++) {
        for (void *it = coro.next(cs[i]); !coro.done(cs[i]); it = coro.next(cs[i])) {
            sum += (i64)it;
        }
    }
    for (i = 0; i < 64; i++) { coro.release(cs[i]); }
    /* 0+1+1+2+3+5+8+13+21+34+55+89 = 232 */
    return sum != 64 * 232;
}

i32 gPipe[2];

i64 gFrame[2];

i64 deep(i64 n) {
    i64 pad[8];
    pad[0] = n;
    if (n < 2) { gFrame[n] = (i64)pad; }
    if (n == 0) { return 0; }
    return pad[0] - n + 1 + deep(n - 1);
}

i64 overflowDepth()
{// Deep enough to run 64 KB past the end of the stack, no further
    deep(1);
    i64 frame = gFrame[1] - gFrame[0];
    if (frame <= 0) { frame = 64; }
    return (STACK_SIZE + 65536) / frame;
}

void *overflow(void *arg) {
    coro.yield((void *)deep((i64)arg));
    return null;
}

void *locals(void *arg) {
    i64 arr[4000], bad = 0, i;
    for (i = 0; i < 4000; i++) { arr[i] = 12345; }
    coro.yield((void *)1);
    for (i = 0; i < 4000; i++) { if (arr[i] != 12345) { bad++; } }
    coro.yield((void *)bad);
    return null;
}

i32 childOverflow()
{// Must die on the guard page; returning means the overflow went unseen
    i64 depth = overflowDepth();
    coro.Coroutine *a = coro.spawn(&overflow, (void *)depth, STACK_SIZE);
    coro.Coroutine *b = coro.spawn(&locals, null, STACK_SIZE);
    if (!a || !b) { return 1; }
    coro.next(b);
    libc.write(gPipe[1], "r", 1); /* reached the overflow */
    coro.next(a);
    libc.write(gPipe[1], "a", 1); /* the overflow went unseen */
    if ((i64)coro.next(b) != 0) { return 2; } /* b's stack was overwritten */
    return 3;
}

i32 runInChild(fn() i32 fn_)
{// fn's result, or 100 + the signal that killed it
    libc.fflush(null);           /* or the child repeats our buffered output */
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
    printf("Test - coroutine stacks: ");
    i32 rc;
    tests++;
    if ((rc = runInChild(&childFib)) == 0) { correct++; }
    else { printf("\n  fib generator (%d)\n", rc); }

    tests++;
    if ((rc = runInChild(&childAlignment)) == 0) { correct++; }
    else { printf("\n  f64 printing in a coroutine (%d)\n", rc); }

    tests++;
    if ((rc = runInChild(&childMany)) == 0) { correct++; }
    else { printf("\n  64 coroutines (%d)\n", rc); }

    tests++;
    u8 reached[4];
    mem.set(reached, 0, sizeof(reached));
    libc.pipe(gPipe);
    rc = runInChild(&childOverflow);
    libc.close(gPipe[1]);
    libc.read(gPipe[0], reached, 2);
    libc.close(gPipe[0]);
    /* killed in the overflow: SIGSEGV (11) on Linux, SIGBUS (10) or
     * SIGSEGV on macOS */
    if (!str.cmp(reached, "r") && (rc == 111 || rc == 110)) { correct++; }
    else { printf("\n  stack overflow not caught by the guard page (%d)\n", rc); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
