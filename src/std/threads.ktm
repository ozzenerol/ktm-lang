#include "./memory.ktm"
#include "./list.ktm"

#ifdef IS_MACOS
#define __MUTEX_LOCK_PADDING__   56
#define __MUTEX_LOCK_ATTR_SIZE__ 8

#define __THREAD_COND_SIZE__     40
#define __THREAD_CONDATTR_SIZE__ 8
#define __THREAD_SIZE__          8176
#define __THREAD_ATTR_SIZE__     56

/* MACOS definitions */
class pthread_mutex_t 
{
  I64 __sig;
  I8 __opaque[__MUTEX_LOCK_PADDING__];
};

class pthread_mutexattr_t
{
  I64 __sig;
  I8 __opaque[__MUTEX_LOCK_ATTR_SIZE__];
};

class pthread_cond_t
{
  I64 __sig;
  I8 __opaque[__THREAD_COND_SIZE__];
};

class pthread_condattr_t
{
  I64 __sig;
  I8 __opaque[__THREAD_CONDATTR_SIZE__];
};

class pthread_handler_rec;
class pthread_handler_rec
{
  U0 (*__routine)(U0 *__arg); // Routine to call
  U0 *__arg;                  // Argument to pass
  pthread_handler_rec *__next;
};

class pthread_attr_t
{  
  I64 __sig;
  I8 __opaque[__THREAD_ATTR_SIZE__];
};

class pthread_t 
{
  I64 __sig;
  pthread_handler_rec  *__cleanup_stack;
  I8 __opaque[__THREAD_SIZE__];
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

union pthread_mutexattr_t
{
  I8  __size[__SIZEOF_PTHREAD_MUTEXATTR_T];
  I32 __align;
};

union pthread_condattr_t
{
  I8  __size[__SIZEOF_PTHREAD_CONDATTR_T];
  I32 __align;
};

union pthread_attr_t
{
  I8  __size[__SIZEOF_PTHREAD_ATTR_T];
  I64 __align;
};


/* this is the spiritual equivilent of typedef  */ 
public class pthread_t
{
  U64 __padding;
};

class __pthread_list_t;
class __pthread_list_t
{
  __pthread_list_t *__prev;
  __pthread_list_t *__next;
};

class __pthread_mutex_s
{
  I32 __lock;
  U32 __count;
  I32 __owner;
  U32 __nusers;
  I32 __kind;
  I16 __spins;
  I16 __elision;
  __pthread_list_t __list;
};

union pthread_mutex_t 
{
  __pthread_mutex_s __data;
  I8  __size[__SIZEOF_PTHREAD_MUTEX_T];
  I64 __align;
};

union __atomic_wide_counter
{
  U64 __value64;
  class
  {
    U32 __low;
    U32 __high;
  };
};

class __pthread_cond_s
{
  __atomic_wide_counter __wseg;
  __atomic_wide_counter __g1_start;
  U32 __g_regs[2];
  U32 __g_size[2];
  U32 __g1_orig_size;
  U32 __wrefs;
  U32 __g_signals[2];
};

union pthread_cond_t
{
  __pthread_cond_s __data;
  I8  __size[__SIZEOF_PTHREAD_COND_T];
  I64 __align;
};
#endif

class ThreadWorker
{
  pthread_t *th;
  I32 id;
};

class ThreadWorkerJob;
class ThreadWorkerJob
{
  U0 (*callback)(U0 *priv_data, U0 *argv);
  U0 *argv;
  ThreadWorkerJob *next;
};

/* `lock` guards `jobs`, `run`, `active_threads` and `alive_threads`.
 * Workers sleep on `has_work`; ThreadPoolWait sleeps on `no_work`. */
class ThreadPool
{
  U64 worker_count,alive_threads,active_threads;
  Bool run;
  pthread_cond_t has_work,no_work;
  pthread_mutex_t lock;
  ThreadWorker *workers;
  List *jobs;
  U0 *priv_data;
};

public extern "c" I32 pthread_create(pthread_t **th, U0 *thread_attr=NULL, 
		U0 *(*function_ptr)(U0 *argv), U0 *argv=NULL);
public extern "c" I32 pthread_mutex_init(pthread_mutex_t *lk, pthread_mutexattr_t *mutex_attr=NULL);
public extern "c" I32 pthread_mutex_destroy(pthread_mutex_t *lk);
public extern "c" I32 pthread_mutex_lock(pthread_mutex_t *lk);
public extern "c" I32 pthread_mutex_unlock(pthread_mutex_t *lk);
public extern "c" I32 pthread_join(pthread_t *th, U0 *ptr=NULL);
public extern "c" I32 pthread_cond_broadcast(pthread_cond_t *cond);
public extern "c" I32 pthread_cond_destroy(pthread_cond_t *cond);
public extern "c" I32 pthread_cond_init(pthread_cond_t *cond,
		pthread_condattr_t *attr=NULL);
public extern "c" I32 pthread_cond_signal(pthread_cond_t *cond);
public extern "c" I32 pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *lk);
public extern "c" I32 pthread_exit(U0 *data=NULL);
public extern "c" I32 pthread_detach(pthread_t *th);
public extern "c" I32 pthread_attr_setstacksize(pthread_attr_t *attr, U64 stacksize);

