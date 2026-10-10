/* Running programs without a shell: ProcSpawn starts argv with each of
 * stdin/stdout/stderr inherited, piped to us, /dev/null or (stderr) the
 * child's stdout; ProcWait/ProcDone reap it, ProcKill signals it, and
 * ProcCommunicate feeds stdin and collects both outputs with one poll()
 * loop, so neither side can block the other.
 *
 * Programs are started with posix_spawnp, never fork: fork in a program
 * with threads (or the JIT) can leave the child stuck on a lock another
 * thread held, and macOS doesn't support fork without exec. The child
 * gets default signal handlers and an empty signal mask, and every fd
 * other than 0-2 is closed in it (closefrom on Linux, CLOEXEC_DEFAULT on
 * macOS), so it never inherits our sockets or files. Our own pipe ends
 * are close-on-exec as well.
 *
 * The module installs no SIGCHLD handler and only ever waits for the pid
 * of a Proc, so it doesn't interfere with other code that starts
 * processes. */
#include "./defs.ktmh"

#define PROC_INHERIT 0
#define PROC_PIPE    1
#define PROC_NULL    2
#define PROC_STDOUT  3

#define PROC_WNOHANG        1
#define PROC_F_GETFD        1
#define PROC_F_SETFD        2
#define PROC_F_GETFL        3
#define PROC_F_SETFL        4
#define PROC_FD_CLOEXEC     1
#define PROC_O_RDWR         2
#define PROC_SPAWN_SETPGROUP 0x02
#define PROC_SPAWN_SETSIGDEF 0x04
#define PROC_SPAWN_SETSIGMASK 0x08
#define PROC_SIGPIPE        13
#ifdef IS_MACOS
#define PROC_O_NONBLOCK     0x4
#define PROC_F_DUPFD_CLOEXEC 67
#define PROC_F_SETNOSIGPIPE 73
#define PROC_SPAWN_CLOEXEC_DEFAULT 0x4000
#define PROC_EPIPE          32
#else
#define PROC_O_NONBLOCK     0x800
#define PROC_O_CLOEXEC      0x80000
#define PROC_F_DUPFD_CLOEXEC 1030
#define PROC_SIG_BLOCK      0
#define PROC_SIG_SETMASK    2
#define PROC_EPIPE          32
#endif
#define PROC_EINTR          4
#define PROC_POLLIN         0x1
#define PROC_POLLOUT        0x4
#define PROC_SIGTERM        15
#ifdef IS_MACOS
#define PROC_EAGAIN         35
#else
#define PROC_EAGAIN         11
#endif

/* posix_spawnattr_t / posix_spawn_file_actions_t are structs on glibc
 * (336 / 80 bytes) and one pointer on macOS; sigset_t is 128 bytes on
 * glibc and 4 on macOS. Opaque buffers big enough for all of them. */
#define PROC_SPAWN_BUF 512
#define PROC_SIGSET_BUF 128

extern "c" I32 posix_spawnp(I32 *pid, U8 *file, U0 *file_actions, U0 *attr,
                            U8 **argv, U8 **envp);
extern "c" I32 posix_spawn(I32 *pid, U8 *path, U0 *file_actions, U0 *attr,
                           U8 **argv, U8 **envp);
extern "c" I32 posix_spawn_file_actions_init(U0 *fa);
extern "c" I32 posix_spawn_file_actions_destroy(U0 *fa);
extern "c" I32 posix_spawn_file_actions_adddup2(U0 *fa, I32 fd, I32 newfd);
extern "c" I32 posix_spawn_file_actions_addopen(U0 *fa, I32 fd, U8 *path,
                                                I32 oflag, I32 mode);
