module proc;
import libc, mem, os;

const i64 PROC_WNOHANG = 1;

const i64 PROC_F_GETFD = 1;

const i64 PROC_F_SETFD = 2;

const i64 PROC_F_GETFL = 3;

const i64 PROC_F_SETFL = 4;

const i64 PROC_FD_CLOEXEC = 1;

const i64 PROC_O_RDWR = 2;

const i64 PROC_SPAWN_SETPGROUP = 0x02;

const i64 PROC_SPAWN_SETSIGDEF = 0x04;

const i64 PROC_SPAWN_SETSIGMASK = 0x08;

const i64 PROC_SIGPIPE = 13;

#ifdef IS_MACOS
const i64 PROC_O_NONBLOCK = 0x4;
const i64 PROC_F_DUPFD_CLOEXEC = 67;
const i64 PROC_F_SETNOSIGPIPE = 73;
const i64 PROC_SPAWN_CLOEXEC_DEFAULT = 0x4000;
const i64 PROC_EPIPE = 32;
#else
const i64 PROC_O_NONBLOCK = 0x800;
const i64 PROC_O_CLOEXEC = 0x80000;
const i64 PROC_F_DUPFD_CLOEXEC = 1030;
const i64 PROC_SIG_BLOCK = 0;
const i64 PROC_SIG_SETMASK = 2;
const i64 PROC_EPIPE = 32;
#endif

const i64 PROC_EINTR = 4;

const i64 PROC_POLLIN = 0x1;

const i64 PROC_POLLOUT = 0x4;

const i64 PROC_SIGTERM = 15;

#ifdef IS_MACOS
const i64 PROC_EAGAIN = 35;
#else
const i64 PROC_EAGAIN = 11;
#endif

/* posix_spawnattr_t / posix_spawn_file_actions_t are structs on glibc
 * (336 / 80 bytes) and one pointer on macOS; sigset_t is 128 bytes on
 * glibc and 4 on macOS. Opaque buffers big enough for all of them. */
const i64 PROC_SPAWN_BUF = 512;

const i64 PROC_SIGSET_BUF = 128;

#ifdef IS_LINUX
extern "c" i32 posix_spawn_file_actions_addclosefrom_np(void *fa, i32 from);
#else
extern "c" i32 posix_spawn_file_actions_addinherit_np(void *fa, i32 fd);
#endif

struct ProcPollFd {
    i32 fd;
    i16 events;
    i16 revents;
};

#ifdef IS_LINUX
extern "c" i32 pipe2(i32 *fds, i32 flags);
extern "c" i32 sigtimedwait(void *set, void *info, void *timeout);
extern "c" void *dlsym(void *handle, u8 *name);
#else
/* _NSGetEnviron(): the raw Mach-O name, as ktm doesn't prefix names
 * starting with _ */
_extern __NSGetEnviron u8 ***ProcNSGetEnviron();
#endif

pub struct Opts {
    i64 in, out, err;
    u8 *cwd;
    u8 **env;
    bool noPath;
    bool newGroup;
};

pub struct Proc {
    i32 pid;
    i32 inFd, outFd, errFd;
    bool done;
    i64 exitCode;
    i32 termSignal;
    bool group;
};

/* The current environment, as posix_spawn wants it. */
static u8 **procEnviron() {
#ifdef IS_LINUX
    u8 ***p = dlsym(null, "environ");
    if (p) { return *p; }
    return null;
#else
    return *ProcNSGetEnviron();
#endif
}

static void procClose(i32 *fd) {
    if (*fd != -1) {
        libc.close(*fd);
        *fd = -1;
    }
}

/* A close-on-exec pipe whose ends are both above 2, so the child's
 * dup2 onto 0-2 never meets an fd that is already in place (and would
 * keep its close-on-exec flag). false on failure. */
static bool procPipe(i32 *fds) {
    i64 i;
    i32 fd;
#ifdef IS_LINUX
    if (pipe2(fds, PROC_O_CLOEXEC) != 0) { return false; }
#else
    /* No pipe2: a spawn in another thread between pipe and fcntl is
     * harmless, as every child closes what it didn't ask for. */
    if (libc.pipe(fds) != 0) return false;
    libc.fcntl(fds[0], PROC_F_SETFD, PROC_FD_CLOEXEC);
    libc.fcntl(fds[1], PROC_F_SETFD, PROC_FD_CLOEXEC);
#endif
    for (i = 0; i < 2; i++) {
        if (fds[i] <= 2) {
            fd = libc.fcntl(fds[i], PROC_F_DUPFD_CLOEXEC, 3);
            libc.close(fds[i]);
            fds[i] = fd;
        }
    }
    if (fds[0] == -1 || fds[1] == -1) {
        if (fds[0] != -1) { libc.close(fds[0]); }
        if (fds[1] != -1) { libc.close(fds[1]); }
        return false;
    }
    return true;
}

