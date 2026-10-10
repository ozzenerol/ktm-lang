#include "testhelper.ktm"

#define THREAD_COUNT 5

class ThreadArgs
{
  pthread_mutex_t *lk;
  pthread_t *th;
  I64 id;
  I64 *ptr;
};

U0 *Func(ThreadArgs *args)
{
  pthread_mutex_lock(args->lk);
  I64 x = *args->ptr;
  x++;
  *args->ptr = x;
  pthread_mutex_unlock(args->lk);
  return NULL;
}

Bool pthread_test()
{// this is using libc, with a little bit of ktm magic 
  I64 threads[THREAD_COUNT];
  I64 correct = 0, tests = 1;
  ThreadArgs thread_args[THREAD_COUNT];

  pthread_mutex_t lk;
  pthread_mutex_init(&lk);

  I64 x = 0;
  for (I64 i = 0; i < THREAD_COUNT; ++i) {
    ThreadArgs *args = &thread_args[i];
    args->lk = &lk;
    args->ptr = &x;
    args->id = i;
    threads[i] = pthread_create(&args->th,,(&Func)(U0 *(*)(U0 *)), &thread_args[i]);
  }

  for (I64 i = 0; i < THREAD_COUNT; ++i) {
    ThreadArgs *args = &thread_args[i];
    pthread_join(args->th);
  }

  /* this proves that 1: thread join works, 2: the mutex lock works */
  pthread_mutex_destroy(&lk);
  return x == THREAD_COUNT;
}

U0 PoolFunc(U0 *priv_data, ThreadArgs *args)
{
  pthread_mutex_lock(args->lk);
  I64 x = *args->ptr;
  x++;
  *args->ptr = x;
  pthread_mutex_unlock(args->lk);
}

Bool ThreadPoolTest()
{
  I64 x = 0;
  ThreadArgs *arg = MAlloc(sizeof(ThreadArgs));
  ThreadPool *pool = ThreadPoolNew(10);

  pthread_mutex_t lk;
  pthread_mutex_init(&lk);

  arg->id = 1;
  arg->ptr = &x;
  arg->lk = &lk;
  arg->th = NULL;
  for (I64 i = 0; i < 10; ++i) {
    ThreadPoolEnqueue(pool,arg,(&PoolFunc)(fn(U0 *, U0 *) U0));
  }
  ThreadPoolWait(pool);
  pthread_mutex_destroy(&lk);
  ThreadPoolRelease(pool);
  Free(arg);
  return x == 10;
}

I32 Main()
{
  I32 correct = 0, tests = 2;
  "Test - Simple Threading: \n";
  if (pthread_test()) correct++;
  if (ThreadPoolTest()) correct++;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
