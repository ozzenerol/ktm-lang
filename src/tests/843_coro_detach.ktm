import coro, libc, test;

/* An fd a coroutine waited on stayed registered with its thread's loop
 * (epoll, kqueue). Handed to another thread's loop and closed there, the
 * first loop still took the fd's number as registered, so a new fd with
 * that number was never waited for: coro.wait on it timed out with the
 * data there. coro.detach(fd) takes it out of the loop first (as a pool
 * of connections that threads share needs). */

i32 gHanded = -1;        /* the fd thread A hands to thread B */
i64 gRead = 0;           /* what B read from it */

void *bBody(void *arg) {
    u8 buf[8];
    if (coro.wait(gHanded, libc.POLLIN, 2000) & libc.POLLIN) {
        gRead = libc.read(gHanded, buf, 8);
    }
    coro.close(gHanded);
    return null;
}

/* thread B: a loop of its own waits on the fd, reads, closes it */
void *threadB(void *arg) {
    coro.go(&bBody);
    coro.run();
    return null;
}

i64 gFirst = 0, gAfter = 0;
bool gReused = false;

void *aBody(void *arg) {
    i32 p[2], q[2];
    u8 buf[8];
    libc.pipe(p);
    libc.write(p[1], "a", 1);
    gFirst = coro.wait(p[0], libc.POLLIN, 2000);
    libc.read(p[0], buf, 8);
    coro.detach(p[0]);
    gHanded = p[0];
    libc.write(p[1], "b", 1);
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &threadB);
    libc.pthread_join(th);
    libc.close(p[1]);
    /* the same numbers again, a new pipe */
    libc.pipe(q);
    gReused = q[0] == p[0];
    libc.write(q[1], "c", 1);
    gAfter = coro.wait(q[0], libc.POLLIN, 1000);
    libc.close(q[0]);
    libc.close(q[1]);
    return null;
}

/* thread A, with the backend asked for (null: the default) */
void *threadA(void *backend) {
    if (backend) { libc.setenv("KTM_CORO_BACKEND", backend, 1); }
    coro.go(&aBody);
    coro.run();
    return null;
}

bool handoff(u8 *backend) {
    gFirst = 0;
    gAfter = 0;
    gRead = 0;
    gReused = false;
    libc.pthread_t *th;
    libc.pthread_create(&th, null, &threadA, backend);
    libc.pthread_join(th);
    bool ok = (gFirst & libc.POLLIN) && gRead == 1 && gReused && (gAfter & libc.POLLIN);
    if (!ok) {
        printf("\n  %s: first %d, B read %d, reused %d, after %d\n", backend ? backend : "default",
               gFirst, gRead, gReused, gAfter);
    }
    return ok;
}

/* detach while another coroutine of the loop waits on the fd */
i32 gBusy[2];
i64 gWoke = 0;
bool gThrew = false;

void *waiter(void *arg) {
    gWoke = coro.wait(gBusy[0], libc.POLLIN, 2000);
    return null;
}

void *detacher(void *arg) {
    try {
        coro.detach(gBusy[0]);
    } catch (Error e) {
        gThrew = e.code == coro.ERR_LOOP;
    }
    libc.write(gBusy[1], "d", 1);
    return null;
}

i32 main() {
    printf("Test - coro.detach: ");
    i64 correct = 0, tests = 0;

    tests++;
    if (handoff(null)) { correct++; }
#ifdef IS_LINUX
    tests++;
    if (handoff("epoll")) { correct++; }
#endif
    tests++;
    if (handoff("poll")) { correct++; }
    libc.unsetenv("KTM_CORO_BACKEND");

    /* not while a coroutine of the loop waits on it */
    tests++;
    libc.pipe(gBusy);
    coro.go(&waiter);
    coro.go(&detacher);
    coro.run();
    libc.close(gBusy[0]);
    libc.close(gBusy[1]);
    if (gThrew && (gWoke & libc.POLLIN)) { correct++; } else { printf("\n  threw %d, woke %d\n", gThrew, gWoke); }

    /* an fd the loop never saw, and no loop at all: nothing to do */
    tests++;
    coro.detach(0);
    correct++;

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
