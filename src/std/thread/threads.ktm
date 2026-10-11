module thread;
import libc, list, mem;

#ifdef IS_MACOS
#define __MUTEX_LOCK_PADDING__   56
#define __MUTEX_LOCK_ATTR_SIZE__ 8

#define __THREAD_COND_SIZE__     40
#define __THREAD_CONDATTR_SIZE__ 8
#define __THREAD_SIZE__          8176
#define __THREAD_ATTR_SIZE__     56

/* MACOS definitions */
struct pthread_mutex_t {
        i64 __sig;
        i8 __opaque[__MUTEX_LOCK_PADDING__];
};

struct pthread_mutexattr_t {
        i64 __sig;
        i8 __opaque[__MUTEX_LOCK_ATTR_SIZE__];
};

struct pthread_cond_t {
        i64 __sig;
        i8 __opaque[__THREAD_COND_SIZE__];
};

struct pthread_condattr_t {
        i64 __sig;
        i8 __opaque[__THREAD_CONDATTR_SIZE__];
};

struct pthread_handler_rec;
struct pthread_handler_rec {
        void (*__routine)(void *__arg); // Routine to call
        void *__arg;                  // Argument to pass
        pthread_handler_rec *__next;
};

struct pthread_attr_t
{  
        i64 __sig;
        i8 __opaque[__THREAD_ATTR_SIZE__];
};

struct pthread_t {
        i64 __sig;
        pthread_handler_rec  *__cleanup_stack;
        i8 __opaque[__THREAD_SIZE__];
};

#elifdef IS_LINUX /* Linux definitions, this needs to be auto generated */

/* glibc's bits/pthreadtypes-arch.h: the sizes differ between x86_64 and aarch64 */
#ifdef IS_X86_64
#define __SIZEOF_PTHREAD_MUTEX_T   40
#define __SIZEOF_PTHREAD_ATTR_T    56
#define __SIZEOF_PTHREAD_RWLOCK_T  56
#define __SIZEOF_PTHREAD_BARRIER_T 32

#define __SIZEOF_PTHREAD_MUTEXATTR_T   4
#define __SIZEOF_PTHREAD_COND_T        48
#define __SIZEOF_PTHREAD_CONDATTR_T    4
#define __SIZEOF_PTHREAD_RWLOCKATTR_T  8
#define __SIZEOF_PTHREAD_BARRIERATTR_T 4
#else /* aarch64 */
#define __SIZEOF_PTHREAD_MUTEX_T   48
#define __SIZEOF_PTHREAD_ATTR_T    64
#define __SIZEOF_PTHREAD_RWLOCK_T  56
#define __SIZEOF_PTHREAD_BARRIER_T 32

#define __SIZEOF_PTHREAD_MUTEXATTR_T   8
#define __SIZEOF_PTHREAD_COND_T        48
#define __SIZEOF_PTHREAD_CONDATTR_T    8
#define __SIZEOF_PTHREAD_RWLOCKATTR_T  8
#define __SIZEOF_PTHREAD_BARRIERATTR_T 8
#endif

union pthread_mutexattr_t {
        i8  __size[__SIZEOF_PTHREAD_MUTEXATTR_T];
        i32 __align;
};

union pthread_condattr_t {
        i8  __size[__SIZEOF_PTHREAD_CONDATTR_T];
        i32 __align;
};

union pthread_attr_t {
        i8  __size[__SIZEOF_PTHREAD_ATTR_T];
        i64 __align;
};


/* this is the spiritual equivilent of typedef  */ 
pub struct pthread_t {
        u64 __padding;
};

struct __pthread_list_t;
struct __pthread_list_t {
        __pthread_list_t *__prev;
        __pthread_list_t *__next;
};

struct __pthread_mutex_s {
        i32 __lock;
        u32 __count;
        i32 __owner;
        u32 __nusers;
        i32 __kind;
        i16 __spins;
        i16 __elision;
        __pthread_list_t __list;
};

union pthread_mutex_t {
        __pthread_mutex_s __data;
        i8  __size[__SIZEOF_PTHREAD_MUTEX_T];
        i64 __align;
};

union __atomic_wide_counter {
        u64 __value64;
        struct
        {
                u32 __low;
                u32 __high;
        };
};

struct __pthread_cond_s {
        __atomic_wide_counter __wseg;
        __atomic_wide_counter __g1_start;
        u32 __g_regs[2];
        u32 __g_size[2];
        u32 __g1_orig_size;
        u32 __wrefs;
        u32 __g_signals[2];
};

union pthread_cond_t {
        __pthread_cond_s __data;
        i8  __size[__SIZEOF_PTHREAD_COND_T];
        i64 __align;
};
#endif

pub struct ThreadWorker {
        pthread_t *th;
        i32 id;
};

pub struct ThreadWorkerJob;

pub struct ThreadWorkerJob {
        void (*callback)(void *priv_data, void *argv);
        void *argv;
        ThreadWorkerJob *next;
};

/* `lock` guards `jobs`, `run`, `active_threads` and `alive_threads`.
    * Workers sleep on `has_work`; ThreadPoolWait sleeps on `no_work`. */
pub struct ThreadPool {
        u64 worker_count,alive_threads,active_threads;
        bool run;
        pthread_cond_t has_work,no_work;
        pthread_mutex_t lock;
        ThreadWorker *workers;
        List *jobs;
        void *priv_data;
};

pub void poolEnqueue(ThreadPool *pool, void *argv, fn(void *priv_data, void *argv) void callback)
{// Enqueue a job
        if (!pool) { return; }
        ThreadWorkerJob *job = mem.alloc(sizeof(ThreadWorkerJob));
        job.callback = callback;
        job.argv = argv;
        job.next = null;
        libc.pthread_mutex_lock(&pool.lock);
        pool.jobs.append(job);
        libc.pthread_cond_signal(&pool.has_work);
        libc.pthread_mutex_unlock(&pool.lock);
}

pub void poolWait(ThreadPool *pool)
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
        ThreadPool *pool = (ThreadPool *)argv;
        ThreadWorkerJob *job;

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

pub ThreadPool *poolNew(i64 worker_count)
{// Create a pool of worker_count threads; null if worker_count <= 0 or
    // a thread can't be created (the ones already started are stopped)
        if (worker_count <= 0) {
                return null;
        }
        ThreadPool *pool = mem.alloc(sizeof(ThreadPool));
        ThreadWorker *workers = mem.alloc(sizeof(ThreadWorker) * worker_count);
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
                ThreadWorker *worker = workers + i;
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

pub void poolStop(ThreadPool *pool)
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

pub void poolRelease(ThreadPool *pool)
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
