import libc, mem, test, thread;

const i64 THREAD_COUNT = 5;

struct ThreadArgs {
    libc.pthread_mutex_t *lk;
    libc.pthread_t *th;
    i64 id;
    i64 *ptr;
};

void *func(ThreadArgs *args) {
    libc.pthread_mutex_lock(args.lk);
    i64 x = *args.ptr;
    x++;
    *args.ptr = x;
    libc.pthread_mutex_unlock(args.lk);
    return null;
}

bool pthreadTest()
{// this is using libc, with a little bit of ktm magic 
    i64 threads[THREAD_COUNT];
    i64 correct = 0, tests = 1;
    ThreadArgs threadArgs[THREAD_COUNT];

    libc.pthread_mutex_t lk;
    libc.pthread_mutex_init(&lk);

    i64 x = 0;
    for (i64 i = 0; i < THREAD_COUNT; ++i) {
        ThreadArgs *args = &threadArgs[i];
        args.lk = &lk;
        args.ptr = &x;
        args.id = i;
        threads[i] = libc.pthread_create(&args.th,,(fn(void *) void *)(&func), &threadArgs[i]);
    }

    for (i64 i = 0; i < THREAD_COUNT; ++i) {
        ThreadArgs *args = &threadArgs[i];
        libc.pthread_join(args.th);
    }

    /* this proves that 1: thread join works, 2: the mutex lock works */
    libc.pthread_mutex_destroy(&lk);
    return x == THREAD_COUNT;
}

void poolFunc(void *priv_data, ThreadArgs *args) {
    libc.pthread_mutex_lock(args.lk);
    i64 x = *args.ptr;
    x++;
    *args.ptr = x;
    libc.pthread_mutex_unlock(args.lk);
}

bool threadPoolTest() {
    i64 x = 0;
    ThreadArgs *arg = mem.alloc(sizeof(ThreadArgs));
    thread.Pool *pool = thread.poolNew(10);

    libc.pthread_mutex_t lk;
    libc.pthread_mutex_init(&lk);

    arg.id = 1;
    arg.ptr = &x;
    arg.lk = &lk;
    arg.th = null;
    for (i64 i = 0; i < 10; ++i) {
        thread.poolEnqueue(pool,arg,(fn(void *, void *) void)(&poolFunc));
    }
    thread.poolWait(pool);
    libc.pthread_mutex_destroy(&lk);
    thread.poolRelease(pool);
    mem.free(arg);
    return x == 10;
}

i32 main() {
    i32 correct = 0, tests = 2;
    printf("Test - Simple Threading: \n");
    if (pthreadTest()) { correct++; }
    if (threadPoolTest()) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
