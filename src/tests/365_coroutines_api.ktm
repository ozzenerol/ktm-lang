#include "testhelper.ktm"

/* Coroutines as a library module (coroutines.ktm), on x86_64 and arm64:
 * values both ways through Yield/Next, the function's return value from
 * the last Next, coroutines resuming coroutines, a try spanning a yield
 * (each coroutine has its own catch chain), an uncaught throw going on
 * from the Next that resumed it, F64 values kept across switches, many
 * coroutines alive at once, coroutines on two threads, the FP control
 * state per coroutine, and misuse exiting with a message. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 dup2(I32 oldfd, I32 newfd);
extern "c" I32 fflush(U0 *stream);

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

Bool Generator()
{
  I64 want[7] = {0, 1, 1, 2, 3, 5, 8}, n = 0;
  Coroutine *c = CoroutineNew(&Fib, 10(U0 *));
  U0 *v;
  if (!c || CoroutineDone(c)) return FALSE;
  for (v = CoroutineNext(c); !CoroutineDone(c); v = CoroutineNext(c)) {
    if (n >= 7 || v(I64) != want[n]) return FALSE;
    n++;
  }
  if (n != 7 || v(I64) != 'end') return FALSE;    /* fn's return value */
  if (CoroutineNext(c) != NULL) return FALSE;     /* done: NULL */
  if (!CoroutineDone(c)) return FALSE;
  CoroutineRelease(c);
  return TRUE;
}

U0 *Sum(U0 *start)
{
  I64 total = start(I64);
  while (TRUE) total += CoroutineYield(total(U0 *))(I64);
  return NULL;
}

Bool SendValues()
{
  Coroutine *c = CoroutineNew(&Sum, 100(U0 *));
  if (CoroutineNext(c, 555(U0 *))(I64) != 100) return FALSE; /* send ignored */
  if (CoroutineNext(c, 5(U0 *))(I64) != 105) return FALSE;
  if (CoroutineNext(c, 7(U0 *))(I64) != 112) return FALSE;
  if (CoroutineNext(c)(I64) != 112) return FALSE;            /* NULL = 0 */
  CoroutineRelease(c);                                        /* while paused */
  return TRUE;
}

/* Yields every value of an inner generator, doubled. */
U0 *Doubler(U0 *max)
{
  Coroutine *inner = CoroutineNew(&Fib, max);
  U0 *v;
  if (CoroutineSelf() == NULL) return 'self'(U0 *);
  for (v = CoroutineNext(inner); !CoroutineDone(inner); v = CoroutineNext(inner))
    CoroutineYield((v(I64) * 2)(U0 *));
  CoroutineRelease(inner);
  return NULL;
}

Bool Nested()
{
  I64 want[6] = {0, 2, 2, 4, 6, 10}, n = 0;
  if (CoroutineSelf() != NULL) return FALSE;
  Coroutine *c = CoroutineNew(&Doubler, 6(U0 *));
  U0 *v;
  for (v = CoroutineNext(c); !CoroutineDone(c); v = CoroutineNext(c)) {
    if (n >= 6 || v(I64) != want[n]) return FALSE;
    n++;
  }
  if (n != 6 || v != NULL) return FALSE;
  if (CoroutineSelf() != NULL) return FALSE;
  CoroutineRelease(c);
  return TRUE;
}

/* A try whose body yields: the throw after the yield must land in this
 * coroutine's catch, not in whatever try the resumer is in. */
U0 *TryAcross(U0 *tag)
{
  try {
    CoroutineYield(1(U0 *));
    throw(tag(I64));
  } catch {
    CoroutineYield(Fs->except_ch(U0 *));
  }
  return 2(U0 *);
}

Bool TryAcrossYield()
{
  Coroutine *a = CoroutineNew(&TryAcross, 'A'(U0 *));
  Coroutine *b = CoroutineNew(&TryAcross, 'B'(U0 *));
  I64 r1, r2, r3, r4, r5 = 0, r6;
  try {
    if (CoroutineNext(a)(I64) != 1) return FALSE;
    if (CoroutineNext(b)(I64) != 1) return FALSE;
    r1 = CoroutineNext(a)(I64);   /* a throws and catches 'A' */
    r2 = CoroutineNext(b)(I64);   /* b throws and catches 'B' */
    try {
      throw('Main');
    } catch {
      r5 = Fs->except_ch;
    }
    r3 = CoroutineNext(a)(I64);
    r4 = CoroutineNext(b)(I64);
  } catch {
    return FALSE;                 /* a coroutine's throw leaked out */
  }
  r6 = CoroutineDone(a) && CoroutineDone(b);
  CoroutineRelease(a);
  CoroutineRelease(b);
  return r1 == 'A' && r2 == 'B' && r3 == 2 && r4 == 2 && r5 == 'Main' && r6;
}