extern "c" I32 posix_spawn_file_actions_addchdir_np(U0 *fa, U8 *path);
#ifdef IS_LINUX
extern "c" I32 posix_spawn_file_actions_addclosefrom_np(U0 *fa, I32 from);
#else
extern "c" I32 posix_spawn_file_actions_addinherit_np(U0 *fa, I32 fd);
#endif
extern "c" I32 posix_spawnattr_init(U0 *attr);
extern "c" I32 posix_spawnattr_destroy(U0 *attr);
extern "c" I32 posix_spawnattr_setflags(U0 *attr, I16 flags);
extern "c" I32 posix_spawnattr_setpgroup(U0 *attr, I32 pgroup);
extern "c" I32 posix_spawnattr_setsigdefault(U0 *attr, U0 *sigset);
extern "c" I32 posix_spawnattr_setsigmask(U0 *attr, U0 *sigset);
extern "c" I32 sigemptyset(U0 *set);
extern "c" I32 sigfillset(U0 *set);
extern "c" I32 sigaddset(U0 *set, I32 sig);
extern "c" I32 sigismember(U0 *set, I32 sig);
extern "c" I32 sigpending(U0 *set);
extern "c" I32 pthread_sigmask(I32 how, U0 *set, U0 *oldset);
extern "c" I32 kill(I32 pid, I32 sig);

class ProcPollFd
{
  I32 fd;
  I16 events;
  I16 revents;
};
extern "c" I32 poll(ProcPollFd *fds, U64 nfds, I32 timeout);
extern "c" I32 pipe(I32 *fds);
#ifdef IS_LINUX
extern "c" I32 pipe2(I32 *fds, I32 flags);
extern "c" I32 sigtimedwait(U0 *set, U0 *info, U0 *timeout);
extern "c" U0 *dlsym(U0 *handle, U8 *name);
#else
/* _NSGetEnviron(): the raw Mach-O name, as ktm doesn't prefix names
 * starting with _ */
_extern __NSGetEnviron U8 ***ProcNSGetEnviron();
#endif

public class ProcOpts
{
  I64 in, out, err;
  U8 *cwd;
  U8 **env;
  Bool no_path;
  Bool new_group;
};

public class Proc
{
  I32 pid;
  I32 in_fd, out_fd, err_fd;
  Bool done;
  I64 exit_code;
  I32 term_signal;
  Bool group;
};

/* The current environment, as posix_spawn wants it. */
static U8 **ProcEnviron()
{
#ifdef IS_LINUX
  U8 ***p = dlsym(NULL, "environ");
  if (p) return *p;
  return NULL;
#else
  return *ProcNSGetEnviron();
#endif
}

static U0 ProcClose(I32 *fd)
{
  if (*fd != -1) {
    close(*fd);
    *fd = -1;
  }
}

/* A close-on-exec pipe whose ends are both above 2, so the child's
 * dup2 onto 0-2 never meets an fd that is already in place (and would
 * keep its close-on-exec flag). FALSE on failure. */
static Bool ProcPipe(I32 *fds)
{
  I64 i;
  I32 fd;
#ifdef IS_LINUX
  if (pipe2(fds, PROC_O_CLOEXEC) != 0) return FALSE;
#else
  /* No pipe2: a spawn in another thread between pipe and fcntl is
   * harmless, as every child closes what it didn't ask for. */
  if (pipe(fds) != 0) return FALSE;
  fcntl(fds[0], PROC_F_SETFD, PROC_FD_CLOEXEC);
  fcntl(fds[1], PROC_F_SETFD, PROC_FD_CLOEXEC);
#endif
  for (i = 0; i < 2; i++) {
    if (fds[i] <= 2) {
      fd = fcntl(fds[i], PROC_F_DUPFD_CLOEXEC, 3);
      close(fds[i]);
      fds[i] = fd;
    }
  }
  if (fds[0] == -1 || fds[1] == -1) {
    if (fds[0] != -1) close(fds[0]);
    if (fds[1] != -1) close(fds[1]);
    return FALSE;
  }
  return TRUE;
}

static U0 ProcNonBlocking(I32 fd)
{
  if (fd != -1) fcntl(fd, PROC_F_SETFL, fcntl(fd, PROC_F_GETFL, 0) | PROC_O_NONBLOCK);
}

/* Record a wait status: exited with a code, or killed by a signal. */
static U0 ProcSetStatus(Proc *p, I32 st)
{
  p->done = TRUE;
  if ((st & 0x7F) == 0) {
    p->exit_code = (st >> 8) & 0xFF;
    p->term_signal = 0;
  } else {
    p->exit_code = -1;
    p->term_signal = st & 0x7F;
  }
}

