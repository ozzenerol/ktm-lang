import test;

import libc, mem, net, os, proc, str;

/* Processes (process.ktm): ProcSpawn runs argv without a shell; exit codes
 * and signals from ProcWait; ENOENT for a missing program; PROC_NULL,
 * PROC_STDOUT, cwd and env; ProcDone; ProcKill of a whole process group;
 * ProcCommunicate moving 1 MiB through stdin/stdout while 1 MiB goes to
 * stderr (no deadlock); a child that never reads its stdin (no SIGPIPE);
 * no fd of ours leaking into the child; spawns from 4 threads at once.
 * Only sh, cat, echo, printf, pwd, yes and head are used: all exist on
 * Linux and macOS. alarm() ends the test instead of hanging the suite.
 * Under QEMU user emulation (docker/test.sh arm64 on an x86 host) glibc's
 * posix_spawn can't use clone(CLONE_VM|CLONE_VFORK): a missing program
 * then shows up as exit 127 instead of ENOENT and the process group isn't
 * made, so "missing program" and "ProcKill of a process group" fail
 * there; native arm64 Linux (Docker on the Mac) passes. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 fflush(void *stream);
extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);


proc.Opts *opts(i64 in, i64 out, i64 err) {
    proc.Opts *o = mem.allocZero(sizeof(proc.Opts));
    o.in = in;
    o.out = out;
    o.err = err;
    return o;
}

/* Run argv with stdout piped; its output (Free it) and exit code. */
u8 *Out(u8 **argv, proc.Opts *o, i64 *code) {
    u8 *out = null;
    proc.Proc *p = proc.spawn(argv, o);
    if (!p) { return null; }
    *code = proc.communicate(p, null, 0, &out);
    proc.release(p);
    return out;
}

bool exitCodes() {
    u8 *a0[] = {"sh", "-c", "exit 0", null};
    u8 *a3[] = {"sh", "-c", "exit 3", null};
    u8 *a255[] = {"sh", "-c", "exit 255", null};
    u8 *k[] = {"sh", "-c", "kill -9 $$", null};
    proc.Proc *p;
    i64 r0, r3, r255, rk;
    p = proc.spawn(a0);   r0 = proc.wait(p);   proc.release(p);
    p = proc.spawn(a3);   r3 = proc.wait(p);   proc.release(p);
    p = proc.spawn(a255); r255 = proc.wait(p); proc.release(p);
    p = proc.spawn(k);
    rk = proc.wait(p);
    bool killed = rk == -1 && p.termSignal == 9 && p.done;
    bool again = proc.wait(p) == -1;                 /* stored, no second wait */
    proc.release(p);
    return r0 == 0 && r3 == 3 && r255 == 255 && killed && again;
}

bool missing() {
    u8 *a[] = {"ktm-no-such-program-379", null};
    u8 *b[] = {"sh", "-c", "exit 7", null};
    proc.Opts o;
    mem.set(&o, 0, sizeof(proc.Opts));
    if (proc.spawn(a) != null || os.errno() != libc.ENOENT) { return false; }
    o.noPath = true;                               /* "sh" is not a path */
    if (proc.spawn(b, &o) != null || os.errno() != libc.ENOENT) { return false; }
    b[0] = "/bin/sh";
    proc.Proc *p = proc.spawn(b, &o);
    if (!p) { return false; }
    i64 rc = proc.wait(p);
    proc.release(p);
    return rc == 7;
}

bool noShell()
{// every argument arrives as it was, nothing is expanded or split
    u8 *a[] = {"printf", "%s|", "a b", "it's", "$HOME", ";", "*", "", "\"q\"", null};
    i64 code = -2;
    u8 *s = Out(a, opts(proc.DEVNULL, proc.PIPE, proc.INHERIT), &code);
    bool ok = s && !str.cmp(s, "a b|it's|$HOME|;|*||\"q\"|") && code == 0;
    mem.free(s);
    u8 *e[] = {"echo", "hello", null};
    s = proc.run(e, &code);
    ok = ok && s && !str.cmp(s, "hello") && code == 0; /* newline removed */
    mem.free(s);
    return ok;
}

