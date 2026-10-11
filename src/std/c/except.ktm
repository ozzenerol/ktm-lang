module c;

/* The compiler's try and defer frames use _setjmp, which unlike setjmp
 * doesn't save the signal mask (a sigprocmask syscall per try or defer
 * reached); so a throw is a _longjmp, which doesn't restore it. */
pub extern "c" void _longjmp(void *env, i32 val);

pub extern "c" i32 fflush(void *stream);

pub extern "c" i32 pthread_once(void *once, fn() void init);

pub extern "c" i32 pthread_key_create(u64 *key, fn(void *) void destructor);

pub extern "c" void *pthread_getspecific(u64 key);

pub extern "c" i32 pthread_setspecific(u64 key, void *value);
