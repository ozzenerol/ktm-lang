module thread;
import libc, list, mem, rt;

pub struct Worker {
        libc.pthread_t *th;
        i32 id;
};

pub struct Job;

pub struct Job {
        fn(void *privData, void *argv) void callback;
        void *argv;
        Job *next;
};

/* `lock` guards `jobs`, `run`, `active_threads` and `alive_threads`.
    * Workers sleep on `has_work`; thread.poolWait sleeps on `no_work`. */
pub struct Pool {
        u64 workerCount,aliveThreads,activeThreads;
        bool run;
        libc.pthread_cond_t hasWork,noWork;
        libc.pthread_mutex_t lock;
        Worker *workers;
        list.List<Job *> jobs;
        void *privData;
};

pub void poolEnqueue(Pool *pool, void *argv, fn(void *privData, void *argv) void callback)
{// Enqueue a job
        if (!pool) { return; }
        Job *job = mem.alloc(sizeof(Job));
        job.callback = callback;
        job.argv = argv;
        job.next = null;
        libc.pthread_mutex_lock(&pool.lock);
        pool.jobs.append(job);
        libc.pthread_cond_signal(&pool.hasWork);
        libc.pthread_mutex_unlock(&pool.lock);
}

pub void poolWait(Pool *pool)
{//Wait for all jobs in the queue to be complete
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
                while (pool.run && !pool.jobs.len) {
                        libc.pthread_cond_wait(&pool.hasWork,&pool.lock);
                }
                job = pool.jobs.shift();
                if (!job) {
                        break; /* stopped and the queue is drained */
                }
                pool.activeThreads++;
                libc.pthread_mutex_unlock(&pool.lock);

                job.callback(pool.privData,job.argv);
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

pub Pool *poolNew(i64 workerCount)
{// Create a pool of worker_count threads; null if worker_count <= 0 or
    // a thread can't be created (the ones already started are stopped)
        if (workerCount <= 0) {
                return null;
        }
        Pool *pool = mem.alloc(sizeof(Pool));
        Worker *workers = mem.alloc(sizeof(Worker) * workerCount);
        i32 err;

        pool.jobs = list.new<Job *>();
        pool.run = true;
        pool.activeThreads = 0;
        pool.aliveThreads = 0;
        pool.workerCount = 0; /* counts the started workers, the ones to join */
        pool.workers = workers;
        pool.privData = null;

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

pub void poolStop(Pool *pool)
{// Finish every queued job, then stop and join the workers
        if (!pool) { return; }
        libc.pthread_mutex_lock(&pool.lock);
        pool.run = false;
        libc.pthread_cond_broadcast(&pool.hasWork);
        libc.pthread_mutex_unlock(&pool.lock);

        for (i64 i = 0; i < pool.workerCount; ++i) {
                libc.pthread_join(pool.workers[i].th);
        }
        pool.workerCount = 0;
}

pub void poolRelease(Pool *pool)
{// Stop the pool (queued jobs still run, see thread.poolStop), destroy
    // conditions and locks and free the structures
        if (pool) {
                poolStop(pool);

                libc.pthread_cond_destroy(&pool.hasWork);
                libc.pthread_cond_destroy(&pool.noWork);
                libc.pthread_mutex_destroy(&pool.lock);
                /* Only jobs enqueued after thread.poolStop can be left */
                for (auto n = pool.jobs.head; n; n = n.next) { mem.free(n.value); }
                pool.jobs.free();

                mem.free(pool.workers);
                mem.free(pool);
        }
}
