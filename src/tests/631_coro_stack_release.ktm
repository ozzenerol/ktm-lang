import coro, cstr, fs, libc, str, test;

/* A released coroutine stack in the thread's cache gives its pages back
 * (v0.12.0) once it is behind the 4 newest cached stacks: Linux
 * madvise(MADV_DONTNEED), macOS a fresh mapping. The resident size falls
 * by about what the coroutine had touched, and the stack, taken from the
 * cache again, still runs a coroutine correctly. The 4 newest keep their
 * pages, so a spawn/release churn doesn't pay for it. */

extern "c" i32 getpid();

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

#ifdef IS_MACOS
/* task_info(MACH_TASK_BASIC_INFO): no fork, unlike ps */
struct TaskBasicInfo {
    u64 virtualSize, residentSize, residentSizeMax;
    i32 rest[6];
};
extern "c" u32 mach_task_self_;
extern "c" i32 task_info(u32 task, i32 flavor, TaskBasicInfo *info, u32 *count);
#endif

extern "c" i32 mincore(void *addr, u64 len, u8 *vec);

/* The resident size of this process in KiB */
i64 rssKiB() {
#ifdef IS_MACOS
    TaskBasicInfo info;
    u32 count = sizeof(TaskBasicInfo) / 4;
    if (task_info(mach_task_self_, 20, &info, &count) != 0) { return -1; }
    return (i64)(info.residentSize / 1024);
#else
    String s = fs.readAll("/proc/self/statm");
    defer s.free();
    u8[] size, rest;
    if (!s.ptr || !str.splitFirst(s, " ", &size, &rest)) { return -1; }
    return cstr.toInt(rest.ptr) * libc.getpagesize() / 1024;
#endif
}

/* KiB of a stack mapping that are resident, by mincore(2) */
i64 residentKiB(void *base, i64 len) {
    i64 page = libc.getpagesize();
    u8 vec[1024];
    i64 n = len / page, kib = 0;
    if (n > 1024 || mincore(base, len, vec) != 0) { return -1; }
    for (i64 i = 0; i < n; i++) {
        if (vec[i] & 1) { kib += page / 1024; }
    }
    return kib;
}

const i64 DEEP = 200 * 1024;

/* Touch DEEP bytes of the stack, every 1 KiB, and add them up */
void *deep(void *arg) {
    u8 buf[DEEP];
    i64 seed = (i64)arg, sum = 0;
    for (i64 i = 0; i < DEEP; i += 1024) { buf[i] = (u8)(seed + i / 1024); }
    for (i64 i = 0; i < DEEP; i += 1024) { sum += buf[i]; }
    return (void *)sum;
}

i64 expected(i64 seed) {
    i64 sum = 0;
    for (i64 i = 0; i < DEEP; i += 1024) { sum += (u8)(seed + i / 1024); }
    return sum;
}

void *shallow(void *arg) { return arg; }

const i64 BIG = 512 * 1024;     /* the deep coroutines' stack size */
const i64 SMALL = 64 * 1024;    /* a size the cache keeps apart */

i32 main() {
    printf("Test - coro stack release: ");

    /* four shallow ones (of a size the cache keeps apart), made first so
     * their stacks are resident before the measuring starts */
    coro.Coroutine *s[4];
    for (i64 i = 0; i < 4; i++) {
        s[i] = coro.spawn(&shallow, (void *)i, SMALL);
        coro.next(s[i]);
    }

    /* one deep coroutine, released: the newest in the cache, still resident */
    coro.Coroutine *c = coro.spawn(&deep, (void *)1, BIG);
    check((i64)coro.next(c) == expected(1), "the deep coroutine's result");
    void *base = c.stackBase;
    i64 len = c.stackLen;
    i64 used = residentKiB(base, len);
    check(used >= 200, "the deep coroutine's stack is resident");
    i64 before = rssKiB();
    coro.release(c);
    check(residentKiB(base, len) == used, "the newest cached stack keeps its pages");

    /* releasing the four shallow ones pushes it behind the 4 newest */
    for (i64 i = 0; i < 4; i++) { coro.release(s[i]); }
    i64 trimmed = rssKiB();
    i64 kept = residentKiB(base, len);
    check(kept >= 0 && kept <= 2 * libc.getpagesize() / 1024, "behind the 4 newest, only its top 2 pages stay");
    check(before - trimmed >= 100, "the resident size fell by at least half of what it had touched");

    /* the trimmed stack, from the cache again, still works */
    c = coro.spawn(&deep, (void *)7, BIG);
    check(c.stackBase == base, "the deep stack came from the cache");
    check((i64)coro.next(c) == expected(7), "a coroutine on the reused stack gives the right result");
    coro.release(c);

    /* a burst of 8 deep ones: the 4 oldest give their pages back */
    coro.Coroutine *b[8];
    for (i64 i = 0; i < 8; i++) {
        b[i] = coro.spawn(&deep, (void *)(i + 10), BIG);
        check((i64)coro.next(b[i]) == expected(i + 10), "a deep coroutine of the burst");
    }
    i64 held = rssKiB();
    for (i64 i = 0; i < 8; i++) { coro.release(b[i]); }
    i64 after = rssKiB();
    check(held - after >= 4 * 100, "a burst gives back the pages of all but the 4 newest");
    for (i64 i = 0; i < 8; i++) {
        b[i] = coro.spawn(&deep, (void *)(i + 20), BIG);
        check((i64)coro.next(b[i]) == expected(i + 20), "reused stacks of the burst work");
    }
    for (i64 i = 0; i < 8; i++) { coro.release(b[i]); }

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