U0 *Thrower(U0 *arg)
{
  CoroutineYield(1(U0 *));
  throw('Boom');
  return NULL;
}

U0 *Middle(U0 *arg)
{/* doesn't catch: the inner throw passes through it */
  Coroutine *t = CoroutineNew(&Thrower);
  CoroutineYield(CoroutineNext(t));
  CoroutineNext(t);
  return 'no'(U0 *);
}

Bool ThrowOut()
{
  Coroutine *c = CoroutineNew(&Thrower);
  I64 caught = 0;
  CoroutineNext(c);
  try {
    CoroutineNext(c);
  } catch {
    caught = Fs->except_ch;
  }
  if (caught != 'Boom' || !CoroutineDone(c)) return FALSE;
  if (CoroutineNext(c) != NULL) return FALSE;     /* no second rethrow */
  CoroutineRelease(c);

  caught = 0;
  Coroutine *m = CoroutineNew(&Middle);
  try {
    if (CoroutineNext(m)(I64) != 1) return FALSE;
    CoroutineNext(m);
  } catch {
    caught = Fs->except_ch;
  }
  if (caught != 'Boom' || !CoroutineDone(m)) return FALSE;
  CoroutineRelease(m);              /* Thrower inside it leaks: fine */
  return TRUE;
}

U0 *FloatWork(U0 *arg)
{
  F64 x = 1.0, y = 0.5, z = 0.25;
  I64 i;
  for (i = 0; i < 5; i++) {
    x = x * 3.0 + y;
    y = y * 0.5 + z;
    z = z + x * 0.001;
    CoroutineYield(i(U0 *));
  }
  F64 *out = arg;
  *out = x + y + z;
  return NULL;
}

Bool Floats()
{
  F64 a = 1.25, b = -2.5, c = 1e10, d = 3.0 / 7.0, got = 0, want;
  Coroutine *co = CoroutineNew(&FloatWork, &got);
  while (!CoroutineDone(co)) {
    CoroutineNext(co);
    a = a * 2.0;
    b = b + a;
    c = c / 2.0;
    d = d * d + 0.1;
  }
  CoroutineRelease(co);
  /* the same sums with no coroutine */
  F64 x = 1.0, y = 0.5, z = 0.25, a2 = 1.25, b2 = -2.5, c2 = 1e10, d2 = 3.0 / 7.0;
  I64 i;
  for (i = 0; i < 5; i++) {
    x = x * 3.0 + y;
    y = y * 0.5 + z;
    z = z + x * 0.001;
  }
  want = x + y + z;
  for (i = 0; i < 6; i++) {
    a2 = a2 * 2.0;
    b2 = b2 + a2;
    c2 = c2 / 2.0;
    d2 = d2 * d2 + 0.1;
  }
  return got == want && a == a2 && b == b2 && c == c2 && d == d2;
}

#define MANY 1000

Bool Many()
{
  Coroutine **cs = MAlloc(MANY * sizeof(Coroutine *));
  I64 i, sum = 0, live = MANY;
  for (i = 0; i < MANY; i++) {
    cs[i] = CoroutineNew(&Fib, (i % 50)(U0 *), 64 * 1024);
    if (!cs[i]) return FALSE;
  }
  while (live) {                  /* round robin until all are done */
    live = 0;
    for (i = 0; i < MANY; i++) {
      if (CoroutineDone(cs[i])) continue;
      U0 *v = CoroutineNext(cs[i]);
      if (!CoroutineDone(cs[i])) {
        sum += v(I64);
        live++;
      }
    }
  }
  for (i = 0; i < MANY; i++) CoroutineRelease(cs[i]);
  Free(cs);
  /* sum of the Fibonacci numbers below m, for m = 0..49, 20 times */
  I64 want = 0, m, a, b, t;
  for (m = 0; m < 50; m++) {
    a = 0;
    b = 1;
    while (a < m) {
      want += a;
      t = a + b;
      a = b;
      b = t;
    }
  }
  return sum == want * (MANY / 50);
}

