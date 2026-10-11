import libc, os, signal, test;

/* sigaction-based signals (signals.ktm): SigAction/SigHandle install a
 * handler, SIG_IGN or SIG_DFL and return the previous action; the mask
 * blocks other signals while the handler runs; without SA_RESTART a
 * blocking read is interrupted (EINTR), with it the read goes on;
 * signal() and SigAction see each other's actions. SigStopInit: SIGINT
 * and SIGTERM set the flag and make the fd readable, errno survives the
 * handler, and a second signal ends the program. The struct sigaction
 * layouts (glibc x86_64/aarch64 and macOS) were checked in C. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 fflush(void *stream);
extern "c" i32 getpid();
extern "c" i32 pipe(i32 *fds);
extern "c" i32 usleep(u32 usec);
extern "c" void *pthread_self();
extern "c" i32 pthread_kill(void *thread, i32 sig);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i64 gCount = 0, gOrder = 0, gUsr1At = 0, gUsr2At = 0;

void count(i32 sig) {
    gCount++;
}

void onUsr2(i32 sig) {
    gUsr2At = ++gOrder;
}

void onUsr1(i32 sig)
{// SIGUSR2 is in this handler's mask: it waits until we return
    libc.raise(libc.SIGUSR2);
    gUsr1At = ++gOrder;
}

void install() {
    signal.Action old, now;
    check(signal.handle(libc.SIGUSR1, (i64)(&count), libc.SA_RESTART, 0, &old)
                && old.handler == libc.SIG_DFL, "first install returns SIG_DFL");
    libc.raise(libc.SIGUSR1);
    libc.raise(libc.SIGUSR1);
    check(gCount == 2, "handler runs");

    check(signal.handle(libc.SIGUSR1, libc.SIG_IGN, 0, 0, &old) && old.handler == (i64)(&count)
                && (old.flags & libc.SA_RESTART), "previous handler and flags returned");
    libc.raise(libc.SIGUSR1);
    check(gCount == 2, "SIG_IGN ignores");

    check(signal.action(libc.SIGUSR1, null, &now) && now.handler == libc.SIG_IGN,
                "read the action without changing it");
    check(signal.action(libc.SIGUSR1, &old, null), "restore the saved action");
    libc.raise(libc.SIGUSR1);
    check(gCount == 3, "restored handler runs");
    signal.handle(libc.SIGUSR1, libc.SIG_DFL);

    check(!signal.handle(libc.SIGKILL, libc.SIG_IGN) && os.errno() == libc.EINVAL, "SIGKILL can't be caught");
    check(signal.bit(1) == 1 && signal.bit(libc.SIGTERM) == 0x4000 && signal.bit(0) == 0, "SigBit");
}

void maskFn() {
    signal.Action got;
    signal.handle(libc.SIGUSR2, (i64)(&onUsr2));
    signal.handle(libc.SIGUSR1, (i64)(&onUsr1), libc.SA_RESTART, signal.bit(libc.SIGUSR2));
    signal.action(libc.SIGUSR1, null, &got);
    check((got.mask & signal.bit(libc.SIGUSR2)) != 0, "mask read back");
    libc.raise(libc.SIGUSR1);
    check(gUsr1At == 1 && gUsr2At == 2, "masked signal waits for the handler");
    signal.handle(libc.SIGUSR1, libc.SIG_DFL);
    signal.handle(libc.SIGUSR2, libc.SIG_DFL);
}

i32 gPipe[2];
void *gMain;

void *poker(void *arg)
{// interrupt main's read with SIGUSR1, then give it a byte
    usleep(100000);
    pthread_kill(gMain, libc.SIGUSR1);
    usleep(200000);
    libc.write(gPipe[1], "x", 1);
    return null;
}

/* read() on an empty pipe while another thread signals us: -1 EINTR
 * without SA_RESTART, the byte with it. */
i64 readAcrossSignal(i32 flags, i32 *err) {
    libc.pthread_t *t;
    u8 c;
    i64 n;
    signal.handle(libc.SIGUSR1, (i64)(&count), flags);
    libc.pipe(gPipe);
    gMain = pthread_self();
    libc.pthread_create(&t, null, &poker, null);
    n = libc.read(gPipe[0], &c, 1);
    *err = os.errno();
    libc.pthread_join(t);
    libc.close(gPipe[0]);
    libc.close(gPipe[1]);
    signal.handle(libc.SIGUSR1, libc.SIG_DFL);
    return n;
}

void restart() {
    i32 err = 0;
    i64 before = gCount;
    i64 n = readAcrossSignal(0, &err);
    check(n == -1 && err == libc.EINTR && gCount == before + 1, "no SA_RESTART: EINTR");
    n = readAcrossSignal(libc.SA_RESTART, &err);
    check(n == 1 && gCount == before + 2, "SA_RESTART: the read goes on");
}

void withSignal()
{// signal() and SigAction set the same action
    signal.Action got;
    libc.signal(libc.SIGUSR2, (i64)(&count));
    check(signal.action(libc.SIGUSR2, null, &got) && got.handler == (i64)(&count)
                && (got.flags & libc.SA_RESTART), "signal() seen by SigAction (SA_RESTART)");
    signal.handle(libc.SIGUSR2, libc.SIG_IGN);
    check(libc.signal(libc.SIGUSR2, libc.SIG_DFL) == libc.SIG_IGN, "SigHandle seen by signal()");
}

i32 childStop() {
    i32 fd = signal.stopInit();
    libc.pollfd p;
    if (fd < 0 || signal.stopInit() != fd) { return 1; }
    if (signal.stopRequested() || signal.stopSignal() != 0) { return 2; }
    p.fd = fd;
    p.events = libc.POLLIN;
    p.revents = 0;
    if (libc.poll(&p, 1, 0) != 0) { return 3; }
    os.setErrno(1234);
    libc.kill(getpid(), libc.SIGTERM);
    if (os.errno() != 1234) { return 4; }                    /* errno kept */
    if (!signal.stopRequested() || signal.stopSignal() != libc.SIGTERM) { return 5; }
    p.revents = 0;
    if (libc.poll(&p, 1, 1000) != 1 || !(p.revents & libc.POLLIN)) { return 6; }
    libc.kill(getpid(), libc.SIGINT);
    if (signal.stopSignal() != libc.SIGINT) { return 7; }
    libc.kill(getpid(), libc.SIGTERM);                        /* the second one ends us */
    return 8;
}

i32 runInChild(fn() i32 fn_)
{// fn's result, or 100 + the signal that killed it
    libc.fflush(null);
    i32 pid = libc.fork();
    if (pid == 0) {
        alarm(30);
        _exit(fn_());
    }
    i32 status = 0;
    libc.waitpid(pid, &status, 0);
    if (status & 0x7f) { return 100 + (status & 0x7f); }
    return (status >> 8) & 0xff;
}

i32 main() {
    printf("Test - sigaction: ");
    alarm(60);
    install();
    maskFn();
    restart();
    withSignal();
    i32 rc = runInChild(&childStop);
    check(rc == 100 + libc.SIGTERM, "SigStopInit flag, fd, errno, second signal");
    if (rc != 100 + libc.SIGTERM) { printf("  (child: %d)\n", rc); }
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
