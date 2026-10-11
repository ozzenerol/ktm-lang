import test;

import libc, thread;

/* ThreadPoolRelease used to hang: the workers slept on a semaphore that
 * shutdown never signalled, and the detached workers were then joined.
 * A hang must fail the test rather than hang the suite, so a watchdog
 * alarm kills the process (the runner then reports it as FAILED). */
extern "c" u32 alarm(u32 seconds);

libc.pthread_mutex_t gLk;
i64 gCount;

void countJob(void *priv_data, void *argv) {
    libc.pthread_mutex_lock(&gLk);
    gCount++;
    libc.pthread_mutex_unlock(&gLk);
}

void slowCountJob(void *priv_data, void *argv) {
    i64 spin = 0;
    for (i64 i = 0; i < 200000; ++i) { spin += i; }
    if (spin < 0) { printf("unreachable\n"); }
    countJob(priv_data, argv);
}

bool submitWaitRelease(i64 workers, i64 jobs) {
    gCount = 0;
    thread.Pool *pool = thread.poolNew(workers);
    for (i64 i = 0; i < jobs; ++i) {
        thread.poolEnqueue(pool, null, &countJob);
    }
    thread.poolWait(pool);
    bool ok = gCount == jobs;
    thread.poolRelease(pool);
    return ok && gCount == jobs;
}

bool releaseWithoutWork() {
    thread.Pool *pool = thread.poolNew(4);
    thread.poolWait(pool); /* nothing queued: returns at once */
    thread.poolRelease(pool);
    return true;
}

bool releaseWithQueuedWork(i64 workers, i64 jobs)
{// Release drains the queue: every queued job still runs
    gCount = 0;
    thread.Pool *pool = thread.poolNew(workers);
    for (i64 i = 0; i < jobs; ++i) {
        thread.poolEnqueue(pool, null, &slowCountJob);
    }
    thread.poolRelease(pool);
    return gCount == jobs;
}

bool stopThenRelease() {
    gCount = 0;
    thread.Pool *pool = thread.poolNew(3);
    for (i64 i = 0; i < 10; ++i) {
        thread.poolEnqueue(pool, null, &countJob);
    }
    thread.poolStop(pool);
    bool ok = gCount == 10;
    thread.poolWait(pool); /* stopped and drained: returns at once */
    thread.poolRelease(pool);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - Thread pool release: ");
    alarm(60);
    libc.pthread_mutex_init(&gLk);

    bool ok = true;
    for (i64 i = 0; i < 20; ++i) {
        if (!submitWaitRelease(4, 50)) { ok = false; }
    }
    tests++; if (ok) { correct++; }
    else { printf("submit/wait/release in a loop miscounted\n"); }

    tests++;
    if (submitWaitRelease(1, 25)) { correct++; }
    else { printf("single worker pool miscounted\n"); }

    ok = true;
    for (i64 i = 0; i < 20; ++i) {
        if (!releaseWithoutWork()) { ok = false; }
    }
    tests++; if (ok) { correct++; }

    ok = true;
    for (i64 i = 0; i < 5; ++i) {
        if (!releaseWithQueuedWork(2, 20)) { ok = false; }
    }
    tests++; if (ok) { correct++; }
    else { printf("release with queued work did not run every job\n"); }

    tests++;
    if (stopThenRelease()) { correct++; }
    else { printf("stop then release miscounted\n"); }

    thread.poolRelease(null);
    tests++; correct++;

    libc.pthread_mutex_destroy(&gLk);
    alarm(0);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
