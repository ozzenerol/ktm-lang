import coro, date, fs, libc, os, proc, str, test;

/* coro.waitChild (v0.6.0): children waited for inside the loop, several
 * at once from several coroutines, without blocking the others; a
 * timeout, a pid that isn't ours, a child that already exited, and
 * proc.exitFd / proc.setStatus. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* sh -c script, started now */
proc.Proc *sh(u8 *script) {
    u8 *argv[4];
    argv[0] = "/bin/sh";
    argv[1] = "-c";
    argv[2] = script;
    argv[3] = null;
    return proc.spawn(argv);
}

i32 gStatus[3];
i64 gEnded[3];
/* marker files that chain the children's exits: 1, then 2, then 0 */
String gMark1, gMark2;
i64 gTicks = 0;
bool gWaiting = true;

void *waiter(void *arg) {
    i64 i = (i64)arg;
    proc.Proc *p;
    /* each child ends 100 ms after the one before it, however slowly the
     * shells start (a fixed sleep each once raced on a loaded arm64 box) */
    scoped String script;
    if (i == 0) {
        script = str.format("until [ -e %s ]; do sleep 0.02; done; sleep 0.1; exit 3", gMark2);
    } else if (i == 1) {
        script = str.format("sleep 0.1; touch %s; exit 4", gMark1);
    } else {
        script = str.format("until [ -e %s ]; do sleep 0.02; done; sleep 0.1; touch %s; kill -TERM $$",
                            gMark1, gMark2);
    }
    p = sh(script.cstr());
    gStatus[i] = coro.waitChild(p.pid);
    gEnded[i] = date.monoMs();
    proc.setStatus(p, gStatus[i]);
    if (i == 2) {
        gStatus[i] = p.termSignal == 15 && p.exitCode == -1 ? gStatus[i] : -2;
    }
    proc.release(p);
    return null;
}

/* counts while the others wait: the loop is not blocked */
void *ticker(void *arg) {
    while (gWaiting) {
        gTicks++;
        coro.sleep(10);
    }
    return null;
}

void *lastOne(void *arg) {
    coro.sleep(400);
    gWaiting = false;
    return null;
}

i32 gTimeoutR, gTimeoutErr, gAfterKill, gNotOurs, gNotOursErr, gZombie, gBadPid;
i64 gTimeoutMs, gZombieMs;

void *edges(void *arg) {
    proc.Proc *p = sh("sleep 5");
    i64 t0 = date.monoMs();
    gTimeoutR = coro.waitChild(p.pid, 100);
    gTimeoutErr = os.errno();
    gTimeoutMs = date.monoMs() - t0;
    proc.kill(p);
    gAfterKill = coro.waitChild(p.pid, 5000);
    proc.release(p);

    gNotOurs = coro.waitChild(1, 50);
    gNotOursErr = os.errno();
    gBadPid = coro.waitChild(-1);

    /* exited before the wait starts */
    p = sh("exit 7");
    coro.sleep(200);
    t0 = date.monoMs();
    gZombie = coro.waitChild(p.pid);
    gZombieMs = date.monoMs() - t0;
    proc.release(p);
    return null;
}

i32 main() {
    printf("Test - coro.waitChild: ");

    gMark1 = str.format("/tmp/__534_%d_1", libc.getpid());
    gMark2 = str.format("/tmp/__534_%d_2", libc.getpid());
    i64 t0 = date.monoMs();
    for (i64 i = 0; i < 3; i++) { coro.go(&waiter, (void *)i); }
    coro.go(&ticker);
    coro.go(&lastOne);
    coro.run();
    i64 took = date.monoMs() - t0;

    check(proc.exited(gStatus[0]) && proc.exitCode(gStatus[0]) == 3, "first child: exit 3");
    check(proc.exited(gStatus[1]) && proc.exitCode(gStatus[1]) == 4, "second child: exit 4");
    check(gStatus[2] >= 0 && proc.signaled(gStatus[2]) && proc.termSignal(gStatus[2]) == 15,
          "third child: SIGTERM, and proc.setStatus records it");
    fs.remove(gMark1[0:]);
    fs.remove(gMark2[0:]);
    check(gEnded[1] < gEnded[2] && gEnded[2] < gEnded[0], "each waiter wakes when its child ends");
    check(gEnded[0] - t0 < 1500, "the waits overlap");
    check(gTicks >= 15, "the loop keeps running while children are waited for");
    check(took < 2000, "run returns");

    coro.go(&edges);
    coro.run();
    check(gTimeoutR == -1 && gTimeoutErr == libc.ETIMEDOUT, "a timeout: -1, ETIMEDOUT");
    check(gTimeoutMs >= 90 && gTimeoutMs < 1500, "the timeout is about 100 ms");
    check(gAfterKill >= 0 && proc.signaled(gAfterKill), "the same child waited again after kill");
    check(gNotOurs == -1 && gNotOursErr == libc.ECHILD, "not our child: -1, ECHILD");
    check(gBadPid == -1, "pid -1 is refused (never waits for any child)");
    check(proc.exited(gZombie) && proc.exitCode(gZombie) == 7 && gZombieMs < 100,
          "a child that already exited is reaped at once");

    /* proc.exitFd outside the loop: readable once the child exits */
    proc.Proc *p = sh("sleep 0.1");
    i32 fd = proc.exitFd(p.pid);
    check(fd >= 0, "proc.exitFd");
    libc.pollfd pfd;
    pfd.fd = fd;
    pfd.events = libc.POLLIN;
    pfd.revents = 0;
    check(libc.poll(&pfd, 1, 0) == 0, "not readable while the child runs");
    check(libc.poll(&pfd, 1, 5000) == 1 && (pfd.revents & libc.POLLIN), "readable once it exits");
    check(proc.wait(p) == 0, "then reaped as usual");
    libc.close(fd);
    proc.release(p);
    check(proc.exitFd(-5) == -1 && proc.exitFd(0) == -1, "exitFd of a bad pid");

    /* waitChild outside a loop coroutine throws ERR_LOOP */
    i64 code = 0;
    try { coro.waitChild(1); } catch (Error e) { code = e.code; }
    check(code == coro.ERR_LOOP, "outside the loop: ERR_LOOP");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
