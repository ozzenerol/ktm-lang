module signal;
import c, mem, os;

#ifdef IS_MACOS
#define SIG_SA_FLAGS_AT  12
#define SIG_SA_RESTART   0x2
#define SIG_SA_RESETHAND 0x4
#define SIG_MAX          31
#else
#define SIG_SA_FLAGS_AT  136
#define SIG_SA_RESTART   0x10000000
#define SIG_SA_RESETHAND 0x80000000
#define SIG_MAX          64
#endif

const i64 SIG_SA_BYTES = 160;   /* >= 152, the largest struct sigaction */

const i64 SIG_SIGINT = 2;

const i64 SIG_SIGTERM = 15;

const i64 SIG_F_SETFD = 2;

const i64 SIG_F_GETFL = 3;

const i64 SIG_F_SETFL = 4;

#ifdef IS_MACOS
#define SIG_O_NONBLOCK   0x4
#else
#define SIG_O_NONBLOCK   0x800
#endif

/* A signal action, as SigAction takes and returns it. */
pub struct SigAct {
    i64 handler;  /* SIG_DFL, SIG_IGN, or a void F(i32 sig) as (&F)(i64) */
    u64 mask;     /* signals blocked while it runs: SigBit(s) | ... */
    i32 flags;    /* SA_RESTART, SA_RESETHAND, SA_NODEFER, ... */
};

/* The bit for sig in SigAct.mask. */
pub u64 sigBit(i32 sig) {
    if (sig < 1 || sig > 64) { return 0; }
    return (u64)1 << (sig - 1);
}

/* Set the action for sig to *act (unless act is null) and store the
 * previous one in *old (unless old is null). false on failure, Errno()
 * says why (EINVAL: a bad signal number, or SIGKILL/SIGSTOP). */
pub bool sigAction(i32 sig, SigAct *act, SigAct *old=null) {
    i64 cact[SIG_SA_BYTES / 8], cold[SIG_SA_BYTES / 8];
    i32 s;
    void *pact = null, *pold = null;
    memSet(cact, 0, sizeof(cact));
    memSet(cold, 0, sizeof(cold));
    if (act) {
        cact[0] = act.handler;
        c.sigemptyset((u8 *)cact + 8);
        for (s = 1; s <= SIG_MAX; s++) {
            if (act.mask & sigBit(s)) { c.sigaddset((u8 *)cact + 8, s); }
        }
        *(i32 *)((u8 *)cact + SIG_SA_FLAGS_AT) = act.flags;
        pact = cact;
    }
    if (old) { pold = cold; }
    if (c.sigaction(sig, pact, pold) != 0) { return false; }
    if (old) {
        old.handler = cold[0];
        old.mask = 0;
        for (s = 1; s <= SIG_MAX; s++) {
            if (c.sigismember((u8 *)cold + 8, s) == 1) { old.mask |= sigBit(s); }
        }
        old.flags = *(i32 *)((u8 *)cold + SIG_SA_FLAGS_AT);
    }
    return true;
}

/* SigAction in one call: handler (SIG_DFL, SIG_IGN or (&F)(i64)) for
 * sig, with flags and mask; the previous action in *old if given. */
pub bool sigHandle(i32 sig, i64 handler, i32 flags=SIG_SA_RESTART,
                                            u64 mask=0, SigAct *old=null) {
    SigAct a;
    a.handler = handler;
    a.mask = mask;
    a.flags = flags;
    return sigAction(sig, &a, old);
}

/* The pipe SigStopInit made, -1 before; the signal that asked to stop */
static i32 sigStopRfd = -1, sigStopWfd = -1;

static i64 sigStopSig = 0;

/* Runs in the signal: only a store and write(2), and errno is left as
 * the interrupted code had it. */
static void sigStopHandler(i32 sig) {
    i32 err = errno();
    u8 b = 1;
    sigStopSig = sig;
    c.write(sigStopWfd, &b, 1);    /* non-blocking: a full pipe is fine */
    setErrno(err);
}

/* Make SIGINT and SIGTERM request a clean stop instead of ending the
 * program: each sets SigStopRequested and makes the returned fd
 * readable, for a poll() loop to notice. The handlers use SA_RESTART and
 * SA_RESETHAND, so a second SIGINT or SIGTERM ends the program as usual
 * (a way out if the loop never stops). The fd is non-blocking and
 * close-on-exec; reading it is optional. Returns the same fd on later
 * calls, -1 if the pipe can't be made. Call it once, before starting
 * threads. */
pub i32 sigStopInit() {
    i32 fds[2];
    if (sigStopRfd != -1) { return sigStopRfd; }
    if (c.pipe(fds) != 0) { return -1; }
    c.fcntl(fds[0], SIG_F_SETFD, 1);
    c.fcntl(fds[1], SIG_F_SETFD, 1);
    c.fcntl(fds[0], SIG_F_SETFL, c.fcntl(fds[0], SIG_F_GETFL, 0) | SIG_O_NONBLOCK);
    c.fcntl(fds[1], SIG_F_SETFL, c.fcntl(fds[1], SIG_F_GETFL, 0) | SIG_O_NONBLOCK);
    sigStopRfd = fds[0];
    sigStopWfd = fds[1];
    sigHandle(SIG_SIGINT, (i64)(&sigStopHandler), SIG_SA_RESTART | SIG_SA_RESETHAND);
    sigHandle(SIG_SIGTERM, (i64)(&sigStopHandler), SIG_SA_RESTART | SIG_SA_RESETHAND);
    return sigStopRfd;
}

/* true once SIGINT or SIGTERM arrived after SigStopInit. */
pub bool sigStopRequested() {
    return sigStopSig != 0;
}

/* The signal that requested the stop (SIGINT or SIGTERM), 0 if none. */
pub i32 sigStopSignal() {
    return sigStopSig;
}