bool redirects() {
    i64 code;
    u8 *cat[] = {"cat", null};
    u8 *s = Out(cat, opts(proc.DEVNULL, proc.PIPE, proc.INHERIT), &code);
    bool ok = s && !str.cmp(s, "") && code == 0;     /* stdin /dev/null: EOF */
    mem.free(s);

    u8 *both[] = {"sh", "-c", "echo out; echo err >&2", null};
    s = Out(both, opts(proc.DEVNULL, proc.PIPE, proc.STDOUT), &code);
    ok = ok && s && !str.cmp(s, "out\nerr\n");
    mem.free(s);
    s = Out(both, opts(proc.DEVNULL, proc.PIPE, proc.DEVNULL), &code);
    ok = ok && s && !str.cmp(s, "out\n");
    mem.free(s);

    u8 *out = null, *err = null;
    i64 olen = -1, elen = -1;
    proc.Proc *p = proc.spawn(both, opts(proc.DEVNULL, proc.PIPE, proc.PIPE));
    proc.communicate(p, null, 0, &out, &olen, &err, &elen);
    ok = ok && !str.cmp(out, "out\n") && olen == 4 && !str.cmp(err, "err\n") && elen == 4;
    proc.release(p);
    mem.free(out);
    mem.free(err);

    /* a stream that isn't piped gives null */
    p = proc.spawn(both, opts(proc.DEVNULL, proc.PIPE, proc.DEVNULL));
    err = "x";
    proc.communicate(p, null, 0, &out, null, &err, &elen);
    ok = ok && err == null && elen == 0;
    proc.release(p);
    mem.free(out);
    return ok;
}

bool cwdEnv() {
    i64 code;
    u8 *pwd[] = {"pwd", null};
    proc.Opts *o = opts(proc.DEVNULL, proc.PIPE, proc.INHERIT);
    o.cwd = "/";
    u8 *s = Out(pwd, o, &code);
    bool ok = s && !str.cmp(s, "/\n");
    mem.free(s);

    u8 *show[] = {"sh", "-c", "echo \"$KTM_379|$HOME\"", null};
    u8 *env[] = {"KTM_379=replaced", null};
    o = opts(proc.DEVNULL, proc.PIPE, proc.INHERIT);
    o.env = env;
    s = Out(show, o, &code);
    ok = ok && s && !str.cmp(s, "replaced|\n");      /* HOME is gone */
    mem.free(s);

    setenv("KTM_379", "inherited", 1);              /* set at run time */
    s = Out(show, opts(proc.DEVNULL, proc.PIPE, proc.INHERIT), &code);
    ok = ok && s && str.len(s) > 10 && !str.cmpLen(s, "inherited|", 10);
    mem.free(s);
    return ok;
}

bool doneAndKill() {
    u8 *rd[] = {"sh", "-c", "read x; exit 4", null};
    proc.Proc *p = proc.spawn(rd, opts(proc.PIPE, proc.INHERIT, proc.INHERIT));
    bool ok = !proc.done(p);                         /* waiting for a line */
    libc.write(p.inFd, "go\n", 3);
    ok = ok && proc.wait(p) == 4 && proc.done(p);
    ok = ok && !proc.kill(p);                        /* reaped: refused */
    proc.release(p);

    /* the group: sh and its background sleep both hold stdout; only
     * killing the whole group lets stdout reach EOF */
    u8 *tree[] = {"sh", "-c", "sleep 30 & sleep 30", null};
    proc.Opts *o = opts(proc.DEVNULL, proc.PIPE, proc.INHERIT);
    o.newGroup = true;
    p = proc.spawn(tree, o);
    ok = ok && proc.kill(p, libc.SIGKILL);
    u8 *out;
    i64 rc = proc.communicate(p, null, 0, &out);     /* returns at EOF */
    ok = ok && rc == -1 && p.termSignal == libc.SIGKILL && !str.cmp(out, "");
    mem.free(out);
    proc.release(p);
    return ok;
}

const i64 MIB = (1024 * 1024);

bool noDeadlock()
{// 1 MiB in and out through cat while 1 MiB of 'y' lines goes to stderr
    u8 *a[] = {"sh", "-c", "yes | head -c 1048576 >&2 & cat; wait", null};
    u8 *in = mem.alloc(MIB), *out = null, *err = null;
    i64 i, olen = 0, elen = 0;
    for (i = 0; i < MIB; i++) { in[i] = 'a' + i % 23; }
    proc.Proc *p = proc.spawn(a, opts(proc.PIPE, proc.PIPE, proc.PIPE));
    i64 rc = proc.communicate(p, in, MIB, &out, &olen, &err, &elen);
    bool ok = rc == 0 && olen == MIB && elen == MIB && !libc.memcmp(in, out, MIB);
    ok = ok && err[0] == 'y' && err[1] == '\n' && err[MIB - 1] == '\n';
    proc.release(p);
    mem.free(in);
    mem.free(out);
    mem.free(err);

    /* output dropped (null) is still drained */
    u8 *big[] = {"sh", "-c", "yes | head -c 1048576", null};
    p = proc.spawn(big, opts(proc.DEVNULL, proc.PIPE, proc.INHERIT));
    ok = ok && proc.communicate(p, null, 0, null) == 0;
    proc.release(p);
    return ok;
}

