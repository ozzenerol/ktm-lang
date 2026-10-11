import test;

import list, thread;

/* ListDeque takes from the front (FIFO with ListAppend) and ListPop
 * from the back (LIFO with ListAppend); a single-worker ThreadPool runs
 * jobs in submission order. */

i64 gOrder[8];
i64 gN = 0;

void recordJob(void *priv, void *argv) {
    gOrder[gN++] = (i64)argv;
}

i32 main() {
    printf("Test - ListDeque FIFO order: ");
    i64 correct = 0, tests = 7, i;
    list.List *l = list.new();

    /* Empty and null lists */
    if (l.shift() == null && l.pop() == null && list.List.shift(null) == null) {
        correct++;
    }

    l.append((void *)1);
    l.append((void *)2);
    l.append((void *)3);
    if ((i64)l.shift() == 1 && (i64)l.shift() == 2 &&
            (i64)l.shift() == 3 && l.shift() == null && l.isEmpty()) { correct++; }

    /* Interleaved append / deque keeps FIFO order */
    bool ok = true;
    i64 nextIn = 0, nextOut = 0;
    for (i = 0; i < 50; i++) {
        l.append((void *)nextIn++);
        l.append((void *)nextIn++);
        if ((i64)l.shift() != nextOut++) { ok = false; }
    }
    while (!l.isEmpty()) {
        if ((i64)l.shift() != nextOut++) { ok = false; }
    }
    if (ok && nextOut == 100 && l.count() == 0) { correct++; }

    /* ListPop takes from the back */
    l.append((void *)1);
    l.append((void *)2);
    l.append((void *)3);
    if ((i64)l.pop() == 3 && l.count() == 2) { correct++; }
    /* ListPrepend puts at the front, where ListDeque takes from */
    l.prepend((void *)0);
    if ((i64)l.shift() == 0 && (i64)l.shift() == 1 &&
            (i64)l.pop() == 2 && l.isEmpty()) { correct++; }
    l.release(null);

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
