module thread;
import cstr, libc, list, mem, proc, rt;

pub struct Worker {
        libc.pthread_t *th;
        i32 id;
};

pub struct Job;

pub struct Job {
        fn(void *privData, void *argv) void callback;
        fn(void *) void body;   /* Pool.run's, else callback's */
        void *argv;
        Job *next;
};

/* Threads that run the functions given to them, in order:
 *     scoped thread.Pool *p = thread.pool(4);
 *     for (i64 i = 0; i < 100; i++) { p.run(&work, &items[i]); }
 *     p.wait();
 * `lock` guards `jobs`, `running`, `activeThreads`, `aliveThreads` and the
 * first error. Workers sleep on `hasWork`; wait() sleeps on `noWork`. */
pub struct Pool {
        u64 workerCount,aliveThreads,activeThreads;
        bool running;
        bool failed;    /* a Pool.run function threw: err, for wait() */
        Error err;
        libc.pthread_cond_t hasWork,noWork;
        libc.pthread_mutex_t lock;
        Worker *workers;
        list.List<Job *> jobs;
        void *privData;
};

/* Queues callback(pool.privData, argv). Old name, kept for one minor:
 * use Pool.run */
pub void poolEnqueue(Pool *pool, void *argv, fn(void *privData, void *argv) void callback)
{
        if (!pool) { return; }
        Job *job = mem.alloc(sizeof(Job));
        job.callback = callback;
        job.body = null;
        job.argv = argv;
        job.next = null;
        libc.pthread_mutex_lock(&pool.lock);
        pool.jobs.append(job);
        libc.pthread_cond_signal(&pool.hasWork);
        libc.pthread_mutex_unlock(&pool.lock);
}

/* Waits until every queued job is done. Old name, kept for one minor:
 * use Pool.wait */
pub void poolWait(Pool *pool)
{
        if (!pool) { return; }
        libc.pthread_mutex_lock(&pool.lock);
        while (!!pool.jobs.len || pool.activeThreads != 0) {
                libc.pthread_cond_wait(&pool.noWork,&pool.lock);
        }
        libc.pthread_mutex_unlock(&pool.lock);
}

static void *threadPoolMain(void *argv)
{// This takes jobs of the queue, calls them and waits for more work.
    // Once the pool is stopped it finishes the queued jobs, then returns.
        Pool *pool = (Pool *)argv;
        Job *job;

        rt.__ktmCrashThread();

        libc.pthread_mutex_lock(&pool.lock);
        pool.aliveThreads++;
        while (true) {
                while (pool.running && !pool.jobs.len) {
                        libc.pthread_cond_wait(&pool.hasWork,&pool.lock);
                }
                job = pool.jobs.shift();
                if (!job) {
                        break; /* stopped and the queue is drained */
                }
                pool.activeThreads++;
                libc.pthread_mutex_unlock(&pool.lock);

                if (job.body) {
                        try {
                                job.body(job.argv);
                        } catch (Error e) {
                                libc.pthread_mutex_lock(&pool.lock);
                                if (!pool.failed) {
                                        pool.failed = true;
                                        pool.err = e;
                                        pool.err.message = cstr.dup(e.message);
                                }
                                libc.pthread_mutex_unlock(&pool.lock);
                        }
                } else {
                        job.callback(pool.privData,job.argv);
                }
                mem.free(job);

                libc.pthread_mutex_lock(&pool.lock);
                pool.activeThreads--;
                if (pool.activeThreads == 0 && !pool.jobs.len) {
                        libc.pthread_cond_broadcast(&pool.noWork);
                }
        }
        pool.aliveThreads--;
        libc.pthread_mutex_unlock(&pool.lock);
        return null;
}

/* A pool of workerCount threads; null if workerCount <= 0 or a thread
 * can't be created (the ones already started are stopped). Old name,
 * kept for one minor: use thread.pool */
