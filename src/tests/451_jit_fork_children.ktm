import libc, test;

/* F27: on macOS (Apple Silicon) a child forked by a program run with
 * `ktm -jit` could start with its thread's JIT write protection off, so
 * the JIT code it returned into from fork() wasn't executable: SIGBUS,
 * about once in three hundred forks. Here 2000 children each run JIT
 * code and exit with a code they computed; every one must. (Elsewhere,
 * and compiled, this just checks forking.) */

extern "c" void _exit(i32 code);

const i64 FORKS = 2000;

i64 work(i64 i) {
    i64 s = 0;
    for (i64 k = 0; k <= i % 50; k++) { s += k; }
    return s % 200;
}

i32 main() {
    printf("Test - children forked by a JIT-run program: ");
    i64 correct = 0, tests = 0, bad = 0, signalled = 0;
    i32 status;
    libc.fflush(null);   /* or each child would print what's buffered again */
    for (i64 i = 0; i < FORKS; i++) {
        i32 pid = libc.fork();
        if (pid == 0) {
            _exit((i32)work(i));
        }
        if (pid < 0) { bad++; continue; }
        libc.waitpid(pid, &status, 0);
        if ((status & 0x7f) != 0) {
            signalled++;
        } else if (((status >> 8) & 0xff) != work(i)) {
            bad++;
        }
    }
    tests++;
    if (signalled == 0 && bad == 0) { correct++; }
    else { printf("\n  %d of %d children killed by a signal, %d wrong\n", signalled, FORKS, bad); }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