U0 *ThreadMain(U0 *arg)
{
  I64 *res = arg, rounds, sum = 0;
  for (rounds = 0; rounds < 200; rounds++) {
    Coroutine *c = CoroutineNew(&Sum, 0(U0 *), 16 * 1024);
    CoroutineNext(c);
    for (I64 i = 1; i <= 10; i++) sum = CoroutineNext(c, i(U0 *))(I64) + sum;
    CoroutineRelease(c);
  }
  *res = sum;
  return NULL;
}

Bool Threads()
{
  pthread_t *t1, *t2;
  I64 r1 = 0, r2 = 0;
  /* per round: running totals 1+3+6+...+55 = 220 */
  pthread_create(&t1, NULL, &ThreadMain, &r1);
  pthread_create(&t2, NULL, &ThreadMain, &r2);
  pthread_join(t1);
  pthread_join(t2);
  return r1 == 200 * 220 && r2 == 200 * 220 && CoroutineSelf() == NULL;
}

#ifdef IS_X86_64
/* glibc's fesetround sets both MXCSR and the x87 control word */
extern "c" I32 fesetround(I32 mode);
extern "c" I32 fegetround();
#define FE_TONEAREST 0
#define FE_DOWNWARD  0x400

U0 *Rounding(U0 *arg)
{
  fesetround(FE_DOWNWARD);
  CoroutineYield(fegetround()(U0 *));
  CoroutineYield(fegetround()(U0 *));     /* its own mode is back */
  fesetround(FE_TONEAREST);
  return NULL;
}

Bool FpControl()
{
  Coroutine *c = CoroutineNew(&Rounding);
  if (CoroutineNext(c)(I64) != FE_DOWNWARD) return FALSE;
  if (fegetround() != FE_TONEAREST) return FALSE;   /* not leaked to us */
  if (CoroutineNext(c)(I64) != FE_DOWNWARD) return FALSE;
  CoroutineNext(c);
  CoroutineRelease(c);
  return fegetround() == FE_TONEAREST;
}
#else
Bool FpControl()
{
  return TRUE;                    /* FPCR is not switched on arm64 */
}
#endif

U0 *NextSelf(U0 *arg)
{
  CoroutineNext(CoroutineSelf());
  return NULL;
}

I32 ChildYieldOutside()
{
  CoroutineYield(1(U0 *));
  return 0;
}

I32 ChildNextRunning()
{
  Coroutine *c = CoroutineNew(&NextSelf);
  CoroutineNext(c);
  return 0;
}

I32 RunInChild(I32 (*fn)())
{// fn's exit code, or 100 + the signal that killed it
  fflush(NULL);                   /* misuse exits via exit(), which flushes */
  I32 pid = fork();
  if (pid == 0) {
    alarm(30);
    I32 null = open("/dev/null", 1);
    dup2(null, 2);                /* the misuse message goes to stderr */
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
  "Test - coroutines api: ";

  tests++;
  if (Generator) correct++;
  else "\n  generator values / final value / done\n";

  tests++;
  if (SendValues) correct++;
  else "\n  values sent by CoroutineNext\n";

  tests++;
  if (Nested) correct++;
  else "\n  a coroutine resuming a coroutine\n";

  tests++;
  if (TryAcrossYield) correct++;
  else "\n  try spanning a yield\n";

  tests++;
  if (ThrowOut) correct++;
  else "\n  uncaught throw rethrown by CoroutineNext\n";

  tests++;
  if (Floats) correct++;
  else "\n  F64 values across switches\n";

  tests++;
  if (Many) correct++;
  else "\n  %d coroutines round robin\n", MANY;

  tests++;
  if (Threads) correct++;
  else "\n  coroutines on two threads\n";

  tests++;
  if (FpControl) correct++;
  else "\n  rounding mode per coroutine\n";

  tests++;
  I32 rc1 = RunInChild(&ChildYieldOutside);
  I32 rc2 = RunInChild(&ChildNextRunning);
  if (rc1 == 1 && rc2 == 1) correct++;
  else "\n  misuse must exit with 1 (%d, %d)\n", rc1, rc2;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
