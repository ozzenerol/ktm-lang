#include "testhelper.ktm"

/* sigaction-based signals (signals.ktm): SigAction/SigHandle install a
 * handler, SIG_IGN or SIG_DFL and return the previous action; the mask
 * blocks other signals while the handler runs; without SA_RESTART a
 * blocking read is interrupted (EINTR), with it the read goes on;
 * signal() and SigAction see each other's actions. SigStopInit: SIGINT
 * and SIGTERM set the flag and make the fd readable, errno survives the
 * handler, and a second signal ends the program. The struct sigaction
 * layouts (glibc x86_64/aarch64 and macOS) were checked in C. */

extern "c" U32 alarm(U32 seconds);
extern "c" U0 _exit(I32 code);
extern "c" I32 fflush(U0 *stream);
extern "c" I32 getpid();
extern "c" I32 pipe(I32 *fds);
extern "c" I32 usleep(U32 usec);
extern "c" U0 *pthread_self();
extern "c" I32 pthread_kill(U0 *thread, I32 sig);

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

I64 g_count = 0, g_order = 0, g_usr1_at = 0, g_usr2_at = 0;

U0 Count(I32 sig)
{
  g_count++;
}

U0 OnUsr2(I32 sig)
{
  g_usr2_at = ++g_order;
}

U0 OnUsr1(I32 sig)
{// SIGUSR2 is in this handler's mask: it waits until we return
  raise(SIGUSR2);
  g_usr1_at = ++g_order;
}

U0 Install()
{
  SigAct old, now;
  Check(SigHandle(SIGUSR1, (&Count)(I64), SA_RESTART, 0, &old)
        && old.handler == SIG_DFL, "first install returns SIG_DFL");
  raise(SIGUSR1);
  raise(SIGUSR1);
  Check(g_count == 2, "handler runs");

  Check(SigHandle(SIGUSR1, SIG_IGN, 0, 0, &old) && old.handler == (&Count)(I64)
        && (old.flags & SA_RESTART), "previous handler and flags returned");
  raise(SIGUSR1);
  Check(g_count == 2, "SIG_IGN ignores");

  Check(SigAction(SIGUSR1, NULL, &now) && now.handler == SIG_IGN,
        "read the action without changing it");
  Check(SigAction(SIGUSR1, &old, NULL), "restore the saved action");
  raise(SIGUSR1);
  Check(g_count == 3, "restored handler runs");
  SigHandle(SIGUSR1, SIG_DFL);

  Check(!SigHandle(SIGKILL, SIG_IGN) && Errno == EINVAL, "SIGKILL can't be caught");
  Check(SigBit(1) == 1 && SigBit(SIGTERM) == 0x4000 && SigBit(0) == 0, "SigBit");
}

U0 Mask()
{
  SigAct got;
  SigHandle(SIGUSR2, (&OnUsr2)(I64));
  SigHandle(SIGUSR1, (&OnUsr1)(I64), SA_RESTART, SigBit(SIGUSR2));
  SigAction(SIGUSR1, NULL, &got);
  Check((got.mask & SigBit(SIGUSR2)) != 0, "mask read back");
  raise(SIGUSR1);
  Check(g_usr1_at == 1 && g_usr2_at == 2, "masked signal waits for the handler");
  SigHandle(SIGUSR1, SIG_DFL);
  SigHandle(SIGUSR2, SIG_DFL);
}

I32 g_pipe[2];
U0 *g_main;

U0 *Poker(U0 *arg)
{// interrupt main's read with SIGUSR1, then give it a byte
  usleep(100000);
  pthread_kill(g_main, SIGUSR1);
  usleep(200000);
  write(g_pipe[1], "x", 1);
  return NULL;
}

/* read() on an empty pipe while another thread signals us: -1 EINTR
 * without SA_RESTART, the byte with it. */
I64 ReadAcrossSignal(I32 flags, I32 *err)
{
  pthread_t *t;
  U8 c;
  I64 n;
  SigHandle(SIGUSR1, (&Count)(I64), flags);
  pipe(g_pipe);
  g_main = pthread_self();
  pthread_create(&t, NULL, &Poker, NULL);
  n = read(g_pipe[0], &c, 1);
  *err = Errno;
  pthread_join(t);
  close(g_pipe[0]);
  close(g_pipe[1]);
  SigHandle(SIGUSR1, SIG_DFL);
  return n;
}

U0 Restart()
{
  I32 err = 0;
  I64 before = g_count;
  I64 n = ReadAcrossSignal(0, &err);
  Check(n == -1 && err == EINTR && g_count == before + 1, "no SA_RESTART: EINTR");
  n = ReadAcrossSignal(SA_RESTART, &err);
  Check(n == 1 && g_count == before + 2, "SA_RESTART: the read goes on");
}

U0 WithSignal()
{// signal() and SigAction set the same action
  SigAct got;
  signal(SIGUSR2, (&Count)(I64));
  Check(SigAction(SIGUSR2, NULL, &got) && got.handler == (&Count)(I64)
        && (got.flags & SA_RESTART), "signal() seen by SigAction (SA_RESTART)");
  SigHandle(SIGUSR2, SIG_IGN);
  Check(signal(SIGUSR2, SIG_DFL) == SIG_IGN, "SigHandle seen by signal()");
}

I32 ChildStop()
{
  I32 fd = SigStopInit();
  pollfd p;
  if (fd < 0 || SigStopInit() != fd) return 1;
  if (SigStopRequested() || SigStopSignal() != 0) return 2;
  p.fd = fd;
  p.events = POLLIN;
  p.revents = 0;
  if (poll(&p, 1, 0) != 0) return 3;
  SetErrno(1234);
  kill(getpid(), SIGTERM);
  if (Errno != 1234) return 4;                    /* errno kept */
  if (!SigStopRequested() || SigStopSignal() != SIGTERM) return 5;
  p.revents = 0;
  if (poll(&p, 1, 1000) != 1 || !(p.revents & POLLIN)) return 6;
  kill(getpid(), SIGINT);
  if (SigStopSignal() != SIGINT) return 7;
  kill(getpid(), SIGTERM);                        /* the second one ends us */
  return 8;
}

I32 RunInChild(I32 (*fn)())
{// fn's result, or 100 + the signal that killed it
  fflush(NULL);
  I32 pid = fork();
  if (pid == 0) {
    alarm(30);
    _exit(fn());
  }
  I32 status = 0;
  waitpid(pid, &status, 0);
  if (status & 0x7f) return 100 + (status & 0x7f);
  return (status >> 8) & 0xff;
}

I32 Main()
{
  "Test - sigaction: ";
  alarm(60);
  Install;
  Mask;
  Restart;
  WithSignal;
  I32 rc = RunInChild(&ChildStop);
  Check(rc == 100 + SIGTERM, "SigStopInit flag, fd, errno, second signal");
  if (rc != 100 + SIGTERM) "  (child: %d)\n", rc;
  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
