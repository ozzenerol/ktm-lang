module libc;

pub extern "c" i32 open(u8 *__name, i32 flags, ...);

pub extern "c" i32 creat(u8 *__name, u16 __flags);

pub extern "c" i32 fcntl(i32 __fd, i32 __flags, ...);

pub extern "c" i32 close(i32 __fd);

pub extern "c" i64 read(i32 __fd, void *__buf, u64 __nbyte);

pub extern "c" i64 write(i32 __fd, void *__buf, u64 __nbyte);

pub extern "c" i64 lseek(i32 __fd, i64 __offset, i32 __whence);

pub extern "c" i32 chdir(u8 *path);

/* XXX: Normalise stat ? */
pub struct timespec {
    i64 tv_sec;
    i64 tv_nsec;
};

pub struct timeval
{ // c compatability
    i64 tv_sec;     /* seconds since Jan. 1, 1970 */
#ifdef IS_LINUX
    i64 tv_usec;    /* and microseconds (a long) */
#else
    i32 tv_usec;    /* and microseconds (suseconds_t, an int) */
    i32 __pad;      /* C pads the struct to 16 bytes */
#endif
};

pub struct tm 
{     // c compatability
    i32 tm_sec;  /* seconds after the minute [0-60] */
    i32 tm_min;  /* minutes after the hour [0-59] */
    i32 tm_hour;   /* hours since midnight [0-23] */
    i32 tm_mday;   /* day of the month [1-31] */
    i32 tm_mon;  /* months since January [0-11] */
    i32 tm_year;   /* years since 1900 */
    i32 tm_wday;   /* days since Sunday [0-6] */
    i32 tm_yday;   /* days since January 1 [0-365] */
    i32 tm_isdst;  /* Daylight Savings Time flag */
    i64 tm_gmtoff; /* offset from UTC in seconds */
    i8 *tm_zone;   /* timezone abbreviation */
};

pub extern "c" tm *localtime(i64 *clock);

pub extern "c" i32 gettimeofday(timeval *tp, void *tzp);

/* THREADS ==========*/
#ifdef IS_MACOS
pub const i64 __MUTEX_LOCK_PADDING__ = 56;
pub const i64 __MUTEX_LOCK_ATTR_SIZE__ = 8;

pub const i64 __THREAD_COND_SIZE__ = 40;
pub const i64 __THREAD_CONDATTR_SIZE__ = 8;
pub const i64 __THREAD_SIZE__ = 8176;
pub const i64 __THREAD_ATTR_SIZE__ = 56;

/* MACOS definitions */
pub struct pthread_mutex_t {
    i64 __sig;
    i8 __opaque[__MUTEX_LOCK_PADDING__];
};

pub struct pthread_mutexattr_t {
    i64 __sig;
    i8 __opaque[__MUTEX_LOCK_ATTR_SIZE__];
};

pub struct pthread_cond_t {
    i64 __sig;
    i8 __opaque[__THREAD_COND_SIZE__];
};

pub struct pthread_condattr_t {
    i64 __sig;
    i8 __opaque[__THREAD_CONDATTR_SIZE__];
};

pub struct pthread_handler_rec;
pub struct pthread_handler_rec {
    fn(void *__arg) void __routine; // Routine to call
    void *__arg;                  // Argument to pass
    pthread_handler_rec *__next;
};

pub struct pthread_attr_t
{  
    i64 __sig;
    i8 __opaque[__THREAD_ATTR_SIZE__];
};

pub struct pthread_t {
    i64 __sig;
    pthread_handler_rec  *__cleanup_stack;
    i8 __opaque[__THREAD_SIZE__];
};

#elifdef IS_LINUX /* Linux definitions, this needs to be auto generated */

/* glibc's bits/pthreadtypes-arch.h: the sizes differ between x86_64 and aarch64 */
#ifdef IS_X86_64
pub const i64 __SIZEOF_PTHREAD_MUTEX_T = 40;
pub const i64 __SIZEOF_PTHREAD_ATTR_T = 56;
pub const i64 __SIZEOF_PTHREAD_RWLOCK_T = 56;
pub const i64 __SIZEOF_PTHREAD_BARRIER_T = 32;

pub const i64 __SIZEOF_PTHREAD_MUTEXATTR_T = 4;
pub const i64 __SIZEOF_PTHREAD_COND_T = 48;
pub const i64 __SIZEOF_PTHREAD_CONDATTR_T = 4;
pub const i64 __SIZEOF_PTHREAD_RWLOCKATTR_T = 8;
pub const i64 __SIZEOF_PTHREAD_BARRIERATTR_T = 4;
#else /* aarch64 */
pub const i64 __SIZEOF_PTHREAD_MUTEX_T = 48;
pub const i64 __SIZEOF_PTHREAD_ATTR_T = 64;
pub const i64 __SIZEOF_PTHREAD_RWLOCK_T = 56;
pub const i64 __SIZEOF_PTHREAD_BARRIER_T = 32;

pub const i64 __SIZEOF_PTHREAD_MUTEXATTR_T = 8;
pub const i64 __SIZEOF_PTHREAD_COND_T = 48;
pub const i64 __SIZEOF_PTHREAD_CONDATTR_T = 8;
pub const i64 __SIZEOF_PTHREAD_RWLOCKATTR_T = 8;
pub const i64 __SIZEOF_PTHREAD_BARRIERATTR_T = 8;
#endif

pub union pthread_mutexattr_t {
    i8  __size[__SIZEOF_PTHREAD_MUTEXATTR_T];
    i32 __align;
};

pub union pthread_condattr_t {
    i8  __size[__SIZEOF_PTHREAD_CONDATTR_T];
    i32 __align;
};

pub union pthread_attr_t {
    i8  __size[__SIZEOF_PTHREAD_ATTR_T];
    i64 __align;
};


/* this is the spiritual equivilent of typedef  */ 
pub struct pthread_t {
    u64 __padding;
};

pub struct __pthread_list_t;
pub struct __pthread_list_t {
    __pthread_list_t *__prev;
    __pthread_list_t *__next;
};

pub struct __pthread_mutex_s {
    i32 __lock;
    u32 __count;
    i32 __owner;
    u32 __nusers;
    i32 __kind;
    i16 __spins;
    i16 __elision;
    __pthread_list_t __list;
};

pub union pthread_mutex_t {
    __pthread_mutex_s __data;
    i8  __size[__SIZEOF_PTHREAD_MUTEX_T];
    i64 __align;
};

pub union __atomic_wide_counter {
    u64 __value64;
    struct
    {
        u32 __low;
        u32 __high;
    };
};

pub struct __pthread_cond_s {
    __atomic_wide_counter __wseg;
    __atomic_wide_counter __g1_start;
    u32 __g_regs[2];
    u32 __g_size[2];
    u32 __g1_orig_size;
    u32 __wrefs;
    u32 __g_signals[2];
};

pub union pthread_cond_t {
    __pthread_cond_s __data;
    i8 __size[__SIZEOF_PTHREAD_COND_T];
    i64 __align;
};
#endif

/* The thread handle is the pointer itself: declare `pthread_t *th;`, pass
 * `&th` to pthread_create and `th` to pthread_join/pthread_detach. On Linux
 * the C pthread_t is an unsigned long, so `th` doesn't point at anything:
 * never dereference it. pthread_create returns 0 or an error number. */
pub extern "c" i32 pthread_create(pthread_t **th, void *threadAttr = null,
                                fn(void *_argv) void *functionPtr, void *argv = null);

pub extern "c" i32 pthread_mutex_init(pthread_mutex_t *lk,
                                    pthread_mutexattr_t *mutexAttr = null);

