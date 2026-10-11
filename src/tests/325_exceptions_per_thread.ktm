import fs, libc, mem, proc, str, test;

/* The catch chain was one process-wide global, so a throw in one thread
 * could longjmp onto another thread's stack ("stack smashing detected").
 * Each thread now has its own catch chain. */

enum Err { again, main };

const i64 WORKERS = 8;
const i64 ROUNDS = 5000;

#define UNCAUGHT_SRC "__325_uncaught.ktm"
#define UNCAUGHT_BIN "./__325_uncaught.out"
#define UNCAUGHT_LOG "__325_uncaught.log"

struct Work {
    libc.pthread_t *th;
    i64 id;
    i64 inner;
    i64 outer;
    i64 wrong;
};

extern "c" i32 usleep(u32 usec);

/* Workers wait here until all of them are running, so none has exited
 * (and the next thread reused its state) while the others throw. */
libc.pthread_mutex_t startedLock;
i64 started;

void waitForAllWorkers() {
    i64 n;
    libc.pthread_mutex_lock(&startedLock);
    started++;
    libc.pthread_mutex_unlock(&startedLock);
    do {
        usleep(100);
        libc.pthread_mutex_lock(&startedLock);
        n = started;
        libc.pthread_mutex_unlock(&startedLock);
    } while (n < WORKERS);
}

void raiseFn(i64 v) {
    throw(v);
}

void *worker(Work *w) {
    i64 i, v;
    waitForAllWorkers();
    for (i = 0; i < ROUNDS; i++) {
        v = w.id * 1000000 + i;
        try {
            try {
                raiseFn(v);
            } catch (Error e) {
                if (e.code == v) { w.inner++; }
                else { w.wrong++; }
                throw(v + 1);
            }
        } catch (Error e) {
            if (e.code == v + 1) { w.outer++; }
            else { w.wrong++; }
        }
    }
    return null;
}

bool uncaughtInThreadExits() {
    u8 *src = "
import libc;

enum Err { thr };

void *T(void *a)
{
  throw(Err.thr);
  return null;
}

void main()
{
  libc.pthread_t *t;
  try {
    libc.pthread_create(&t, null, &T, null);
    libc.pthread_join(t);
    printf(\"joined\\n\");
  } catch {
    printf(\"main caught\\n\");
  }
}
";
    bool ok = false;
    if (!fs.writeAll(UNCAUGHT_SRC, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         UNCAUGHT_SRC " -o " UNCAUGHT_BIN) == 0) {
        i64 res = proc.shell(UNCAUGHT_BIN " > " UNCAUGHT_LOG " 2>&1");
        u8 *out = fs.readAll(UNCAUGHT_LOG);
        if (out) {
            ok = res == 1 && libc.strstr(out, "uncaught error") != null &&
                     libc.strstr(out, "main caught") == null &&
                     libc.strstr(out, "joined") == null;
            if (!ok) { printf("exit %d: %s\n", res, out); }
            mem.free(out);
        }
    }
    fs.remove(UNCAUGHT_SRC);
    fs.remove(UNCAUGHT_BIN);
    fs.remove(UNCAUGHT_LOG);
    return ok;
}

i32 main() {
    printf("Test - each thread has its own catch chain: ");
    i64 correct = 0, tests = 5, i;
    Work work[WORKERS];
    bool counts = true;
    i64 after = 0;

    mem.set(work, 0, sizeof(work));
    libc.pthread_mutex_init(&startedLock);
    started = 0;
    try {
        for (i = 0; i < WORKERS; i++) {
            work[i].id = i + 1;
            libc.pthread_create(&work[i].th, null, (fn(void *) void *)(&worker), &work[i]);
        }
        for (i = 0; i < WORKERS; i++) {
            libc.pthread_join(work[i].th);
        }
        /* Still inside this thread's try: the workers never touched it. */
        raiseFn(Err.main);
    } catch (Error e) {
        after = e.code;
    }
    if (after == Err.main) { correct++; }

    for (i = 0; i < WORKERS; i++) {
        if (work[i].inner != ROUNDS || work[i].outer != ROUNDS ||
                work[i].wrong != 0) {
            counts = false;
            printf("worker %d: inner %d outer %d wrong %d\n", i, work[i].inner,
                    work[i].outer, work[i].wrong);
        }
    }
    if (counts) { correct++; }
    /* the workers' throws never reached this thread's catches, nor the
     * other way round: a throw here still lands here */
    i64 back = 0;
    try {
        raiseFn(42);
    } catch (Error e) {
        back = e.code;
    }
    if (back == 42) { correct++; }

    try {
        raiseFn(Err.again);
    } catch (Error e) {
        if (e.code == Err.again) { correct++; }
    }

    if (uncaughtInThreadExits()) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
