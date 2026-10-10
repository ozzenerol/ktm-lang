#include "testhelper.ktm"

/* The Linux pthread types had the x86_64 glibc sizes on every
 * architecture: on aarch64 pthread_attr_t is 64 bytes (not 56) and the
 * mutex/cond attributes 8 (not 4), so pthread_attr_init and
 * pthread_mutexattr_init wrote past the variable. pthread_mutex_t was
 * declared with I32 __size[40], 160 bytes instead of 40. */

extern "c" I32 pthread_attr_destroy(pthread_attr_t *a);
extern "c" I32 pthread_mutexattr_init(pthread_mutexattr_t *a);
extern "c" I32 pthread_mutexattr_settype(pthread_mutexattr_t *a, I32 t);
extern "c" I32 pthread_mutexattr_destroy(pthread_mutexattr_t *a);
extern "c" I32 pthread_condattr_init(pthread_condattr_t *a);
extern "c" I32 pthread_condattr_destroy(pthread_condattr_t *a);

#define CANARY 0x1122334455667788

class AttrBox { I64 lo; pthread_attr_t v; I64 canary; };
class MAttrBox { I64 lo; pthread_mutexattr_t v; I64 canary; };
class CAttrBox { I64 lo; pthread_condattr_t v; I64 canary; };
class MutexBox { I64 lo; pthread_mutex_t v; I64 canary; };
class CondBox { I64 lo; pthread_cond_t v; I64 canary; };

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U0 *ThreadFn(U0 *arg)
{
  return arg;
}

I32 Main()
{
  "Test - pthread type sizes per architecture: ";

#ifdef IS_MACOS
  Check(sizeof(pthread_attr_t) == 64, "sizeof(pthread_attr_t)");
  Check(sizeof(pthread_mutexattr_t) == 16, "sizeof(pthread_mutexattr_t)");
  Check(sizeof(pthread_condattr_t) == 16, "sizeof(pthread_condattr_t)");
  Check(sizeof(pthread_mutex_t) == 64, "sizeof(pthread_mutex_t)");
  Check(sizeof(pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#elifdef IS_ARM_64
  Check(sizeof(pthread_attr_t) == 64, "sizeof(pthread_attr_t)");
  Check(sizeof(pthread_mutexattr_t) == 8, "sizeof(pthread_mutexattr_t)");
  Check(sizeof(pthread_condattr_t) == 8, "sizeof(pthread_condattr_t)");
  Check(sizeof(pthread_mutex_t) == 48, "sizeof(pthread_mutex_t)");
  Check(sizeof(pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#else
  Check(sizeof(pthread_attr_t) == 56, "sizeof(pthread_attr_t)");
  Check(sizeof(pthread_mutexattr_t) == 4, "sizeof(pthread_mutexattr_t)");
  Check(sizeof(pthread_condattr_t) == 4, "sizeof(pthread_condattr_t)");
  Check(sizeof(pthread_mutex_t) == 40, "sizeof(pthread_mutex_t)");
  Check(sizeof(pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#endif

  /* libc must not write past any of them */
  AttrBox a;
  a.lo = CANARY; a.canary = CANARY;
  Check(pthread_attr_init(&a.v) == 0, "pthread_attr_init");
  Check(pthread_attr_setstacksize(&a.v, 1 << 20) == 0, "pthread_attr_setstacksize");
  Check(a.canary == CANARY && a.lo == CANARY, "pthread_attr_t canary");
  pthread_t *th = NULL;
  U0 *ret = NULL;
  Check(pthread_create(&th, &a.v, &ThreadFn, 7(U0 *)) == 0, "pthread_create with attr");
  Check(pthread_join(th, &ret) == 0 && ret == 7(U0 *), "join thread created with attr");
  pthread_attr_destroy(&a.v);
  Check(a.canary == CANARY, "pthread_attr_t canary after destroy");

  MAttrBox ma;
  ma.lo = CANARY; ma.canary = CANARY;
  Check(pthread_mutexattr_init(&ma.v) == 0, "pthread_mutexattr_init");
  Check(pthread_mutexattr_settype(&ma.v, 1) == 0, "pthread_mutexattr_settype");
  Check(ma.canary == CANARY && ma.lo == CANARY, "pthread_mutexattr_t canary");

  MutexBox m;
  m.lo = CANARY; m.canary = CANARY;
  Check(pthread_mutex_init(&m.v, &ma.v) == 0, "pthread_mutex_init");
  Check(pthread_mutex_lock(&m.v) == 0 && pthread_mutex_unlock(&m.v) == 0, "lock/unlock");
  Check(m.canary == CANARY && m.lo == CANARY, "pthread_mutex_t canary");
  pthread_mutex_destroy(&m.v);
  pthread_mutexattr_destroy(&ma.v);

  CAttrBox ca;
  ca.lo = CANARY; ca.canary = CANARY;
  Check(pthread_condattr_init(&ca.v) == 0, "pthread_condattr_init");
  Check(ca.canary == CANARY && ca.lo == CANARY, "pthread_condattr_t canary");

  CondBox c;
  c.lo = CANARY; c.canary = CANARY;
  Check(pthread_cond_init(&c.v, &ca.v) == 0, "pthread_cond_init");
  Check(pthread_cond_signal(&c.v) == 0 && pthread_cond_broadcast(&c.v) == 0, "cond signal");
  Check(c.canary == CANARY && c.lo == CANARY, "pthread_cond_t canary");
  pthread_cond_destroy(&c.v);
  pthread_condattr_destroy(&ca.v);

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