pub extern "c" i32 pthread_mutex_destroy(pthread_mutex_t *lk);

pub extern "c" i32 pthread_mutex_lock(pthread_mutex_t *lk);

pub extern "c" i32 pthread_mutex_unlock(pthread_mutex_t *lk);

pub extern "c" i32 pthread_join(pthread_t *th, void *ptr = null);

pub extern "c" i32 pthread_cond_broadcast(pthread_cond_t *cond);

pub extern "c" i32 pthread_cond_destroy(pthread_cond_t *cond);

pub extern "c" i32 pthread_cond_init(pthread_cond_t *cond, pthread_condattr_t *attr = null);

pub extern "c" i32 pthread_cond_signal(pthread_cond_t *cond);

pub extern "c" i32 pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *lk);

pub extern "c" i32 pthread_exit(void *data = null);

pub extern "c" i32 pthread_atfork(void *prepare, void *parent, void *child);

pub extern "c" i32 pthread_detach(pthread_t *th);

pub extern "c" i32 pthread_attr_setstacksize(pthread_attr_t *attr, u64 stacksize);

pub extern "c" i32 pthread_attr_init(pthread_attr_t *attr);

pub extern "c" i32 wait(i32 *statloc);

pub extern "c" i32 waitpid(i32 pid, i32 *statlock, i32 options);

pub extern "c" i32 fork();

/* Processes, sessions and the terminal (shells and daemons). pid_t is
 * i32 on Linux and macOS. */
pub extern "c" i32 getpid();

pub extern "c" i32 getppid();

pub extern "c" i32 getpgrp();

pub extern "c" i32 getpgid(i32 pid);

pub extern "c" i32 setpgid(i32 pid, i32 pgid);

pub extern "c" i32 setsid();

pub extern "c" i32 tcgetpgrp(i32 fd);

pub extern "c" i32 tcsetpgrp(i32 fd, i32 pgid);

pub extern "c" u32 getuid();

pub extern "c" u32 geteuid();

/* File descriptors and paths */
pub extern "c" i32 dup(i32 fd);

pub extern "c" i32 dup2(i32 fd, i32 fd2);

pub extern "c" i32 fsync(i32 fd);

pub extern "c" i32 ftruncate(i32 fd, i64 length);

pub extern "c" i64 pread(i32 fd, void *buf, u64 n, i64 offset);

pub extern "c" i64 pwrite(i32 fd, void *buf, u64 n, i64 offset);

/* The target of a symbolic link into buf, NOT NUL-terminated: returns its
 * length, -1 on error */
pub extern "c" i64 readlink(u8 *path, u8 *buf, u64 size);

pub extern "c" i32 symlink(u8 *target, u8 *linkpath);

pub extern "c" i32 rename(u8 *from, u8 *to);

/* access(2) modes */
pub const i64 F_OK = 0;

pub const i64 X_OK = 1;

pub const i64 W_OK = 2;

pub const i64 R_OK = 4;

pub extern "c" i32 access(u8 *path, i32 mode);

/* realpath(3): resolved into a malloc'd C string (libc.free it) when
 * resolved is null */
pub extern "c" u8 *realpath(u8 *path, u8 *resolved);

pub extern "c" i64 sysconf(i32 name);

pub extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);

pub extern "c" i32 unsetenv(u8 *name);

/* Resource limits (setrlimit/getrlimit); RLIMIT_NOFILE is the number of
 * open file descriptors */
pub struct rlimit {
    u64 rlim_cur;
    u64 rlim_max;
};

#ifdef IS_LINUX
pub const i64 RLIMIT_NOFILE = 7;
#else
pub const i64 RLIMIT_NOFILE = 8;
#endif

pub extern "c" i32 getrlimit(i32 resource, rlimit *rl);

pub extern "c" i32 setrlimit(i32 resource, rlimit *rl);

/* errno values (the ones networking code checks most); they differ
 * between Linux and macOS, so compare os.errno() with these names. */
pub const i64 EPERM = 1;               /* Operation not permitted */

pub const i64 ENOENT = 2;               /* No such file or directory (or program) */

pub const i64 ESRCH = 3;               /* No such process */

pub const i64 EINTR = 4;               /* Interrupted by a signal: retry */

pub const i64 EIO = 5;               /* Input/output error */

pub const i64 EBADF = 9;               /* Bad file descriptor */

pub const i64 ECHILD = 10;              /* No child process to wait for */

pub const i64 ENOMEM = 12;              /* Out of memory */

pub const i64 EACCES = 13;              /* Permission denied */

pub const i64 EEXIST = 17;              /* File exists */

pub const i64 EXDEV = 18;              /* Rename across file systems */

pub const i64 ENOTDIR = 20;              /* Not a directory */

pub const i64 EISDIR = 21;              /* Is a directory */

pub const i64 EINVAL = 22;              /* Invalid argument */

pub const i64 ENFILE = 23;              /* Too many open files in the system */

pub const i64 EMFILE = 24;              /* Too many open files in this process */

pub const i64 ENOTTY = 25;              /* Not a terminal */

pub const i64 ENOSPC = 28;              /* No space left on the device */

pub const i64 ESPIPE = 29;              /* Seek on a pipe or socket */

pub const i64 EROFS = 30;              /* Read-only file system */

pub const i64 EPIPE = 32;              /* Write to a closed pipe or socket */

pub const i64 ERANGE = 34;              /* Result too large (a buffer too small) */

#ifdef IS_LINUX
pub const i64 EAGAIN = 11;              /* Would block: try again later */
pub const i64 EADDRINUSE = 98;              /* Address (port) already in use */
pub const i64 EADDRNOTAVAIL = 99;              /* Address not available on this host */
pub const i64 ENETUNREACH = 101;             /* Network unreachable */
pub const i64 ECONNRESET = 104;             /* Connection reset by the peer */
pub const i64 ETIMEDOUT = 110;             /* Timed out */
pub const i64 ECONNREFUSED = 111;             /* Connection refused */
pub const i64 EHOSTUNREACH = 113;             /* No route to host */
pub const i64 EINPROGRESS = 115;             /* Non-blocking connect started */
pub const i64 ENAMETOOLONG = 36;              /* File name too long */
pub const i64 ENOSYS = 38;              /* Not implemented */
pub const i64 ENOTEMPTY = 39;              /* Directory not empty */
pub const i64 ELOOP = 40;              /* Too many symbolic links */
#else
pub const i64 EAGAIN = 35;              /* Would block: try again later */
pub const i64 EINPROGRESS = 36;              /* Non-blocking connect started */
pub const i64 EADDRINUSE = 48;              /* Address (port) already in use */
pub const i64 EADDRNOTAVAIL = 49;              /* Address not available on this host */
pub const i64 ENETUNREACH = 51;              /* Network unreachable */
pub const i64 ECONNRESET = 54;              /* Connection reset by the peer */
pub const i64 ETIMEDOUT = 60;              /* Timed out */
pub const i64 ECONNREFUSED = 61;              /* Connection refused */
pub const i64 EHOSTUNREACH = 65;              /* No route to host */
pub const i64 ELOOP = 62;              /* Too many symbolic links */
pub const i64 ENAMETOOLONG = 63;              /* File name too long */
pub const i64 ENOTEMPTY = 66;              /* Directory not empty */
pub const i64 ENOSYS = 78;              /* Not implemented */
#endif

pub const i64 EWOULDBLOCK = EAGAIN;          /* Same value on Linux and macOS */

