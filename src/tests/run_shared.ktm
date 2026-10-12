import cstr, fs, libc, mem, os, vec;

i64 cmpFileNames(u8 *str1, u8 *str2) {
    if ('0'<=*str1<='9' && '0'<=*str2<='9') {
        i64 n1 = 0, n2 = 0;
        while ('0'<=*str1<='9') {
            n1 = (n1 * 10) + (*str1-'0');
            str1++;
        }
        while ('0'<=*str2<='9') {
            n2 = (n2 * 10) + (*str2-'0');
            str2++;
        }
        return n2 > n1;
    }
    return 0;
}

void qSortStrings(u8 **arr, i64 high, i64 low=0) {
    if (low < high) {
        auto pivot = arr[high];
        auto idx = low;

        for (auto i = low; i < high; ++i) {
            if (cmpFileNames(arr[i],pivot)) {
                auto tmp = arr[i];
                arr[i] = arr[idx];
                arr[idx] = tmp;
                idx++;
            }
        }

        arr[high] = arr[idx];
        arr[idx] = pivot;
        qSortStrings(arr,high,idx+1);
        qSortStrings(arr,idx-1,low);
    }
}


/* Only NN_name.ktm files are tests: digits, `_`, a name, `.ktm`. The
 * runners, the test module and any helper or scratch .ktm next to them
 * are not. */
bool isTestFile(u8 *filename) {
    i64 i = 0;
    while (filename[i] >= '0' && filename[i] <= '9') { i++; }
    if (i == 0 || filename[i] != '_') { return false; }
    i64 len = cstr.len(filename);
    if (len < i + 6 || cstr.cmp(filename + len - 4, ".ktm")) { return false; }
#ifndef __KTM_LINK_SQLITE3__
    /* The NN_sql*.ktm tests need libktm built with --sqlite. */
    if (libc.strstr(filename, "_sql")) { return false; }
#endif
    return true;
}

/* Cross-compilation hook.
 *
 * When the runner is invoked with `--target=<triple>` as argv[1] we
 * pass `--target=<triple> --install-dir=../../build/<triple>` to ktm
 * for each test, then invoke the resulting binary via the
 * appropriate emulator (`arch -x86_64 ./a.out` on Apple Silicon for
 * x86_64 targets). Empty target string = host arch, default flow.
 */
/* ktm doesn't seem to honour `u8 *name = "lit";` initialisers
 * for globals - the pointer comes through null and any later
 * dereference segfaults. Declare uninitialised and assign in Main
 * before any reader runs. */
u8 *gTarget;

/* Optional libktm prefix for host runs, e.g. build.sh sets
 * KTM_INSTALL_DIR=build/prefix so the tests link the in-tree library
 * rather than whatever is installed in /usr/local. */
extern "c" u8 *getenv(u8 *name);
extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);
extern "c" u8 *realpath(u8 *path, u8 *resolved);
extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 symlink(u8 *target, u8 *path);
extern "c" i32 unlink(u8 *path);
extern "c" i32 dup2(i32 fd, i32 fd2);
extern "c" i32 fflush(void *stream);
extern "c" void _exit(i32 status);
extern "c" i64 sysconf(i32 name);
extern "c" i32 setpgid(i32 pid, i32 pgid);
extern "c" i32 kill(i32 pid, i32 sig);
extern "c" i32 usleep(u32 usec);
extern "c" i32 clock_gettime(i32 clock, i64 *ts);

const i64 WNOHANG = 1;
#ifdef IS_MACOS
const i64 RUNNER_CLOCK_MONOTONIC = 6;
#else
const i64 RUNNER_CLOCK_MONOTONIC = 1;
#endif

/* Milliseconds on a clock that only goes forward */
i64 nowMs() {
    i64 ts[2]; /* struct timespec: seconds, nanoseconds */
    clock_gettime(RUNNER_CLOCK_MONOTONIC, ts);
    return ts[0] * 1000 + ts[1] / 1000000;
}

