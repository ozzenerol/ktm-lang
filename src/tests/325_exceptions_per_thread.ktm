#include "testhelper.ktm"

/* The catch chain was one process-wide global, so a throw in one thread
 * could longjmp onto another thread's stack ("stack smashing detected").
 * Each thread now has its own Fs. */

#define WORKERS 8
#define ROUNDS  5000

#define UNCAUGHT_SRC "__325_uncaught.ktm"
#define UNCAUGHT_BIN "./__325_uncaught.out"
#define UNCAUGHT_LOG "__325_uncaught.log"

class Work
{
  pthread_t *th;
  I64 id;
  I64 inner;
  I64 outer;
  I64 wrong;
  HCFs *fs;
};

extern "c" I32 usleep(U32 usec);

/* Workers wait here until all of them are running, so none has exited
 * (freeing its Fs, which the next thread could get at the same address)
 * when their Fs are compared. */
pthread_mutex_t started_lock;
I64 started;

U0 WaitForAllWorkers()
{
  I64 n;
  pthread_mutex_lock(&started_lock);
  started++;
  pthread_mutex_unlock(&started_lock);
  do {
    usleep(100);
    pthread_mutex_lock(&started_lock);
    n = started;
    pthread_mutex_unlock(&started_lock);
  } while (n < WORKERS);
}

U0 Raise(I64 v)
{
  throw(v);
}

U0 *Worker(Work *w)
{
  I64 i, v;
  w->fs = Fs;
  WaitForAllWorkers;
  for (i = 0; i < ROUNDS; i++) {
    v = w->id * 1000000 + i;
    try {
      try {
        Raise(v);
      } catch {
        if (Fs->except_ch == v) w->inner++;
        else w->wrong++;
        throw(v + 1);
      }
    } catch {
      if (Fs->except_ch == v + 1) w->outer++;
      else w->wrong++;
    }
    if (Fs->catch_top != NULL) w->wrong++;
  }
  return NULL;
}

Bool UncaughtInThreadExits()
{
  U8 *src = "
U0 *T(U0 *a)
{
  throw('Thr');
  return NULL;
}

U0 Main()
{
  pthread_t *t;
  try {
    pthread_create(&t, NULL, &T, NULL);
    pthread_join(t);
    \"joined\\n\";
  } catch {
    \"main caught\\n\";
  }
}
";
  Bool ok = FALSE;
  if (!FileWrite(UNCAUGHT_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC)) {
    return FALSE;
  }
  if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
             UNCAUGHT_SRC " -o " UNCAUGHT_BIN) == 0) {
    I64 res = System(UNCAUGHT_BIN " > " UNCAUGHT_LOG " 2>&1");
    U8 *out = FileRead(UNCAUGHT_LOG);
    if (out) {
      ok = res == 1 && strstr(out, "uncaught error") != NULL &&
           strstr(out, "main caught") == NULL &&
           strstr(out, "joined") == NULL;
      if (!ok) "exit %d: %s\n", res, out;
      Free(out);
    }
  }
  Rm(UNCAUGHT_SRC);
  Rm(UNCAUGHT_BIN);
  Rm(UNCAUGHT_LOG);
  return ok;
}

I32 Main()
{
  "Test - each thread has its own catch chain: ";
  I64 correct = 0, tests = 6, i, j;
  Work work[WORKERS];
  Bool counts = TRUE, distinct = TRUE;
  I64 after = 0;

  MemSet(work, 0, sizeof(work));
  pthread_mutex_init(&started_lock);
  started = 0;
  try {
    for (i = 0; i < WORKERS; i++) {
      work[i].id = i + 1;
      pthread_create(&work[i].th, NULL, &Worker, &work[i]);
    }
    for (i = 0; i < WORKERS; i++) {
      pthread_join(work[i].th);
    }
    /* Still inside this thread's try: the workers never touched it. */
    Raise('Main');
  } catch {
    after = Fs->except_ch;
  }
  if (after == 'Main') correct++;

  for (i = 0; i < WORKERS; i++) {
    if (work[i].inner != ROUNDS || work[i].outer != ROUNDS ||
        work[i].wrong != 0) {
      counts = FALSE;
      "worker %d: inner %d outer %d wrong %d\n", i, work[i].inner,
          work[i].outer, work[i].wrong;
    }
    if (work[i].fs == Fs || work[i].fs == NULL) distinct = FALSE;
    for (j = 0; j < i; j++) {
      if (work[i].fs == work[j].fs) distinct = FALSE;
    }
  }
  if (counts) correct++;
  if (distinct) correct++;
  if (Fs == Fs && Fs->catch_top == NULL) correct++;

  try {
    Raise('Again');
  } catch {
    if (Fs->except_ch == 'Again') correct++;
  }

  if (UncaughtInThreadExits()) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