pub Pool *poolNew(i64 workerCount)
{
        if (workerCount <= 0) {
                return null;
        }
        Pool *pool = mem.alloc(sizeof(Pool));
        Worker *workers = mem.alloc(sizeof(Worker) * workerCount);
        i32 err;

        pool.jobs = list.new<Job *>();
        pool.running = true;
        pool.activeThreads = 0;
        pool.aliveThreads = 0;
        pool.workerCount = 0; /* counts the started workers, the ones to join */
        pool.workers = workers;
        pool.privData = null;
        pool.failed = false;

        libc.pthread_cond_init(&pool.noWork);
        libc.pthread_cond_init(&pool.hasWork);
        libc.pthread_mutex_init(&pool.lock);

        /* The workers stay joinable so thread.poolStop can wait for them */
        for (i64 i = 0; i < workerCount; ++i) {
                Worker *worker = workers + i;
                worker.id = i;
                err = libc.pthread_create(&worker.th,null,&threadPoolMain,pool);
                if (err != 0) {
                        poolRelease(pool);
                        return null;
                }
                pool.workerCount++;
        }
        return pool;
}

/* Finishes every queued job, then stops and joins the workers. Old
 * name, kept for one minor: use Pool.free */
pub void poolStop(Pool *pool)
{
        if (!pool) { return; }
        libc.pthread_mutex_lock(&pool.lock);
        pool.running = false;
        libc.pthread_cond_broadcast(&pool.hasWork);
        libc.pthread_mutex_unlock(&pool.lock);

        for (i64 i = 0; i < pool.workerCount; ++i) {
                libc.pthread_join(pool.workers[i].th);
        }
        pool.workerCount = 0;
}

/* Stops the pool (queued jobs still run, see thread.poolStop) and frees
 * it. Old name, kept for one minor: use Pool.free */
pub void poolRelease(Pool *pool)
{
        if (pool) {
                poolStop(pool);

                libc.pthread_cond_destroy(&pool.hasWork);
                libc.pthread_cond_destroy(&pool.noWork);
                libc.pthread_mutex_destroy(&pool.lock);
                /* Only jobs enqueued after thread.poolStop can be left */
                for (auto n = pool.jobs.head; n; n = n.next) { mem.free(n.value); }
                pool.jobs.free();

                if (pool.failed) { mem.free(pool.err.message); }
                mem.free(pool.workers);
                mem.free(pool);
        }
}

/* A Pool of n threads (n <= 0: one per core). free() stops it: the
 * functions already given still run. Throws ERR_THREAD when a thread
 * can't be started. */
pub Pool *pool(i64 n = 0) {
        if (n <= 0) { n = proc.cpuCount(); }
        if (n < 1) { n = 1; }
        Pool *p = poolNew(n);
        if (!p) { throw(ERR_THREAD, "thread.pool: can't start %d threads", n); }
        return p;
}

/* Runs f(arg) on one of the pool's threads, after the functions given
 * before it are started. Keep arg alive until wait() returns. What f
 * throws, wait() throws. Throws ERR_THREAD once the pool is stopped. */
pub void Pool.run<T>(fn(T *) void f, T *arg) {
        Job *job = mem.alloc(sizeof(Job));
        job.callback = null;
        job.body = (fn(void *) void)f;
        job.argv = (void *)arg;
        job.next = null;
        libc.pthread_mutex_lock(&self.lock);
        if (!self.running) {
                libc.pthread_mutex_unlock(&self.lock);
                mem.free(job);
                throw(ERR_THREAD, "thread.Pool.run: the pool is stopped");
        }
        self.jobs.append(job);
        libc.pthread_cond_signal(&self.hasWork);
        libc.pthread_mutex_unlock(&self.lock);
}

/* Waits until every function given has returned. Throws the first error
 * one threw since the last wait(), with its code, message, file and line. */
pub void Pool.wait() {
        poolWait(self);
        libc.pthread_mutex_lock(&self.lock);
        bool failed = self.failed;
        Error e = self.err;
        self.failed = false;
        libc.pthread_mutex_unlock(&self.lock);
        if (failed) {
                defer mem.free(e.message);
                throw(e);
        }
}

/* Finishes the functions given, stops the threads and frees the pool */
pub void Pool.free() {
        poolRelease(self);
}
