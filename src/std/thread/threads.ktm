module thread;
import libc, list, mem;

pub struct Worker {
        libc.pthread_t *th;
        i32 id;
};

pub struct Job;

pub struct Job {
        fn(void *priv_data, void *argv) void callback;
        void *argv;
        Job *next;
};

/* `lock` guards `jobs`, `run`, `active_threads` and `alive_threads`.
    * Workers sleep on `has_work`; ThreadPoolWait sleeps on `no_work`. */
pub struct Pool {
        u64 worker_count,alive_threads,active_threads;
        bool run;
        libc.pthread_cond_t has_work,no_work;
        libc.pthread_mutex_t lock;
        Worker *workers;
        list.List *jobs;
        void *priv_data;
};

pub void poolEnqueue(Pool *pool, void *argv, fn(void *priv_data, void *argv) void callback)
{// Enqueue a job
        if (!pool) { return; }
        Job *job = mem.alloc(sizeof(Job));
        job.callback = callback;
        job.argv = argv;
        job.next = null;
        libc.pthread_mutex_lock(&pool.lock);
        pool.jobs.append(job);
        libc.pthread_cond_signal(&pool.has_work);
        libc.pthread_mutex_unlock(&pool.lock);
}

pub void poolWait(Pool *pool)
{//Wait for all jobs in the queue to be complete
        if (!pool) { return; }
        libc.pthread_mutex_lock(&pool.lock);
        while (!pool.jobs.isEmpty() || pool.active_threads != 0) {
                libc.pthread_cond_wait(&pool.no_work,&pool.lock);
        }
        libc.pthread_mutex_unlock(&pool.lock);
}

static void *threadPoolMain(void *argv)
{// This takes jobs of the queue, calls them and waits for more work.
    // Once the pool is stopped it finishes the queued jobs, then returns.
        Pool *pool = (Pool *)argv;
        Job *job;

        libc.pthread_mutex_lock(&pool.lock);
        pool.alive_threads++;
        while (true) {
                while (pool.run && pool.jobs.isEmpty()) {
                        libc.pthread_cond_wait(&pool.has_work,&pool.lock);
                }
                job = pool.jobs.shift();
                if (!job) {
                        break; /* stopped and the queue is drained */
                }
                pool.active_threads++;
                libc.pthread_mutex_unlock(&pool.lock);

                job.callback(pool.priv_data,job.argv);
                mem.free(job);

                libc.pthread_mutex_lock(&pool.lock);
                pool.active_threads--;
                if (pool.active_threads == 0 && pool.jobs.isEmpty()) {
                        libc.pthread_cond_broadcast(&pool.no_work);
                }
        }
        pool.alive_threads--;
        libc.pthread_mutex_unlock(&pool.lock);
        return null;
}

pub Pool *poolNew(i64 worker_count)
{// Create a pool of worker_count threads; null if worker_count <= 0 or
    // a thread can't be created (the ones already started are stopped)
        if (worker_count <= 0) {
                return null;
        }
        Pool *pool = mem.alloc(sizeof(Pool));
        Worker *workers = mem.alloc(sizeof(Worker) * worker_count);
        i32 err;

        pool.jobs = list.new();
        pool.run = true;
        pool.active_threads = 0;
        pool.alive_threads = 0;
        pool.worker_count = 0; /* counts the started workers, the ones to join */
        pool.workers = workers;
        pool.priv_data = null;

        libc.pthread_cond_init(&pool.no_work);
        libc.pthread_cond_init(&pool.has_work);
        libc.pthread_mutex_init(&pool.lock);

        /* The workers stay joinable so ThreadPoolStop can wait for them */
        for (i64 i = 0; i < worker_count; ++i) {
                Worker *worker = workers + i;
                worker.id = i;
                err = libc.pthread_create(&worker.th,null,&threadPoolMain,pool);
                if (err != 0) {
                        poolRelease(pool);
                        return null;
                }
                pool.worker_count++;
        }
        return pool;
}

pub void poolStop(Pool *pool)
{// Finish every queued job, then stop and join the workers
        if (!pool) { return; }
        libc.pthread_mutex_lock(&pool.lock);
        pool.run = false;
        libc.pthread_cond_broadcast(&pool.has_work);
        libc.pthread_mutex_unlock(&pool.lock);

        for (i64 i = 0; i < pool.worker_count; ++i) {
                libc.pthread_join(pool.workers[i].th);
        }
        pool.worker_count = 0;
}

pub void poolRelease(Pool *pool)
{// Stop the pool (queued jobs still run, see ThreadPoolStop), destroy
    // conditions and locks and free the structures
        if (pool) {
                poolStop(pool);

                libc.pthread_cond_destroy(&pool.has_work);
                libc.pthread_cond_destroy(&pool.no_work);
                libc.pthread_mutex_destroy(&pool.lock);
                /* Only jobs enqueued after ThreadPoolStop can be left */
                pool.jobs.release(&mem.free);

                mem.free(pool.workers);
                mem.free(pool);
        }
}