static Proc *ProcSpawnEx(U8 **argv, ProcOpts *opts, Bool like_system);

/* Start argv[0] (looked up in $PATH unless opts->no_path) with the
 * arguments argv, a NULL-terminated list; never through a shell. opts
 * NULL or all zero: the child inherits our stdin, stdout, stderr, working
 * directory and environment. NULL on failure, with Errno() saying why
 * (ENOENT for a program that doesn't exist, EACCES, ...). */
public Proc *ProcSpawn(U8 **argv, ProcOpts *opts=NULL)
{
  return ProcSpawnEx(argv, opts, FALSE);
}

/* ProcSpawn, or with like_system the way system(3) starts its shell:
 * the child keeps every fd that isn't close-on-exec and our signal mask
 * and ignored signals (Shlurp, whose commands may use them). */
static Proc *ProcSpawnEx(U8 **argv, ProcOpts *opts, Bool like_system)
{
  ProcOpts zero;
  /* I64 arrays: the glibc structs want 8-byte alignment */
  I64 fa[PROC_SPAWN_BUF / 8], attr[PROC_SPAWN_BUF / 8];
  I64 sigs[PROC_SIGSET_BUF / 8], nosigs[PROC_SIGSET_BUF / 8];
  I32 pin[2], pout[2], perr[2], pid = 0, err;
  I16 flags = 0;
  U8 **env;

  if (!argv || !argv[0]) {
    SetErrno(22); /* EINVAL */
    return NULL;
  }
  if (!opts) {
    MemSet(&zero, 0, sizeof(ProcOpts));
    opts = &zero;
  }
  pin[0] = pin[1] = pout[0] = pout[1] = perr[0] = perr[1] = -1;
  if ((opts->in == PROC_PIPE && !ProcPipe(pin)) ||
      (opts->out == PROC_PIPE && !ProcPipe(pout)) ||
      (opts->err == PROC_PIPE && !ProcPipe(perr))) {
    err = Errno();
    ProcClose(&pin[0]);
    ProcClose(&pin[1]);
    ProcClose(&pout[0]);
    ProcClose(&pout[1]);
    SetErrno(err);
    return NULL;
  }

  posix_spawn_file_actions_init(fa);
#ifdef IS_MACOS
  /* CLOEXEC_DEFAULT (below) closes every fd the file actions don't name,
   * 0-2 included: name the ones the child inherits. Only open ones: a
   * closed fd named here makes the spawn fail, where Linux just leaves it
   * closed in the child. */
  if (!like_system) {
    if (opts->in == PROC_INHERIT && fcntl(0, PROC_F_GETFD) != -1)
      posix_spawn_file_actions_addinherit_np(fa, 0);
    if ((opts->out == PROC_INHERIT || opts->err == PROC_STDOUT) &&
        fcntl(1, PROC_F_GETFD) != -1)
      posix_spawn_file_actions_addinherit_np(fa, 1);
    if (opts->err == PROC_INHERIT && fcntl(2, PROC_F_GETFD) != -1)
      posix_spawn_file_actions_addinherit_np(fa, 2);
  }
#endif
  if (opts->in == PROC_PIPE) posix_spawn_file_actions_adddup2(fa, pin[0], 0);
  else if (opts->in == PROC_NULL)
    posix_spawn_file_actions_addopen(fa, 0, "/dev/null", PROC_O_RDWR, 0);
  if (opts->out == PROC_PIPE) posix_spawn_file_actions_adddup2(fa, pout[1], 1);
  else if (opts->out == PROC_NULL)
    posix_spawn_file_actions_addopen(fa, 1, "/dev/null", PROC_O_RDWR, 0);
  if (opts->err == PROC_PIPE) posix_spawn_file_actions_adddup2(fa, perr[1], 2);
  else if (opts->err == PROC_NULL)
    posix_spawn_file_actions_addopen(fa, 2, "/dev/null", PROC_O_RDWR, 0);
  else if (opts->err == PROC_STDOUT) posix_spawn_file_actions_adddup2(fa, 1, 2);
  if (opts->cwd) posix_spawn_file_actions_addchdir_np(fa, opts->cwd);
  posix_spawnattr_init(attr);
  if (!like_system) {
#ifdef IS_LINUX
    posix_spawn_file_actions_addclosefrom_np(fa, 3);
#else
    flags |= PROC_SPAWN_CLOEXEC_DEFAULT;
#endif
    flags |= PROC_SPAWN_SETSIGDEF | PROC_SPAWN_SETSIGMASK;
    sigfillset(sigs);
    sigemptyset(nosigs);
    posix_spawnattr_setsigdefault(attr, sigs);
    posix_spawnattr_setsigmask(attr, nosigs);
  }
  if (opts->new_group) {
    flags |= PROC_SPAWN_SETPGROUP;
    posix_spawnattr_setpgroup(attr, 0);
  }
  posix_spawnattr_setflags(attr, flags);

  env = opts->env;
  if (!env) env = ProcEnviron();
  if (opts->no_path) err = posix_spawn(&pid, argv[0], fa, attr, argv, env);
  else               err = posix_spawnp(&pid, argv[0], fa, attr, argv, env);
  posix_spawn_file_actions_destroy(fa);
  posix_spawnattr_destroy(attr);

  /* the child's ends are its own now */
  ProcClose(&pin[0]);
  ProcClose(&pout[1]);
  ProcClose(&perr[1]);
  if (err != 0) {
    ProcClose(&pin[1]);
    ProcClose(&pout[0]);
    ProcClose(&perr[0]);
    SetErrno(err);
    return NULL;
  }

  Proc *p = calloc(1, sizeof(Proc));
  if (!p) {
    "out of memory for a Proc\n";
    Exit(1);
  }
  p->pid = pid;
  p->in_fd = pin[1];
  p->out_fd = pout[0];
  p->err_fd = perr[0];
  p->group = opts->new_group;
#ifdef IS_MACOS
  if (p->in_fd != -1) fcntl(p->in_fd, PROC_F_SETNOSIGPIPE, 1);
#endif
  return p;
}

