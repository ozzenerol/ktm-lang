import libc, mem, proc, str, test;

/* On macOS a proc.spawn child had no stdin, stdout or stderr when they
 * were PROC_INHERIT (the default): POSIX_SPAWN_CLOEXEC_DEFAULT closes
 * every fd the file actions don't name, 0-2 included. Each case runs in a
 * forked child whose stdin, stdout and stderr are pipes of this test, so
 * what an inherited fd carries can be checked: a program writing to an
 * inherited stdout/stderr, reading an inherited stdin, alone and mixed
 * with PROC_PIPE / PROC_NULL / PROC_STDOUT, proc.run's inherited stderr,
 * and a parent whose own stdin is closed. */

extern "c" u32 alarm(u32 seconds);
extern "c" void _exit(i32 code);
extern "c" i32 fflush(void *stream);
extern "c" i32 pipe(i32 *fds);
extern "c" i32 dup2(i32 fd, i32 fd2);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* Spawn sh -c script with the given modes; when out/err is PROC_PIPE,
 * that stream must equal want_out/want_err. The exit code of sh, or 100+
 * when a piped stream differs. */
i32 spawn(u8 *script, i64 in, i64 out, i64 err, u8 *wantOut=null, u8 *wantErr=null) {
    u8 *argv[4];
    argv[0] = "sh";
    argv[1] = "-c";
    argv[2] = script;
    argv[3] = null;
    proc.Opts o;
    mem.set(&o, 0, sizeof(proc.Opts));
    o.in = in;
    o.out = out;
    o.err = err;
    proc.Proc *p = proc.spawn(argv, &o);
    if (!p) { return 99; }
    u8 *gout = null, *gerr = null;
    i64 rc = proc.communicate(p, null, 0, &gout, null, &gerr);
    proc.release(p);
    if (wantOut && (!gout || str.cmp(gout, wantOut))) { return 101; }
    if (wantErr && (!gerr || str.cmp(gerr, wantErr))) { return 102; }
    return rc;
}

i32 allInherit() {
    return spawn("read x; echo \"out:$x\"; echo \"err:$x\" >&2", proc.INHERIT, proc.INHERIT, proc.INHERIT);
}
i32 outPiped() {
    return spawn("read x; echo o; echo \"e:$x\" >&2", proc.INHERIT, proc.PIPE, proc.INHERIT, "o\n");
}
i32 errPiped() {
    return spawn("cat; echo out; echo err >&2", proc.DEVNULL, proc.INHERIT, proc.PIPE, null, "err\n");
}
i32 errToStdout() {
    return spawn("echo a; echo b >&2", proc.DEVNULL, proc.INHERIT, proc.STDOUT);
}
i32 inOnly() {
    return spawn("cat", proc.INHERIT, proc.PIPE, proc.PIPE, "data\n", "");
}
i32 errOnly() {
    return spawn("echo x; echo y >&2", proc.DEVNULL, proc.DEVNULL, proc.INHERIT);
}
i32 runErr() {
    u8 *argv[4];
    argv[0] = "sh";
    argv[1] = "-c";
    argv[2] = "echo o; echo e >&2; exit 3";
    argv[3] = null;
    i64 code;
    u8 *out = proc.run(argv, &code);
    i32 rc = 0;
    if (!out || str.cmp(out, "o") || code != 3) { rc = 1; }
    mem.free(out);
    return rc;
}
i32 closedStdin() {
    libc.close(0);           // our stdin is closed: the child just doesn't get one
    return spawn("echo ok", proc.INHERIT, proc.INHERIT, proc.INHERIT);
}

/* Run fn in a child whose stdin holds `input` and whose stdout/stderr we
 * capture. Its exit code, or 200+ if killed. */
i32 inChild(fn() i32 fn_, u8 *input, u8 *out, u8 *err) {
    i32 pi[2], po[2], pe[2];
    i64 n, no = 0, ne = 0;
    libc.pipe(pi);
    libc.pipe(po);
    libc.pipe(pe);
    libc.fflush(null);
    i32 pid = libc.fork();
    if (pid == 0) {
        alarm(20);
        dup2(pi[0], 0);
        dup2(po[1], 1);
        dup2(pe[1], 2);
        libc.close(pi[0]); libc.close(pi[1]); libc.close(po[0]); libc.close(po[1]); libc.close(pe[0]); libc.close(pe[1]);
        i32 rc = fn_();
        libc.fflush(null);
        _exit(rc);
    }
    libc.close(pi[0]);
    libc.close(po[1]);
    libc.close(pe[1]);
    libc.write(pi[1], input, str.len(input));
    libc.close(pi[1]);
    /* small outputs: read stdout to EOF, then stderr */
    while ((n = libc.read(po[0], out + no, 255 - no)) > 0) { no += n; }
    while ((n = libc.read(pe[0], err + ne, 255 - ne)) > 0) { ne += n; }
    out[no] = 0;
    err[ne] = 0;
    libc.close(po[0]);
    libc.close(pe[0]);
    i32 status = 0;
    libc.waitpid(pid, &status, 0);
    if (status & 0x7f) { return 200 + (status & 0x7f); }
    return (status >> 8) & 0xff;
}

void caseFn(u8 *what, fn() i32 fn_, u8 *input, u8 *wantOut, u8 *wantErr) {
    u8 out[256], err[256];
    i32 rc = inChild(fn_, input, out, err);
    bool ok = rc == 0 && !str.cmp(out, wantOut) && !str.cmp(err, wantErr);
    check(ok, what);
    if (!ok) { printf("    exit %d, stdout [%s], stderr [%s]\n", rc, out, err); }
}

i32 main() {
    printf("Test - ProcSpawn PROC_INHERIT: ");
    caseFn("all inherited: reads stdin, writes stdout and stderr", &allInherit,
             "hello\n", "out:hello\n", "err:hello\n");
    caseFn("stdout piped, stdin and stderr inherited", &outPiped, "hi\n", "", "e:hi\n");
    caseFn("stdin /dev/null, stdout inherited, stderr piped", &errPiped, "unread\n", "out\n", "");
    caseFn("stderr into an inherited stdout (2>&1)", &errToStdout, "", "a\nb\n", "");
    caseFn("only stdin inherited", &inOnly, "data\n", "", "");
    caseFn("only stderr inherited", &errOnly, "", "", "y\n");
    caseFn("ProcRun: stderr inherited", &runErr, "", "", "e\n");
    caseFn("our stdin closed: the child starts anyway", &closedStdin, "", "ok\n", "");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
