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
pub struct cTimespec {
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
#define __MUTEX_LOCK_PADDING__   56
#define __MUTEX_LOCK_ATTR_SIZE__ 8

#define __THREAD_COND_SIZE__     40
#define __THREAD_CONDATTR_SIZE__ 8
#define __THREAD_SIZE__          8176
#define __THREAD_ATTR_SIZE__     56

/* MACOS definitions */
struct pthread_mutex_t {
    i64 __sig;
    i8 __opaque[__MUTEX_LOCK_PADDING__];
};

struct pthread_mutexattr_t {
    i64 __sig;
    i8 __opaque[__MUTEX_LOCK_ATTR_SIZE__];
};

struct pthread_cond_t {
    i64 __sig;
    i8 __opaque[__THREAD_COND_SIZE__];
};

struct pthread_condattr_t {
    i64 __sig;
    i8 __opaque[__THREAD_CONDATTR_SIZE__];
};

struct pthread_handler_rec;
struct pthread_handler_rec {
    void (*__routine)(void *__arg); // Routine to call
    void *__arg;                  // Argument to pass
    pthread_handler_rec *__next;
};

struct pthread_attr_t
{  
    i64 __sig;
    i8 __opaque[__THREAD_ATTR_SIZE__];
};

struct pthread_t {
    i64 __sig;
    pthread_handler_rec  *__cleanup_stack;
    i8 __opaque[__THREAD_SIZE__];
};

#elifdef IS_LINUX /* Linux definitions, this needs to be auto generated */

/* glibc's bits/pthreadtypes-arch.h: the sizes differ between x86_64 and aarch64 */
#ifdef IS_X86_64
#define __SIZEOF_PTHREAD_MUTEX_T   40
#define __SIZEOF_PTHREAD_ATTR_T    56
#define __SIZEOF_PTHREAD_RWLOCK_T  56
#define __SIZEOF_PTHREAD_BARRIER_T 32

#define __SIZEOF_PTHREAD_MUTEXATTR_T   4
#define __SIZEOF_PTHREAD_COND_T        48
#define __SIZEOF_PTHREAD_CONDATTR_T    4
#define __SIZEOF_PTHREAD_RWLOCKATTR_T  8
#define __SIZEOF_PTHREAD_BARRIERATTR_T 4
#else /* aarch64 */
#define __SIZEOF_PTHREAD_MUTEX_T   48
#define __SIZEOF_PTHREAD_ATTR_T    64
#define __SIZEOF_PTHREAD_RWLOCK_T  56
#define __SIZEOF_PTHREAD_BARRIER_T 32

#define __SIZEOF_PTHREAD_MUTEXATTR_T   8
#define __SIZEOF_PTHREAD_COND_T        48
#define __SIZEOF_PTHREAD_CONDATTR_T    8
#define __SIZEOF_PTHREAD_RWLOCKATTR_T  8
#define __SIZEOF_PTHREAD_BARRIERATTR_T 8
#endif

union pthread_mutexattr_t {
    i8  __size[__SIZEOF_PTHREAD_MUTEXATTR_T];
    i32 __align;
};

union pthread_condattr_t {
    i8  __size[__SIZEOF_PTHREAD_CONDATTR_T];
    i32 __align;
};

union pthread_attr_t {
    i8  __size[__SIZEOF_PTHREAD_ATTR_T];
    i64 __align;
};


/* this is the spiritual equivilent of typedef  */ 
pub struct pthread_t {
    u64 __padding;
};

struct __pthread_list_t;
struct __pthread_list_t {
    __pthread_list_t *__prev;
    __pthread_list_t *__next;
};

struct __pthread_mutex_s {
    i32 __lock;
    u32 __count;
    i32 __owner;
    u32 __nusers;
    i32 __kind;
    i16 __spins;
    i16 __elision;
    __pthread_list_t __list;
};

union pthread_mutex_t {
    __pthread_mutex_s __data;
    i8  __size[__SIZEOF_PTHREAD_MUTEX_T];
    i64 __align;
};

union __atomic_wide_counter {
    u64 __value64;
    struct
    {
        u32 __low;
        u32 __high;
    };
};

struct __pthread_cond_s {
    __atomic_wide_counter __wseg;
    __atomic_wide_counter __g1_start;
    u32 __g_regs[2];
    u32 __g_size[2];
    u32 __g1_orig_size;
    u32 __wrefs;
    u32 __g_signals[2];
};

union pthread_cond_t {
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

pub extern "c" i32 pthread_detach(pthread_t *th);

pub extern "c" i32 pthread_attr_setstacksize(pthread_attr_t *attr, u64 stacksize);

pub extern "c" i32 pthread_attr_init(pthread_attr_t *attr);

pub extern "c" i32 wait(i32 *statloc);

pub extern "c" i32 waitpid(i32 pid, i32 *statlock, i32 options);

pub extern "c" i32 fork();

/* errno values (the ones networking code checks most); they differ
 * between Linux and macOS, so compare Errno() with these names. */
pub #define ENOENT          2               /* No such file or directory (or program) */

pub #define EINTR           4               /* Interrupted by a signal: retry */

pub #define EBADF           9               /* Bad file descriptor */

pub #define ECHILD          10              /* No child process to wait for */

pub #define EACCES          13              /* Permission denied */

pub #define EINVAL          22              /* Invalid argument */

pub #define ENFILE          23              /* Too many open files in the system */

pub #define EMFILE          24              /* Too many open files in this process */

pub #define EPIPE           32              /* Write to a closed pipe or socket */

#ifdef IS_LINUX
#define EAGAIN          11              /* Would block: try again later */
#define EADDRINUSE      98              /* Address (port) already in use */
#define EADDRNOTAVAIL   99              /* Address not available on this host */
#define ENETUNREACH     101             /* Network unreachable */
#define ECONNRESET      104             /* Connection reset by the peer */
#define ETIMEDOUT       110             /* Timed out */
#define ECONNREFUSED    111             /* Connection refused */
#define EHOSTUNREACH    113             /* No route to host */
#define EINPROGRESS     115             /* Non-blocking connect started */
#else
#define EAGAIN          35              /* Would block: try again later */
#define EINPROGRESS     36              /* Non-blocking connect started */
#define EADDRINUSE      48              /* Address (port) already in use */
#define EADDRNOTAVAIL   49              /* Address not available on this host */
#define ENETUNREACH     51              /* Network unreachable */
#define ECONNRESET      54              /* Connection reset by the peer */
#define ETIMEDOUT       60              /* Timed out */
#define ECONNREFUSED    61              /* Connection refused */
#define EHOSTUNREACH    65              /* No route to host */
#endif

pub #define EWOULDBLOCK     EAGAIN          /* Same value on Linux and macOS */

/* Signals (each platform's numbers) and signal(3). signal returns
 * the previous handler, or SIG_ERR. SIG_IGN on SIGPIPE makes a write to a
 * closed socket or pipe return -1 with Errno() EPIPE instead of ending
 * the program. */
pub #define SIGHUP          1               /* Terminal hung up */

pub #define SIGINT          2               /* Ctrl-C */

pub #define SIGKILL         9               /* Kill (can't be caught or ignored) */

pub #define SIGPIPE         13              /* Write to a closed pipe or socket */

pub #define SIGTERM         15              /* Termination request */

pub #define SIG_DFL         0               /* The default action */

pub #define SIG_IGN         1               /* Ignore the signal */

pub #define SIG_ERR         -1              /* signal() failed */

/* handler is SIG_DFL, SIG_IGN or a void F(i32 sig) as (&F)(i64) */
pub extern "c" i64 signal(i32 sig, i64 handler);

pub #define SIGQUIT         3               /* Ctrl-\ */

pub #define SIGALRM         14              /* alarm() timer */

pub #define SIGTTIN         21              /* Background read from the terminal */

pub #define SIGTTOU         22              /* Background write to the terminal */

#ifdef IS_MACOS
#define SIGUSR1         30              /* For the program's own use */
#define SIGUSR2         31
#define SIGCHLD         20              /* A child process ended */
#define SIGSTOP         17              /* Stop (can't be caught or ignored) */
#define SIGTSTP         18              /* Ctrl-Z */
#define SIGCONT         19              /* Continue a stopped process */
#define SA_RESTART      0x2             /* Restart read/write/... after the handler */
#define SA_RESETHAND    0x4             /* Back to SIG_DFL once the handler ran */
#define SA_NODEFER      0x10            /* Don't block the signal in its handler */
#else
#define SIGUSR1         10              /* For the program's own use */
#define SIGUSR2         12
#define SIGCHLD         17              /* A child process ended */
#define SIGCONT         18              /* Continue a stopped process */
#define SIGSTOP         19              /* Stop (can't be caught or ignored) */
#define SIGTSTP         20              /* Ctrl-Z */
#define SA_RESTART      0x10000000      /* Restart read/write/... after the handler */
#define SA_RESETHAND    0x80000000      /* Back to SIG_DFL once the handler ran */
#define SA_NODEFER      0x40000000      /* Don't block the signal in its handler */
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

pub #define F_DUPFD 0 /* Duplicate a file descriptor */

pub #define F_GETFD 1 /* Retrieve file descriptor flags */

pub #define F_SETFD 2 /* Assign flags to file descriptor */

pub #define F_GETFL 3 /* Get file status flags */

pub #define F_SETFL 4 /* Assign file status flags */

pub #define F_FLUSH_DATA  40 /* Commit data to disk */

pub #define F_CHKCLEAN    41 /* Verify file is not modified */

pub #define F_PREALLOCATE 42 /* Allocate space for file in advance */

pub #define F_SETSIZE     43 /* Adjust file size */

pub #define F_RDADVISE    44 /* Suggest read behavior */

pub #define F_RDAHEAD     45 /* Enable read ahead */

pub #define FD_CLOEXEC 1  /* Close file descriptor on exec */

#ifdef IS_LINUX
  #define F_GETOWN 0x9 /* Retrieve SIGIO/SIGURG process/group */
  #define F_SETOWN 0x8 /* Set SIGIO/SIGURG process/group */
  #define F_GETLK  0x5 /* Query record locking info */
  #define F_SETLK  0x6 /* Set record locking info without waiting */
  #define F_SETLKW 0x7 /* Set record locking info with waiting */
#else
  #define F_GETOWN 0x5
  #define F_SETOWN 0x6
  #define F_GETLK  0x7
  #define F_SETLK  0x8
  #define F_SETLKW 0x9
#endif

/* record locking flags */
#ifdef IS_LINUX
  #define F_RDLCK 0 /* Establish a read lock */
  #define F_WRLCK 1 /* Establish a write lock */
  #define F_UNLCK 2 /* Remove a lock */
#else
  #define F_RDLCK 1
  #define F_UNLCK 2
  #define F_WRLCK 3
#endif

pub #define O_RDONLY  0x0000 /* Open exclusively for reading */

pub #define O_WRONLY  0x0001 /* Open exclusively for writing */

pub #define O_RDWR    0x0002 /* Open for reading and writing */

pub #define O_ACCMODE 0x0003 /* Mask to extract access modes */

/* open() flags whose values differ between Linux and macOS */
#ifdef IS_LINUX
  #define O_CREAT    0x040 /* Create file if it doesn't exist */
  #define O_TRUNC    0x200 /* Truncate existing file to zero length */
  #define O_EXCL     0x080 /* Error if file already exists */
  #define O_NONBLOCK 0x800 /* Non-blocking mode */
  #define O_APPEND   0x400 /* Append data to the end of the file */
  #define O_CLOEXEC  0x80000 /* Close the fd in programs started by exec */
#else
  #define O_CREAT    0x00000200
  #define O_TRUNC    0x00000400
  #define O_EXCL     0x00000800
  #define O_NONBLOCK 0x00000004
  #define O_APPEND   0x00000008
  #define O_CLOEXEC  0x01000000
#endif

pub #define SEEK_SET 0 /* Set offset to specified value */

pub #define SEEK_CUR 1 /* Set offset to current position plus value */

pub #define SEEK_END 2 /* Set offset to size of file plus value */

/*
 * The iovec class represents a buffer in memory, with a base address and a length.
 * It's typically used for scatter-gather I/O operations to manage multiple buffers.
 */
pub struct iovec {
    void *iov_base;  /* Pointer to data buffer */
    u64 iov_len;   /* Length of the buffer */
};

pub #define DT_UNKNOWN 0  /* Type not determined */

pub #define DT_FIFO    1  /* FIFO/pipe */

pub #define DT_CHR     2  /* Character device */

pub #define DT_DIR     4  /* Directory */

pub #define DT_BLK     6  /* Block device */

pub #define DT_REG     8  /* Regular file */

pub #define DT_LNK    10  /* Symbolic link */

pub #define DT_SOCK   12  /* Socket */

pub #define DT_WHT    14  /* Whiteout */

pub extern "c" i32 closedir(cDIR *dir);

pub extern "c" void rewinddir(cDIR *dir);

pub extern "c" void seekdir(cDIR *dir, i64 pos);

pub extern "c" i64 telldir(cDIR *dir);

#ifdef IS_MACOS
pub struct cStat {
    i32 st_dev;
    u16 st_mode;
    u16 st_nlink;
    u64 st_ino;
    u32 st_uid;
    u32 st_gid;
    i32 st_rdev;
    cTimespec st_atimespec;
    cTimespec st_mtimespec;
    cTimespec st_ctimespec;
    cTimespec st_birthtimespec;
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
struct cStat {
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
    cTimespec st_atim;
    cTimespec st_mtim;
    cTimespec st_ctim;
    i32 __unused[2];
};
#else
struct cStat {
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
    cTimespec st_atim;
    cTimespec st_mtim;
    cTimespec st_ctim;
    i64 __glibc_reserved[3];
};
#endif

struct statx_timestamp {
    i64  tv_sec;
    u32  tv_nsec;
    i32  __reserved;
};

struct Statx {
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
#define STATX_BTIME          0x00000800
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
#define STATX_BTIME           0x00000800

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

pub #define S_IFMT      0xF000  /* Bit mask for the file type bit fields */

pub #define S_IFIFO     0x1000  /* FIFO or pipe */

pub #define S_IFCHR     0x2000  /* Character device */

pub #define S_IFDIR     0x4000  /* Directory */

pub #define S_IFBLK     0x6000  /* Block device */

pub #define S_IFREG     0x8000  /* Regular file */

pub #define S_IFLNK     0xA000  /* Symbolic link */

pub #define S_IFSOCK    0xC000  /* Socket */

pub #define S_IRWXU 0x1C0  /* Permission: user (owner) can read, write, execute */

pub #define S_IRUSR 0x100  /* Permission: user (owner) can read */

pub #define S_IWUSR 0x80   /* Permission: user (owner) can write */

pub #define S_IXUSR 0x40   /* Permission: user (owner) can execute */

/* Permissions: group can read, write, execute */
pub #define S_IRWXG 0x38

pub #define S_IRGRP 0x20   /* Permission: group can read */

pub #define S_IWGRP 0x10   /* Permission: group can write */

pub #define S_IXGRP 0x8    /* Permission: group can execute */

/* Permissions: others can read, write, execute */
pub #define S_IRWXO 0x7

pub #define S_IROTH 0x4    /* Permission: others can read */

pub #define S_IWOTH 0x2    /* Permission: others can write */

pub #define S_IXOTH 0x1    /* Permission: others can execute */

pub #define S_ISUID 0x800  /* Set user ID on execution */

pub #define S_ISGID 0x400  /* Set group ID on execution */

pub #define S_ISVTX 0x200  /* Save swapped text after use (sticky) */

/* Socket options level */
#ifdef IS_LINUX
#define SOL_SOCKET      1               /* Socket-level options */
#else
#define SOL_SOCKET      0xffff          /* Socket-level options */
#endif

/*
 * Address families.
 */
pub #define AF_UNSPEC       0               /* Unspecified */

pub #define AF_UNIX         1               /* Local communication (unix pipes) */

pub #define AF_INET         2               /* Internet IPv4 */

#ifdef IS_LINUX
#define AF_INET6        10              /* Internet IPv6 */
#else
#define AF_INET6        30              /* Internet IPv6 */
#endif

/* Socket types */
pub #define SOCK_STREAM     1               /* Stream (connection-oriented) socket */

pub #define SOCK_DGRAM      2               /* Datagram (connectionless) socket */

pub #define SOCK_RAW        3               /* Raw protocol interface */

pub #define SOCK_SEQPACKET  5               /* Sequenced packet stream */

#ifdef IS_LINUX
pub const i64 SOCK_CLOEXEC = 0x80000;         /* Or into socket()'s type: close on exec */
#endif

/* Flags for send, recv, sendto, recvfrom, sendmsg and recvmsg */
pub #define MSG_OOB         0x1             /* Out-of-band data */

pub #define MSG_PEEK        0x2             /* Read without removing it from the queue */

pub #define MSG_DONTROUTE   0x4             /* Don't use a gateway */

#ifdef IS_LINUX
#define MSG_CTRUNC      0x8             /* recvmsg: control data was cut short */
#define MSG_TRUNC       0x20            /* recvmsg: datagram was longer than the buffer */
#define MSG_EOR         0x80            /* End of record */
#define MSG_WAITALL     0x100           /* Wait for the full request */
#define MSG_DONTWAIT    0x40            /* This call only: don't block */
#define MSG_NOSIGNAL    0x4000          /* No SIGPIPE on a closed peer: EPIPE */
#else
#define MSG_CTRUNC      0x20            /* recvmsg: control data was cut short */
#define MSG_TRUNC       0x10            /* recvmsg: datagram was longer than the buffer */
#define MSG_EOR         0x8             /* End of record */
#define MSG_WAITALL     0x40            /* Wait for the full request */
#define MSG_DONTWAIT    0x80            /* This call only: don't block */
#define MSG_NOSIGNAL    0x80000         /* No SIGPIPE on a closed peer: EPIPE */
#endif

/*
 * Per-socket option flags.
 */
#ifdef IS_LINUX
#define SO_DEBUG        1               /* Enable debugging information */
#define SO_ACCEPTCONN   30              /* Socket has had listen() applied */
#define SO_REUSEADDR    2               /* Allow reuse of local addresses */
#define SO_KEEPALIVE    9               /* Keep connections active */
#define SO_DONTROUTE    5               /* Route packets through local interfaces */
#define SO_BROADCAST    6               /* Allow broadcast messages */
#define SO_LINGER       13              /* Linger on close if data is present */
#define SO_OOBINLINE    10              /* Leave out-of-band data inline */
#define SO_REUSEPORT    15              /* Allow reuse of local ports */
#define SO_TIMESTAMP    29              /* Timestamp incoming datagrams */
#define SO_SNDBUF       7               /* Send buffer size */
#define SO_RCVBUF       8               /* Receive buffer size */
#define SO_ERROR        4               /* Get and clear the pending error */
#define SO_RCVTIMEO     20              /* Read timeout (a timeval) */
#define SO_SNDTIMEO     21              /* Write timeout (a timeval) */
#else
#define SO_DEBUG        0x0001          /* Enable debugging information */
#define SO_ACCEPTCONN   0x0002          /* Socket has had listen() applied */
#define SO_REUSEADDR    0x0004          /* Allow reuse of local addresses */
#define SO_KEEPALIVE    0x0008          /* Keep connections active */
#define SO_DONTROUTE    0x0010          /* Route packets through local interfaces */
#define SO_BROADCAST    0x0020          /* Allow broadcast messages */
#define SO_LINGER       0x1080          /* Linger on close if data is present */
#define SO_OOBINLINE    0x0100          /* Leave out-of-band data inline */
#define SO_REUSEPORT    0x0200          /* Allow reuse of local ports */
#define SO_TIMESTAMP    0x0400          /* Timestamp incoming datagrams */
#define SO_TIMESTAMP_MONOTONIC  0x0800  /* Use monotonic timestamps for datagrams */
#define SO_SNDBUF       0x1001          /* Send buffer size */
#define SO_RCVBUF       0x1002          /* Receive buffer size */
#define SO_ERROR        0x1007          /* Get and clear the pending error */
#define SO_RCVTIMEO     0x1006          /* Read timeout (a timeval) */
#define SO_SNDTIMEO     0x1005          /* Write timeout (a timeval) */
#endif

/* Protocols */
pub #define IPPROTO_IP              0               /* Placeholder for IP */

pub #define IPPROTO_ICMP            1               /* Internet Control Message Protocol */

pub #define IPPROTO_IPV4            4               /* IPv4 encapsulation */

pub #define IPPROTO_TCP             6               /* Transmission Control Protocol */

pub #define IPPROTO_UDP             17              /* User Datagram Protocol */

pub #define IPPROTO_IPV6            41              /* IPv6 protocol */

pub #define IPPROTO_ICMPV6          58              /* ICMP for IPv6 */

pub #define IPPROTO_RAW             255             /* Raw IP packets */

/* IPPROTO_IPV6 level option: an AF_INET6 socket takes IPv6 only, no IPv4 */
#ifdef IS_LINUX
#define IPV6_V6ONLY             26
#else
#define IPV6_V6ONLY             27
#endif

/* TCP options */
#ifdef IS_LINUX
#define TCP_NODELAY             1       /* Disable Nagle's algorithm */
#define TCP_MAXSEG              2       /* Set maximum segment size */
pub const i64 TCP_CORK = 3;       /* Don't send partial frames */
pub const i64 TCP_KEEPIDLE = 4;       /* Set keepalive idle time */
#define TCP_KEEPALIVE           4       /* The macOS name of TCP_KEEPIDLE */
#define TCP_KEEPINTVL           5       /* Interval between keepalives */
#define TCP_KEEPCNT             6       /* Number of keepalives before close */
pub const i64 TCP_INFO = 11;      /* TCP connection state info */
pub const i64 TCP_QUICKACK = 12;      /* Disable/enable delayed acks */
pub const i64 TCP_USER_TIMEOUT = 18;      /* Timeout for unacknowledged data */
#define TCP_FASTOPEN            23      /* TCP Fast Open */
#else
#define TCP_NODELAY             0x01    /* Disable Nagle's algorithm */
#define TCP_MAXSEG              0x02    /* Set maximum segment size */
#define TCP_NOPUSH              0x04    /* Disable push on final write */
#define TCP_NOOPT               0x08    /* Disable TCP options */
#define TCP_KEEPALIVE           0x10    /* Set keepalive idle time */
#define TCP_CONNECTIONTIMEOUT   0x20    /* Set connection timeout */
#define PERSIST_TIMEOUT         0x40    /* Timeout for persist state */
#define TCP_RXT_CONNDROPTIME    0x80    /* Connection drop time for retransmissions */
#define TCP_RXT_FINDROP         0x100   /* Drop connection after retransmitting FIN */
#define TCP_KEEPINTVL           0x101   /* Interval between keepalives */
#define TCP_KEEPCNT             0x102   /* Number of keepalives before close */
#define TCP_SENDMOREACKS        0x103   /* Acknowledge every other packet */
#define TCP_ENABLE_ECN          0x104   /* Enable Explicit Congestion Notification */
#define TCP_FASTOPEN            0x105   /* TCP Fast Open */
#define TCP_CONNECTION_INFO     0x106   /* TCP connection state info */
#endif

pub #define INET6_ADDRSTRLEN        46      /* Length of string form of IPv6 address */

/* Address info flags */
pub #define AI_PASSIVE     0x00000001 /* Suitable for bind() */

pub #define AI_CANONNAME   0x00000002 /* Request canonical name */

pub #define AI_NUMERICHOST 0x00000004 /* Numeric host address string */

#ifdef IS_LINUX
#define AI_NUMERICSERV 0x00000400 /* Numeric service address string */
#else
#define AI_NUMERICSERV 0x00001000 /* Numeric service address string */
#define AI_UNUSABLE    0x10000000 /* Include unusable addresses */
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
 * sendmsg, gives -1 with Errno() EPIPE instead; signal(SIGPIPE, SIG_IGN)
 * does that for every write in the program. */

/* shutdown(2) `how` */
pub #define SHUT_RD         0               /* No more receives */

pub #define SHUT_WR         1               /* No more sends (the peer reads EOF) */

pub #define SHUT_RDWR       2               /* Both */

/* poll(2): wait until one of nfds descriptors in fds is ready, at most
 * timeout milliseconds (-1: no limit, 0: just check). Returns how many
 * have revents set, 0 on timeout, -1 on error (Errno() EINTR: retry). */
pub struct pollfd {
    i32 fd;      /* Descriptor to watch; a negative fd is skipped */
    i16 events;  /* POLL* events wanted */
    i16 revents; /* POLL* events that happened, set by poll */
};

pub #define POLLIN          0x1             /* Data to read (or a connection to accept) */

pub #define POLLPRI         0x2             /* Urgent data to read */

pub #define POLLOUT         0x4             /* Writing won't block */

pub #define POLLERR         0x8             /* Error (revents only) */

pub #define POLLHUP         0x10            /* Peer hung up (revents only) */

pub #define POLLNVAL        0x20            /* fd not open (revents only) */

pub extern "c" i32 poll(pollfd *fds, u64 nfds, i32 timeout);