static void procNonBlocking(i32 fd) {
    if (fd != -1) { libc.fcntl(fd, PROC_F_SETFL, libc.fcntl(fd, PROC_F_GETFL, 0) | PROC_O_NONBLOCK); }
}

/* Record a wait status: exited with a code, or killed by a signal. */
static void procSetStatus(Proc *p, i32 st) {
    p.done = true;
    if ((st & 0x7F) == 0) {
        p.exitCode = (st >> 8) & 0xFF;
        p.termSignal = 0;
    } else {
        p.exitCode = -1;
        p.termSignal = st & 0x7F;
    }
}

/* Start argv[0] (looked up in $PATH unless opts->no_path) with the
 * arguments argv, a null-terminated list; never through a shell. opts
 * null or all zero: the child inherits our stdin, stdout, stderr, working
 * directory and environment. null on failure, with Errno() saying why
 * (ENOENT for a program that doesn't exist, EACCES, ...). */
pub Proc *spawn(u8 **argv, Opts *opts=null) {
    return procSpawnEx(argv, opts, false);
}

/* ProcSpawn, or with like_system the way system(3) starts its shell:
 * the child keeps every fd that isn't close-on-exec and our signal mask
 * and ignored signals (Shlurp, whose commands may use them). */
static Proc *procSpawnEx(u8 **argv, Opts *opts, bool likeSystem) {
    Opts zero;
    /* i64 arrays: the glibc structs want 8-byte alignment */
    i64 fa[PROC_SPAWN_BUF / 8], attr[PROC_SPAWN_BUF / 8];
    i64 sigs[PROC_SIGSET_BUF / 8], nosigs[PROC_SIGSET_BUF / 8];
    i32 pin[2], pout[2], perr[2], pid = 0, err;
    i16 flags = 0;
    u8 **env;

    if (!argv || !argv[0]) {
        os.setErrno(22); /* EINVAL */
        return null;
    }
    if (!opts) {
        mem.set(&zero, 0, sizeof(Opts));
        opts = &zero;
    }
    pin[0] = pin[1] = pout[0] = pout[1] = perr[0] = perr[1] = -1;
    if ((opts.in == PIPE && !procPipe(pin)) ||
            (opts.out == PIPE && !procPipe(pout)) ||
            (opts.err == PIPE && !procPipe(perr))) {
        err = os.errno();
        procClose(&pin[0]);
        procClose(&pin[1]);
        procClose(&pout[0]);
        procClose(&pout[1]);
        os.setErrno(err);
        return null;
    }

    libc.posix_spawn_file_actions_init(fa);
#ifdef IS_MACOS
    /* CLOEXEC_DEFAULT (below) closes every fd the file actions don't name,
     * 0-2 included: name the ones the child inherits. Only open ones: a
     * closed fd named here makes the spawn fail, where Linux just leaves it
     * closed in the child. */
    if (!like_system) {
        if (opts->in == INHERIT && libc.fcntl(0, PROC_F_GETFD) != -1)
            posix_spawn_file_actions_addinherit_np(fa, 0);
        if ((opts->out == INHERIT || opts->err == STDOUT) &&
                libc.fcntl(1, PROC_F_GETFD) != -1)
            posix_spawn_file_actions_addinherit_np(fa, 1);
        if (opts->err == INHERIT && libc.fcntl(2, PROC_F_GETFD) != -1)
            posix_spawn_file_actions_addinherit_np(fa, 2);
    }
#endif
    if (opts.in == PIPE) { libc.posix_spawn_file_actions_adddup2(fa, pin[0], 0); }
    else if (opts.in == DEVNULL) {
        libc.posix_spawn_file_actions_addopen(fa, 0, "/dev/null", PROC_O_RDWR, 0);
    }
    if (opts.out == PIPE) { libc.posix_spawn_file_actions_adddup2(fa, pout[1], 1); }
    else if (opts.out == DEVNULL) {
        libc.posix_spawn_file_actions_addopen(fa, 1, "/dev/null", PROC_O_RDWR, 0);
    }
    if (opts.err == PIPE) { libc.posix_spawn_file_actions_adddup2(fa, perr[1], 2); }
    else if (opts.err == DEVNULL) {
        libc.posix_spawn_file_actions_addopen(fa, 2, "/dev/null", PROC_O_RDWR, 0);
    } else if (opts.err == STDOUT) { libc.posix_spawn_file_actions_adddup2(fa, 1, 2); }
    if (opts.cwd) { libc.posix_spawn_file_actions_addchdir_np(fa, opts.cwd); }
    libc.posix_spawnattr_init(attr);
    if (!likeSystem) {
#ifdef IS_LINUX
        posix_spawn_file_actions_addclosefrom_np(fa, 3);
#else
        flags |= PROC_SPAWN_CLOEXEC_DEFAULT;
#endif
        flags |= PROC_SPAWN_SETSIGDEF | PROC_SPAWN_SETSIGMASK;
        libc.sigfillset(sigs);
        libc.sigemptyset(nosigs);
        libc.posix_spawnattr_setsigdefault(attr, sigs);
        libc.posix_spawnattr_setsigmask(attr, nosigs);
    }
    if (opts.newGroup) {
        flags |= PROC_SPAWN_SETPGROUP;
        libc.posix_spawnattr_setpgroup(attr, 0);
    }
    libc.posix_spawnattr_setflags(attr, flags);

    env = opts.env;
    if (!env) { env = procEnviron(); }
    if (opts.noPath) { err = libc.posix_spawn(&pid, argv[0], fa, attr, argv, env); }
    else               { err = libc.posix_spawnp(&pid, argv[0], fa, attr, argv, env); }
    libc.posix_spawn_file_actions_destroy(fa);
    libc.posix_spawnattr_destroy(attr);

    /* the child's ends are its own now */
    procClose(&pin[0]);
    procClose(&pout[1]);
    procClose(&perr[1]);
    if (err != 0) {
        procClose(&pin[1]);
        procClose(&pout[0]);
        procClose(&perr[0]);
        os.setErrno(err);
        return null;
    }

    Proc *p = libc.calloc(1, sizeof(Proc));
    if (!p) {
        printf("out of memory for a Proc\n");
        os.exit(1);
    }
    p.pid = pid;
    p.inFd = pin[1];
    p.outFd = pout[0];
    p.errFd = perr[0];
    p.group = opts.newGroup;
#ifdef IS_MACOS
    if (p->in_fd != -1) libc.fcntl(p->in_fd, PROC_F_SETNOSIGPIPE, 1);
#endif
    return p;
}

