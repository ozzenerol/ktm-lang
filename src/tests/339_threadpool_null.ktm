#include "testhelper.ktm"

/* ThreadPoolNew returns NULL for a bad count or when a thread can't be
 * created, but ThreadPoolEnqueue, ThreadPoolWait and ThreadPoolStop then
 * crashed on it; only ThreadPoolRelease checked. All four ignore NULL. */

I64 ran = 0;

U0 Job(U0 *priv, U0 *argv)
{
  ran++;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - ThreadPool with a NULL pool: ";

  ThreadPool *pool = ThreadPoolNew(0);
  tests++;
  if (pool == NULL) correct++;

  ThreadPoolEnqueue(pool, NULL, &Job);
  ThreadPoolWait(pool);
  ThreadPoolStop(pool);
  ThreadPoolRelease(pool);
  tests++;
  if (ran == 0) correct++;

  /* A real pool still works */
  pool = ThreadPoolNew(2);
  ThreadPoolEnqueue(pool, NULL, &Job);
  ThreadPoolWait(pool);
  ThreadPoolRelease(pool);
  tests++;
  if (ran == 1) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