/* Whether the test file imports module `mod`: a line starting with
 * `import` that names it as a whole word */
bool importsModule(u8 *filename, u8 *mod) {
    String src = fs.readAll(cstr.view(filename));
    if (!src.ptr) { return false; }
    i64 mlen = cstr.len(mod);
    bool found = false;
    u8 *p = src.ptr;
    while (*p && !found) {
        u8 *eol = p;
        while (*eol && *eol != '\n') { eol++; }
        if (eol - p > 7 && !cstr.cmpLen(p, "import ", 7)) {
            for (u8 *q = p + 7; q + mlen <= eol; q++) {
                bool start = q == p + 7 || q[-1] == ' ' || q[-1] == ',';
                bool end = q + mlen == eol || q[mlen] == ',' || q[mlen] == ';' || q[mlen] == ' ';
                if (start && end && !cstr.cmpLen(q, mod, mlen)) {
                    found = true;
                    break;
                }
            }
        }
        p = *eol ? eol + 1 : eol;
    }
    src.free();
    return found;
}

/* Every test file here, or with KTM_TEST_MODULE set, only the ones that
 * import that module (build.sh reruns the coro tests on another backend) */
vec.Vec<u8 *> collectTests() {
    u8 *onlyMod = libc.getenv("KTM_TEST_MODULE");
    auto dir = libc.opendir(".");
    if (!dir) {
        printf("Failed to open directory\n");
        os.exit();
    }
    vec.Vec<u8 *> vecVal = vec.new<u8 *>();
    libc.Dirent *ent;
    while ((ent = libc.readdir(dir)) != null) {
        if (ent.type & libc.DT_REG) {
            if (!isTestFile(ent.name)) {
                continue;
            }
            if (onlyMod && *onlyMod && !importsModule(ent.name, onlyMod)) {
                continue;
            }
            auto strVal = cstr.dup(ent.name);
            vecVal.push(strVal);
        }
    }

    qSortStrings(vecVal.data, vecVal.len - 1);
    libc.closedir(dir);
    return vecVal;
}

/* Tests run in parallel, each in a forked child with its own working
 * directory, TMPDIR and binary, so tests that write fixed file names
 * (./a.out, __NN_*.ktm) can't collide. Everything lives under one
 * temporary root R:
 *   R/ktm     symlink to the ktm under test: a test's "../../ktm" works
 *             from its working directory R/w/<n>
 *   R/w/<n>   working directory of test n
 *   R/t/<n>   its TMPDIR
 *   R/b/<n>   its binary (AOT)
 *   R/o/<n>   its stdout and stderr, printed by the parent in test order,
 *             so the output is the same whatever finishes first.
 * JOBS sets how many run at once (default: the number of CPUs).
 * KTM_TEST_TIMEOUT is how many seconds a test may take (default 60, 0 for
 * no limit): then its process group (the test, its shell, ktm and
 * anything they started) is killed and it is reported as FAILED. */
u8 *gStartDir;   /* where the runner was started (src/tests) */
u8 *gKtm;         /* absolute path of ../../ktm */
u8 *gKtmFlags;   /* --install-dir / --target flags for the runner's ktm */
u8 *gRoot;

#ifdef IS_MACOS
const i64 SC_NPROCESSORS_ONLN = 58;
#else
const i64 SC_NPROCESSORS_ONLN = 84;
#endif

u8 *absPath(u8 *path) {
    u8 *abs = realpath(path, null);
    if (abs) { return abs; }
    if (path[0] == '/') { return cstr.dup(path); }
    return cstr.format("%s/%s", gStartDir, path);
}

i64 jobCount() {
    u8 *env = libc.getenv("JOBS");
    i64 jobs = 0;
    if (env) { jobs = cstr.toInt(env); }
    if (jobs < 1) { jobs = sysconf(SC_NPROCESSORS_ONLN); }
    if (jobs < 1) { jobs = 1; }
    return jobs;
}