/* waitpid on p's pid; TRUE once p has been reaped. */
static Bool ProcReap(Proc *p, I32 options)
{
  I32 st = 0, r;
  if (p->done) return TRUE;
  while (TRUE) {
    r = waitpid(p->pid, &st, options);
    if (r == p->pid) {
      ProcSetStatus(p, st);
      return TRUE;
    }
    if (r == 0) return FALSE;            /* WNOHANG: still running */
    if (Errno() == PROC_EINTR) continue;
    /* ECHILD: someone else reaped it (SIGCHLD set to SIG_IGN, or a
     * wait(-1) elsewhere): the status is lost */
    p->done = TRUE;
    p->exit_code = -1;
    p->term_signal = 0;
    return TRUE;
  }
}

/* Wait until p ends. Returns its exit code (0-255), or -1 if a signal
 * killed it (the signal is in p->term_signal). */
public I64 ProcWait(Proc *p)
{
  ProcReap(p, 0);
  return p->exit_code;
}

/* Without waiting: TRUE once p has ended (then reaped: p->exit_code and
 * p->term_signal are set), FALSE while it runs. */
public Bool ProcDone(Proc *p)
{
  return ProcReap(p, PROC_WNOHANG);
}

/* Send sig to p (to its whole process group if it was started with
 * new_group). FALSE if p has already been reaped or kill fails. */
public Bool ProcKill(Proc *p, I32 sig=PROC_SIGTERM)
{
  if (p->done) return FALSE;
  if (p->group) return kill(-p->pid, sig) == 0;
  return kill(p->pid, sig) == 0;
}

/* write() that gives -1 with Errno() EPIPE for a reader that has gone,
 * instead of a SIGPIPE that would end the program. macOS: the fd has
 * F_SETNOSIGPIPE. Linux: SIGPIPE is blocked in this thread around the
 * write and a SIGPIPE the write raised is taken off the pending set. */