/* waitpid on p's pid; true once p has been reaped. */
static bool procReap(Proc *p, i32 options) {
    i32 st = 0, r;
    if (p.done) { return true; }
    while (true) {
        r = libc.waitpid(p.pid, &st, options);
        if (r == p.pid) {
            procSetStatus(p, st);
            return true;
        }
        if (r == 0) { return false; }            /* WNOHANG: still running */
        if (os.errno() == PROC_EINTR) { continue; }
        /* ECHILD: someone else reaped it (SIGCHLD set to SIG_IGN, or a
         * wait(-1) elsewhere): the status is lost */
        p.done = true;
        p.exitCode = -1;
        p.termSignal = 0;
        return true;
    }
}

/* Wait until p ends. Returns its exit code (0-255), or -1 if a signal
 * killed it (the signal is in p->term_signal). */
pub i64 wait(Proc *p) {
    procReap(p, 0);
    return p.exitCode;
}

/* Without waiting: true once p has ended (then reaped: p->exit_code and
 * p->term_signal are set), false while it runs. */
pub bool done(Proc *p) {
    return procReap(p, PROC_WNOHANG);
}

/* Send sig to p (to its whole process group if it was started with
 * new_group). false if p has already been reaped or kill fails. */
pub bool kill(Proc *p, i32 sig=PROC_SIGTERM) {
    if (p.done) { return false; }
    if (p.group) { return libc.kill(-p.pid, sig) == 0; }
    return libc.kill(p.pid, sig) == 0;
}

