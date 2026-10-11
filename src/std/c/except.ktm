module c;

/* The compiler's try and defer frames use _setjmp, which unlike setjmp
 * doesn't save the signal mask (a sigprocmask syscall per try or defer
 * reached); so a throw is a _longjmp, which doesn't restore it. */
public extern "c" U0 _longjmp(U0 *env, I32 val);

public extern "c" I32 fflush(U0 *stream);

public extern "c" I32 pthread_once(U0 *once, U0 (*init)());

public extern "c" I32 pthread_key_create(U64 *key, U0 (*destructor)(U0 *));

public extern "c" U0 *pthread_getspecific(U64 key);

public extern "c" I32 pthread_setspecific(U64 key, U0 *value);
