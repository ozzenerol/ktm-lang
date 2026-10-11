module c;

/* XXX: Normalise stat ? */
public class cTimespec
{
  I64 tv_sec;
  I64 tv_nsec;
};

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

public extern "c" I32 pthread_attr_init(pthread_attr_t *attr);

public extern "c" I32 wait(I32 *statloc);

public extern "c" I32 waitpid(I32 pid, I32 *statlock, I32 options);

public extern "c" I32 fork();

/* errno values (the ones networking code checks most); they differ
 * between Linux and macOS, so compare Errno() with these names. */
#define ENOENT          2               /* No such file or directory (or program) */

#define EINTR           4               /* Interrupted by a signal: retry */

#define EBADF           9               /* Bad file descriptor */

#define ECHILD          10              /* No child process to wait for */

#define EACCES          13              /* Permission denied */

#define EINVAL          22              /* Invalid argument */

#define ENFILE          23              /* Too many open files in the system */

#define EMFILE          24              /* Too many open files in this process */

#define EPIPE           32              /* Write to a closed pipe or socket */

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

#define EWOULDBLOCK     EAGAIN          /* Same value on Linux and macOS */

/* Signals (each platform's numbers) and signal(3). signal returns
 * the previous handler, or SIG_ERR. SIG_IGN on SIGPIPE makes a write to a
 * closed socket or pipe return -1 with Errno() EPIPE instead of ending
 * the program. */
#define SIGHUP          1               /* Terminal hung up */

#define SIGINT          2               /* Ctrl-C */

#define SIGKILL         9               /* Kill (can't be caught or ignored) */

#define SIGPIPE         13              /* Write to a closed pipe or socket */

#define SIGTERM         15              /* Termination request */

#define SIG_DFL         0               /* The default action */

#define SIG_IGN         1               /* Ignore the signal */

#define SIG_ERR         -1              /* signal() failed */

/* handler is SIG_DFL, SIG_IGN or a U0 F(I32 sig) as (&F)(I64) */
public extern "c" I64 signal(I32 sig, I64 handler);

#define SIGQUIT         3               /* Ctrl-\ */

#define SIGALRM         14              /* alarm() timer */

#define SIGTTIN         21              /* Background read from the terminal */

#define SIGTTOU         22              /* Background write to the terminal */

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

extern "c" I32 strcasecmp(U8 *__s1, U8 *__s2);

extern "c" U8 *strdup(U8 *__s);

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

public extern "c" I32 rmdir(U8 *dirname);

#define S_IFMT      0xF000  /* Bit mask for the file type bit fields */

#define S_IFIFO     0x1000  /* FIFO or pipe */

#define S_IFCHR     0x2000  /* Character device */

#define S_IFDIR     0x4000  /* Directory */

#define S_IFBLK     0x6000  /* Block device */

#define S_IFREG     0x8000  /* Regular file */

#define S_IFLNK     0xA000  /* Symbolic link */

#define S_IFSOCK    0xC000  /* Socket */

#define S_IRWXU 0x1C0  /* Permission: user (owner) can read, write, execute */

#define S_IRUSR 0x100  /* Permission: user (owner) can read */

#define S_IWUSR 0x80   /* Permission: user (owner) can write */

#define S_IXUSR 0x40   /* Permission: user (owner) can execute */

/* Permissions: group can read, write, execute */
#define S_IRWXG 0x38

#define S_IRGRP 0x20   /* Permission: group can read */

#define S_IWGRP 0x10   /* Permission: group can write */

#define S_IXGRP 0x8    /* Permission: group can execute */

/* Permissions: others can read, write, execute */
#define S_IRWXO 0x7

#define S_IROTH 0x4    /* Permission: others can read */

#define S_IWOTH 0x2    /* Permission: others can write */

#define S_IXOTH 0x1    /* Permission: others can execute */

#define S_ISUID 0x800  /* Set user ID on execution */

#define S_ISGID 0x400  /* Set group ID on execution */

#define S_ISVTX 0x200  /* Save swapped text after use (sticky) */

/* Socket options level */
#ifdef IS_LINUX
#define SOL_SOCKET      1               /* Socket-level options */
#else
#define SOL_SOCKET      0xffff          /* Socket-level options */
#endif

#ifdef IS_LINUX
#define AF_INET6        10              /* Internet IPv6 */
#else
#define AF_INET6        30              /* Internet IPv6 */
#endif

#ifdef IS_LINUX
#define SOCK_CLOEXEC    0x80000         /* Or into socket()'s type: close on exec */
#endif

/* Flags for send, recv, sendto, recvfrom, sendmsg and recvmsg */
#define MSG_OOB         0x1             /* Out-of-band data */

#define MSG_PEEK        0x2             /* Read without removing it from the queue */

#define MSG_DONTROUTE   0x4             /* Don't use a gateway */

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

#ifdef IS_LINUX
#define AI_NUMERICSERV 0x00000400 /* Numeric service address string */
#else
#define AI_NUMERICSERV 0x00001000 /* Numeric service address string */
#define AI_UNUSABLE    0x10000000 /* Include unusable addresses */
#endif

/* Writing to a socket whose peer has closed raises SIGPIPE, which kills the
 * process, as in C. NetSend, or MSG_NOSIGNAL in the flags of send/sendto/
 * sendmsg, gives -1 with Errno() EPIPE instead; signal(SIGPIPE, SIG_IGN)
 * does that for every write in the program. */

/* shutdown(2) `how` */
#define SHUT_RD         0               /* No more receives */

#define SHUT_WR         1               /* No more sends (the peer reads EOF) */

#define SHUT_RDWR       2               /* Both */

/* poll(2): wait until one of nfds descriptors in fds is ready, at most
 * timeout milliseconds (-1: no limit, 0: just check). Returns how many
 * have revents set, 0 on timeout, -1 on error (Errno() EINTR: retry). */
public class pollfd
{
  I32 fd;      /* Descriptor to watch; a negative fd is skipped */
  I16 events;  /* POLL* events wanted */
  I16 revents; /* POLL* events that happened, set by poll */
};

#define POLLIN          0x1             /* Data to read (or a connection to accept) */

#define POLLPRI         0x2             /* Urgent data to read */

#define POLLOUT         0x4             /* Writing won't block */

#define POLLERR         0x8             /* Error (revents only) */

#define POLLHUP         0x10            /* Peer hung up (revents only) */

#define POLLNVAL        0x20            /* fd not open (revents only) */
