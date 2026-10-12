import cstr, fs, libc, mem, proc, test, vec;
#include "run_shared.ktm"

/* The test runners had no time limit: one hanging test (a deadlock)
 * blocked the whole suite until someone killed it. RunTests now kills a
 * test after KTM_TEST_TIMEOUT seconds, together with everything it
 * started, reports it as FAILED "timed out" and goes on. Here it runs two
 * tests with a 2 s limit: "hang" starts a shell that sleeps for 20 s,
 * "quick" just prints. */

const i64 HANG_SECS = 20;

u8 *gTestDir;   /* this test's working directory */

/* RunTests' hook: what each of the two tests does */
void runTest(u8 *name, i64 idx) {
    if (!cstr.cmp(name, "hang")) {
        /* the shell writes its pid, then becomes the sleep */
        u8 *cmd = cstr.format("echo $$ > '%s/__381_pid'; exec sleep %d",
                                                gTestDir, HANG_SECS);
        proc.shell(cstr.view(cmd));
        mem.free(cmd);
        printf("hang finished\n");
    } else {
        printf("quick ran\n");
    }
}

bool contains(u8 *s, u8 *what) {
    return s && libc.strstr(s, what) != null;
}

i32 main() {
    printf("Test - runner timeout: ");
    i64 correct = 0, tests = 0, len = 0, began, took, i;
    i32 pid, status, sleepPid = 0;
    gTarget = "";
    gTestDir = absPath(".");
    libc.unlink("__381_pid");
    libc.unlink("__381_out");

    setenv("KTM_TEST_TIMEOUT", "2", 1);
    setenv("JOBS", "2", 1);
    vec.Vec<u8 *> names = vec.new<u8 *>();
    names.push(cstr.dup("hang"));
    names.push(cstr.dup("quick"));

    /* RunTests in a child, its output in __381_out */
    began = nowMs();
    libc.fflush(null);
    pid = libc.fork();
    if (pid == 0) {
        i32 fd = libc.creat("__381_out", 0x1A4);
        dup2(fd, 1);
        dup2(fd, 2);
        libc.close(fd);
        runTests(&names);
        libc.fflush(null);
        _exit(0);
    }
    libc.waitpid(pid, &status, 0);
    took = nowMs() - began;
    String out_read = fs.readAll("__381_out");
    len = out_read.len;
    u8 *out = out_read.take();

    tests++;
    if (contains(out, "FAILED") && contains(out, "timed out after 2 s: hang")) {
        correct++;
    } else { printf("no timeout reported "); }

    tests++; if (contains(out, "quick ran")) { correct++; } else { printf("quick test missing "); }
    tests++; if (!contains(out, "hang finished")) { correct++; } else { printf("hang was not stopped "); }
    /* not before the limit, and not as long as the sleep */
    tests++;
    if (took >= 2000 && took < (HANG_SECS - 5) * 1000) { correct++; }
    else { printf("took %d ms ", took); }

    /* the sleep the hanging test started was killed too */
    u8 *pidText = fs.readAll("__381_pid").take();
    if (pidText) { sleepPid = cstr.toInt(pidText); }
    for (i = 0; i < 300 && sleepPid > 0 && libc.kill(sleepPid, 0) == 0; i++) {
        usleep(10000);
    }
    tests++;
    if (sleepPid > 0 && libc.kill(sleepPid, 0) != 0) { correct++; }
    else { printf("sleep %d still running ", sleepPid); }

    if (correct != tests && out) { printf("\n%s", out); }
    mem.free(out);
    mem.free(pidText);
    for (auto n : names) { mem.free(n); }
    names.free();
    libc.unlink("__381_pid");
    libc.unlink("__381_out");
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
