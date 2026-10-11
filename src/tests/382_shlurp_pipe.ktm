import test;

import fs, libc, mem, proc, str;

/* Shlurp read the command's output back from a temporary file in
 * $TMPDIR, so it returned null when $TMPDIR was missing or read-only and
 * left a file there whenever the program was killed mid-call. It reads a
 * pipe now (process.ktm), started the way system() starts its shell:
 * nothing in $TMPDIR, the same result, the user's own inheritable fds
 * still reach the command, and a background job the command starts
 * doesn't make Shlurp wait for it. */

extern "c" u8 *getenv(u8 *name);
extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);
extern "c" i32 unsetenv(u8 *name);
extern "c" i32 rmdir(u8 *path);
extern "c" i64 time(i64 *t);
extern "c" i32 dup(i32 fd);
extern "c" i32 dup2(i32 fd, i32 fd2);

#define DIR "./ktm-382-tmp"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* The result is `want`; frees it */
bool is(u8 *got, u8 *want) {
    bool ok = got && !str.cmp(got, want);
    mem.free(got);
    return ok;
}

void *worker(void *arg) {
    i64 id = (i64)arg, j, *bad = mem.alloc(sizeof(i64));
    *bad = 0;
    for (j = 0; j < 25; j++) {
        u8 *want = str.format("w%d-%d", id, j);
        if (!is(proc.shellOutput("echo %s", want), want)) { *bad += 1; }
        mem.free(want);
    }
    return bad;
}

i32 main() {
    printf("Test - Shlurp through a pipe: ");
    i64 i, t0, bad = 0, *r;
    u8 *out;

    u8 *tmpdir = libc.getenv("TMPDIR");
    if (tmpdir) { tmpdir = str.dup(tmpdir); }

    /* $TMPDIR doesn't exist: Shlurp doesn't need it */
    setenv("TMPDIR", "./ktm-382-does-not-exist", 1);
    check(is(proc.shellOutput("echo ok"), "ok"), "Shlurp with a missing $TMPDIR");

    /* nothing is created in $TMPDIR */
    fs.makeDir(DIR);
    setenv("TMPDIR", DIR, 1);
    for (i = 0; i < 10; i++) { mem.free(proc.shellOutput("echo %d", i)); }
    check(is(proc.shellOutput("ls -A %s | wc -l | tr -d ' '", DIR), "0"),
                "no file in $TMPDIR");
    libc.rmdir(DIR);
    if (tmpdir) { setenv("TMPDIR", tmpdir, 1); }
    else { unsetenv("TMPDIR"); }
    mem.free(tmpdir);

    /* more than a pipe buffer, with one trailing newline removed */
    out = proc.shellOutput("yes | head -c 1048576");
    check(out && str.len(out) == 1048575 && out[0] == 'y' && out[1] == '\n',
                "1 MiB of output");
    mem.free(out);

    /* a background job keeps the pipe open; Shlurp still returns when the
     * shell exits */
    t0 = time(null);
    out = proc.shellOutput("echo first; sleep 5 > /dev/null 2>&1 & sleep 5 &");
    check(out && !str.cmp(out, "first") && time(null) - t0 < 4,
                "background job not waited for");
    mem.free(out);

    /* the user's inheritable fds reach the command, as with System */
    i32 fd = libc.open("/dev/null", libc.O_RDONLY);
    check(is(proc.shellOutput("[ -e /dev/fd/%d ] && echo yes", fd), "yes"),
                "an inheritable fd reaches the command");
    libc.close(fd);

    /* stdin is ours: /dev/null here, so cat ends at once */
    i32 saved = dup(0);
    i32 nul = libc.open("/dev/null", libc.O_RDONLY);
    dup2(nul, 0);
    check(is(proc.shellOutput("cat; echo end"), "end"), "stdin is inherited");
    dup2(saved, 0);
    libc.close(nul);
    libc.close(saved);

    /* from 4 threads at once */
    libc.pthread_t *t[4];
    for (i = 0; i < 4; i++) { libc.pthread_create(&t[i], null, &worker, (void *)i); }
    for (i = 0; i < 4; i++) {
        libc.pthread_join(t[i], &r);
        bad += *r;
        mem.free(r);
    }
    check(bad == 0, "Shlurp from 4 threads");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
