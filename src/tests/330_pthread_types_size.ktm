import test;

import libc;

/* The Linux pthread types had the x86_64 glibc sizes on every
 * architecture: on aarch64 pthread_attr_t is 64 bytes (not 56) and the
 * mutex/cond attributes 8 (not 4), so pthread_attr_init and
 * pthread_mutexattr_init wrote past the variable. pthread_mutex_t was
 * declared with i32 __size[40], 160 bytes instead of 40. */

extern "c" i32 pthread_attr_destroy(libc.pthread_attr_t *a);
extern "c" i32 pthread_mutexattr_init(libc.pthread_mutexattr_t *a);
extern "c" i32 pthread_mutexattr_settype(libc.pthread_mutexattr_t *a, i32 t);
extern "c" i32 pthread_mutexattr_destroy(libc.pthread_mutexattr_t *a);
extern "c" i32 pthread_condattr_init(libc.pthread_condattr_t *a);
extern "c" i32 pthread_condattr_destroy(libc.pthread_condattr_t *a);

const i64 CANARY = 0x1122334455667788;

struct AttrBox { i64 lo; libc.pthread_attr_t v; i64 canary; };
struct MAttrBox { i64 lo; libc.pthread_mutexattr_t v; i64 canary; };
struct CAttrBox { i64 lo; libc.pthread_condattr_t v; i64 canary; };
struct MutexBox { i64 lo; libc.pthread_mutex_t v; i64 canary; };
struct CondBox { i64 lo; libc.pthread_cond_t v; i64 canary; };

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void *threadFn(void *arg) {
    return arg;
}

i32 main() {
    printf("Test - pthread type sizes per architecture: ");

#ifdef IS_MACOS
    Check(sizeof(libc.pthread_attr_t) == 64, "sizeof(pthread_attr_t)");
    Check(sizeof(libc.pthread_mutexattr_t) == 16, "sizeof(pthread_mutexattr_t)");
    Check(sizeof(libc.pthread_condattr_t) == 16, "sizeof(pthread_condattr_t)");
    Check(sizeof(libc.pthread_mutex_t) == 64, "sizeof(pthread_mutex_t)");
    Check(sizeof(libc.pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#elifdef IS_ARM_64
    Check(sizeof(libc.pthread_attr_t) == 64, "sizeof(pthread_attr_t)");
    Check(sizeof(libc.pthread_mutexattr_t) == 8, "sizeof(pthread_mutexattr_t)");
    Check(sizeof(libc.pthread_condattr_t) == 8, "sizeof(pthread_condattr_t)");
    Check(sizeof(libc.pthread_mutex_t) == 48, "sizeof(pthread_mutex_t)");
    Check(sizeof(libc.pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#else
    check(sizeof(libc.pthread_attr_t) == 56, "sizeof(pthread_attr_t)");
    check(sizeof(libc.pthread_mutexattr_t) == 4, "sizeof(pthread_mutexattr_t)");
    check(sizeof(libc.pthread_condattr_t) == 4, "sizeof(pthread_condattr_t)");
    check(sizeof(libc.pthread_mutex_t) == 40, "sizeof(pthread_mutex_t)");
    check(sizeof(libc.pthread_cond_t) == 48, "sizeof(pthread_cond_t)");
#endif

    /* libc must not write past any of them */
    AttrBox a;
    a.lo = CANARY; a.canary = CANARY;
    check(libc.pthread_attr_init(&a.v) == 0, "pthread_attr_init");
    check(libc.pthread_attr_setstacksize(&a.v, 1 << 20) == 0, "pthread_attr_setstacksize");
    check(a.canary == CANARY && a.lo == CANARY, "pthread_attr_t canary");
    libc.pthread_t *th = null;
    void *ret = null;
    check(libc.pthread_create(&th, &a.v, &threadFn, (void *)7) == 0, "pthread_create with attr");
    check(libc.pthread_join(th, &ret) == 0 && ret == (void *)7, "join thread created with attr");
    pthread_attr_destroy(&a.v);
    check(a.canary == CANARY, "pthread_attr_t canary after destroy");

    MAttrBox ma;
    ma.lo = CANARY; ma.canary = CANARY;
    check(pthread_mutexattr_init(&ma.v) == 0, "pthread_mutexattr_init");
    check(pthread_mutexattr_settype(&ma.v, 1) == 0, "pthread_mutexattr_settype");
    check(ma.canary == CANARY && ma.lo == CANARY, "pthread_mutexattr_t canary");

    MutexBox m;
    m.lo = CANARY; m.canary = CANARY;
    check(libc.pthread_mutex_init(&m.v, &ma.v) == 0, "pthread_mutex_init");
    check(libc.pthread_mutex_lock(&m.v) == 0 && libc.pthread_mutex_unlock(&m.v) == 0, "lock/unlock");
    check(m.canary == CANARY && m.lo == CANARY, "pthread_mutex_t canary");
    libc.pthread_mutex_destroy(&m.v);
    pthread_mutexattr_destroy(&ma.v);

    CAttrBox ca;
    ca.lo = CANARY; ca.canary = CANARY;
    check(pthread_condattr_init(&ca.v) == 0, "pthread_condattr_init");
    check(ca.canary == CANARY && ca.lo == CANARY, "pthread_condattr_t canary");

    CondBox c;
    c.lo = CANARY; c.canary = CANARY;
    check(libc.pthread_cond_init(&c.v, &ca.v) == 0, "pthread_cond_init");
    check(libc.pthread_cond_signal(&c.v) == 0 && libc.pthread_cond_broadcast(&c.v) == 0, "cond signal");
    check(c.canary == CANARY && c.lo == CANARY, "pthread_cond_t canary");
    libc.pthread_cond_destroy(&c.v);
    pthread_condattr_destroy(&ca.v);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