/* write() that gives -1 with Errno() EPIPE for a reader that has gone,
 * instead of a SIGPIPE that would end the program. macOS: the fd has
 * F_SETNOSIGPIPE. Linux: SIGPIPE is blocked in this thread around the
 * write and a SIGPIPE the write raised is taken off the pending set. */
static i64 procWriteNoSigpipe(i32 fd, u8 *buf, i64 len) {
#ifdef IS_MACOS
    return libc.write(fd, buf, len);
#else
    i64 set[PROC_SIGSET_BUF / 8], old[PROC_SIGSET_BUF / 8];
    i64 pend[PROC_SIGSET_BUF / 8];
    i64 zero[2];
    bool wasPending;
    i64 n;
    i32 err;
    libc.sigemptyset(set);
    libc.sigaddset(set, PROC_SIGPIPE);
    libc.pthread_sigmask(PROC_SIG_BLOCK, set, old);
    libc.sigpending(pend);
    wasPending = libc.sigismember(pend, PROC_SIGPIPE) == 1;
    n = libc.write(fd, buf, len);
    err = os.errno();
    if (n == -1 && err == PROC_EPIPE && !wasPending) {
        zero[0] = 0;
        zero[1] = 0;
        while (sigtimedwait(set, null, zero) == -1 && os.errno() == PROC_EINTR) {}
    }
    libc.pthread_sigmask(PROC_SIG_SETMASK, old, null);
    os.setErrno(err);
    return n;
#endif
}

/* A growing buffer for one output stream. */
struct ProcBuf {
    u8 *data;
    i64 len, cap;
};

/* Read what fd has into b (b null: read and drop). false at EOF or on an
 * error other than EAGAIN/EINTR. */
static bool procDrain(i32 fd, ProcBuf *b) {
    u8 tmp[4096];
    i64 n;
    while (true) {
        n = libc.read(fd, tmp, sizeof(tmp));
        if (n > 0) {
            if (b) {
                if (b.len + n + 1 > b.cap) {
                    b.cap = (b.len + n + 1) * 2;
                    b.data = mem.realloc(b.data, b.cap);
                }
                mem.copy(b.data + b.len, tmp, n);
                b.len += n;
            }
            continue;
        }
        if (n == 0) { return false; }
        if (os.errno() == PROC_EINTR) { continue; }
        return os.errno() == PROC_EAGAIN;
    }
}

/* Write input (input_len bytes; nothing if null) to p's stdin and close
 * it, read p's stdout and stderr until both reach EOF, then wait for p.
 * *out / *err get what was read as a new NUL-terminated string to Free
 * (the length in *out_len / *err_len), or null for a stream that isn't a
 * PROC_PIPE; pass null to read and drop a stream. A child that exits
 * without reading all of its input is fine: the rest is dropped. Returns
 * what ProcWait returns. */
pub i64 communicate(Proc *p, u8 *input, i64 inputLen,
                                                     u8 **out, i64 *outLen=null,
                                                     u8 **err=null, i64 *errLen=null) {
    ProcBuf ob, eb;
    ProcPollFd fds[3];
    i64 nfds, i, n, sent = 0;
    i32 inSlot, outSlot, errSlot;
    bool pipedOut = p.outFd != -1, pipedErr = p.errFd != -1;

    mem.set(&ob, 0, sizeof(ProcBuf));
    mem.set(&eb, 0, sizeof(ProcBuf));
    if (!input) { inputLen = 0; }
    if (p.inFd != -1 && inputLen == 0) { procClose(&p.inFd); }
    procNonBlocking(p.inFd);
    procNonBlocking(p.outFd);
    procNonBlocking(p.errFd);

    while (p.inFd != -1 || p.outFd != -1 || p.errFd != -1) {
        nfds = 0;
        inSlot = outSlot = errSlot = -1;
        if (p.inFd != -1) {
            inSlot = nfds;
            fds[nfds].fd = p.inFd;
            fds[nfds++].events = PROC_POLLOUT;
        }
        if (p.outFd != -1) {
            outSlot = nfds;
            fds[nfds].fd = p.outFd;
            fds[nfds++].events = PROC_POLLIN;
        }
        if (p.errFd != -1) {
            errSlot = nfds;
            fds[nfds].fd = p.errFd;
            fds[nfds++].events = PROC_POLLIN;
        }
        for (i = 0; i < nfds; i++) { fds[i].revents = 0; }
        if (libc.poll(fds, nfds, -1) < 0) {
            if (os.errno() == PROC_EINTR) { continue; }
            break;
        }
        if (inSlot != -1 && fds[inSlot].revents) {
            n = procWriteNoSigpipe(p.inFd, input + sent, inputLen - sent);
            if (n > 0) { sent += n; }
            if (sent >= inputLen ||
                    (n == -1 && os.errno() != PROC_EAGAIN && os.errno() != PROC_EINTR)) {
                procClose(&p.inFd);
                    }              /* done, or the child is gone */
        }
        if (outSlot != -1 && fds[outSlot].revents) {
            if (out) {
                if (!procDrain(p.outFd, &ob)) { procClose(&p.outFd); }
            } else if (!procDrain(p.outFd, null)) {
                procClose(&p.outFd);
            }
        }
        if (errSlot != -1 && fds[errSlot].revents) {
            if (err) {
                if (!procDrain(p.errFd, &eb)) { procClose(&p.errFd); }
            } else if (!procDrain(p.errFd, null)) {
                procClose(&p.errFd);
            }
        }
    }

    if (out) {
        if (pipedOut) {
            if (!ob.data) { ob.data = mem.alloc(1); }
            ob.data[ob.len] = 0;
        }
        *out = ob.data;
        if (outLen) { *outLen = ob.len; }
    }
    if (err) {
        if (pipedErr) {
            if (!eb.data) { eb.data = mem.alloc(1); }
            eb.data[eb.len] = 0;
        }
        *err = eb.data;
        if (errLen) { *errLen = eb.len; }
    }
    return wait(p);
}

