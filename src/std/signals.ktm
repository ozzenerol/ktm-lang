/* Signal handling with sigaction(2): SigAction installs a handler, SIG_IGN
 * or SIG_DFL with flags (SA_RESTART ...) and a mask of signals blocked
 * while the handler runs, and gives back the previous action. SigHandle
 * is the one-line form.
 *
 * SigStopInit is the clean-shutdown helper for programs built around a
 * poll() loop: after it, SIGINT or SIGTERM sets a flag (SigStopRequested)
 * and makes a pipe readable (the fd it returns), so the loop wakes up and
 * can stop at a point of its choosing.
 *
 * struct sigaction differs per platform (checked in C): glibc on x86_64
 * and aarch64 has the handler at 0, a 128-byte sigset_t at 8 and the
 * int flags at 136 (152 bytes in all); macOS has the handler at 0, a
 * 4-byte sigset_t at 8 and the flags at 12 (16 bytes). */
#include "./defs.ktmh"

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
#define SIG_SA_BYTES     160   /* >= 152, the largest struct sigaction */
#define SIG_SIGINT       2
#define SIG_SIGTERM      15
#define SIG_F_SETFD      2
#define SIG_F_GETFL      3
#define SIG_F_SETFL      4
#ifdef IS_MACOS
#define SIG_O_NONBLOCK   0x4
#else
#define SIG_O_NONBLOCK   0x800
#endif

extern "c" I32 sigaction(I32 sig, U0 *act, U0 *oldact);
extern "c" I32 sigemptyset(U0 *set);
extern "c" I32 sigaddset(U0 *set, I32 sig);
extern "c" I32 sigismember(U0 *set, I32 sig);
extern "c" I32 pipe(I32 *fds);

/* A signal action, as SigAction takes and returns it. */
public class SigAct
{
  I64 handler;  /* SIG_DFL, SIG_IGN, or a U0 F(I32 sig) as (&F)(I64) */
  U64 mask;     /* signals blocked while it runs: SigBit(s) | ... */
  I32 flags;    /* SA_RESTART, SA_RESETHAND, SA_NODEFER, ... */
};

/* The bit for sig in SigAct.mask. */
public U64 SigBit(I32 sig)
{
  if (sig < 1 || sig > 64) return 0;
  return 1(U64) << (sig - 1);
}

/* Set the action for sig to *act (unless act is NULL) and store the
 * previous one in *old (unless old is NULL). FALSE on failure, Errno()
 * says why (EINVAL: a bad signal number, or SIGKILL/SIGSTOP). */
public Bool SigAction(I32 sig, SigAct *act, SigAct *old=NULL)
{
  I64 cact[SIG_SA_BYTES / 8], cold[SIG_SA_BYTES / 8];
  I32 s;
  U0 *pact = NULL, *pold = NULL;
  MemSet(cact, 0, sizeof(cact));
  MemSet(cold, 0, sizeof(cold));
  if (act) {
    cact[0] = act->handler;
    sigemptyset(cact(U8 *) + 8);
    for (s = 1; s <= SIG_MAX; s++)
      if (act->mask & SigBit(s)) sigaddset(cact(U8 *) + 8, s);
    *(cact(U8 *) + SIG_SA_FLAGS_AT)(I32 *) = act->flags;
    pact = cact;
  }
  if (old) pold = cold;
  if (sigaction(sig, pact, pold) != 0) return FALSE;
  if (old) {
    old->handler = cold[0];
    old->mask = 0;
    for (s = 1; s <= SIG_MAX; s++)
      if (sigismember(cold(U8 *) + 8, s) == 1) old->mask |= SigBit(s);
    old->flags = *(cold(U8 *) + SIG_SA_FLAGS_AT)(I32 *);
  }
  return TRUE;
}

/* SigAction in one call: handler (SIG_DFL, SIG_IGN or (&F)(I64)) for
 * sig, with flags and mask; the previous action in *old if given. */
public Bool SigHandle(I32 sig, I64 handler, I32 flags=SIG_SA_RESTART,
                      U64 mask=0, SigAct *old=NULL)
{
  SigAct a;
  a.handler = handler;
  a.mask = mask;
  a.flags = flags;
  return SigAction(sig, &a, old);
}

/* The pipe SigStopInit made, -1 before; the signal that asked to stop */
static I32 sig_stop_rfd = -1, sig_stop_wfd = -1;
static I64 sig_stop_sig = 0;

/* Runs in the signal: only a store and write(2), and errno is left as
 * the interrupted code had it. */
static U0 SigStopHandler(I32 sig)
{
  I32 err = Errno();
  U8 b = 1;
  sig_stop_sig = sig;
  write(sig_stop_wfd, &b, 1);    /* non-blocking: a full pipe is fine */
  SetErrno(err);
}

/* Make SIGINT and SIGTERM request a clean stop instead of ending the
 * program: each sets SigStopRequested and makes the returned fd
 * readable, for a poll() loop to notice. The handlers use SA_RESTART and
 * SA_RESETHAND, so a second SIGINT or SIGTERM ends the program as usual
 * (a way out if the loop never stops). The fd is non-blocking and
 * close-on-exec; reading it is optional. Returns the same fd on later
 * calls, -1 if the pipe can't be made. Call it once, before starting
 * threads. */
public I32 SigStopInit()
{
  I32 fds[2];
  if (sig_stop_rfd != -1) return sig_stop_rfd;
  if (pipe(fds) != 0) return -1;
  fcntl(fds[0], SIG_F_SETFD, 1);
  fcntl(fds[1], SIG_F_SETFD, 1);
  fcntl(fds[0], SIG_F_SETFL, fcntl(fds[0], SIG_F_GETFL, 0) | SIG_O_NONBLOCK);
  fcntl(fds[1], SIG_F_SETFL, fcntl(fds[1], SIG_F_GETFL, 0) | SIG_O_NONBLOCK);
  sig_stop_rfd = fds[0];
  sig_stop_wfd = fds[1];
  SigHandle(SIG_SIGINT, (&SigStopHandler)(I64), SIG_SA_RESTART | SIG_SA_RESETHAND);
  SigHandle(SIG_SIGTERM, (&SigStopHandler)(I64), SIG_SA_RESTART | SIG_SA_RESETHAND);
  return sig_stop_rfd;
}

/* TRUE once SIGINT or SIGTERM arrived after SigStopInit. */
public Bool SigStopRequested()
{
  return sig_stop_sig != 0;
}

/* The signal that requested the stop (SIGINT or SIGTERM), 0 if none. */
public I32 SigStopSignal()
{
  return sig_stop_sig;
}