static I64 ProcWriteNoSigpipe(I32 fd, U8 *buf, I64 len)
{
#ifdef IS_MACOS
  return write(fd, buf, len);
#else
  I64 set[PROC_SIGSET_BUF / 8], old[PROC_SIGSET_BUF / 8];
  I64 pend[PROC_SIGSET_BUF / 8];
  I64 zero[2];
  Bool was_pending;
  I64 n;
  I32 err;
  sigemptyset(set);
  sigaddset(set, PROC_SIGPIPE);
  pthread_sigmask(PROC_SIG_BLOCK, set, old);
  sigpending(pend);
  was_pending = sigismember(pend, PROC_SIGPIPE) == 1;
  n = write(fd, buf, len);
  err = Errno();
  if (n == -1 && err == PROC_EPIPE && !was_pending) {
    zero[0] = 0;
    zero[1] = 0;
    while (sigtimedwait(set, NULL, zero) == -1 && Errno() == PROC_EINTR) {}
  }
  pthread_sigmask(PROC_SIG_SETMASK, old, NULL);
  SetErrno(err);
  return n;
#endif
}

/* A growing buffer for one output stream. */
class ProcBuf
{
  U8 *data;
  I64 len, cap;
};

/* Read what fd has into b (b NULL: read and drop). FALSE at EOF or on an
 * error other than EAGAIN/EINTR. */
static Bool ProcDrain(I32 fd, ProcBuf *b)
{
  U8 tmp[4096];
  I64 n;
  while (TRUE) {
    n = read(fd, tmp, sizeof(tmp));
    if (n > 0) {
      if (b) {
        if (b->len + n + 1 > b->cap) {
          b->cap = (b->len + n + 1) * 2;
          b->data = ReAlloc(b->data, b->cap);
        }
        MemCpy(b->data + b->len, tmp, n);
        b->len += n;
      }
      continue;
    }
    if (n == 0) return FALSE;
    if (Errno() == PROC_EINTR) continue;
    return Errno() == PROC_EAGAIN;
  }
}

/* Write input (input_len bytes; nothing if NULL) to p's stdin and close
 * it, read p's stdout and stderr until both reach EOF, then wait for p.
 * *out / *err get what was read as a new NUL-terminated string to Free
 * (the length in *out_len / *err_len), or NULL for a stream that isn't a
 * PROC_PIPE; pass NULL to read and drop a stream. A child that exits
 * without reading all of its input is fine: the rest is dropped. Returns
 * what ProcWait returns. */
public I64 ProcCommunicate(Proc *p, U8 *input, I64 input_len,
                           U8 **out, I64 *out_len=NULL,
                           U8 **err=NULL, I64 *err_len=NULL)
{
  ProcBuf ob, eb;
  ProcPollFd fds[3];
  I64 nfds, i, n, sent = 0;
  I32 in_slot, out_slot, err_slot;
  Bool piped_out = p->out_fd != -1, piped_err = p->err_fd != -1;

  MemSet(&ob, 0, sizeof(ProcBuf));
  MemSet(&eb, 0, sizeof(ProcBuf));
  if (!input) input_len = 0;
  if (p->in_fd != -1 && input_len == 0) ProcClose(&p->in_fd);
  ProcNonBlocking(p->in_fd);
  ProcNonBlocking(p->out_fd);
  ProcNonBlocking(p->err_fd);

  while (p->in_fd != -1 || p->out_fd != -1 || p->err_fd != -1) {
    nfds = 0;
    in_slot = out_slot = err_slot = -1;
    if (p->in_fd != -1) {
      in_slot = nfds;
      fds[nfds].fd = p->in_fd;
      fds[nfds++].events = PROC_POLLOUT;
    }
    if (p->out_fd != -1) {
      out_slot = nfds;
      fds[nfds].fd = p->out_fd;
      fds[nfds++].events = PROC_POLLIN;
    }
    if (p->err_fd != -1) {
      err_slot = nfds;
      fds[nfds].fd = p->err_fd;
      fds[nfds++].events = PROC_POLLIN;
    }
    for (i = 0; i < nfds; i++) fds[i].revents = 0;
    if (poll(fds, nfds, -1) < 0) {
      if (Errno() == PROC_EINTR) continue;
      break;
    }
    if (in_slot != -1 && fds[in_slot].revents) {
      n = ProcWriteNoSigpipe(p->in_fd, input + sent, input_len - sent);
      if (n > 0) sent += n;
      if (sent >= input_len ||
          (n == -1 && Errno() != PROC_EAGAIN && Errno() != PROC_EINTR))
        ProcClose(&p->in_fd);              /* done, or the child is gone */
    }
    if (out_slot != -1 && fds[out_slot].revents) {
      if (out) {
        if (!ProcDrain(p->out_fd, &ob)) ProcClose(&p->out_fd);
      } else if (!ProcDrain(p->out_fd, NULL)) {
        ProcClose(&p->out_fd);
      }
    }
    if (err_slot != -1 && fds[err_slot].revents) {
      if (err) {
        if (!ProcDrain(p->err_fd, &eb)) ProcClose(&p->err_fd);
      } else if (!ProcDrain(p->err_fd, NULL)) {
        ProcClose(&p->err_fd);
      }
    }
  }

  if (out) {
    if (piped_out) {
      if (!ob.data) ob.data = MAlloc(1);
      ob.data[ob.len] = 0;
    }
    *out = ob.data;
    if (out_len) *out_len = ob.len;
  }
  if (err) {
    if (piped_err) {
      if (!eb.data) eb.data = MAlloc(1);
      eb.data[eb.len] = 0;
    }
    *err = eb.data;
    if (err_len) *err_len = eb.len;
  }
  return ProcWait(p);
}

