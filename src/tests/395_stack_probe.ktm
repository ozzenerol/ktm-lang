#include "testhelper.ktm"

/* A function with a frame larger than a page now reserves it a page at a
 * time, touching each page as the stack descends (stack-clash probing).
 * A frame bigger than the guard page therefore faults on overflow instead
 * of leaping over the guard into other memory: a 1 MiB frame on a 256 KiB
 * thread stack dies with SIGSEGV rather than silently corrupting whatever
 * lies below the stack. Normal big frames are unaffected. Checked in a
 * forked child so the thread's fault doesn't take the test down. */

extern "c" I32 pthread_attr_destroy(pthread_attr_t *attr);
extern "c" I32 fflush(U0 *stream);
extern "c" U0 exit(I32 status);

#define SIGSEGV      11
#define SIGBUS       10          /* macOS delivers this for a guard-page hit */
#define SMALL_STACK  (256 * 1024)
#define BIG_FRAME    (1024 * 1024)

I64 g_result = 0;

/* A 1 MiB frame. It writes only the top of the array (near the frame
 * base, inside the mapped stack), so without probing the oversized `sub
 * sp` is never noticed and the call returns; with probing the descent
 * hits the guard page first. */
I64 BigFrame()
{
  U8 buf[BIG_FRAME];
  buf[BIG_FRAME - 1] = 0x5A;
  return buf[BIG_FRAME - 1];
}

U0 *ThreadFn(U0 *arg)
{
  g_result = BigFrame();
  return NULL;
}

/* fork a child that runs BigFrame on a 256 KiB thread stack, and return
 * the child's wait status. */
I64 ChildRunsThread()
{
  I32 pid, status = 0;
  fflush(NULL);                 /* don't duplicate buffered output in the child */
  pid = fork();
  if (pid == 0) {
    pthread_attr_t attr;
    pthread_t *th;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, SMALL_STACK);
    if (pthread_create(&th, &attr, &ThreadFn, NULL) != 0) {
      exit(42);                 /* couldn't start the thread */
    }
    pthread_join(th);           /* the fault kills the process before this returns */
    pthread_attr_destroy(&attr);
    exit(0);                    /* reached only if BigFrame did NOT fault */
  }
  waitpid(pid, &status, 0);
  return status;
}

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I32 Main()
{
  I64 status;
  "Test - stack-clash probing: ";

  /* A 1 MiB frame on the main thread's 8 MiB stack is fine. */
  Check(BigFrame() == 0x5A, "1 MiB frame on the main stack works");

  /* The same frame on a 256 KiB thread stack must fault, not return. */
  status = ChildRunsThread();
  Check((status & 0x7F) != 0 && (status & 0x7F) != 0x7F,
      "the child was killed by a signal");
  /* Linux delivers SIGSEGV, macOS SIGBUS for the guard-page hit; both
   * mean the overflow faulted instead of corrupting memory. */
  Check((status & 0x7F) == SIGSEGV || (status & 0x7F) == SIGBUS,
      "1 MiB frame on a 256 KiB thread stack faults (SIGSEGV/SIGBUS)");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