/* Signals (each platform's numbers) and signal(3). signal returns
 * the previous handler, or SIG_ERR. SIG_IGN on SIGPIPE makes a write to a
 * closed socket or pipe return -1 with os.errno() EPIPE instead of ending
 * the program. */
pub const i64 SIGHUP = 1;               /* Terminal hung up */

pub const i64 SIGINT = 2;               /* Ctrl-C */

pub const i64 SIGKILL = 9;               /* Kill (can't be caught or ignored) */

pub const i64 SIGPIPE = 13;              /* Write to a closed pipe or socket */

pub const i64 SIGTERM = 15;              /* Termination request */

pub const i64 SIG_DFL = 0;               /* The default action */

pub const i64 SIG_IGN = 1;               /* Ignore the signal */

pub const i64 SIG_ERR = -1;              /* signal() failed */

/* handler is SIG_DFL, SIG_IGN or a void F(i32 sig) as (&F)(i64) */
pub extern "c" i64 signal(i32 sig, i64 handler);

pub const i64 SIGQUIT = 3;               /* Ctrl-\ */

pub const i64 SIGALRM = 14;              /* alarm() timer */

pub const i64 SIGTTIN = 21;              /* Background read from the terminal */

pub const i64 SIGTTOU = 22;              /* Background write to the terminal */

#ifdef IS_MACOS
pub const i64 SIGUSR1 = 30;              /* For the program's own use */
pub const i64 SIGUSR2 = 31;
pub const i64 SIGCHLD = 20;              /* A child process ended */
pub const i64 SIGSTOP = 17;              /* Stop (can't be caught or ignored) */
pub const i64 SIGTSTP = 18;              /* Ctrl-Z */
pub const i64 SIGCONT = 19;              /* Continue a stopped process */
pub const i64 SA_RESTART = 0x2;             /* Restart read/write/... after the handler */
pub const i64 SA_RESETHAND = 0x4;             /* Back to SIG_DFL once the handler ran */
pub const i64 SA_NODEFER = 0x10;            /* Don't block the signal in its handler */
#else
pub const i64 SIGUSR1 = 10;              /* For the program's own use */
pub const i64 SIGUSR2 = 12;
pub const i64 SIGCHLD = 17;              /* A child process ended */
pub const i64 SIGCONT = 18;              /* Continue a stopped process */
pub const i64 SIGSTOP = 19;              /* Stop (can't be caught or ignored) */
pub const i64 SIGTSTP = 20;              /* Ctrl-Z */
pub const i64 SA_RESTART = 0x10000000;      /* Restart read/write/... after the handler */
pub const i64 SA_RESETHAND = 0x80000000;      /* Back to SIG_DFL once the handler ran */
pub const i64 SA_NODEFER = 0x40000000;      /* Don't block the signal in its handler */
#endif

/* Send sig to the calling thread / to process pid (-pid: its group). */
pub extern "c" i32 raise(i32 sig);

pub extern "c" i32 kill(i32 pid, i32 sig);

pub extern "c" i32 setjmp(void *env);

pub extern "c" void longjmp(void *env, i32 val);

pub extern "c" f64 acos(f64 f1);

pub extern "c" f64 asin(f64 f1);

pub extern "c" f64 atan(f64 f1);

pub extern "c" f64 atan2(f64 f1, f64 f2);

pub extern "c" f64 cos(f64 f1);

pub extern "c" f64 sin(f64 f1);

pub extern "c" f64 tan(f64 f1);

pub extern "c" f64 acosh(f64 f1);

pub extern "c" f64 asinh(f64 f1);

pub extern "c" f64 atanh(f64 f1);

pub extern "c" f64 cosh(f64 f1);

pub extern "c" f64 sinh(f64 f1);

pub extern "c" f64 tanh(f64 f1);

pub extern "c" f64 exp(f64 f1);

pub extern "c" f64 exp2(f64 f1);

pub extern "c" f64 expm1(f64 f1);

pub extern "c" f64 log(f64 f1);

pub extern "c" f64 log10(f64 f1);

pub extern "c" f64 log2(f64 f1);

pub extern "c" f64 log1p(f64 f1);

pub extern "c" f64 logb(f64 f1);

pub extern "c" f64 modf(f64 f1, f64 *fptr);

pub extern "c" f64 ldexp(f64 f1, i32 i);

pub extern "c" f64 frexp(f64 f1, i32 *i);

pub extern "c" i32 ilogb(f64 f1);

pub extern "c" f64 scalbn(f64 f1, i32 i);

pub extern "c" f64 scalbln(f64 f1, i64 i);

pub extern "c" f64 fabs(f64 f1);

pub extern "c" f64 cbrt(f64 f1);

pub extern "c" f64 hypot(f64 f1, f64 f2);

pub extern "c" f64 pow(f64 f1, f64 f2);

pub extern "c" f64 sqrt(f64 f1);

pub extern "c" f64 erf(f64 f1);

pub extern "c" f64 erfc(f64 f1);

pub extern "c" f64 lgamma(f64 f1);

pub extern "c" f64 tgamma(f64 f1);

pub extern "c" f64 ceil(f64 f1);

pub extern "c" f64 floor(f64 f1);

pub extern "c" f64 nearbyint(f64 f1);

pub extern "c" f64 rint(f64 f1);

pub extern "c" i64 lrint(f64 f1);

pub extern "c" f64 round(f64 f1);

pub extern "c" i64 lround(f64 f1);

pub extern "c" f64 trunc(f64 f1);

pub extern "c" f64 fmod(f64 f1, f64 f2);

pub extern "c" f64 remainder(f64 f1, f64 f2);

pub extern "c" f64 remquo(f64 f1, f64 f2, i32 *ptr);

pub extern "c" f64 copysign(f64 f1, f64 f2);

pub extern "c" f64 nan(u8 *s);

pub extern "c" f64 nextafter(f64 f1, f64 f2);

pub extern "c" f64 fdim(f64 f1, f64 f2);

pub extern "c" f64 fmax(f64 f1, f64 f2);

pub extern "c" f64 fmin(f64 f1, f64 f2);

pub extern "c" f64 fma(f64 f1, f64 f2, f64 f3);

pub extern "c" void *memchr(void *__s, i32 __c, u64 __n);

pub extern "c" i32 memcmp(void *__s1, void *__s2, u64 __n);

pub extern "c" void *memmove(void *__dst, void *__src, u64 __len);

pub extern "c" void free(void *ptr);

pub extern "c" void *malloc(u64 size);

pub extern "c" f64 strtod(u8 *nptr, u8 **endptr);

pub extern "c" i64 strtoll(u8 *nptr, u8 **endptr, i64 base = 10);

pub extern "c" u64 strtoul(u8 *nptr, u8 **endptr, i64 base = 10);

pub extern "c" i64 snprintf(i8 *nptr, u64 len, i8 *fmt, ...);

pub extern "c" i64 printf(i8 *fmt, ...);

pub extern "c" i64 strnlen(u8 *s, i64 size);

pub extern "c" u8 *strcat(u8 *__s1, u8 *__s2);

pub extern "c" u8 *strchr(u8 *__s, i32 __c);

pub extern "c" i32 strcmp(u8 *__s1, u8 *__s2);

pub extern "c" i32 strcasecmp(u8 *__s1, u8 *__s2);

pub extern "c" i32 strncasecmp(u8 *__s1, u8 *__s2, u64 __n);

pub extern "c" i64 atol(u8 *__str);

pub extern "c" i32 strcoll(u8 *__s1, u8 *__s2);

pub extern "c" u8 *strcpy(u8 *__dst, u8 *__src);

pub extern "c" u64 strcspn(u8 *__s, u8 *__u8set);

