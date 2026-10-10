#include "testhelper.ktm"

/* The coroutine stacks (src/std/coroutines.ktm). The old x86_64-only
 * demo crashed on first use: mmap got MAP_SHARED|MAP_PRIVATE instead of
 * MAP_PRIVATE|MAP_ANONYMOUS and its result was not checked. The odd
 * STACK_CAPACITY misaligned every coroutine stack, which crashed F64
 * printing, and a stack overflow silently wrote over the stack mapped
 * below it. Each case runs in a forked child, so a crash fails the test
 * instead of the suite. Since the module is part of the library it runs
 * on every architecture. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 pipe(I32 *fds);
extern "c" I32 fflush(U0 *stream);

#define STACK_SIZE (256 * 1024)

U0 *Fib(U0 *arg)
{
  I64 max = arg(I64);
  I64 a = 0, b = 1, c;
  while (a < max) {
    CoroutineYield(a(U0 *));
    c = a + b;
    a = b;
    b = c;
  }
  return NULL;
}

I32 ChildFib()
{
  I64 want[7] = {0, 1, 1, 2, 3, 5, 8};
  I64 n = 0;
  Coroutine *c = CoroutineNew(&Fib, 10(U0 *));
  if (!c) return 1;
  for (U0 *it = CoroutineNext(c); !CoroutineDone(c); it = CoroutineNext(c)) {
    if (n >= 7 || it(I64) != want[n]) return 2;
    n++;
  }
  if (n != 7) return 3;
  CoroutineRelease(c);
  return 0;
}

U0 *Floats(U0 *arg)
{
  F64 x = 1.5;
  for (I64 i = 0; i < 3; i++) {
    x *= 2.0;
    U8 *s = MStrPrint("%.2f", x);
    CoroutineYield(StrCmp(s, arg(U8 **)[i])(U0 *));
    Free(s);
  }
  return NULL;
}

I32 ChildAlignment()
{
  U8 *want[3] = {"3.00", "6.00", "12.00"};
  Coroutine *c = CoroutineNew(&Floats, want);
  if (!c) return 1;
  /* the first frame sits on a 16-byte aligned stack pointer */
  if (c->sp(I64) % 16 != 0) return 2;
  for (U0 *it = CoroutineNext(c); !CoroutineDone(c); it = CoroutineNext(c)) {
    if (it(I64) != 0) return 3;
  }
  CoroutineRelease(c);
  return 0;
}

I32 ChildMany()
{
  Coroutine *cs[64];
  I64 i, sum = 0;
  for (i = 0; i < 64; i++) {
    cs[i] = CoroutineNew(&Fib, 100(U0 *), STACK_SIZE);
    if (!cs[i]) return 1;
  }
  for (i = 0; i < 64; i++) {
    for (U0 *it = CoroutineNext(cs[i]); !CoroutineDone(cs[i]); it = CoroutineNext(cs[i]))
      sum += it(I64);
  }
  for (i = 0; i < 64; i++) CoroutineRelease(cs[i]);
  /* 0+1+1+2+3+5+8+13+21+34+55+89 = 232 */
  return sum != 64 * 232;
}

I32 g_pipe[2];

I64 g_frame[2];

I64 Deep(I64 n)
{
  I64 pad[8];
  pad[0] = n;
  if (n < 2) g_frame[n] = pad(I64);
  if (n == 0) return 0;
  return pad[0] - n + 1 + Deep(n - 1);
}

I64 OverflowDepth()
{// Deep enough to run 64 KB past the end of the stack, no further
  Deep(1);
  I64 frame = g_frame[1] - g_frame[0];
  if (frame <= 0) frame = 64;
  return (STACK_SIZE + 65536) / frame;
}

U0 *Overflow(U0 *arg)
{
  CoroutineYield(Deep(arg(I64))(U0 *));
  return NULL;
}

U0 *Locals(U0 *arg)
{
  I64 arr[4000], bad = 0, i;
  for (i = 0; i < 4000; i++) arr[i] = 12345;
  CoroutineYield(1(U0 *));
  for (i = 0; i < 4000; i++) if (arr[i] != 12345) bad++;
  CoroutineYield(bad(U0 *));
  return NULL;
}

I32 ChildOverflow()
{// Must die on the guard page; returning means the overflow went unseen
  I64 depth = OverflowDepth;
  Coroutine *a = CoroutineNew(&Overflow, depth(U0 *), STACK_SIZE);
  Coroutine *b = CoroutineNew(&Locals, NULL, STACK_SIZE);
  if (!a || !b) return 1;
  CoroutineNext(b);
  write(g_pipe[1], "r", 1); /* reached the overflow */
  CoroutineNext(a);
  write(g_pipe[1], "a", 1); /* the overflow went unseen */
  if (CoroutineNext(b)(I64) != 0) return 2; /* b's stack was overwritten */
  return 3;
}

I32 RunInChild(I32 (*fn)())
{// fn's result, or 100 + the signal that killed it
  fflush(NULL);           /* or the child repeats our buffered output */
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
  "Test - coroutine stacks: ";
  I32 rc;
  tests++;
  if ((rc = RunInChild(&ChildFib)) == 0) correct++;
  else "\n  fib generator (%d)\n", rc;

  tests++;
  if ((rc = RunInChild(&ChildAlignment)) == 0) correct++;
  else "\n  F64 printing in a coroutine (%d)\n", rc;

  tests++;
  if ((rc = RunInChild(&ChildMany)) == 0) correct++;
  else "\n  64 coroutines (%d)\n", rc;

  tests++;
  U8 reached[4];
  MemSet(reached, 0, sizeof(reached));
  pipe(g_pipe);
  rc = RunInChild(&ChildOverflow);
  close(g_pipe[1]);
  read(g_pipe[0], reached, 2);
  close(g_pipe[0]);
  /* killed in the overflow: SIGSEGV (11) on Linux, SIGBUS (10) or
   * SIGSEGV on macOS */
  if (!StrCmp(reached, "r") && (rc == 111 || rc == 110)) correct++;
  else "\n  stack overflow not caught by the guard page (%d)\n", rc;
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
