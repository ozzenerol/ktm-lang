module c;

public extern "c" I32 open(U8 *__name, I32 flags, ...);

public extern "c" I32 creat(U8 *__name, U16 __flags);

public extern "c" I32 fcntl(I32 __fd, I32 __flags, ...);

public extern "c" I32 close(I32 __fd);

public extern "c" I64 read(I32 __fd, U0 *__buf, U64 __nbyte);

public extern "c" I64 write(I32 __fd, U0 *__buf, U64 __nbyte);

public extern "c" I64 lseek(I32 __fd, I64 __offset, I32 __whence);

public extern "c" I32 chdir(U8 *path);

/* XXX: Normalise stat ? */
public class cTimespec
{
  I64 tv_sec;
  I64 tv_nsec;
};

public class timeval
{ // c compatability
  I64 tv_sec;     /* seconds since Jan. 1, 1970 */
#ifdef IS_LINUX
  I64 tv_usec;    /* and microseconds (a long) */
#else
  I32 tv_usec;    /* and microseconds (suseconds_t, an int) */
  I32 __pad;      /* C pads the struct to 16 bytes */
#endif
};

public class tm 
{     // c compatability
  I32 tm_sec;  /* seconds after the minute [0-60] */
  I32 tm_min;  /* minutes after the hour [0-59] */
  I32 tm_hour;   /* hours since midnight [0-23] */
  I32 tm_mday;   /* day of the month [1-31] */
  I32 tm_mon;  /* months since January [0-11] */
  I32 tm_year;   /* years since 1900 */
  I32 tm_wday;   /* days since Sunday [0-6] */
  I32 tm_yday;   /* days since January 1 [0-365] */
  I32 tm_isdst;  /* Daylight Savings Time flag */
  I64 tm_gmtoff; /* offset from UTC in seconds */
  I8 *tm_zone;   /* timezone abbreviation */
};

public extern "c" tm *localtime(I64 *clock);

public extern "c" I32 gettimeofday(timeval *tp, U0 *tzp);

/* THREADS ==========*/
#ifdef IS_MACOS
#define __MUTEX_LOCK_PADDING__   56
#define __MUTEX_LOCK_ATTR_SIZE__ 8

#define __THREAD_COND_SIZE__     40
#define __THREAD_CONDATTR_SIZE__ 8
#define __THREAD_SIZE__          8176
#define __THREAD_ATTR_SIZE__     56

/* MACOS definitions */
class pthread_mutex_t 
{
  I64 __sig;
  I8 __opaque[__MUTEX_LOCK_PADDING__];
};

class pthread_mutexattr_t
{
  I64 __sig;
  I8 __opaque[__MUTEX_LOCK_ATTR_SIZE__];
};

class pthread_cond_t
{
  I64 __sig;
  I8 __opaque[__THREAD_COND_SIZE__];
};

class pthread_condattr_t
{
  I64 __sig;
  I8 __opaque[__THREAD_CONDATTR_SIZE__];
};

class pthread_handler_rec;
class pthread_handler_rec
{
  U0 (*__routine)(U0 *__arg); // Routine to call
  U0 *__arg;                  // Argument to pass
  pthread_handler_rec *__next;
};

class pthread_attr_t
{  
  I64 __sig;
  I8 __opaque[__THREAD_ATTR_SIZE__];
};