i32 childNoSigpipe()
{// the child exits at once; 1 MiB for its stdin must not kill us
    u8 *a[] = {"sh", "-c", "exit 0", null};
    u8 *in = mem.allocZero(MIB);
    proc.Proc *p = proc.spawn(a, opts(proc.PIPE, proc.INHERIT, proc.INHERIT));
    i64 rc = proc.communicate(p, in, MIB, null);
    proc.release(p);
    return rc;                      /* 0 */
}

bool noLeak() {
    i32 srv = net.listen(0, "127.0.0.1", 4);  /* inheritable socket */
    u8 *cat[] = {"cat", null};
    proc.Proc *keep = proc.spawn(cat, opts(proc.PIPE, proc.PIPE, proc.INHERIT));
    if (srv < 3 || !keep) { return false; }
    /* our pipe ends are close-on-exec */
    bool ok = (libc.fcntl(keep.inFd, libc.F_GETFD) & libc.FD_CLOEXEC) &&
                        (libc.fcntl(keep.outFd, libc.F_GETFD) & libc.FD_CLOEXEC);
    u8 *script = str.format("for fd in %d %d %d; do (: <&$fd) 2>/dev/null && echo open || echo closed; done",
                                                 srv, keep.inFd, keep.outFd);
    u8 *a[] = {"sh", "-c", script, null};
    i64 code;
    u8 *s = Out(a, opts(proc.DEVNULL, proc.PIPE, proc.INHERIT), &code);
    ok = ok && s && !str.cmp(s, "closed\nclosed\nclosed\n");
    mem.free(s);
    mem.free(script);
    proc.release(keep);              /* cat sees EOF and ends */
    libc.close(srv);
    return ok;
}

void *spawner(void *arg) {
    i64 id = (i64)arg, j, code, *bad = mem.alloc(sizeof(i64));
    *bad = 0;
    for (j = 0; j < 25; j++) {
        u8 *want = str.format("t%d-%d", id, j);
        u8 *a[] = {"echo", want, null};
        u8 *s = proc.run(a, &code);
        if (!s || str.cmp(s, want) || code != 0) { *bad += 1; }
        mem.free(s);
        mem.free(want);
    }
    return bad;
}

bool threads() {
    libc.pthread_t *t[4];
    i64 i, bad = 0, *r;
    for (i = 0; i < 4; i++) { libc.pthread_create(&t[i], null, &spawner, (void *)i); }
    for (i = 0; i < 4; i++) {
        libc.pthread_join(t[i], &r);
        bad += *r;
        mem.free(r);
    }
    return bad == 0;
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
    i64 correct = 0, tests = 0;
    alarm(120);
    printf("Test - processes: ");

    tests++;
    if (exitCodes()) { correct++; }
    else { printf("\n  exit codes / killed by a signal\n"); }

    tests++;
    if (missing()) { correct++; }
    else { printf("\n  missing program: ENOENT / no_path\n"); }

    tests++;
    if (noShell()) { correct++; }
    else { printf("\n  argv reaches the program unchanged\n"); }

    tests++;
    if (redirects()) { correct++; }
    else { printf("\n  PROC_NULL / PROC_STDOUT / both pipes\n"); }

    tests++;
    if (cwdEnv()) { correct++; }
    else { printf("\n  cwd and env\n"); }

    tests++;
    if (doneAndKill()) { correct++; }
    else { printf("\n  ProcDone / ProcKill of a process group\n"); }

    tests++;
    if (noDeadlock()) { correct++; }
    else { printf("\n  1 MiB both ways plus 1 MiB on stderr\n"); }

    tests++;
    i32 rc = runInChild(&childNoSigpipe);
    if (rc == 0) { correct++; }
    else { printf("\n  writing to a child that exited (%d)\n", rc); }

    tests++;
    if (noLeak()) { correct++; }
    else { printf("\n  our fds leak into the child\n"); }

    tests++;
    if (threads()) { correct++; }
    else { printf("\n  spawning from 4 threads\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