pub extern "c" u8 *strerror(i32 __errnum);

pub extern "c" u64 strlen(u8 *__s);

pub extern "c" u8 *strncat(u8 *__s1, u8 *__s2, u64 __n);

pub extern "c" i32 strncmp(u8 *__s1, u8 *__s2, u64 __n);

pub extern "c" u8 *strncpy(u8 *__dst, u8 *__src, u64 __n);

pub extern "c" u8 *strpbrk(u8 *__s, u8 *__u8set);

pub extern "c" u8 *strrchr(u8 *__s, i32 __c);

pub extern "c" u8 *strdup(u8 *__s);

pub extern "c" u64 strspn(u8 *__s, u8 *__u8set);

pub extern "c" u8 *strstr(u8 *__big, u8 *__little);

pub extern "c" u8 *strtok(u8 *__str, u8 *__sep);

pub extern "c" u64 strxfrm(u8 *__s1, u8 *__s2, u64 __n);

pub const i64 F_DUPFD = 0; /* Duplicate a file descriptor */

pub const i64 F_GETFD = 1; /* Retrieve file descriptor flags */

pub const i64 F_SETFD = 2; /* Assign flags to file descriptor */

pub const i64 F_GETFL = 3; /* Get file status flags */

pub const i64 F_SETFL = 4; /* Assign file status flags */

pub const i64 F_FLUSH_DATA = 40; /* Commit data to disk */

pub const i64 F_CHKCLEAN = 41; /* Verify file is not modified */

pub const i64 F_PREALLOCATE = 42; /* Allocate space for file in advance */

pub const i64 F_SETSIZE = 43; /* Adjust file size */

pub const i64 F_RDADVISE = 44; /* Suggest read behavior */

pub const i64 F_RDAHEAD = 45; /* Enable read ahead */

pub const i64 FD_CLOEXEC = 1;  /* Close file descriptor on exec */

#ifdef IS_LINUX
    pub const i64 F_GETOWN = 0x9; /* Retrieve SIGIO/SIGURG process/group */
    pub const i64 F_SETOWN = 0x8; /* Set SIGIO/SIGURG process/group */
    pub const i64 F_GETLK = 0x5; /* Query record locking info */
    pub const i64 F_SETLK = 0x6; /* Set record locking info without waiting */
    pub const i64 F_SETLKW = 0x7; /* Set record locking info with waiting */
#else
    pub const i64 F_GETOWN = 0x5;
    pub const i64 F_SETOWN = 0x6;
    pub const i64 F_GETLK = 0x7;
    pub const i64 F_SETLK = 0x8;
    pub const i64 F_SETLKW = 0x9;
#endif

/* record locking flags */
#ifdef IS_LINUX
    pub const i64 F_RDLCK = 0; /* Establish a read lock */
    pub const i64 F_WRLCK = 1; /* Establish a write lock */
    pub const i64 F_UNLCK = 2; /* Remove a lock */
#else
    pub const i64 F_RDLCK = 1;
    pub const i64 F_UNLCK = 2;
    pub const i64 F_WRLCK = 3;
#endif

pub const i64 O_RDONLY = 0x0000; /* Open exclusively for reading */

pub const i64 O_WRONLY = 0x0001; /* Open exclusively for writing */

pub const i64 O_RDWR = 0x0002; /* Open for reading and writing */

pub const i64 O_ACCMODE = 0x0003; /* Mask to extract access modes */

/* open() flags whose values differ between Linux and macOS */
#ifdef IS_LINUX
    pub const i64 O_CREAT = 0x040; /* Create file if it doesn't exist */
    pub const i64 O_TRUNC = 0x200; /* Truncate existing file to zero length */
    pub const i64 O_EXCL = 0x080; /* Error if file already exists */
    pub const i64 O_NONBLOCK = 0x800; /* Non-blocking mode */
    pub const i64 O_APPEND = 0x400; /* Append data to the end of the file */
    pub const i64 O_CLOEXEC = 0x80000; /* Close the fd in programs started by exec */
#else
    pub const i64 O_CREAT = 0x00000200;
    pub const i64 O_TRUNC = 0x00000400;
    pub const i64 O_EXCL = 0x00000800;
    pub const i64 O_NONBLOCK = 0x00000004;
    pub const i64 O_APPEND = 0x00000008;
    pub const i64 O_CLOEXEC = 0x01000000;
#endif

pub const i64 SEEK_SET = 0; /* Set offset to specified value */

pub const i64 SEEK_CUR = 1; /* Set offset to current position plus value */

pub const i64 SEEK_END = 2; /* Set offset to size of file plus value */

/*
 * The iovec class represents a buffer in memory, with a base address and a length.
 * It's typically used for scatter-gather I/O operations to manage multiple buffers.
 */
pub struct iovec {
    void *iov_base;  /* Pointer to data buffer */
    u64 iov_len;   /* Length of the buffer */
};

pub const i64 DT_UNKNOWN = 0;  /* Type not determined */

pub const i64 DT_FIFO = 1;  /* FIFO/pipe */

pub const i64 DT_CHR = 2;  /* Character device */

pub const i64 DT_DIR = 4;  /* Directory */

pub const i64 DT_BLK = 6;  /* Block device */

pub const i64 DT_REG = 8;  /* Regular file */

pub const i64 DT_LNK = 10;  /* Symbolic link */

pub const i64 DT_SOCK = 12;  /* Socket */

pub const i64 DT_WHT = 14;  /* Whiteout */

pub struct DIR;