U0 ThreadPoolRelease(ThreadPool *pool);

U0 ThreadPoolEnqueue(ThreadPool *pool, U0 *argv, U0 (*callback)(U0 *priv_data, U0 *argv))
{// Enqueue a job
  if (!pool) return;
  ThreadWorkerJob *job = MAlloc(sizeof(ThreadWorkerJob));
  job->callback = callback;
  job->argv = argv;
  job->next = NULL;
  pthread_mutex_lock(&pool->lock);
  ListAppend(pool->jobs,job);
  pthread_cond_signal(&pool->has_work);
  pthread_mutex_unlock(&pool->lock);
}

U0 ThreadPoolWait(ThreadPool *pool)
{//Wait for all jobs in the queue to be complete
  if (!pool) return;
  pthread_mutex_lock(&pool->lock);
  while (!ListEmpty(pool->jobs) || pool->active_threads != 0) {
    pthread_cond_wait(&pool->no_work,&pool->lock);
  }
  pthread_mutex_unlock(&pool->lock);
}

static U0 *ThreadPoolMain(U0 *argv)
{// This takes jobs of the queue, calls them and waits for more work.
 // Once the pool is stopped it finishes the queued jobs, then returns.
  ThreadPool *pool = argv(ThreadPool *);
  ThreadWorkerJob *job;

  pthread_mutex_lock(&pool->lock);
  pool->alive_threads++;
  while (TRUE) {
    while (pool->run && ListEmpty(pool->jobs)) {
      pthread_cond_wait(&pool->has_work,&pool->lock);
    }
    job = ListDeque(pool->jobs);
    if (!job) {
      break; /* stopped and the queue is drained */
    }
    pool->active_threads++;
    pthread_mutex_unlock(&pool->lock);

    job->callback(pool->priv_data,job->argv);
    Free(job);

    pthread_mutex_lock(&pool->lock);
    pool->active_threads--;
    if (pool->active_threads == 0 && ListEmpty(pool->jobs)) {
      pthread_cond_broadcast(&pool->no_work);
    }
  }
  pool->alive_threads--;
  pthread_mutex_unlock(&pool->lock);
  return NULL;
}

ThreadPool *ThreadPoolNew(I64 worker_count)
{// Create a pool of worker_count threads; NULL if worker_count <= 0 or
 // a thread can't be created (the ones already started are stopped)
  if (worker_count <= 0) {
    return NULL;
  }
  ThreadPool *pool = MAlloc(sizeof(ThreadPool));
  ThreadWorker *workers = MAlloc(sizeof(ThreadWorker) * worker_count);
  I32 err;

  pool->jobs = ListNew();
  pool->run = TRUE;
  pool->active_threads = 0;
  pool->alive_threads = 0;
  pool->worker_count = 0; /* counts the started workers, the ones to join */
  pool->workers = workers;
  pool->priv_data = NULL;

  pthread_cond_init(&pool->no_work);
  pthread_cond_init(&pool->has_work);
  pthread_mutex_init(&pool->lock);

  /* The workers stay joinable so ThreadPoolStop can wait for them */
  for (I64 i = 0; i < worker_count; ++i) {
    ThreadWorker *worker = workers + i;
    worker->id = i;
    err = pthread_create(&worker->th,NULL,&ThreadPoolMain,pool);
    if (err != 0) {
      ThreadPoolRelease(pool);
      return NULL;
    }
    pool->worker_count++;
  }
  return pool;
}

U0 ThreadPoolStop(ThreadPool *pool)
{// Finish every queued job, then stop and join the workers
  if (!pool) return;
  pthread_mutex_lock(&pool->lock);
  pool->run = FALSE;
  pthread_cond_broadcast(&pool->has_work);
  pthread_mutex_unlock(&pool->lock);

  for (I64 i = 0; i < pool->worker_count; ++i) {
    pthread_join(pool->workers[i].th);
  }
  pool->worker_count = 0;
}

U0 ThreadPoolRelease(ThreadPool *pool)
{// Stop the pool (queued jobs still run, see ThreadPoolStop), destroy
 // conditions and locks and free the structures
  if (pool) {
    ThreadPoolStop(pool);

    pthread_cond_destroy(&pool->has_work);
    pthread_cond_destroy(&pool->no_work);
    pthread_mutex_destroy(&pool->lock);
    /* Only jobs enqueued after ThreadPoolStop can be left */
    ListRelease(pool->jobs,&Free);

    Free(pool->workers);
    Free(pool);
  }
}