/* Close our pipe ends and free p (null is ignored). A child that is
 * still running is waited for: ProcKill it first to stop it. */
pub void release(Proc *p) {
    if (!p) { return; }
    procClose(&p.inFd);
    procClose(&p.outFd);
    procClose(&p.errFd);
    procReap(p, 0);
    libc.free(p);
}

/* Run argv (no shell; stdin /dev/null, stderr inherited) and return what
 * it printed on stdout as a new string to Free, with one trailing newline
 * removed; its exit code (as ProcWait) in *code. null if it couldn't be
 * started (Errno() says why). */
pub u8 *run(u8 **argv, i64 *code=null) {
    Opts o;
    u8 *out = null;
    i64 len = 0, rc;
    mem.set(&o, 0, sizeof(Opts));
    o.in = DEVNULL;
    o.out = PIPE;
    Proc *p = spawn(argv, &o);
    if (!p) { return null; }
    rc = communicate(p, null, 0, &out, &len);
    release(p);
    if (len > 0 && out[len - 1] == '\n') { out[len - 1] = 0; }
    if (code) { *code = rc; }
    return out;
}

/* Shlurp's engine (system.ktm): run `sh -c cmd` like system(3) does, but
 * with stdout piped (stdin and stderr ours), and return what it printed, NUL-terminated, length in
 * *len; null if sh can't be started. Reading stops when sh exits, not at
 * EOF: a background job the command started (`cmd &`) keeps the pipe
 * open, and Shlurp has always returned without waiting for it. poll()
 * wakes up every 10 ms to notice that; the usual case ends at EOF. */
u8 *procShellOutput(u8 *cmd, i64 *len) {
    u8 *argv[4];
    Opts o;
    ProcBuf b;
    ProcPollFd fd;
    i32 r;
    argv[0] = "sh";
    argv[1] = "-c";
    argv[2] = cmd;
    argv[3] = null;
    mem.set(&o, 0, sizeof(Opts));
    o.out = PIPE;
    Proc *p = procSpawnEx(argv, &o, true);
    if (!p) { return null; }
    mem.set(&b, 0, sizeof(ProcBuf));
    procNonBlocking(p.outFd);
    while (p.outFd != -1) {
        fd.fd = p.outFd;
        fd.events = PROC_POLLIN;
        fd.revents = 0;
        r = libc.poll(&fd, 1, 10);
        if (r < 0 && os.errno() != PROC_EINTR) { break; }
        if (r > 0 && !procDrain(p.outFd, &b)) {
            procClose(&p.outFd);               /* EOF */
        } else if (done(p)) {
            procDrain(p.outFd, &b);            /* what is already there */
            procClose(&p.outFd);
        }
    }
    release(p);
    if (!b.data) { b.data = mem.alloc(1); }
    b.data[b.len] = 0;
    *len = b.len;
    return b.data;
}