#ifdef IS_MACOS
pub const i64 DIR_MAXPATHLEN = (1024);
pub struct Dirent {
    u64 ino;           /* file number of entry */
    u64 seekoff;       /* seek offset (optional, used by servers) */
    u16 reclen;        /* length of this record */
    u16 namlen;        /* length of string in d_name */
    u8 type;           /* file type, one of the DT_* above */
    i8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
#endif

#ifdef IS_LINUX
pub const i64 DIR_MAXPATHLEN = (256);
pub struct Dirent {
    u64 ino;           /* inode number (d_ino) */
    u64 seekoff;       /* position of the next entry, for seekdir (d_off) */
    u16 reclen;        /* length of this record */
    u8 type;           /* file type, one of the DT_* above; DT_UNKNOWN on
                                            * filesystems that don't report it */
    i8 name[DIR_MAXPATHLEN]; /* entry name, NUL-terminated */
};
pub extern "c" DIR *opendir(u8 *dirname);
pub extern "c" Dirent *readdir(DIR *dir);
#endif

/* Mac x86 vs ARM symbol-name divergence -- see dir.ktm. */
#ifdef IS_X86_64
pub _extern _opendir$INODE64 DIR *opendir(u8 *dirname);
pub _extern _readdir$INODE64 Dirent *readdir(DIR *dir);
#endif

#ifdef IS_ARM_64
pub _extern _opendir DIR *opendir(u8 *dirname);
pub _extern _readdir Dirent *readdir(DIR *dir);
#endif

pub extern "c" i32 closedir(DIR *dir);

pub extern "c" void rewinddir(DIR *dir);

pub extern "c" void seekdir(DIR *dir, i64 pos);

pub extern "c" i64 telldir(DIR *dir);

#ifdef IS_MACOS
pub struct cStat {
    i32 st_dev;
    u16 st_mode;
    u16 st_nlink;
    u64 st_ino;
    u32 st_uid;
    u32 st_gid;
    i32 st_rdev;
    timespec st_atimespec;
    timespec st_mtimespec;
    timespec st_ctimespec;
    timespec st_birthtimespec;
    i64 st_size;
    i64 st_blocks;
    i32 st_blksize;
    u32 st_flags;
    u32 st_gen;
    i32 st_lspare;
    i64 st_qspare[2];
};
#elifdef IS_LINUX
#ifdef IS_ARM_64
/* aarch64 uses the generic struct stat, not x86_64's */
pub struct cStat {
    u64 st_dev;
    u64 st_ino;
    u32 st_mode;
    u32 st_nlink;
    u32 st_uid;
    u32 st_gid;
    u64 st_rdev;
    u64 __pad1;
    i64 st_size;
    i32 st_blksize;
    i32 __pad2;
    i64 st_blocks;
    timespec st_atim;
    timespec st_mtim;
    timespec st_ctim;
    i32 __unused[2];
};
#else
pub struct cStat {
    u64 st_dev;
    u64 st_ino;
    u64 st_nlink;
    u32 st_mode;
    u32 st_uid;
    u32 st_gid;
    i32 __pad0;
    u64 st_rdev;
    i64 st_size;
    i64 st_blksize;
    i64 st_blocks;
    timespec st_atim;
    timespec st_mtim;
    timespec st_ctim;
    i64 __glibc_reserved[3];
};
#endif

pub struct statx_timestamp {
    i64  tv_sec;
    u32  tv_nsec;
    i32  __reserved;
};

pub struct Statx {
    u32 stx_mask;
    u32 stx_blksize;
    u64 stx_attributes;
    u32 stx_nlink;
    u32 stx_uid;
    u32 stx_gid;
    u16 stx_mode;
    u16 __spare0[1];
    u64 stx_ino;
    u64 stx_size;
    u64 stx_blocks;
    u64 stx_attributes_mask;
    statx_timestamp  stx_atime;
    statx_timestamp  stx_btime;
    statx_timestamp  stx_ctime;
    statx_timestamp  stx_mtime;
    u32 stx_rdev_major;
    u32 stx_rdev_minor;
    u32 stx_dev_major;
    u32 stx_dev_minor;
    u64 stx_mnt_id;
    u32 stx_dio_mem_align;
    u32 stx_dio_offset_align;
    u64 stx_subvol;
    u32 stx_atomic_write_unit_min;
    u32 stx_atomic_write_unit_max;
    u32 stx_atomic_write_segments_max;
    u32 stx_dio_read_offset_align;
    u32 stx_atomic_write_unit_max_opt;
    u32 __spare2[1];
    u64 __spare3[8];
};

pub const i64 STATX_TYPE = 0x00000001;
pub const i64 STATX_MODE = 0x00000002;
pub const i64 STATX_NLINK = 0x00000004;
pub const i64 STATX_UID = 0x00000008;
pub const i64 STATX_GID = 0x00000010;
pub const i64 STATX_ATIME = 0x00000020;
pub const i64 STATX_MTIME = 0x00000040;
pub const i64 STATX_CTIME = 0x00000080;
pub const i64 STATX_INO = 0x00000100;
pub const i64 STATX_SIZE = 0x00000200;
pub const i64 STATX_BLOCKS = 0x00000400;
pub const i64 STATX_BASIC_STATS = 0x000007ff;
pub const i64 STATX_BTIME = 0x00000800;
pub const i64 STATX_MNT_ID = 0x00001000;
pub const i64 STATX_DIOALIGN = 0x00002000;
pub const i64 STATX_MNT_ID_UNIQUE = 0x00004000;
pub const i64 STATX_SUBVOL = 0x00008000;
pub const i64 STATX_WRITE_ATOMIC = 0x00010000;
pub const i64 STATX_DIO_READ_ALIGN = 0x00020000;

pub const i64 STATX__RESERVED = 0x80000000;
pub const i64 STATX_ALL = 0x00000fff;
pub const i64 STATX_ATTR_COMPRESSED = 0x00000004;
pub const i64 STATX_ATTR_IMMUTABLE = 0x00000010;
pub const i64 STATX_ATTR_APPEND = 0x00000020;
pub const i64 STATX_ATTR_NODUMP = 0x00000040;
pub const i64 STATX_ATTR_ENCRYPTED = 0x00000800;
pub const i64 STATX_ATTR_AUTOMOUNT = 0x00001000;
pub const i64 STATX_ATTR_MOUNT_ROOT = 0x00002000;
pub const i64 STATX_ATTR_VERITY = 0x00100000;
pub const i64 STATX_ATTR_DAX = 0x00200000;
pub const i64 STATX_ATTR_WRITE_ATOMIC = 0x00400000;
pub const i64 STAT_X_AT_FDCWD = -100;
pub const i64 AT_STATX_SYNC_AS_STAT = 0x0000;
pub const i64 STATX_BTIME = 0x00000800;

pub extern "c" i32 statx(i32 __dirfd,
                                                        i8 *__path,
                                                        i32 __flags,
                                                        u32 __mask,
                                                        Statx *stx);
pub extern "c" i32 stat(u8 *pathname, cStat *st);
pub extern "c" i32 lstat(u8 *pathname, cStat *st);
pub extern "c" i32 fstat(i32 fd, cStat *st);
#endif

/* Mac x86 uses the $INODE64 stat variants; ARM uses the plain
 * symbols. Linux is handled above with `extern "c"`. */
#ifdef IS_X86_64
pub _extern _stat$INODE64 i32 stat(u8 *pathname, cStat *st);
pub _extern _lstat$INODE64 i32 lstat(u8 *pathname, cStat *st);
pub _extern _fstat$INODE64 i32 fstat(i32 fd, cStat *st);
#endif

#ifdef IS_ARM_64
pub _extern _stat i32 stat(u8 *pathname, cStat *st);
pub _extern _lstat i32 lstat(u8 *pathname, cStat *st);
pub _extern _fstat i32 fstat(i32 fd, cStat *st);
#endif

pub extern "c" i32 mkdir(u8 *dirname, i32 mode = 438);

pub extern "c" i32 unlink(u8 *pathname);

pub extern "c" i32 rmdir(u8 *dirname);

pub const i64 S_IFMT = 0xF000;  /* Bit mask for the file type bit fields */

pub const i64 S_IFIFO = 0x1000;  /* FIFO or pipe */

pub const i64 S_IFCHR = 0x2000;  /* Character device */

pub const i64 S_IFDIR = 0x4000;  /* Directory */

pub const i64 S_IFBLK = 0x6000;  /* Block device */

pub const i64 S_IFREG = 0x8000;  /* Regular file */

pub const i64 S_IFLNK = 0xA000;  /* Symbolic link */

pub const i64 S_IFSOCK = 0xC000;  /* Socket */

pub const i64 S_IRWXU = 0x1C0;  /* Permission: user (owner) can read, write, execute */

pub const i64 S_IRUSR = 0x100;  /* Permission: user (owner) can read */

pub const i64 S_IWUSR = 0x80;   /* Permission: user (owner) can write */

pub const i64 S_IXUSR = 0x40;   /* Permission: user (owner) can execute */

/* Permissions: group can read, write, execute */
pub const i64 S_IRWXG = 0x38;

pub const i64 S_IRGRP = 0x20;   /* Permission: group can read */

pub const i64 S_IWGRP = 0x10;   /* Permission: group can write */

pub const i64 S_IXGRP = 0x8;    /* Permission: group can execute */

/* Permissions: others can read, write, execute */
pub const i64 S_IRWXO = 0x7;

pub const i64 S_IROTH = 0x4;    /* Permission: others can read */

pub const i64 S_IWOTH = 0x2;    /* Permission: others can write */

pub const i64 S_IXOTH = 0x1;    /* Permission: others can execute */

pub const i64 S_ISUID = 0x800;  /* Set user ID on execution */

pub const i64 S_ISGID = 0x400;  /* Set group ID on execution */

pub const i64 S_ISVTX = 0x200;  /* Save swapped text after use (sticky) */

/* Socket options level */
#ifdef IS_LINUX
pub const i64 SOL_SOCKET = 1;               /* Socket-level options */
#else
pub const i64 SOL_SOCKET = 0xffff;          /* Socket-level options */
#endif

/*
 * Address families.
 */
pub const i64 AF_UNSPEC = 0;               /* Unspecified */

pub const i64 AF_UNIX = 1;               /* Local communication (unix pipes) */

pub const i64 AF_INET = 2;               /* Internet IPv4 */

#ifdef IS_LINUX
pub const i64 AF_INET6 = 10;              /* Internet IPv6 */
#else
pub const i64 AF_INET6 = 30;              /* Internet IPv6 */
#endif

/* Socket types */
pub const i64 SOCK_STREAM = 1;               /* Stream (connection-oriented) socket */

pub const i64 SOCK_DGRAM = 2;               /* Datagram (connectionless) socket */

pub const i64 SOCK_RAW = 3;               /* Raw protocol interface */

pub const i64 SOCK_SEQPACKET = 5;               /* Sequenced packet stream */

#ifdef IS_LINUX
pub const i64 SOCK_CLOEXEC = 0x80000;         /* Or into socket()'s type: close on exec */
#endif

/* Flags for send, recv, sendto, recvfrom, sendmsg and recvmsg */
pub const i64 MSG_OOB = 0x1;             /* Out-of-band data */

pub const i64 MSG_PEEK = 0x2;             /* Read without removing it from the queue */

pub const i64 MSG_DONTROUTE = 0x4;             /* Don't use a gateway */

#ifdef IS_LINUX
pub const i64 MSG_CTRUNC = 0x8;             /* recvmsg: control data was cut short */
pub const i64 MSG_TRUNC = 0x20;            /* recvmsg: datagram was longer than the buffer */
pub const i64 MSG_EOR = 0x80;            /* End of record */
pub const i64 MSG_WAITALL = 0x100;           /* Wait for the full request */
pub const i64 MSG_DONTWAIT = 0x40;            /* This call only: don't block */
pub const i64 MSG_NOSIGNAL = 0x4000;          /* No SIGPIPE on a closed peer: EPIPE */
#else
pub const i64 MSG_CTRUNC = 0x20;            /* recvmsg: control data was cut short */
pub const i64 MSG_TRUNC = 0x10;            /* recvmsg: datagram was longer than the buffer */
pub const i64 MSG_EOR = 0x8;             /* End of record */
pub const i64 MSG_WAITALL = 0x40;            /* Wait for the full request */
pub const i64 MSG_DONTWAIT = 0x80;            /* This call only: don't block */
pub const i64 MSG_NOSIGNAL = 0x80000;         /* No SIGPIPE on a closed peer: EPIPE */
#endif

/*
 * Per-socket option flags.
 */
#ifdef IS_LINUX
pub const i64 SO_DEBUG = 1;               /* Enable debugging information */
pub const i64 SO_ACCEPTCONN = 30;              /* Socket has had listen() applied */
pub const i64 SO_REUSEADDR = 2;               /* Allow reuse of local addresses */
pub const i64 SO_KEEPALIVE = 9;               /* Keep connections active */
pub const i64 SO_DONTROUTE = 5;               /* Route packets through local interfaces */
pub const i64 SO_BROADCAST = 6;               /* Allow broadcast messages */
pub const i64 SO_LINGER = 13;              /* Linger on close if data is present */
pub const i64 SO_OOBINLINE = 10;              /* Leave out-of-band data inline */
pub const i64 SO_REUSEPORT = 15;              /* Allow reuse of local ports */
pub const i64 SO_TIMESTAMP = 29;              /* Timestamp incoming datagrams */
pub const i64 SO_SNDBUF = 7;               /* Send buffer size */
pub const i64 SO_RCVBUF = 8;               /* Receive buffer size */
pub const i64 SO_ERROR = 4;               /* Get and clear the pending error */
pub const i64 SO_RCVTIMEO = 20;              /* Read timeout (a timeval) */
pub const i64 SO_SNDTIMEO = 21;              /* Write timeout (a timeval) */
#else
pub const i64 SO_DEBUG = 0x0001;          /* Enable debugging information */
pub const i64 SO_ACCEPTCONN = 0x0002;          /* Socket has had listen() applied */
pub const i64 SO_REUSEADDR = 0x0004;          /* Allow reuse of local addresses */
pub const i64 SO_KEEPALIVE = 0x0008;          /* Keep connections active */
pub const i64 SO_DONTROUTE = 0x0010;          /* Route packets through local interfaces */
pub const i64 SO_BROADCAST = 0x0020;          /* Allow broadcast messages */
pub const i64 SO_LINGER = 0x1080;          /* Linger on close if data is present */
pub const i64 SO_OOBINLINE = 0x0100;          /* Leave out-of-band data inline */
pub const i64 SO_REUSEPORT = 0x0200;          /* Allow reuse of local ports */
pub const i64 SO_TIMESTAMP = 0x0400;          /* Timestamp incoming datagrams */
pub const i64 SO_TIMESTAMP_MONOTONIC = 0x0800;  /* Use monotonic timestamps for datagrams */
pub const i64 SO_SNDBUF = 0x1001;          /* Send buffer size */
pub const i64 SO_RCVBUF = 0x1002;          /* Receive buffer size */
pub const i64 SO_ERROR = 0x1007;          /* Get and clear the pending error */
pub const i64 SO_RCVTIMEO = 0x1006;          /* Read timeout (a timeval) */
pub const i64 SO_SNDTIMEO = 0x1005;          /* Write timeout (a timeval) */
#endif

/* Protocols */
pub const i64 IPPROTO_IP = 0;               /* Placeholder for IP */

pub const i64 IPPROTO_ICMP = 1;               /* Internet Control Message Protocol */

pub const i64 IPPROTO_IPV4 = 4;               /* IPv4 encapsulation */

pub const i64 IPPROTO_TCP = 6;               /* Transmission Control Protocol */

pub const i64 IPPROTO_UDP = 17;              /* User Datagram Protocol */

pub const i64 IPPROTO_IPV6 = 41;              /* IPv6 protocol */

pub const i64 IPPROTO_ICMPV6 = 58;              /* ICMP for IPv6 */

pub const i64 IPPROTO_RAW = 255;             /* Raw IP packets */

/* IPPROTO_IPV6 level option: an AF_INET6 socket takes IPv6 only, no IPv4 */
#ifdef IS_LINUX
pub const i64 IPV6_V6ONLY = 26;
#else
pub const i64 IPV6_V6ONLY = 27;
#endif

/* TCP options */
#ifdef IS_LINUX
pub const i64 TCP_NODELAY = 1;       /* Disable Nagle's algorithm */
pub const i64 TCP_MAXSEG = 2;       /* Set maximum segment size */
pub const i64 TCP_CORK = 3;       /* Don't send partial frames */
pub const i64 TCP_KEEPIDLE = 4;       /* Set keepalive idle time */
pub const i64 TCP_KEEPALIVE = 4;       /* The macOS name of TCP_KEEPIDLE */
pub const i64 TCP_KEEPINTVL = 5;       /* Interval between keepalives */
pub const i64 TCP_KEEPCNT = 6;       /* Number of keepalives before close */
pub const i64 TCP_INFO = 11;      /* TCP connection state info */
pub const i64 TCP_QUICKACK = 12;      /* Disable/enable delayed acks */
pub const i64 TCP_USER_TIMEOUT = 18;      /* Timeout for unacknowledged data */
pub const i64 TCP_FASTOPEN = 23;      /* TCP Fast Open */
#else
pub const i64 TCP_NODELAY = 0x01;    /* Disable Nagle's algorithm */
pub const i64 TCP_MAXSEG = 0x02;    /* Set maximum segment size */
pub const i64 TCP_NOPUSH = 0x04;    /* Disable push on final write */
pub const i64 TCP_NOOPT = 0x08;    /* Disable TCP options */
pub const i64 TCP_KEEPALIVE = 0x10;    /* Set keepalive idle time */
pub const i64 TCP_CONNECTIONTIMEOUT = 0x20;    /* Set connection timeout */
pub const i64 PERSIST_TIMEOUT = 0x40;    /* Timeout for persist state */
pub const i64 TCP_RXT_CONNDROPTIME = 0x80;    /* Connection drop time for retransmissions */
pub const i64 TCP_RXT_FINDROP = 0x100;   /* Drop connection after retransmitting FIN */
pub const i64 TCP_KEEPINTVL = 0x101;   /* Interval between keepalives */
pub const i64 TCP_KEEPCNT = 0x102;   /* Number of keepalives before close */
pub const i64 TCP_SENDMOREACKS = 0x103;   /* Acknowledge every other packet */
pub const i64 TCP_ENABLE_ECN = 0x104;   /* Enable Explicit Congestion Notification */
pub const i64 TCP_FASTOPEN = 0x105;   /* TCP Fast Open */
pub const i64 TCP_CONNECTION_INFO = 0x106;   /* TCP connection state info */
#endif

pub const i64 INET6_ADDRSTRLEN = 46;      /* Length of string form of IPv6 address */

/* Address info flags */
pub const i64 AI_PASSIVE = 0x00000001; /* Suitable for bind() */

pub const i64 AI_CANONNAME = 0x00000002; /* Request canonical name */

pub const i64 AI_NUMERICHOST = 0x00000004; /* Numeric host address string */

#ifdef IS_LINUX
pub const i64 AI_NUMERICSERV = 0x00000400; /* Numeric service address string */
#else
pub const i64 AI_NUMERICSERV = 0x00001000; /* Numeric service address string */
pub const i64 AI_UNUSABLE = 0x10000000; /* Include unusable addresses */
#endif

/*
 * The hostent class represents an entry from the database of host names and addresses.
 * It includes the official name of the host, aliases, the type of address, the length of the address,
 * and a list of addresses for the host.
 */
pub struct hostent {
    u8	*h_name;	/* Official name of the host */
    u8	**h_aliases;	/* Null-terminated array of alternate names for the host */
    i32 h_addrtype;	/* Type of address; typically AF_INET */
    i32 h_length;		/* Length of each address in bytes */
    u8	**h_addr_list;	/* Null-terminated array of addresses for the host */
};

/*
 * The netent class provides details about a network including its name, aliases, address type,
 * and the network number. The assumption is that a network number can fit in an unsigned long.
 */
pub struct netent {
    u8  *n_name;      /* Official name of the network */
    u8  **n_aliases;  /* Null-terminated array of alternate names for the network */
    i32 n_addrtype;   /* Type of address for the network */
    u32 n_net;        /* Network number */
};

/*
 * The servent class encapsulates information about a service provided over the network,
 * including its official name, aliases, port number, and the protocol used.
 */
pub struct servent {
    u8 *s_name;	  /* Official name of the service */
    u8 **s_aliases; /* Null-terminated array of alternate names for the service */
    i32 s_port;     /* Port number associated with the service */
    u8 *s_proto;    /* Name of the protocol to use with this service */
};

/*
 * The protoent class contains information about a given protocol, such as its name,
 * aliases, and the protocol number.
 */
pub struct protoent {
    u8  *p_name;     /* Official name of the protocol */
    u8  **p_aliases; /* Null-terminated array of alternate names for the protocol */
    i32 p_proto;     /* Protocol number */
};

/*
 * The sockaddr class is a generic descriptor for socket address information,
 * including length, family, and actual address data.
 */
pub struct sockaddr {
#ifdef IS_LINUX
    u16 sa_family;   /* Address family (Linux has no length byte) */
#else
    u8 sa_len;       /* Length of the structure */
    u8 sa_family;    /* Address family */
#endif
    u8 sa_data[14];  /* Actual address information */
};

/*
 * The in_addr class represents an IPv4 Internet address,
 * stored as an unsigned long.
 */
pub struct in_addr {
    u32 s_addr; /* IPv4 address */
};

/*
 * The sockaddr_in class represents an IPv4 socket address,
 * including length, family, port, address, and padding to match the size of sockaddr.
 */
pub struct sockaddr_in {
#ifdef IS_LINUX
    u16 sin_family;     /* Address family, AF_INET */
#else
    u8 sin_len;         /* Length of the structure */
    u8 sin_family;      /* Address family, AF_INET */
#endif
    u16 sin_port;       /* Port number */
    in_addr sin_addr;   /* IPv4 address */
    u8 sin_zero[8];     /* Padding to make structure the same size as sockaddr */
};

/*
 * The in6_addr class is used to store an IPv6 address,
 * with three different representations for convenience.
 */
pub struct in6_addr {
    union
    {
        u8   __u6_addr8[16];  /* 16 bytes of IPv6 address */
        u16  __u6_addr16[8];  /* 8 shorts of IPv6 address */
        u32  __u6_addr32[4];  /* 4 ints of IPv6 address */
    };                      /* Represents a 128-bit IPv6 address */
};

/*
 * The sockaddr_in6 class represents an IPv6 socket address,
 * including length, family, port, flow information, the IPv6 address,
 * and the scope zone index for link-local addresses.
 */
pub struct sockaddr_in6 {
#ifdef IS_LINUX
    u16 sin6_family;     /* Address family, AF_INET6 */
#else
    u8  sin6_len;        /* Length of this struct */
    u8  sin6_family;     /* Address family, AF_INET6 */
#endif
    u16 sin6_port;       /* Transport layer port number */
    u32 sin6_flowinfo;   /* IPv6 flow information */
    in6_addr sin6_addr;  /* IPv6 address */
    u32 sin6_scope_id;   /* Scope zone index */
};

/*
 * The addrinfo class is used for domain name resolution,
 * containing flags, family, socket type, protocol, address length,
 * canonical name, address, and pointer to the next in list.
 */
pub struct addrinfo;

pub struct addrinfo {
    i32 ai_flags;	     /* Input flags */
    i32 ai_family;     /* Address family */
    i32 ai_socktype;   /* Socket type */
    i32 ai_protocol;   /* Protocol for the socket */
    u32 ai_addrlen;    /* Length of the address */
#ifdef IS_LINUX
    sockaddr *ai_addr; /* Binary address */
    u8  *ai_canonname; /* Canonical name for the host */
#else
    u8  *ai_canonname; /* Canonical name for the host */
    sockaddr *ai_addr; /* Binary address */
#endif
    addrinfo *ai_next; /* Next structure in the linked list */
};

pub const i64 _SS_PAD1SIZE = (6);

pub const i64 _SS_PAD2SIZE = (112);

/*
 * The sockaddr_storage class is designed to accommodate all supported address families,
 * ensuring sufficient storage and proper alignment. It's used where a socket address
 * structure's size and family are known, but the exact type is not.
 */
pub struct sockaddr_storage {
#ifdef IS_LINUX
    u16 ss_family;      /* Address family (AF_*) */
#else
    u8  ss_len;         /* Total length of the address */
    u8  ss_family;      /* Address family (AF_*) */
#endif
    u8  __ss_pad1[_SS_PAD1SIZE]; /* Padding to achieve alignment */
    i64 __ss_align;     /* Force alignment of the structure */
    u8  __ss_pad2[_SS_PAD2SIZE]; /* Additional padding to reach desired size */
};

/*
 * The msghdr class is used with message-based socket operations (sendmsg, recvmsg).
 * It includes an optional socket address, scatter/gather array, ancillary data,
 * and flags for received messages.
 */
pub struct msghdr {
    void    *msg_name;      /* Optional address */
    u32   msg_namelen;    /* Size of the address (socklen_t) */
    iovec *msg_iov;       /* Scatter/gather array of buffers */
#ifdef IS_LINUX
    u64   msg_iovlen;     /* Number of elements in msg_iov (size_t) */
    void    *msg_control;   /* Ancillary data */
    u64   msg_controllen; /* Length of ancillary data buffer (size_t) */
#else
    i32   msg_iovlen;     /* Number of elements in msg_iov (int) */
    void    *msg_control;   /* Ancillary data */
    u32   msg_controllen; /* Length of ancillary data buffer (socklen_t) */
#endif
    i32   msg_flags;      /* Flags on received message */
};

/*
 * The sockaddr_un class specifies a local socket address for Unix domain sockets.
 * It includes the address length, family, and path.
 */
pub struct sockaddr_un {
#ifdef IS_LINUX
    u16 sun_family;    /* Address family: AF_UNIX */
    u8 sun_path[108];  /* Path name */
#else
    u8 sun_len;        /* Length of the sockaddr including null */
    u8 sun_family;     /* Address family: AF_UNIX */
    u8 sun_path[104];  /* Path name */
#endif
};

pub extern "c" i32 setsockopt(i32 __sockfd, i32 __level, i32 __option_name,
        void *__option_value, u32 __option_len);

pub extern "c" i32 getsockopt(i32 socket, i32 level, i32 optionName,
        void * optionValue, u32 *optionLen);

pub extern "c" i32 shutdown(i32 socket, i32 how);

pub extern "c" u32 inet_addr(u8 *cp);

pub extern "c" i32 inet_aton(u8 *cp, in_addr *pin);

pub extern "c" u32 inet_lnaof(in_addr in);

pub extern "c" in_addr inet_makeaddr(u32 net, u32 lna);

pub extern "c" u32 inet_netof(in_addr in);

pub extern "c" u32 inet_network(u8 *cp);

pub extern "c" u8 *inet_ntoa(in_addr in);

pub extern "c" u8 *inet_ntop(i32 af, void *src, u8 *dst, i64 size);

pub extern "c" i32 inet_pton(i32 af, u8 *src, void *dst);

pub extern "c" void endhostent();

pub extern "c" void endnetent();

pub extern "c" void endprotoent();

pub extern "c" void endservent();

pub extern "c" void freeaddrinfo(addrinfo *info);

pub extern "c" u8 *gai_strerror(i32 __err);

pub extern "c" i32 getaddrinfo(u8 *hostname, u8 *servname, addrinfo *hints, addrinfo **res);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
pub extern "c" hostent *gethostbyaddr(void *ptr, i64 len, i32 type);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
pub extern "c" hostent *gethostbyname(u8 *name);

pub extern "c" hostent *gethostent();

pub extern "c" i32 getnameinfo(sockaddr *sa, i64 salen, u8 *host,
        i64 hostlen, u8 *serv, i64 servlen, i32 flags);

pub extern "c" netent *getnetbyaddr(u32 net, i32 type);

pub extern "c" netent *getnetbyname(u8 *name);

pub extern "c" netent *getnetent();

pub extern "c" protoent *getprotobyname(u8 *name);

pub extern "c" protoent *getprotobynumber(i32 proto);

pub extern "c" protoent *getprotoent();

pub extern "c" servent *getservbyname(u8 *name, u8 *proto);

pub extern "c" servent *getservbyport(i32 port, u8 *proto);

pub extern "c" servent *getservent();

pub extern "c" void sethostent(i32 stayopen);

pub extern "c" void setnetent(i32 stayopen);

pub extern "c" void setprotoent(i32 stayopen);

pub extern "c" void setservent(i32 stayopen);

pub extern "c" u8 *hstrerror(i32 err);

/* Address and option lengths are a socklen_t in C: 32 bits on Linux and
 * macOS. Pass a u32 for the in/out ones (accept, getpeername, getsockname,
 * recvfrom, getsockopt): the kernel writes only 4 bytes, so the upper half
 * of an i64 would keep whatever was in it. */
pub extern "c" i32 socket(i32 domain, i32 type, i32 protocol);

pub extern "c" i32 connect(i32 socket, sockaddr *address, u32 addressLen);

pub extern "c" i32 accept(i32 socket, sockaddr *address, u32 *addressLen);

pub extern "c" i32 bind(i32 socket, sockaddr *address, u32 addressLen);

pub extern "c" i32 getpeername(i32 socket, sockaddr *address, u32 *addressLen);

pub extern "c" i64 recvmsg(i32 socket, msghdr *message, i32 flags);

pub extern "c" i64 recvfrom(i32 socket, void *buffer, u64 length, i32 flags, sockaddr *address, u32 *addressLen);

pub extern "c" i64 recv(i32 socket, void *buffer, u64 length, i32 flags);

pub extern "c" i32 listen(i32 socket, i32 backlog);

pub extern "c" i32 getsockname(i32 socket, sockaddr *address, u32 *addressLen);

pub extern "c" i64 send(i32 socket, void *buffer, u64 length, i32 flags);

pub extern "c" i64 sendmsg(i32 socket, msghdr *message, i32 flags);

pub extern "c" i64 sendto(i32 socket, void *buffer, u64 length, i32 flags, sockaddr *destAddr, u32 destLen);

/* Writing to a socket whose peer has closed raises SIGPIPE, which kills the
 * process, as in C. NetSend, or MSG_NOSIGNAL in the flags of send/sendto/
 * sendmsg, gives -1 with os.errno() EPIPE instead; signal(SIGPIPE, SIG_IGN)
 * does that for every write in the program. */

/* shutdown(2) `how` */
pub const i64 SHUT_RD = 0;               /* No more receives */

pub const i64 SHUT_WR = 1;               /* No more sends (the peer reads EOF) */

pub const i64 SHUT_RDWR = 2;               /* Both */

/* poll(2): wait until one of nfds descriptors in fds is ready, at most
 * timeout milliseconds (-1: no limit, 0: just check). Returns how many
 * have revents set, 0 on timeout, -1 on error (os.errno() EINTR: retry). */
pub struct pollfd {
    i32 fd;      /* Descriptor to watch; a negative fd is skipped */
    i16 events;  /* POLL* events wanted */
    i16 revents; /* POLL* events that happened, set by poll */
};

pub const i64 POLLIN = 0x1;             /* Data to read (or a connection to accept) */

pub const i64 POLLPRI = 0x2;             /* Urgent data to read */

pub const i64 POLLOUT = 0x4;             /* Writing won't block */

pub const i64 POLLERR = 0x8;             /* Error (revents only) */

pub const i64 POLLHUP = 0x10;            /* Peer hung up (revents only) */

pub const i64 POLLNVAL = 0x20;            /* fd not open (revents only) */

pub extern "c" i32 poll(pollfd *fds, u64 nfds, i32 timeout);
