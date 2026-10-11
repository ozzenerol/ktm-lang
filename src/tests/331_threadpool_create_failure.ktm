#include "testhelper.ktm"

import libc, str, thread;

/* ThreadPoolNew ignored pthread_create failing: the handles of the
 * workers that never started were left as uninitialised MAlloc memory
 * and ThreadPoolStop/Release joined them (a crash). ThreadPoolNew(0) gave
 * a pool without workers, so ThreadPoolWait hung once a job was queued.
 * Each case runs in a forked child with a watchdog alarm, so a crash or a
 * hang fails the test instead of the suite. */

extern "c" u32 alarm(u32 seconds);
extern "c" i32 getrlimit(i32 res, u64 *lim);
extern "c" i32 setrlimit(i32 res, u64 *lim);
extern "c" void _exit(i32 code);
extern "c" i32 usleep(u32 usec);

const i64 RLIMIT_AS_LINUX = 9;

void job(void *priv_data, void *argv) {
}

u8 gBuf[4096];

u8 *readProc(u8 *path)
{// /proc files have no size, so FileRead can't be used
    i32 fd = libc.open(path, libc.O_RDONLY);
    if (fd == -1) { return null; }
    i64 n = libc.read(fd, gBuf, sizeof(gBuf) - 1);
    libc.close(fd);
    if (n <= 0) { return null; }
    gBuf[n] = '\0';
    return gBuf;
}

i64 threadsInProcess()
{// The Threads: line of /proc/self/status, -1 if unreadable
    u8 *s = readProc("/proc/self/status");
    if (!s) { return -1; }
    u8 *t = libc.strstr(s, "Threads:");
    if (!t) { return -1; }
    return str.toInt(t + 8);
}

bool onlyMainThread()
{// A joined thread can still be counted for a moment after pthread_join
    for (i64 i = 0; i < 2000; ++i) {
        if (threadsInProcess() == 1) { return true; }
        usleep(1000);
    }
    return false;
}

i64 vmSize()
{// Bytes of address space in use
    u8 *s = readProc("/proc/self/statm");
    if (!s) { return -1; }
    return str.toInt(s) * 4096;
}

i32 childBadCounts() {
    if (thread.poolNew(0) != null) { return 1; }
    if (thread.poolNew(-1) != null) { return 2; }
    if (thread.poolNew(-100) != null) { return 3; }
    thread.poolRelease(null);
    return 0;
}

i32 childCreateFails()
{// Leave room for a few 8 MB thread stacks, not for 64 of them
    u64 lim[2], old[2];
    thread.Pool *pool;
    if (getrlimit(RLIMIT_AS_LINUX, old) != 0) { return 10; }
    i64 used = vmSize();
    if (used <= 0) { return 11; }
    for (i64 room = 0; room <= 64; room += 32) {
        lim[0] = used + room * 1024 * 1024;
        lim[1] = old[1];
        if (setrlimit(RLIMIT_AS_LINUX, lim) != 0) { return 12; }
        pool = thread.poolNew(64);
        if (pool != null) { return 13; }
        /* the workers that did start were stopped and joined */
        if (!onlyMainThread()) { return 14; }
    }
    if (setrlimit(RLIMIT_AS_LINUX, old) != 0) { return 15; }

    /* with the address space back, a pool works */
    pool = thread.poolNew(4);
    if (!pool) { return 16; }
    for (i64 i = 0; i < 20; ++i) { thread.poolEnqueue(pool, null, &job); }
    thread.poolWait(pool);
    if (pool.workerCount != 4) { return 17; }
    thread.poolRelease(pool);
    if (!onlyMainThread()) { return 18; }
    return 0;
}

i32 runInChild(fn() i32 fn_)
{// fn's result, or 100 + the signal that killed it
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
    i32 rc;
    printf("Test - ThreadPoolNew failure handling: ");

    tests++;
    rc = runInChild(&childBadCounts);
    if (rc == 0) { correct++; }
    else { printf("\n  worker_count <= 0 did not give null (%d)\n", rc); }

#ifdef IS_LINUX
    tests++;
    rc = runInChild(&childCreateFails);
    if (rc == 0) { correct++; }
    else { printf("\n  pthread_create failure not handled (%d)\n", rc); }
#endif

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