i64 testTimeout() {
    u8 *env = libc.getenv("KTM_TEST_TIMEOUT");
    if (env && env[0]) {
        i64 secs = cstr.toInt(env);
        if (secs >= 0) { return secs; }
    }
    return 60;
}

/* Ctrl-C or SIGTERM: each test is its own process group, out of the
 * terminal's reach, so RunTests kills the running ones itself. */
i32 gInterrupted;

void runnerInterrupted(i32 sig) {
    gInterrupted = sig;
}

void runnerInit() {
    u8 *tmpdir = libc.getenv("TMPDIR");
    u8 *installDir = libc.getenv("KTM_INSTALL_DIR");

    gStartDir = absPath(".");
    gKtm = absPath("../../ktm");
    /* Tests run elsewhere: where their fixture directories are
     * (src/tests/modules/...) */
    setenv("KTM_TEST_SRC", gStartDir, 1);

    if (gTarget[0] != '\0') {
        gKtmFlags = cstr.format("--target=%s --install-dir=%s", gTarget,
                                                        absPath(cstr.format("../../build/%s", gTarget)));
    } else if (installDir) {
        /* The tests run ktm themselves from their own directories */
        installDir = absPath(installDir);
        setenv("KTM_INSTALL_DIR", installDir, 1);
        gKtmFlags = cstr.format("--install-dir=%s", installDir);
    } else {
        gKtmFlags = "";
    }

    if (!tmpdir || !tmpdir[0]) { tmpdir = "/tmp"; }
    gRoot = mkdtemp(cstr.format("%s/ktm-tests-XXXXXX", tmpdir));
    if (!gRoot) {
        printf("Failed to create a temporary directory in %s\n", tmpdir);
        os.exit(1);
    }
    gRoot = absPath(gRoot);
    symlink(gKtm, cstr.format("%s/ktm", gRoot));
    fs.makeDir(cstr.view(cstr.format("%s/w", gRoot)));
    fs.makeDir(cstr.view(cstr.format("%s/t", gRoot)));
    fs.makeDir(cstr.view(cstr.format("%s/b", gRoot)));
    fs.makeDir(cstr.view(cstr.format("%s/o", gRoot)));
}

void runnerCleanup() {
    libc.unlink(cstr.format("%s/ktm", gRoot));
    fs.removeDir(cstr.view(gRoot), fs.REMOVE_RECURSE);
}

/* Defined by run.ktm / run_jit.ktm: compile and run `name` (relative to
 * g_start_dir) with the working directory already the test's own. */
void runTest(u8 *name, i64 idx);

/* In the child: set up test idx's directories and output file, run it. */
void runTestChild(u8 *name, i64 idx) {
    /* its own process group, so a timeout can kill everything it starts */
    setpgid(0, 0);
    libc.signal(libc.SIGINT, libc.SIG_DFL);
    libc.signal(libc.SIGTERM, libc.SIG_DFL);
    u8 *work = cstr.format("%s/w/%d", gRoot, idx);
    u8 *tmp = cstr.format("%s/t/%d", gRoot, idx);
    fs.makeDir(cstr.view(work));
    fs.makeDir(cstr.view(tmp));
    setenv("TMPDIR", tmp, 1);

    i32 fd = libc.creat(cstr.format("%s/o/%d", gRoot, idx), 0x1A4);
    if (fd == -1 || libc.chdir(work) != 0) {
        printf("\033[0;31mFAILED\033[0;0m to run: %s (no test directory)\n", name);
        libc.fflush(null);
        _exit(1);
    }
    dup2(fd, 1);
    dup2(fd, 2);
    libc.close(fd);

    runTest(name, idx);
    libc.fflush(null);
    _exit(0);
}

void printTestOutput(i64 idx) {
    i64 len = 0;
    u8 *path = cstr.format("%s/o/%d", gRoot, idx);
    String out_read = fs.readAll(cstr.view(path));
    len = out_read.len;
    u8 *out = out_read.take();
    libc.fflush(null);
    if (out) {
        libc.write(1, out, len);
        mem.free(out);
    }
    mem.free(path);
}

