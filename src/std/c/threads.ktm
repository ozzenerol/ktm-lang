module c;

public extern "c" I32 pthread_create(pthread_t **th, U0 *thread_attr=NULL, 
		U0 *(*function_ptr)(U0 *argv), U0 *argv=NULL);

public extern "c" I32 pthread_mutex_init(pthread_mutex_t *lk, pthread_mutexattr_t *mutex_attr=NULL);

public extern "c" I32 pthread_mutex_destroy(pthread_mutex_t *lk);

public extern "c" I32 pthread_mutex_lock(pthread_mutex_t *lk);

public extern "c" I32 pthread_mutex_unlock(pthread_mutex_t *lk);

public extern "c" I32 pthread_join(pthread_t *th, U0 *ptr=NULL);

public extern "c" I32 pthread_cond_broadcast(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_destroy(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_init(pthread_cond_t *cond,
		pthread_condattr_t *attr=NULL);

public extern "c" I32 pthread_cond_signal(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *lk);

public extern "c" I32 pthread_exit(U0 *data=NULL);

public extern "c" I32 pthread_detach(pthread_t *th);

public extern "c" I32 pthread_attr_setstacksize(pthread_attr_t *attr, U64 stacksize);
