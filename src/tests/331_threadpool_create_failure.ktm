#include "testhelper.ktm"

/* ThreadPoolNew ignored pthread_create failing: the handles of the
 * workers that never started were left as uninitialised MAlloc memory
 * and ThreadPoolStop/Release joined them (a crash). ThreadPoolNew(0) gave
 * a pool without workers, so ThreadPoolWait hung once a job was queued.
 * Each case runs in a forked child with a watchdog alarm, so a crash or a
 * hang fails the test instead of the suite. */

extern "c" U32 alarm(U32 seconds);
extern "c" I32 getrlimit(I32 res, U64 *lim);
extern "c" I32 setrlimit(I32 res, U64 *lim);
extern "c" U0 _exit(I32 code);
extern "c" I32 usleep(U32 usec);

#define RLIMIT_AS_LINUX 9

U0 Job(U0 *priv_data, U0 *argv)
{
}

U8 g_buf[4096];

U8 *ReadProc(U8 *path)
{// /proc files have no size, so FileRead can't be used
  I32 fd = open(path, O_RDONLY);
  if (fd == -1) return NULL;
  I64 n = read(fd, g_buf, sizeof(g_buf) - 1);
  close(fd);
  if (n <= 0) return NULL;
  g_buf[n] = '\0';
  return g_buf;
}

I64 ThreadsInProcess()
{// The Threads: line of /proc/self/status, -1 if unreadable
  U8 *s = ReadProc("/proc/self/status");
  if (!s) return -1;
  U8 *t = strstr(s, "Threads:");
  if (!t) return -1;
  return Atoi(t + 8);
}

Bool OnlyMainThread()
{// A joined thread can still be counted for a moment after pthread_join
  for (I64 i = 0; i < 2000; ++i) {
    if (ThreadsInProcess() == 1) return TRUE;
    usleep(1000);
  }
  return FALSE;
}

I64 VmSize()
{// Bytes of address space in use
  U8 *s = ReadProc("/proc/self/statm");
  if (!s) return -1;
  return Atoi(s) * 4096;
}

I32 ChildBadCounts()
{
  if (ThreadPoolNew(0) != NULL) return 1;
  if (ThreadPoolNew(-1) != NULL) return 2;
  if (ThreadPoolNew(-100) != NULL) return 3;
  ThreadPoolRelease(NULL);
  return 0;
}

I32 ChildCreateFails()
{// Leave room for a few 8 MB thread stacks, not for 64 of them
  U64 lim[2], old[2];
  ThreadPool *pool;
  if (getrlimit(RLIMIT_AS_LINUX, old) != 0) return 10;
  I64 used = VmSize();
  if (used <= 0) return 11;
  for (I64 room = 0; room <= 64; room += 32) {
    lim[0] = used + room * 1024 * 1024;
    lim[1] = old[1];
    if (setrlimit(RLIMIT_AS_LINUX, lim) != 0) return 12;
    pool = ThreadPoolNew(64);
    if (pool != NULL) return 13;
    /* the workers that did start were stopped and joined */
    if (!OnlyMainThread()) return 14;
  }
  if (setrlimit(RLIMIT_AS_LINUX, old) != 0) return 15;

  /* with the address space back, a pool works */
  pool = ThreadPoolNew(4);
  if (!pool) return 16;
  for (I64 i = 0; i < 20; ++i) ThreadPoolEnqueue(pool, NULL, &Job);
  ThreadPoolWait(pool);
  if (pool->worker_count != 4) return 17;
  ThreadPoolRelease(pool);
  if (!OnlyMainThread()) return 18;
  return 0;
}

I32 RunInChild(I32 (*fn)())
{// fn's result, or 100 + the signal that killed it
  I32 pid = fork();
  if (pid == 0) {
    alarm(30);
    _exit(fn());
  }
  I32 status = 0;
  waitpid(pid, &status, 0);
  if (status & 0x7f) return 100 + (status & 0x7f);
  return (status >> 8) & 0xff;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  I32 rc;
  "Test - ThreadPoolNew failure handling: ";

  tests++;
  rc = RunInChild(&ChildBadCounts);
  if (rc == 0) correct++;
  else "\n  worker_count <= 0 did not give NULL (%d)\n", rc;

#ifdef IS_LINUX
  tests++;
  rc = RunInChild(&ChildCreateFails);
  if (rc == 0) correct++;
  else "\n  pthread_create failure not handled (%d)\n", rc;
#endif

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
