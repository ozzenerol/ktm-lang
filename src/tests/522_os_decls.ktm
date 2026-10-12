import cstr, libc, os, proc, str, test;

/* OS declarations (v0.5.0): process ids and groups, dup/dup2, pread/pwrite,
 * symlink/readlink/rename/access/realpath, rlimits (EMFILE), more errno
 * codes; proc.getenv/setenv/unsetenv/hasEnv, selfPath, cpuCount and the
 * wait-status helpers. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

i32 main() {
    printf("Test - OS declarations and proc helpers: ");

    i32 pid = libc.getpid();
    check(pid > 0 && libc.getppid() > 0 && libc.getppid() != pid, "getpid/getppid");
    check(libc.getpgrp() == libc.getpgid(0) && libc.getpgrp() > 0, "getpgrp is getpgid(0)");
    check(libc.getuid() == libc.getuid() && libc.geteuid() >= 0, "getuid/geteuid");

    /* dup / dup2 over a pipe */
    i32 fds[2];
    libc.pipe(fds);
    i32 d = libc.dup(fds[1]);
    check(d > fds[1] || d > 2, "dup gives a new fd");
    check(libc.write(d, "ab", 2) == 2, "write through the dup");
    i32 d2 = libc.dup2(fds[1], 60);
    check(d2 == 60 && libc.write(60, "cd", 2) == 2, "dup2 onto 60");
    u8 got[8];
    check(libc.read(fds[0], got, 8) == 4 && !libc.memcmp(got, "abcd", 4), "both writes arrived");
    libc.close(d);
    libc.close(60);
    libc.close(fds[0]);
    libc.close(fds[1]);
    check(libc.write(60, "x", 1) == -1 && os.errno() == libc.EBADF, "a closed fd is EBADF");

    /* files: pwrite/pread, ftruncate, fsync, rename, symlink, readlink,
     * access, realpath */
    String dir = str.format("/tmp/ktm-522-%d", pid);
    defer dir.free();
    libc.mkdir(dir.cstr(), 0x1ED);
    String a = str.format("%s/a", dir), b = str.format("%s/b", dir), l = str.format("%s/l", dir);
    defer a.free();
    defer b.free();
    defer l.free();
    i32 fd = libc.open(a.cstr(), libc.O_RDWR | libc.O_CREAT, 0x1A4);
    check(fd >= 0, "open a file");
    check(libc.pwrite(fd, "hello world", 11, 0) == 11, "pwrite");
    check(libc.pread(fd, got, 5, 6) == 5 && !libc.memcmp(got, "world", 5), "pread at an offset");
    check(libc.ftruncate(fd, 5) == 0 && libc.fsync(fd) == 0, "ftruncate and fsync");
    check(libc.pread(fd, got, 8, 0) == 5, "truncated to 5 bytes");
    libc.close(fd);
    check(libc.rename(a.cstr(), b.cstr()) == 0 && libc.access(a.cstr(), libc.F_OK) == -1
          && os.errno() == libc.ENOENT, "rename; the old name is ENOENT");
    check(libc.access(b.cstr(), libc.R_OK | libc.W_OK) == 0, "access R_OK|W_OK");
    check(libc.symlink("b", l.cstr()) == 0, "symlink");
    u8 link[64];
    i64 n = libc.readlink(l.cstr(), link, sizeof(link));
    check(n == 1 && link[0] == 'b', "readlink gives the target, not NUL-terminated");
    u8 *real = libc.realpath(l.cstr(), null);
    check(real && str.endsWith(cstr.view(real), "/b"), "realpath follows the link");
    libc.free(real);
    check(libc.symlink("b", l.cstr()) == -1 && os.errno() == libc.EEXIST, "EEXIST");
    libc.unlink(l.cstr());
    libc.unlink(b.cstr());
    check(libc.rmdir(dir.cstr()) == 0, "clean up");
    check(libc.ENOTDIR == 20 && libc.EISDIR == 21 && libc.ENOSPC == 28 && libc.ENOTEMPTY > 0
          && libc.ENOSYS > 0 && libc.ENAMETOOLONG > 0 && libc.ELOOP > 0, "more errno codes");

    /* RLIMIT_NOFILE: lower the soft limit, opening past it is EMFILE */
    libc.rlimit was, low;
    check(libc.getrlimit(libc.RLIMIT_NOFILE, &was) == 0 && was.rlim_cur > 0, "getrlimit");
    low = was;
    low.rlim_cur = 32;
    check(libc.setrlimit(libc.RLIMIT_NOFILE, &low) == 0, "setrlimit lower");
    i32 opened[64];
    i64 k = 0;
    i64 err = 0;
    while (k < 64) {
        opened[k] = libc.dup(0);
        if (opened[k] < 0) {
            err = os.errno();
            break;
        }
        k++;
    }
    for (i64 i = 0; i < k; i++) { libc.close(opened[i]); }
    libc.setrlimit(libc.RLIMIT_NOFILE, &was);
    check(k > 0 && k < 32 && err == libc.EMFILE, "past the limit is EMFILE");

    /* environment */
    check(proc.setenv("KTM_522_X", "one two"), "setenv");
    String v = proc.getenv("KTM_522_X");
    check(v.equals("one two") && proc.hasEnv("KTM_522_X"), "getenv and hasEnv");
    v.free();
    String sh = proc.shellOutput("printf %%s \"$KTM_522_X\"");
    check(sh.equals("one two"), "a child sees it");
    sh.free();
    check(proc.unsetenv("KTM_522_X") && !proc.hasEnv("KTM_522_X"), "unsetenv");
    v = proc.getenv("KTM_522_X");
    check(v.len == 0, "getenv of an unset name is empty");
    v.free();
    check(!proc.setenv("A=B", "x") && !proc.setenv("", "x"), "a bad name is refused");

    /* the program's own path and the CPU count */
    String me = proc.selfPath();
    check(me.len > 1 && me[0] == '/' && libc.access(me.cstr(), libc.X_OK) == 0,
          "selfPath is an absolute executable");
    me.free();
    check(proc.cpuCount() >= 1, "cpuCount");

    /* wait statuses: made up, and real ones from system() */
    check(proc.exited(3 << 8) && proc.exitCode(3 << 8) == 3 && proc.statusCode(3 << 8) == 3,
          "exit code 3");
    check(proc.signaled(9) && proc.termSignal(9) == 9 && proc.statusCode(9) == 137, "SIGKILL");
    check(proc.signaled(0x80 | 6) && proc.termSignal(0x80 | 6) == 6, "a core dump bit");
    check(proc.stopped(0x137F) && proc.stopSignal(0x137F) == 19 && proc.statusCode(0x137F) == 147,
          "stopped");
    check(!proc.exited(9) && !proc.signaled(3 << 8) && !proc.stopped(9), "only one of them");
    i32 st = libc.system("exit 5");
    check(proc.exited(st) && proc.statusCode(st) == 5, "a real exit 5");
    st = libc.system("kill -TERM $$");
    check(proc.signaled(st) && proc.termSignal(st) == libc.SIGTERM && proc.statusCode(st) == 143,
          "a real SIGTERM");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
