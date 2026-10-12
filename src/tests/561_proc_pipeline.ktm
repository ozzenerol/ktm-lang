import libc, mem, proc, str, test;

/* proc (v0.8.0): a pipeline from proc.spawn. FD gives a child an fd of
 * ours as its stdin/stdout/stderr, pgid joins a process group, dups maps
 * more fds, proc.pipe connects stages, kill reaches the whole group, and
 * disown leaves the reaping to us. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

u8 *gArgv[4];
u8 **sh(u8 *script) {
    gArgv[0] = "/bin/sh";
    gArgv[1] = "-c";
    gArgv[2] = script;
    gArgv[3] = null;
    return gArgv;
}

proc.Opts zeroOpts() {
    proc.Opts o;
    mem.set(&o, 0, sizeof(proc.Opts));
    return o;
}

/* a | b | c, each stage's stdout the next one's stdin, all in one new
 * process group led by the first; the last one's stdout a PIPE to us */
void startPipeline(u8 *a, u8 *b, u8 *c, proc.Proc **ps) {
    i32 ab[2], bc[2];
    proc.pipe(ab);
    proc.pipe(bc);
    proc.Opts o = zeroOpts();
    o.out = proc.FD;
    o.outFd = ab[1];
    o.newGroup = true;
    ps[0] = proc.spawn(sh(a), &o);
    libc.close(ab[1]);

    o = zeroOpts();
    o.in = proc.FD;
    o.inFd = ab[0];
    o.out = proc.FD;
    o.outFd = bc[1];
    o.pgid = ps[0].pid;
    ps[1] = proc.spawn(sh(b), &o);
    libc.close(ab[0]);
    libc.close(bc[1]);

    o = zeroOpts();
    o.in = proc.FD;
    o.inFd = bc[0];
    o.out = proc.PIPE;
    o.pgid = ps[0].pid;
    ps[2] = proc.spawn(sh(c), &o);
    libc.close(bc[0]);
}

i32 main() {
    printf("Test - proc pipelines and process groups: ");

    proc.Proc *ps[3];
    startPipeline("printf 'pear\\napple\\npear\\nfig\\n'; sleep 0.3", "sort", "uniq -c", ps);
    check(ps[0] && ps[1] && ps[2], "three stages started");
    i32 g = ps[0].pid;
    check(libc.getpgid(ps[0].pid) == g && libc.getpgid(ps[1].pid) == g &&
          libc.getpgid(ps[2].pid) == g && g != libc.getpgrp(), "one new process group, led by the first");
    check(ps[0].group && ps[0].pgid == g && ps[1].group && ps[1].pgid == g && ps[2].pgid == g,
          "Proc.pgid");
    String out;
    proc.communicate(ps[2], "", &out);
    String flat = str.from(out);
    flat.replace(" ", "");
    check(str.equals(flat, "1apple\n1fig\n2pear\n"), "printf | sort | uniq -c");
    check(proc.wait(ps[0]) == 0 && proc.wait(ps[1]) == 0 && ps[2].exitCode == 0, "every stage exits 0");
    out.free();
    flat.free();
    for (i64 i = 0; i < 3; i++) { proc.release(ps[i]); }

    /* kill on any stage reaches the whole group */
    startPipeline("sleep 5", "sleep 5", "sleep 5", ps);
    check(proc.kill(ps[1], 15), "kill the group through the middle stage");
    bool all = true;
    for (i64 i = 0; i < 3; i++) {
        if (proc.wait(ps[i]) != -1 || ps[i].termSignal != 15) { all = false; }
        proc.release(ps[i]);
    }
    check(all, "every stage got SIGTERM");

    /* dups: the child's fd 3 is our pipe's write end; stderr onto stdout */
    i32 p3[2];
    proc.pipe(p3);
    i32 dups[4];
    dups[0] = p3[1];
    dups[1] = 3;
    proc.Opts o = zeroOpts();
    o.out = proc.PIPE;
    o.err = proc.STDOUT;
    o.dups = dups;
    o.ndups = 1;
    proc.Proc *p = proc.spawn(sh("echo three >&3; echo out; echo err >&2; ls /proc/self/fd/4 >/dev/null 2>&1 && echo leak"), &o);
    libc.close(p3[1]);
    u8 buf[64];
    i64 n = libc.read(p3[0], buf, sizeof(buf));
    check(n == 6 && str.equals(buf[0:n], "three\n"), "dups: fd 3 in the child");
    String text;
    proc.communicate(p, "", &text);
    check(str.equals(text, "out\nerr\n"), "err STDOUT, no other fd leaked");
    text.free();
    libc.close(p3[0]);
    proc.release(p);

    /* FD with the same fd as the stream (our stdin as its stdin) */
    o = zeroOpts();
    o.in = proc.FD;
    o.inFd = 0;
    o.out = proc.PIPE;
    p = proc.spawn(sh("echo same"), &o);
    check(p && proc.wait(p) == 0, "FD of the same fd");
    proc.release(p);

    /* disown: we reap it ourselves */
    p = proc.spawn(sh("exit 7"));
    i32 pid = p.pid;
    proc.disown(p);
    i32 st = 0;
    check(libc.waitpid(pid, &st, 0) == pid && proc.exitCode(st) == 7, "disown, then waitpid");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