class pthread_t 
{
  I64 __sig;
  pthread_handler_rec  *__cleanup_stack;
  I8 __opaque[__THREAD_SIZE__];
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

union pthread_mutexattr_t
{
  I8  __size[__SIZEOF_PTHREAD_MUTEXATTR_T];
  I32 __align;
};

union pthread_condattr_t
{
  I8  __size[__SIZEOF_PTHREAD_CONDATTR_T];
  I32 __align;
};

union pthread_attr_t
{
  I8  __size[__SIZEOF_PTHREAD_ATTR_T];
  I64 __align;
};


/* this is the spiritual equivilent of typedef  */ 
public class pthread_t {
  U64 __padding;
};

class __pthread_list_t;
class __pthread_list_t
{
  __pthread_list_t *__prev;
  __pthread_list_t *__next;
};

class __pthread_mutex_s
{
  I32 __lock;
  U32 __count;
  I32 __owner;
  U32 __nusers;
  I32 __kind;
  I16 __spins;
  I16 __elision;
  __pthread_list_t __list;
};

union pthread_mutex_t 
{
  __pthread_mutex_s __data;
  I8  __size[__SIZEOF_PTHREAD_MUTEX_T];
  I64 __align;
};

union __atomic_wide_counter
{
  U64 __value64;
  class
  {
    U32 __low;
    U32 __high;
  };
};

class __pthread_cond_s
{
  __atomic_wide_counter __wseg;
  __atomic_wide_counter __g1_start;
  U32 __g_regs[2];
  U32 __g_size[2];
  U32 __g1_orig_size;
  U32 __wrefs;
  U32 __g_signals[2];
};

union pthread_cond_t
{
  __pthread_cond_s __data;
  I8 __size[__SIZEOF_PTHREAD_COND_T];
  I64 __align;
};
#endif

/* The thread handle is the pointer itself: declare `pthread_t *th;`, pass
 * `&th` to pthread_create and `th` to pthread_join/pthread_detach. On Linux
 * the C pthread_t is an unsigned long, so `th` doesn't point at anything:
 * never dereference it. pthread_create returns 0 or an error number. */
public extern "c" I32 pthread_create(pthread_t **th, U0 *thread_attr = NULL,
                U0 *(*function_ptr)(U0 *_argv), U0 *argv = NULL);

public extern "c" I32 pthread_mutex_init(pthread_mutex_t *lk,
                  pthread_mutexattr_t *mutex_attr = NULL);

public extern "c" I32 pthread_mutex_destroy(pthread_mutex_t *lk);

public extern "c" I32 pthread_mutex_lock(pthread_mutex_t *lk);

public extern "c" I32 pthread_mutex_unlock(pthread_mutex_t *lk);

public extern "c" I32 pthread_join(pthread_t *th, U0 *ptr = NULL);

public extern "c" I32 pthread_cond_broadcast(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_destroy(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_init(pthread_cond_t *cond, pthread_condattr_t *attr = NULL);

public extern "c" I32 pthread_cond_signal(pthread_cond_t *cond);

public extern "c" I32 pthread_cond_wait(pthread_cond_t *cond, pthread_mutex_t *lk);

public extern "c" I32 pthread_exit(U0 *data = NULL);

public extern "c" I32 pthread_detach(pthread_t *th);

public extern "c" I32 pthread_attr_setstacksize(pthread_attr_t *attr, U64 stacksize);

public extern "c" I32 pthread_attr_init(pthread_attr_t *attr);

public extern "c" I32 wait(I32 *statloc);

public extern "c" I32 waitpid(I32 pid, I32 *statlock, I32 options);

public extern "c" I32 fork();

/* errno values (the ones networking code checks most); they differ
 * between Linux and macOS, so compare Errno() with these names. */
public #define ENOENT          2               /* No such file or directory (or program) */

public #define EINTR           4               /* Interrupted by a signal: retry */

public #define EBADF           9               /* Bad file descriptor */

public #define ECHILD          10              /* No child process to wait for */

public #define EACCES          13              /* Permission denied */

public #define EINVAL          22              /* Invalid argument */

public #define ENFILE          23              /* Too many open files in the system */

public #define EMFILE          24              /* Too many open files in this process */

public #define EPIPE           32              /* Write to a closed pipe or socket */

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

public #define EWOULDBLOCK     EAGAIN          /* Same value on Linux and macOS */

/* Signals (each platform's numbers) and signal(3). signal returns
 * the previous handler, or SIG_ERR. SIG_IGN on SIGPIPE makes a write to a
 * closed socket or pipe return -1 with Errno() EPIPE instead of ending
 * the program. */
public #define SIGHUP          1               /* Terminal hung up */

public #define SIGINT          2               /* Ctrl-C */

public #define SIGKILL         9               /* Kill (can't be caught or ignored) */

public #define SIGPIPE         13              /* Write to a closed pipe or socket */

public #define SIGTERM         15              /* Termination request */

public #define SIG_DFL         0               /* The default action */

public #define SIG_IGN         1               /* Ignore the signal */

public #define SIG_ERR         -1              /* signal() failed */

/* handler is SIG_DFL, SIG_IGN or a U0 F(I32 sig) as (&F)(I64) */
public extern "c" I64 signal(I32 sig, I64 handler);

public #define SIGQUIT         3               /* Ctrl-\ */

public #define SIGALRM         14              /* alarm() timer */

public #define SIGTTIN         21              /* Background read from the terminal */

public #define SIGTTOU         22              /* Background write to the terminal */

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
public extern "c" I32 raise(I32 sig);

public extern "c" I32 kill(I32 pid, I32 sig);

public extern "c" I32 setjmp(U0 *env);

public extern "c" U0 longjmp(U0 *env, I32 val);

public extern "c" F64 acos(F64 f1);

public extern "c" F64 asin(F64 f1);

public extern "c" F64 atan(F64 f1);

public extern "c" F64 atan2(F64 f1, F64 f2);

public extern "c" F64 cos(F64 f1);

public extern "c" F64 sin(F64 f1);

public extern "c" F64 tan(F64 f1);

public extern "c" F64 acosh(F64 f1);

public extern "c" F64 asinh(F64 f1);

public extern "c" F64 atanh(F64 f1);

public extern "c" F64 cosh(F64 f1);

public extern "c" F64 sinh(F64 f1);

public extern "c" F64 tanh(F64 f1);

public extern "c" F64 exp(F64 f1);

public extern "c" F64 exp2(F64 f1);

public extern "c" F64 expm1(F64 f1);

public extern "c" F64 log(F64 f1);

public extern "c" F64 log10(F64 f1);

public extern "c" F64 log2(F64 f1);

public extern "c" F64 log1p(F64 f1);

public extern "c" F64 logb(F64 f1);

public extern "c" F64 modf(F64 f1, F64 *fptr);

public extern "c" F64 ldexp(F64 f1, I32 i);

public extern "c" F64 frexp(F64 f1, I32 *i);

public extern "c" I32 ilogb(F64 f1);

public extern "c" F64 scalbn(F64 f1, I32 i);

public extern "c" F64 scalbln(F64 f1, I64 i);

public extern "c" F64 fabs(F64 f1);

public extern "c" F64 cbrt(F64 f1);

public extern "c" F64 hypot(F64 f1, F64 f2);

public extern "c" F64 pow(F64 f1, F64 f2);

public extern "c" F64 sqrt(F64 f1);

public extern "c" F64 erf(F64 f1);

public extern "c" F64 erfc(F64 f1);

public extern "c" F64 lgamma(F64 f1);

public extern "c" F64 tgamma(F64 f1);

public extern "c" F64 ceil(F64 f1);

public extern "c" F64 floor(F64 f1);

public extern "c" F64 nearbyint(F64 f1);

public extern "c" F64 rint(F64 f1);

public extern "c" I64 lrint(F64 f1);

public extern "c" F64 round(F64 f1);

public extern "c" I64 lround(F64 f1);

public extern "c" F64 trunc(F64 f1);

public extern "c" F64 fmod(F64 f1, F64 f2);

public extern "c" F64 remainder(F64 f1, F64 f2);

public extern "c" F64 remquo(F64 f1, F64 f2, I32 *ptr);

public extern "c" F64 copysign(F64 f1, F64 f2);

public extern "c" F64 nan(U8 *s);

public extern "c" F64 nextafter(F64 f1, F64 f2);

public extern "c" F64 fdim(F64 f1, F64 f2);

public extern "c" F64 fmax(F64 f1, F64 f2);

public extern "c" F64 fmin(F64 f1, F64 f2);

public extern "c" F64 fma(F64 f1, F64 f2, F64 f3);

public extern "c" U0 *memchr(U0 *__s, I32 __c, U64 __n);

public extern "c" I32 memcmp(U0 *__s1, U0 *__s2, U64 __n);

public extern "c" U0 *memmove(U0 *__dst, U0 *__src, U64 __len);

public extern "c" U0 free(U0 *ptr);

public extern "c" U0 *malloc(U64 size);

public extern "c" F64 strtod(U8 *nptr, U8 **endptr);

public extern "c" I64 strtoll(U8 *nptr, U8 **endptr, I64 base = 10);

public extern "c" U64 strtoul(U8 *nptr, U8 **endptr, I64 base = 10);

public extern "c" I64 snprintf(I8 *nptr, U64 len, I8 *fmt, ...);

public extern "c" I64 printf(I8 *fmt, ...);

public extern "c" I64 strnlen(U8 *s, I64 size);

public extern "c" U8 *strcat(U8 *__s1, U8 *__s2);

public extern "c" U8 *strchr(U8 *__s, I32 __c);

public extern "c" I32 strcmp(U8 *__s1, U8 *__s2);

public extern "c" I32 strcasecmp(U8 *__s1, U8 *__s2);

public extern "c" I32 strncasecmp(U8 *__s1, U8 *__s2, U64 __n);

public extern "c" I64 atol(U8 *__str);

public extern "c" I32 strcoll(U8 *__s1, U8 *__s2);

public extern "c" U8 *strcpy(U8 *__dst, U8 *__src);

public extern "c" U64 strcspn(U8 *__s, U8 *__u8set);

public extern "c" U8 *strerror(I32 __errnum);

public extern "c" U64 strlen(U8 *__s);

public extern "c" U8 *strncat(U8 *__s1, U8 *__s2, U64 __n);

public extern "c" I32 strncmp(U8 *__s1, U8 *__s2, U64 __n);

public extern "c" U8 *strncpy(U8 *__dst, U8 *__src, U64 __n);

public extern "c" U8 *strpbrk(U8 *__s, U8 *__u8set);

public extern "c" U8 *strrchr(U8 *__s, I32 __c);

public extern "c" U8 *strdup(U8 *__s);

public extern "c" U64 strspn(U8 *__s, U8 *__u8set);

public extern "c" U8 *strstr(U8 *__big, U8 *__little);

public extern "c" U8 *strtok(U8 *__str, U8 *__sep);

public extern "c" U64 strxfrm(U8 *__s1, U8 *__s2, U64 __n);

public #define F_DUPFD 0 /* Duplicate a file descriptor */

public #define F_GETFD 1 /* Retrieve file descriptor flags */

public #define F_SETFD 2 /* Assign flags to file descriptor */

public #define F_GETFL 3 /* Get file status flags */

public #define F_SETFL 4 /* Assign file status flags */

public #define F_FLUSH_DATA  40 /* Commit data to disk */

public #define F_CHKCLEAN    41 /* Verify file is not modified */

public #define F_PREALLOCATE 42 /* Allocate space for file in advance */

public #define F_SETSIZE     43 /* Adjust file size */

public #define F_RDADVISE    44 /* Suggest read behavior */

public #define F_RDAHEAD     45 /* Enable read ahead */

public #define FD_CLOEXEC 1  /* Close file descriptor on exec */

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

public #define O_RDONLY  0x0000 /* Open exclusively for reading */

public #define O_WRONLY  0x0001 /* Open exclusively for writing */

public #define O_RDWR    0x0002 /* Open for reading and writing */

public #define O_ACCMODE 0x0003 /* Mask to extract access modes */

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

public #define SEEK_SET 0 /* Set offset to specified value */

public #define SEEK_CUR 1 /* Set offset to current position plus value */

public #define SEEK_END 2 /* Set offset to size of file plus value */

/*
 * The iovec class represents a buffer in memory, with a base address and a length.
 * It's typically used for scatter-gather I/O operations to manage multiple buffers.
 */
public class iovec
{
  U0 *iov_base;  /* Pointer to data buffer */
  U64 iov_len;   /* Length of the buffer */
};

public #define DT_UNKNOWN 0  /* Type not determined */

public #define DT_FIFO    1  /* FIFO/pipe */

public #define DT_CHR     2  /* Character device */

public #define DT_DIR     4  /* Directory */

public #define DT_BLK     6  /* Block device */

public #define DT_REG     8  /* Regular file */

public #define DT_LNK    10  /* Symbolic link */

public #define DT_SOCK   12  /* Socket */

public #define DT_WHT    14  /* Whiteout */

public extern "c" I32 closedir(cDIR *dir);

public extern "c" U0 rewinddir(cDIR *dir);

public extern "c" U0 seekdir(cDIR *dir, I64 pos);

public extern "c" I64 telldir(cDIR *dir);

#ifdef IS_MACOS
public class cStat {
  I32 st_dev;
  U16 st_mode;
  U16 st_nlink;
  U64 st_ino;
  U32 st_uid;
  U32 st_gid;
  I32 st_rdev;
  cTimespec st_atimespec;
  cTimespec st_mtimespec;
  cTimespec st_ctimespec;
  cTimespec st_birthtimespec;
  I64 st_size;
  I64 st_blocks;
  I32 st_blksize;
  U32 st_flags;
  U32 st_gen;
  I32 st_lspare;
  I64 st_qspare[2];
};
#elifdef IS_LINUX
#ifdef IS_ARM_64
/* aarch64 uses the generic struct stat, not x86_64's */
class cStat
{
  U64 st_dev;
  U64 st_ino;
  U32 st_mode;
  U32 st_nlink;
  U32 st_uid;
  U32 st_gid;
  U64 st_rdev;
  U64 __pad1;
  I64 st_size;
  I32 st_blksize;
  I32 __pad2;
  I64 st_blocks;
  cTimespec st_atim;
  cTimespec st_mtim;
  cTimespec st_ctim;
  I32 __unused[2];
};
#else
class cStat
{
  U64 st_dev;
  U64 st_ino;
  U64 st_nlink;
  U32 st_mode;
  U32 st_uid;
  U32 st_gid;
  I32 __pad0;
  U64 st_rdev;
  I64 st_size;
  I64 st_blksize;
  I64 st_blocks;
  cTimespec st_atim;
  cTimespec st_mtim;
  cTimespec st_ctim;
  I64 __glibc_reserved[3];
};
#endif

class statx_timestamp
{
  I64  tv_sec;
  U32  tv_nsec;
  I32  __reserved;
};

class Statx
{
  U32 stx_mask;
  U32 stx_blksize;
  U64 stx_attributes;
  U32 stx_nlink;
  U32 stx_uid;
  U32 stx_gid;
  U16 stx_mode;
  U16 __spare0[1];
  U64 stx_ino;
  U64 stx_size;
  U64 stx_blocks;
  U64 stx_attributes_mask;
  statx_timestamp  stx_atime;
  statx_timestamp  stx_btime;
  statx_timestamp  stx_ctime;
  statx_timestamp  stx_mtime;
  U32 stx_rdev_major;
  U32 stx_rdev_minor;
  U32 stx_dev_major;
  U32 stx_dev_minor;
  U64 stx_mnt_id;
  U32 stx_dio_mem_align;
  U32 stx_dio_offset_align;
  U64 stx_subvol;
  U32 stx_atomic_write_unit_min;
  U32 stx_atomic_write_unit_max;
  U32 stx_atomic_write_segments_max;
  U32 stx_dio_read_offset_align;
  U32 stx_atomic_write_unit_max_opt;
  U32 __spare2[1];
  U64 __spare3[8];
};

#define STATX_TYPE           0x00000001
#define STATX_MODE           0x00000002
#define STATX_NLINK          0x00000004
#define STATX_UID            0x00000008
#define STATX_GID            0x00000010
#define STATX_ATIME          0x00000020
#define STATX_MTIME          0x00000040
#define STATX_CTIME          0x00000080
#define STATX_INO            0x00000100
#define STATX_SIZE           0x00000200
#define STATX_BLOCKS         0x00000400
#define STATX_BASIC_STATS    0x000007ff
#define STATX_BTIME          0x00000800
#define STATX_MNT_ID         0x00001000
#define STATX_DIOALIGN       0x00002000
#define STATX_MNT_ID_UNIQUE  0x00004000
#define STATX_SUBVOL         0x00008000
#define STATX_WRITE_ATOMIC   0x00010000
#define STATX_DIO_READ_ALIGN 0x00020000

#define STATX__RESERVED             0x80000000
#define STATX_ALL                   0x00000fff
#define STATX_ATTR_COMPRESSED       0x00000004
#define STATX_ATTR_IMMUTABLE        0x00000010
#define STATX_ATTR_APPEND           0x00000020
#define STATX_ATTR_NODUMP           0x00000040
#define STATX_ATTR_ENCRYPTED        0x00000800
#define STATX_ATTR_AUTOMOUNT        0x00001000
#define STATX_ATTR_MOUNT_ROOT       0x00002000
#define STATX_ATTR_VERITY           0x00100000
#define STATX_ATTR_DAX              0x00200000
#define STATX_ATTR_WRITE_ATOMIC     0x00400000
#define STAT_X_AT_FDCWD       -100
#define AT_STATX_SYNC_AS_STAT 0x0000
#define STATX_BTIME           0x00000800

public extern "c" I32 statx(I32 __dirfd,
                            I8 *__path,
                            I32 __flags,
                            U32 __mask,
                            Statx *stx);
public extern "c" I32 stat(U8 *pathname, cStat *st);
public extern "c" I32 lstat(U8 *pathname, cStat *st);
public extern "c" I32 fstat(I32 fd, cStat *st);
#endif

/* Mac x86 uses the $INODE64 stat variants; ARM uses the plain
 * symbols. Linux is handled above with `extern "c"`. */
#ifdef IS_X86_64
public _extern _stat$INODE64 I32 stat(U8 *pathname, cStat *st);
public _extern _lstat$INODE64 I32 lstat(U8 *pathname, cStat *st);
public _extern _fstat$INODE64 I32 fstat(I32 fd, cStat *st);
#endif

#ifdef IS_ARM_64
public _extern _stat I32 stat(U8 *pathname, cStat *st);
public _extern _lstat I32 lstat(U8 *pathname, cStat *st);
public _extern _fstat I32 fstat(I32 fd, cStat *st);
#endif

public extern "c" I32 mkdir(U8 *dirname, I32 mode = 438);

public extern "c" I32 unlink(U8 *pathname);

public extern "c" I32 rmdir(U8 *dirname);

public #define S_IFMT      0xF000  /* Bit mask for the file type bit fields */

public #define S_IFIFO     0x1000  /* FIFO or pipe */

public #define S_IFCHR     0x2000  /* Character device */

public #define S_IFDIR     0x4000  /* Directory */

public #define S_IFBLK     0x6000  /* Block device */

public #define S_IFREG     0x8000  /* Regular file */

public #define S_IFLNK     0xA000  /* Symbolic link */

public #define S_IFSOCK    0xC000  /* Socket */

public #define S_IRWXU 0x1C0  /* Permission: user (owner) can read, write, execute */

public #define S_IRUSR 0x100  /* Permission: user (owner) can read */

public #define S_IWUSR 0x80   /* Permission: user (owner) can write */

public #define S_IXUSR 0x40   /* Permission: user (owner) can execute */

/* Permissions: group can read, write, execute */
public #define S_IRWXG 0x38

public #define S_IRGRP 0x20   /* Permission: group can read */

public #define S_IWGRP 0x10   /* Permission: group can write */

public #define S_IXGRP 0x8    /* Permission: group can execute */

/* Permissions: others can read, write, execute */
public #define S_IRWXO 0x7

public #define S_IROTH 0x4    /* Permission: others can read */

public #define S_IWOTH 0x2    /* Permission: others can write */

public #define S_IXOTH 0x1    /* Permission: others can execute */

public #define S_ISUID 0x800  /* Set user ID on execution */

public #define S_ISGID 0x400  /* Set group ID on execution */

public #define S_ISVTX 0x200  /* Save swapped text after use (sticky) */

/* Socket options level */
#ifdef IS_LINUX
#define SOL_SOCKET      1               /* Socket-level options */
#else
#define SOL_SOCKET      0xffff          /* Socket-level options */
#endif

/*
 * Address families.
 */
public #define AF_UNSPEC       0               /* Unspecified */

public #define AF_UNIX         1               /* Local communication (unix pipes) */

public #define AF_INET         2               /* Internet IPv4 */

#ifdef IS_LINUX
#define AF_INET6        10              /* Internet IPv6 */
#else
#define AF_INET6        30              /* Internet IPv6 */
#endif

/* Socket types */
public #define SOCK_STREAM     1               /* Stream (connection-oriented) socket */

public #define SOCK_DGRAM      2               /* Datagram (connectionless) socket */

public #define SOCK_RAW        3               /* Raw protocol interface */

public #define SOCK_SEQPACKET  5               /* Sequenced packet stream */

#ifdef IS_LINUX
#define SOCK_CLOEXEC    0x80000         /* Or into socket()'s type: close on exec */
#endif

/* Flags for send, recv, sendto, recvfrom, sendmsg and recvmsg */
public #define MSG_OOB         0x1             /* Out-of-band data */

public #define MSG_PEEK        0x2             /* Read without removing it from the queue */

public #define MSG_DONTROUTE   0x4             /* Don't use a gateway */

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
public #define IPPROTO_IP              0               /* Placeholder for IP */

public #define IPPROTO_ICMP            1               /* Internet Control Message Protocol */

public #define IPPROTO_IPV4            4               /* IPv4 encapsulation */

public #define IPPROTO_TCP             6               /* Transmission Control Protocol */

public #define IPPROTO_UDP             17              /* User Datagram Protocol */

public #define IPPROTO_IPV6            41              /* IPv6 protocol */

public #define IPPROTO_ICMPV6          58              /* ICMP for IPv6 */

public #define IPPROTO_RAW             255             /* Raw IP packets */

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
#define TCP_CORK                3       /* Don't send partial frames */
#define TCP_KEEPIDLE            4       /* Set keepalive idle time */
#define TCP_KEEPALIVE           4       /* The macOS name of TCP_KEEPIDLE */
#define TCP_KEEPINTVL           5       /* Interval between keepalives */
#define TCP_KEEPCNT             6       /* Number of keepalives before close */
#define TCP_INFO                11      /* TCP connection state info */
#define TCP_QUICKACK            12      /* Disable/enable delayed acks */
#define TCP_USER_TIMEOUT        18      /* Timeout for unacknowledged data */
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

public #define INET6_ADDRSTRLEN        46      /* Length of string form of IPv6 address */

/* Address info flags */
public #define AI_PASSIVE     0x00000001 /* Suitable for bind() */

public #define AI_CANONNAME   0x00000002 /* Request canonical name */

public #define AI_NUMERICHOST 0x00000004 /* Numeric host address string */

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
public class hostent
{
  U8	*h_name;	/* Official name of the host */
  U8	**h_aliases;	/* Null-terminated array of alternate names for the host */
  I32 h_addrtype;	/* Type of address; typically AF_INET */
  I32 h_length;		/* Length of each address in bytes */
  U8	**h_addr_list;	/* Null-terminated array of addresses for the host */
};

/*
 * The netent class provides details about a network including its name, aliases, address type,
 * and the network number. The assumption is that a network number can fit in an unsigned long.
 */
public class netent
{
  U8  *n_name;      /* Official name of the network */
  U8  **n_aliases;  /* Null-terminated array of alternate names for the network */
  I32 n_addrtype;   /* Type of address for the network */
  U32 n_net;        /* Network number */
};

/*
 * The servent class encapsulates information about a service provided over the network,
 * including its official name, aliases, port number, and the protocol used.
 */
public class servent
{
  U8 *s_name;	  /* Official name of the service */
  U8 **s_aliases; /* Null-terminated array of alternate names for the service */
  I32 s_port;     /* Port number associated with the service */
  U8 *s_proto;    /* Name of the protocol to use with this service */
};

/*
 * The protoent class contains information about a given protocol, such as its name,
 * aliases, and the protocol number.
 */
public class protoent
{
  U8  *p_name;     /* Official name of the protocol */
  U8  **p_aliases; /* Null-terminated array of alternate names for the protocol */
  I32 p_proto;     /* Protocol number */
};

/*
 * The sockaddr class is a generic descriptor for socket address information,
 * including length, family, and actual address data.
 */
public class sockaddr
{
#ifdef IS_LINUX
  U16 sa_family;   /* Address family (Linux has no length byte) */
#else
  U8 sa_len;       /* Length of the structure */
  U8 sa_family;    /* Address family */
#endif
  U8 sa_data[14];  /* Actual address information */
};

/*
 * The in_addr class represents an IPv4 Internet address,
 * stored as an unsigned long.
 */
public class in_addr
{
  U32 s_addr; /* IPv4 address */
};

/*
 * The sockaddr_in class represents an IPv4 socket address,
 * including length, family, port, address, and padding to match the size of sockaddr.
 */
public class sockaddr_in
{
#ifdef IS_LINUX
  U16 sin_family;     /* Address family, AF_INET */
#else
  U8 sin_len;         /* Length of the structure */
  U8 sin_family;      /* Address family, AF_INET */
#endif
  U16 sin_port;       /* Port number */
  in_addr sin_addr;   /* IPv4 address */
  U8 sin_zero[8];     /* Padding to make structure the same size as sockaddr */
};

/*
 * The in6_addr class is used to store an IPv6 address,
 * with three different representations for convenience.
 */
public class in6_addr
{
  union
  {
    U8   __u6_addr8[16];  /* 16 bytes of IPv6 address */
    U16  __u6_addr16[8];  /* 8 shorts of IPv6 address */
    U32  __u6_addr32[4];  /* 4 ints of IPv6 address */
  };                      /* Represents a 128-bit IPv6 address */
};

/*
 * The sockaddr_in6 class represents an IPv6 socket address,
 * including length, family, port, flow information, the IPv6 address,
 * and the scope zone index for link-local addresses.
 */
public class sockaddr_in6
{
#ifdef IS_LINUX
  U16 sin6_family;     /* Address family, AF_INET6 */
#else
  U8  sin6_len;        /* Length of this struct */
  U8  sin6_family;     /* Address family, AF_INET6 */
#endif
  U16 sin6_port;       /* Transport layer port number */
  U32 sin6_flowinfo;   /* IPv6 flow information */
  in6_addr sin6_addr;  /* IPv6 address */
  U32 sin6_scope_id;   /* Scope zone index */
};

/*
 * The addrinfo class is used for domain name resolution,
 * containing flags, family, socket type, protocol, address length,
 * canonical name, address, and pointer to the next in list.
 */
public class addrinfo;

/*
 * The sockaddr_storage class is designed to accommodate all supported address families,
 * ensuring sufficient storage and proper alignment. It's used where a socket address
 * structure's size and family are known, but the exact type is not.
 */
public class sockaddr_storage
{
#ifdef IS_LINUX
  U16 ss_family;      /* Address family (AF_*) */
#else
  U8  ss_len;         /* Total length of the address */
  U8  ss_family;      /* Address family (AF_*) */
#endif
  U8  __ss_pad1[_SS_PAD1SIZE]; /* Padding to achieve alignment */
  I64 __ss_align;     /* Force alignment of the structure */
  U8  __ss_pad2[_SS_PAD2SIZE]; /* Additional padding to reach desired size */
};

/*
 * The msghdr class is used with message-based socket operations (sendmsg, recvmsg).
 * It includes an optional socket address, scatter/gather array, ancillary data,
 * and flags for received messages.
 */
public class msghdr
{
  U0    *msg_name;      /* Optional address */
  U32   msg_namelen;    /* Size of the address (socklen_t) */
  iovec *msg_iov;       /* Scatter/gather array of buffers */
#ifdef IS_LINUX
  U64   msg_iovlen;     /* Number of elements in msg_iov (size_t) */
  U0    *msg_control;   /* Ancillary data */
  U64   msg_controllen; /* Length of ancillary data buffer (size_t) */
#else
  I32   msg_iovlen;     /* Number of elements in msg_iov (int) */
  U0    *msg_control;   /* Ancillary data */
  U32   msg_controllen; /* Length of ancillary data buffer (socklen_t) */
#endif
  I32   msg_flags;      /* Flags on received message */
};

/*
 * The sockaddr_un class specifies a local socket address for Unix domain sockets.
 * It includes the address length, family, and path.
 */
public class sockaddr_un
{
#ifdef IS_LINUX
  U16 sun_family;    /* Address family: AF_UNIX */
  U8 sun_path[108];  /* Path name */
#else
  U8 sun_len;        /* Length of the sockaddr including null */
  U8 sun_family;     /* Address family: AF_UNIX */
  U8 sun_path[104];  /* Path name */
#endif
};

public extern "c" I32 setsockopt(I32 __sockfd, I32 __level, I32 __option_name,
    U0 *__option_value, U32 __option_len);

public extern "c" I32 getsockopt(I32 socket, I32 level, I32 option_name,
    U0 * option_value, U32 *option_len);

public extern "c" I32 shutdown(I32 socket, I32 how);

public extern "c" U32 inet_addr(U8 *cp);

public extern "c" I32 inet_aton(U8 *cp, in_addr *pin);

public extern "c" U32 inet_lnaof(in_addr in);

public extern "c" in_addr inet_makeaddr(U32 net, U32 lna);

public extern "c" U32 inet_netof(in_addr in);

public extern "c" U32 inet_network(U8 *cp);

public extern "c" U8 *inet_ntoa(in_addr in);

public extern "c" U8 *inet_ntop(I32 af, U0 *src, U8 *dst, I64 size);

public extern "c" I32 inet_pton(I32 af, U8 *src, U0 *dst);

public extern "c" U0 endhostent();

public extern "c" U0 endnetent();

public extern "c" U0 endprotoent();

public extern "c" U0 endservent();

public extern "c" U0 freeaddrinfo(addrinfo *info);

public extern "c" U8 *gai_strerror(I32 __err);

public extern "c" I32 getaddrinfo(U8 *hostname, U8 *servname, addrinfo *hints, addrinfo **res);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
public extern "c" hostent *gethostbyaddr(U0 *ptr, I64 len, I32 type);

/* Deprecated by POSIX (IPv4 only, not thread-safe): use getaddrinfo */
public extern "c" hostent *gethostbyname(U8 *name);

public extern "c" hostent *gethostent();

public extern "c" I32 getnameinfo(sockaddr *sa, I64 salen, U8 *host,
    I64 hostlen, U8 *serv, I64 servlen, I32 flags);

public extern "c" netent *getnetbyaddr(U32 net, I32 type);

public extern "c" netent *getnetbyname(U8 *name);

public extern "c" netent *getnetent();

public extern "c" protoent *getprotobyname(U8 *name);

public extern "c" protoent *getprotobynumber(I32 proto);

public extern "c" protoent *getprotoent();

public extern "c" servent *getservbyname(U8 *name, U8 *proto);

public extern "c" servent *getservbyport(I32 port, U8 *proto);

public extern "c" servent *getservent();

public extern "c" U0 sethostent(I32 stayopen);

public extern "c" U0 setnetent(I32 stayopen);

public extern "c" U0 setprotoent(I32 stayopen);

public extern "c" U0 setservent(I32 stayopen);

public extern "c" U8 *hstrerror(I32 err);

/* Address and option lengths are a socklen_t in C: 32 bits on Linux and
 * macOS. Pass a U32 for the in/out ones (accept, getpeername, getsockname,
 * recvfrom, getsockopt): the kernel writes only 4 bytes, so the upper half
 * of an I64 would keep whatever was in it. */
public extern "c" I32 socket(I32 domain, I32 type, I32 protocol);

public extern "c" I32 connect(I32 socket, sockaddr *address, U32 address_len);

public extern "c" I32 accept(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I32 bind(I32 socket, sockaddr *address, U32 address_len);

public extern "c" I32 getpeername(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I64 recvmsg(I32 socket, msghdr *message, I32 flags);

public extern "c" I64 recvfrom(I32 socket, U0 *buffer, U64 length, I32 flags, sockaddr *address, U32 *address_len);

public extern "c" I64 recv(I32 socket, U0 *buffer, U64 length, I32 flags);

public extern "c" I32 listen(I32 socket, I32 backlog);

public extern "c" I32 getsockname(I32 socket, sockaddr *address, U32 *address_len);

public extern "c" I64 send(I32 socket, U0 *buffer, U64 length, I32 flags);

public extern "c" I64 sendmsg(I32 socket, msghdr *message, I32 flags);

public extern "c" I64 sendto(I32 socket, U0 *buffer, U64 length, I32 flags, sockaddr *dest_addr, U32 dest_len);

/* Writing to a socket whose peer has closed raises SIGPIPE, which kills the
 * process, as in C. NetSend, or MSG_NOSIGNAL in the flags of send/sendto/
 * sendmsg, gives -1 with Errno() EPIPE instead; signal(SIGPIPE, SIG_IGN)
 * does that for every write in the program. */

/* shutdown(2) `how` */
public #define SHUT_RD         0               /* No more receives */

public #define SHUT_WR         1               /* No more sends (the peer reads EOF) */

public #define SHUT_RDWR       2               /* Both */

/* poll(2): wait until one of nfds descriptors in fds is ready, at most
 * timeout milliseconds (-1: no limit, 0: just check). Returns how many
 * have revents set, 0 on timeout, -1 on error (Errno() EINTR: retry). */
public class pollfd
{
  I32 fd;      /* Descriptor to watch; a negative fd is skipped */
  I16 events;  /* POLL* events wanted */
  I16 revents; /* POLL* events that happened, set by poll */
};

public #define POLLIN          0x1             /* Data to read (or a connection to accept) */

public #define POLLPRI         0x2             /* Urgent data to read */

public #define POLLOUT         0x4             /* Writing won't block */

public #define POLLERR         0x8             /* Error (revents only) */

public #define POLLHUP         0x10            /* Peer hung up (revents only) */

public #define POLLNVAL        0x20            /* fd not open (revents only) */

public extern "c" I32 poll(pollfd *fds, U64 nfds, I32 timeout);