/* Close our pipe ends and free p (NULL is ignored). A child that is
 * still running is waited for: ProcKill it first to stop it. */
public U0 ProcRelease(Proc *p)
{
  if (!p) return;
  ProcClose(&p->in_fd);
  ProcClose(&p->out_fd);
  ProcClose(&p->err_fd);
  ProcReap(p, 0);
  free(p);
}

/* Run argv (no shell; stdin /dev/null, stderr inherited) and return what
 * it printed on stdout as a new string to Free, with one trailing newline
 * removed; its exit code (as ProcWait) in *code. NULL if it couldn't be
 * started (Errno() says why). */
public U8 *ProcRun(U8 **argv, I64 *code=NULL)
{
  ProcOpts o;
  U8 *out = NULL;
  I64 len = 0, rc;
  MemSet(&o, 0, sizeof(ProcOpts));
  o.in = PROC_NULL;
  o.out = PROC_PIPE;
  Proc *p = ProcSpawn(argv, &o);
  if (!p) return NULL;
  rc = ProcCommunicate(p, NULL, 0, &out, &len);
  ProcRelease(p);
  if (len > 0 && out[len - 1] == '\n') out[len - 1] = 0;
  if (code) *code = rc;
  return out;
}

/* Shlurp's engine (system.ktm): run `sh -c cmd` like system(3) does, but
 * with stdout piped (stdin and stderr ours), and return what it printed, NUL-terminated, length in
 * *len; NULL if sh can't be started. Reading stops when sh exits, not at
 * EOF: a background job the command started (`cmd &`) keeps the pipe
 * open, and Shlurp has always returned without waiting for it. poll()
 * wakes up every 10 ms to notice that; the usual case ends at EOF. */
U8 *ProcShellOutput(U8 *cmd, I64 *len)
{
  U8 *argv[4];
  ProcOpts o;
  ProcBuf b;
  ProcPollFd fd;
  I32 r;
  argv[0] = "sh";
  argv[1] = "-c";
  argv[2] = cmd;
  argv[3] = NULL;
  MemSet(&o, 0, sizeof(ProcOpts));
  o.out = PROC_PIPE;
  Proc *p = ProcSpawnEx(argv, &o, TRUE);
  if (!p) return NULL;
  MemSet(&b, 0, sizeof(ProcBuf));
  ProcNonBlocking(p->out_fd);
  while (p->out_fd != -1) {
    fd.fd = p->out_fd;
    fd.events = PROC_POLLIN;
    fd.revents = 0;
    r = poll(&fd, 1, 10);
    if (r < 0 && Errno() != PROC_EINTR) break;
    if (r > 0 && !ProcDrain(p->out_fd, &b)) {
      ProcClose(&p->out_fd);               /* EOF */
    } else if (ProcDone(p)) {
      ProcDrain(p->out_fd, &b);            /* what is already there */
      ProcClose(&p->out_fd);
    }
  }
  ProcRelease(p);
  if (!b.data) b.data = MAlloc(1);
  b.data[b.len] = 0;
  *len = b.len;
  return b.data;
}
