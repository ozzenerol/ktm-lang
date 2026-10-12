import list, test, thread;

/* list.shift takes from the front (FIFO with list.append) and list.pop
 * from the back (LIFO with list.append); a single-worker thread.Pool runs
 * jobs in submission order. */

i64 gOrder[8];
i64 gN = 0;

void recordJob(void *priv, void *argv) {
    gOrder[gN++] = (i64)argv;
}

i32 main() {
    printf("Test - ListDeque FIFO order: ");
    i64 correct = 0, tests = 7, i;
    ad list.List<i64> l = list.new<i64>();

    /* An empty list */
    bool okFlag = true;
    if (l.shift(&okFlag) == 0 && !okFlag && l.pop() == 0 && l.len == 0) {
        correct++;
    }

    l.append(1);
    l.append(2);
    l.append(3);
    if (l.shift() == 1 && l.shift() == 2 && l.shift() == 3 && l.len == 0) { correct++; }

    /* Interleaved append / deque keeps FIFO order */
    bool ok = true;
    i64 nextIn = 0, nextOut = 0;
    for (i = 0; i < 50; i++) {
        l.append(nextIn++);
        l.append(nextIn++);
        if (l.shift() != nextOut++) { ok = false; }
    }
    while (l.len) {
        if (l.shift() != nextOut++) { ok = false; }
    }
    if (ok && nextOut == 100 && l.len == 0) { correct++; }

    /* list.pop takes from the back */
    l.append(1);
    l.append(2);
    l.append(3);
    if (l.pop() == 3 && l.len == 2) { correct++; }
    /* list.prepend puts at the front, where list.shift takes from */
    l.prepend(0);
    if (l.shift() == 0 && l.shift() == 1 && l.pop() == 2 && l.len == 0) { correct++; }

    /* One worker: the jobs run in the order they were queued */
    thread.Pool *pool = thread.poolNew(1);
    for (i = 0; i < 8; i++) {
        thread.poolEnqueue(pool, (void *)i, &recordJob);
    }
    thread.poolWait(pool);
    if (gN == 8) { correct++; }
    ok = true;
    for (i = 0; i < 8; i++) {
        if (gOrder[i] != i) { ok = false; }
    }
    if (ok) { correct++; }
    thread.poolRelease(pool);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