/* Kill test idx (its whole process group), reap it and add the failure
 * to its output. */
void timeOutTest(u8 *name, i64 idx, i32 pid, i64 timeout) {
    i32 status;
    libc.kill(-pid, libc.SIGKILL);
    libc.kill(pid, libc.SIGKILL);
    libc.waitpid(pid, &status, 0);
    u8 *path = cstr.format("%s/o/%d", gRoot, idx);
    u8 *msg = cstr.format("\033[0;31mFAILED\033[0;0m timed out after %d s: %s\n",
                                            timeout, name);
    fs.writeAll(cstr.view(path), cstr.view(msg), libc.O_CREAT|libc.O_WRONLY|libc.O_APPEND);
    mem.free(msg);
    mem.free(path);
}

/* Run every test in `names`, JobCount() at a time, printing each test's
 * output once it and every test before it have finished. */
void runTests(vec.Vec<u8 *> *names) {
    i64 n = names.len, jobs = jobCount(), next = 0, printed = 0;
    i64 running = 0, i, timeout = testTimeout(), now;
    i32 pid, status;
    i32 *pids = mem.allocZero(sizeof(i32) * (n + 1));
    bool *done = mem.allocZero(sizeof(bool) * (n + 1));
    i64 *start = mem.allocZero(sizeof(i64) * (n + 1));
    u8 **entries = names.data;

    runnerInit();
    gInterrupted = 0;
    libc.signal(libc.SIGINT, (i64)(&runnerInterrupted));
    libc.signal(libc.SIGTERM, (i64)(&runnerInterrupted));

    while (printed < n) {
        if (gInterrupted) {
            for (i = printed; i < next; i++) {
                if (pids[i] && !done[i]) {
                    libc.kill(-pids[i], libc.SIGKILL);
                    libc.kill(pids[i], libc.SIGKILL);
                    libc.waitpid(pids[i], &status, 0);
                }
            }
            runnerCleanup();
            _exit(128 + gInterrupted);
        }
        while (running < jobs && next < n) {
            libc.fflush(null);
            pid = libc.fork();
            if (pid == 0) {
                runTestChild(entries[next], next);
            } else if (pid == -1) {
                /* Out of processes: wait for one of ours to finish and retry */
                if (running > 0) { break; }
                u8 *msg = cstr.format("\033[0;31mFAILED\033[0;0m to run: %s (fork failed)\n",
                                                        entries[next]);
                fs.writeAll(cstr.view(cstr.format("%s/o/%d", gRoot, next)), cstr.view(msg));
                done[next] = true;
            } else {
                /* also here, so the group exists before any kill below */
                setpgid(pid, pid);
                pids[next] = pid;
                start[next] = nowMs();
                running++;
            }
            next++;
        }

        if (running > 0) {
            pid = libc.waitpid(-1, &status, WNOHANG);
            if (pid == 0) {
                /* none finished: time out the ones over the limit, then poll again */
                now = nowMs();
                for (i = printed; i < next; i++) {
                    if (timeout > 0 && pids[i] && !done[i] && now - start[i] >= timeout * 1000) {
                        timeOutTest(entries[i], i, pids[i], timeout);
                        done[i] = true;
                        running--;
                    }
                }
                usleep(10000);
            }
            for (i = printed; pid != 0 && i < next; i++) {
                if (pid == -1 && pids[i] && !done[i]) {
                    /* No children left to wait for: should not happen */
                    done[i] = true;
                    running--;
                    continue;
                }
                if (pids[i] == pid) {
                    done[i] = true;
                    running--;
                    break;
                }
            }
        }

        while (printed < n && done[printed]) {
            printTestOutput(printed);
            printed++;
        }
    }

    libc.signal(libc.SIGINT, libc.SIG_DFL);
    libc.signal(libc.SIGTERM, libc.SIG_DFL);
    runnerCleanup();
    mem.free(pids);
    mem.free(done);
    mem.free(start);
}
