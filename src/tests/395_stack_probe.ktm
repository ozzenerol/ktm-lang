import test;

import libc;

/* A function with a frame larger than a page now reserves it a page at a
 * time, touching each page as the stack descends (stack-clash probing).
 * A frame bigger than the guard page therefore faults on overflow instead
 * of leaping over the guard into other memory: a 1 MiB frame on a 256 KiB
 * thread stack dies with SIGSEGV rather than silently corrupting whatever
 * lies below the stack. Normal big frames are unaffected. Checked in a
 * forked child so the thread's fault doesn't take the test down. */

extern "c" i32 pthread_attr_destroy(libc.pthread_attr_t *attr);
extern "c" i32 fflush(void *stream);
extern "c" void exit(i32 status);

const i64 SIGSEGV = 11;
const i64 SIGBUS = 10;          /* macOS delivers this for a guard-page hit */
const i64 SMALL_STACK = (256 * 1024);
const i64 BIG_FRAME = (1024 * 1024);

i64 gResult = 0;

/* A 1 MiB frame. It writes only the top of the array (near the frame
 * base, inside the mapped stack), so without probing the oversized `sub
 * sp` is never noticed and the call returns; with probing the descent
 * hits the guard page first. */
i64 bigFrame() {
    u8 buf[BIG_FRAME];
    buf[BIG_FRAME - 1] = 0x5A;
    return buf[BIG_FRAME - 1];
}

void *threadFn(void *arg) {
    gResult = bigFrame();
    return null;
}

/* fork a child that runs BigFrame on a 256 KiB thread stack, and return
 * the child's wait status. */
i64 childRunsThread() {
    i32 pid, status = 0;
    libc.fflush(null);                 /* don't duplicate buffered output in the child */
    pid = libc.fork();
    if (pid == 0) {
        libc.pthread_attr_t attr;
        libc.pthread_t *th;
        libc.pthread_attr_init(&attr);
        libc.pthread_attr_setstacksize(&attr, SMALL_STACK);
        if (libc.pthread_create(&th, &attr, &threadFn, null) != 0) {
            libc.exit(42);                 /* couldn't start the thread */
        }
        libc.pthread_join(th);           /* the fault kills the process before this returns */
        pthread_attr_destroy(&attr);
        libc.exit(0);                    /* reached only if BigFrame did NOT fault */
    }
    libc.waitpid(pid, &status, 0);
    return status;
}

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    i64 status;
    printf("Test - stack-clash probing: ");

    /* A 1 MiB frame on the main thread's 8 MiB stack is fine. */
    check(bigFrame() == 0x5A, "1 MiB frame on the main stack works");

    /* The same frame on a 256 KiB thread stack must fault, not return. */
    status = childRunsThread();
    check((status & 0x7F) != 0 && (status & 0x7F) != 0x7F,
            "the child was killed by a signal");
    /* Linux delivers SIGSEGV, macOS SIGBUS for the guard-page hit; both
     * mean the overflow faulted instead of corrupting memory. */
    check((status & 0x7F) == SIGSEGV || (status & 0x7F) == SIGBUS,
            "1 MiB frame on a 256 KiB thread stack faults (SIGSEGV/SIGBUS)");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
