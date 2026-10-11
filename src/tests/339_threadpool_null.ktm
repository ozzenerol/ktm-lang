import test, thread;

/* thread.poolNew returns null for a bad count or when a thread can't be
 * created, but thread.poolEnqueue, thread.poolWait and thread.poolStop then
 * crashed on it; only thread.poolRelease checked. All four ignore null. */

i64 ran = 0;

void job(void *priv, void *argv) {
    ran++;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - ThreadPool with a null pool: ");

    thread.Pool *pool = thread.poolNew(0);
    tests++;
    if (pool == null) { correct++; }

    thread.poolEnqueue(pool, null, &job);
    thread.poolWait(pool);
    thread.poolStop(pool);
    thread.poolRelease(pool);
    tests++;
    if (ran == 0) { correct++; }

    /* A real pool still works */
    pool = thread.poolNew(2);
    thread.poolEnqueue(pool, null, &job);
    thread.poolWait(pool);
    thread.poolRelease(pool);
    tests++;
    if (ran == 1) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
