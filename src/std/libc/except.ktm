module libc;

/* The compiler's try and defer frames use _setjmp, which unlike setjmp
 * doesn't save the signal mask (a sigprocmask syscall per try or defer
 * reached); so a throw is a _longjmp, which doesn't restore it. On
 * Mach-O a name starting with `_` is taken as already mangled: C's
 * _longjmp is written __longjmp there (`_longjmp` would be C's longjmp,
 * which sets the signal mask from what _setjmp never saved). */
#ifdef IS_MACOS
pub extern "c" void __longjmp(void *env, i32 val);
#else
pub extern "c" void _longjmp(void *env, i32 val);
#endif

pub extern "c" i32 fflush(void *stream);

pub extern "c" i32 pthread_once(void *once, fn() void init);

pub extern "c" i32 pthread_key_create(u64 *key, fn(void *) void destructor);

pub extern "c" void *pthread_getspecific(u64 key);

pub extern "c" i32 pthread_setspecific(u64 key, void *value);
