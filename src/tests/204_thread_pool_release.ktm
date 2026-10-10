#include "testhelper.ktm"

/* ThreadPoolRelease used to hang: the workers slept on a semaphore that
 * shutdown never signalled, and the detached workers were then joined.
 * A hang must fail the test rather than hang the suite, so a watchdog
 * alarm kills the process (the runner then reports it as FAILED). */
extern "c" U32 alarm(U32 seconds);

pthread_mutex_t g_lk;
I64 g_count;

U0 CountJob(U0 *priv_data, U0 *argv)
{
  pthread_mutex_lock(&g_lk);
  g_count++;
  pthread_mutex_unlock(&g_lk);
}

U0 SlowCountJob(U0 *priv_data, U0 *argv)
{
  I64 spin = 0;
  for (I64 i = 0; i < 200000; ++i) spin += i;
  if (spin < 0) "unreachable\n";
  CountJob(priv_data, argv);
}

Bool SubmitWaitRelease(I64 workers, I64 jobs)
{
  g_count = 0;
  ThreadPool *pool = ThreadPoolNew(workers);
  for (I64 i = 0; i < jobs; ++i) {
    ThreadPoolEnqueue(pool, NULL, &CountJob);
  }
  ThreadPoolWait(pool);
  Bool ok = g_count == jobs;
  ThreadPoolRelease(pool);
  return ok && g_count == jobs;
}

Bool ReleaseWithoutWork()
{
  ThreadPool *pool = ThreadPoolNew(4);
  ThreadPoolWait(pool); /* nothing queued: returns at once */
  ThreadPoolRelease(pool);
  return TRUE;
}

Bool ReleaseWithQueuedWork(I64 workers, I64 jobs)
{// Release drains the queue: every queued job still runs
  g_count = 0;
  ThreadPool *pool = ThreadPoolNew(workers);
  for (I64 i = 0; i < jobs; ++i) {
    ThreadPoolEnqueue(pool, NULL, &SlowCountJob);
  }
  ThreadPoolRelease(pool);
  return g_count == jobs;
}

Bool StopThenRelease()
{
  g_count = 0;
  ThreadPool *pool = ThreadPoolNew(3);
  for (I64 i = 0; i < 10; ++i) {
    ThreadPoolEnqueue(pool, NULL, &CountJob);
  }
  ThreadPoolStop(pool);
  Bool ok = g_count == 10;
  ThreadPoolWait(pool); /* stopped and drained: returns at once */
  ThreadPoolRelease(pool);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - Thread pool release: ";
  alarm(60);
  pthread_mutex_init(&g_lk);

  Bool ok = TRUE;
  for (I64 i = 0; i < 20; ++i) {
    if (!SubmitWaitRelease(4, 50)) ok = FALSE;
  }
  tests++; if (ok) correct++;
  else "submit/wait/release in a loop miscounted\n";

  tests++;
  if (SubmitWaitRelease(1, 25)) correct++;
  else "single worker pool miscounted\n";

  ok = TRUE;
  for (I64 i = 0; i < 20; ++i) {
    if (!ReleaseWithoutWork()) ok = FALSE;
  }
  tests++; if (ok) correct++;

  ok = TRUE;
  for (I64 i = 0; i < 5; ++i) {
    if (!ReleaseWithQueuedWork(2, 20)) ok = FALSE;
  }
  tests++; if (ok) correct++;
  else "release with queued work did not run every job\n";

  tests++;
  if (StopThenRelease()) correct++;
  else "stop then release miscounted\n";

  ThreadPoolRelease(NULL);
  tests++; correct++;

  pthread_mutex_destroy(&g_lk);
  alarm(0);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
