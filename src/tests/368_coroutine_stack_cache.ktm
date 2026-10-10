#include "testhelper.ktm"

/* CoroutineRelease keeps the stack for the next CoroutineNew of the same
 * size on the same thread instead of unmapping it: at most 16 per
 * thread, the rest unmapped, and the cached ones unmapped when the
 * thread ends. A reused stack starts clean (its old data must not leak
 * into the new coroutine's first frame) and keeps its guard page. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 fflush(U0 *stream);
extern "c" I32 getpagesize();
extern "c" I32 msync(U0 *addr, U64 len, I32 flags);

#define MS_ASYNC 1  /* same value on Linux and macOS */

/* Is the page at addr still mapped? msync fails (ENOMEM) on unmapped memory. */
Bool Mapped(U0 *addr)
{
  return msync(addr, getpagesize(), MS_ASYNC) == 0;
}

/* The top page of a coroutine's stack, writable while it is mapped. */
U0 *TopPage(Coroutine *c)
{
  return c->stack_base + c->stack_len - getpagesize();
}

U0 *Fib(U0 *max)
{
  I64 a = 0, b = 1, t;
  while (a < max(I64)) {
    CoroutineYield(a(U0 *));
    t = a + b;
    a = b;
    b = t;
  }
  return 'end'(U0 *);
}

/* Sum of what Fib(max) yields, and its return value must be 'end'. */
I64 RunFib(Coroutine *c)
{
  I64 sum = 0;
  U0 *v;
  for (v = CoroutineNext(c); !CoroutineDone(c); v = CoroutineNext(c))
    sum += v(I64);
  if (v(I64) != 'end') return -1;
  return sum;
}

Bool Reuse()
{
  HCFs *fs = Fs();
  I64 before = fs->coro_cached;
  Coroutine *a = CoroutineNew(&Fib, 100(U0 *));
  U0 *base = a->stack_base;
  if (RunFib(a) != 232) return FALSE;
  CoroutineRelease(a);
  if (fs->coro_cached != before + 1) return FALSE;

  Coroutine *b = CoroutineNew(&Fib, 100(U0 *));
  if (b->stack_base != base) return FALSE;          /* the cached stack */
  if (fs->coro_cached != before) return FALSE;
  CoroutineNext(b);
  CoroutineNext(b);
  CoroutineRelease(b);                               /* while paused */

  Coroutine *c = CoroutineNew(&Fib, 100(U0 *));
  if (c->stack_base != base) return FALSE;
  if (RunFib(c) != 232) return FALSE;                /* dirty stack, clean start */
  CoroutineRelease(c);
  return fs->coro_cached == before + 1;
}

Bool SizeMatch()
{
  HCFs *fs = Fs();
  Coroutine *big = CoroutineNew(&Fib, 10(U0 *));
  U0 *big_base = big->stack_base;
  CoroutineRelease(big);
  I64 cached = fs->coro_cached;
  Coroutine *small = CoroutineNew(&Fib, 10(U0 *), 64 * 1024);
  Bool ok = small->stack_base != big_base && fs->coro_cached == cached;
  if (RunFib(small) != 20) ok = FALSE;
  CoroutineRelease(small);
  big = CoroutineNew(&Fib, 10(U0 *));               /* still there */
  if (big->stack_base != big_base) ok = FALSE;
  CoroutineRelease(big);
  return ok;
}

#define MANY 40

Bool Bounded()
{
  HCFs *fs = Fs();
  Coroutine *cs[MANY];
  U0 *tops[MANY];
  I64 i;
  for (i = 0; i < MANY; i++) {                       /* empties the cache */
    cs[i] = CoroutineNew(&Fib, 10(U0 *), 32 * 1024);
    tops[i] = TopPage(cs[i]);
  }
  I64 start = fs->coro_cached;
  for (i = 0; i < MANY; i++) CoroutineRelease(cs[i]);
  if (fs->coro_cached != 16) return FALSE;
  /* the first ones released filled the cache, the rest were unmapped */
  I64 mapped = 0;
  for (i = 0; i < MANY; i++) if (Mapped(tops[i])) mapped++;
  if (mapped != 16 - start) return FALSE;
  return !Mapped(tops[MANY - 1]);
}

Bool Cycles()
{
  I64 i, bad = 0;
  for (i = 0; i < 100000; i++) {
    Coroutine *c = CoroutineNew(&Fib, 30(U0 *));
    if (RunFib(c) != 54) bad++;
    CoroutineRelease(c);
  }
  return bad == 0 && Fs()->coro_cached <= 16;
}

U0 *g_tops[8];

U0 *ThreadMain(U0 *arg)
{
  Coroutine *cs[8];
  I64 i;
  for (i = 0; i < 8; i++) {
    cs[i] = CoroutineNew(&Fib, 10(U0 *));
    g_tops[i] = TopPage(cs[i]);
    RunFib(cs[i]);
  }
  for (i = 0; i < 8; i++) CoroutineRelease(cs[i]);
  *arg(I64 *) = Fs()->coro_cached;
  return NULL;
}

Bool ThreadExit()
{
  pthread_t *t;
  I64 cached = 0, i, still = 0;
  pthread_create(&t, NULL, &ThreadMain, &cached);
  pthread_join(t);
  for (i = 0; i < 8; i++) if (Mapped(g_tops[i])) still++;
  return cached == 8 && still == 0;
}

I64 g_frame[2];

I64 Deep(I64 n)
{
  I64 pad[8];
  pad[0] = n;
  if (n < 2) g_frame[n] = pad(I64);
  if (n == 0) return 0;
  return pad[0] - n + 1 + Deep(n - 1);
}

U0 *Overflow(U0 *arg)
{
  CoroutineYield(Deep(arg(I64))(U0 *));
  return NULL;
}

I32 ChildGuard()
{// a reused stack must still die on its guard page
  Deep(1);
  I64 frame = g_frame[1] - g_frame[0];
  if (frame <= 0) frame = 64;
  Coroutine *a = CoroutineNew(&Overflow, 1(U0 *), 64 * 1024);
  U0 *base = a->stack_base;
  CoroutineRelease(a);
  a = CoroutineNew(&Overflow, ((64 * 1024 + 65536) / frame)(U0 *), 64 * 1024);
  if (a->stack_base != base) return 2;
  CoroutineNext(a);
  return 3;                       /* the overflow went unseen */
}

I32 RunInChild(I32 (*fn)())
{// fn's result, or 100 + the signal that killed it
  fflush(NULL);
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
  "Test - coroutine stack cache: ";

  tests++;
  if (Reuse) correct++;
  else "\n  released stack reused\n";

  tests++;
  if (SizeMatch) correct++;
  else "\n  only a stack of the same size is reused\n";

  tests++;
  if (Bounded) correct++;
  else "\n  at most 16 stacks cached, the rest unmapped\n";

  tests++;
  if (Cycles) correct++;
  else "\n  100000 new/release cycles\n";

  tests++;
  if (ThreadExit) correct++;
  else "\n  cached stacks unmapped when the thread ends\n";

  tests++;
  I32 rc = RunInChild(&ChildGuard);
  /* SIGSEGV (11) on Linux, SIGBUS (10) or SIGSEGV on macOS */
  if (rc == 111 || rc == 110) correct++;
  else "\n  reused stack lost its guard page (%d)\n", rc;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
