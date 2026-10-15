import cstr, fs, libc, mem, proc, test;

/* A program that crashed died with a bare "Segmentation fault": no
 * file, no line, no function. It now prints what happened and where,
 * with the calls that led there (the ones the inliner merged included),
 * compiled and in the JIT:
 *   crash: null pointer dereference (address 0x8)
 *     at f.ktm:5 in get (inlined)
 *     called from f.ktm:10 in middle (inlined)
 *     called from f.ktm:16 in main */

#define CR_SRC "__831_crash.ktm"
#define CR_BIN "./__831_crash.out"
#define CR_ERR "__831_crash.stderr"

/* Compile src (AOT, or run it in the JIT), run it and check that its
 * stderr holds each of the `\n`-separated lines of want, in order. With
 * noEnv, the AOT binary runs 8 times with an empty environment, which puts
 * the top of the stack less than a page above the crash; every run must
 * give the whole report. */
bool crashes(u8 *src, bool jit, u8 *want, bool noEnv = false) {
    bool ok = false;
    i64 res = -1;
    u8 *err = null;
    if (!fs.writeAll(CR_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (jit) {
        /* in a subshell that doesn't exec it (the `; true`), so the
         * shell's own "Segmentation fault" goes nowhere */
        res = proc.shell("(../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit "
                         CR_SRC " > /dev/null 2> " CR_ERR "; true) 2> /dev/null");
    } else if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          CR_SRC " -o " CR_BIN) == 0) {
        if (noEnv) {
            /* each run's report is appended: want must be found 8 times */
            res = proc.shell("rm -f " CR_ERR "; for i in 1 2 3 4 5 6 7 8; do (env -i " CR_BIN
                             " > /dev/null 2>> " CR_ERR "; true) 2> /dev/null; done");
        } else {
            res = proc.shell("(" CR_BIN " > /dev/null 2> " CR_ERR "; true) 2> /dev/null");
        }
    }
    err = fs.readAll(CR_ERR).take();
    if (err) {
        ok = res == 0;  /* the run itself; a crash shows in stderr */
        u8 *at = err, *line = want;
        i64 left = noEnv ? 8 : 1;
        while (ok && *line) {
            u8 *end = line;
            while (*end && *end != '\n') { end++; }
            u8 *found = null, *p;
            for (p = at; *p && !found; p++) {
                if (!cstr.cmpLen(p, line, end - line)) { found = p; }
            }
            if (!found) { ok = false; }
            else { at = found + (end - line); }
            line = *end ? end + 1 : end;
            if (!*line && --left > 0) { line = want; }
        }
        if (!ok) { printf("\n%s: exit %d, stderr [%s]\n", jit ? "jit" : "aot", res, err); }
    }
    mem.free(err);
    fs.remove(CR_SRC);
    fs.remove(CR_BIN);
    fs.remove(CR_ERR);
    return ok;
}

#define NULL_CHAIN "struct P { i64 a; i64 b; };\n"   \
    "i64 get(P *p)\n"                                 \
    "{\n"                                             \
    "    return p.b;\n"                               \
    "}\n"                                             \
    "i64 middle(P *p, i64 n)\n"                       \
    "{\n"                                             \
    "    i64 x = get(p);\n"                           \
    "    return x + n;\n"                             \
    "}\n"                                             \
    "i32 main()\n"                                    \
    "{\n"                                             \
    "    P *p = null;\n"                              \
    "    printf(\"%d\\n\", middle(p, 1));\n"          \
    "    return 0;\n"                                 \
    "}\n"

#define NULL_WANT "crash: null pointer dereference (address 0x8)\n" \
    "  at " CR_SRC ":4 in get\n"                                    \
    "  called from " CR_SRC ":8 in middle\n"                        \
    "  called from " CR_SRC ":14 in main\n"

#define RECURSE "i64 down(i64 n)\n"                   \
    "{\n"                                             \
    "    i64 pad[4];\n"                               \
    "    pad[0] = n;\n"                               \
    "    return down(n + 1) + pad[0];\n"              \
    "}\n"                                             \
    "i32 main()\n"                                    \
    "{\n"                                             \
    "    printf(\"%d\\n\", down(0));\n"               \
    "    return 0;\n"                                 \
    "}\n"

#define RECURSE_WANT "crash: stack overflow\n"        \
    "  called from " CR_SRC ":5 in down (\n"          \
    " times)\n"                                       \
    "  called from " CR_SRC ":9 in main\n"

/* a fault in C code: the ktm line that called it */
#define IN_C "import libc;\n"                         \
    "u64 measure(u8 *s)\n"                            \
    "{\n"                                             \
    "    printf(\"measuring\\n\");\n"                 \
    "    return libc.strlen(s);\n"                    \
    "}\n"                                             \
    "i32 main()\n"                                    \
    "{\n"                                             \
    "    printf(\"%d\\n\", measure(null));\n"         \
    "    return 0;\n"                                 \
    "}\n"

#define IN_C_WANT "crash: null pointer dereference (address 0x0)\n" \
    "  in C code\n"                                                 \
    "  called from " CR_SRC ":5 in measure\n"                       \
    "  called from " CR_SRC ":9 in main\n"

/* a thread the stdlib started: its frames, the pool's below them */
#define THREAD "import thread;\n"                     \
    "void job(void *priv, void *arg)\n"               \
    "{\n"                                             \
    "    i64 *q = arg;\n"                             \
    "    printf(\"%d\\n\", *q);\n"                    \
    "}\n"                                             \
    "i32 main()\n"                                    \
    "{\n"                                             \
    "    thread.Pool *p = thread.poolNew(1);\n"       \
    "    thread.poolEnqueue(p, null, &job);\n"        \
    "    thread.poolWait(p);\n"                       \
    "    return 0;\n"                                 \
    "}\n"

#define THREAD_WANT "crash: null pointer dereference (address 0x0)\n" \
    "  at " CR_SRC ":5 in job\n"                                      \
    "  called from thread/threads.ktm:"

i32 main() {
    printf("Test - crash report: ");
    i64 correct = 0, tests = 9;

    if (crashes(NULL_CHAIN, false, NULL_WANT)) { correct++; }
    if (crashes(NULL_CHAIN, true, NULL_WANT)) { correct++; }
    if (crashes(RECURSE, false, RECURSE_WANT)) { correct++; }
    if (crashes(RECURSE, true, RECURSE_WANT)) { correct++; }
    if (crashes(IN_C, false, IN_C_WANT)) { correct++; }
    if (crashes(IN_C, true, IN_C_WANT)) { correct++; }
    if (crashes(IN_C, false, IN_C_WANT, true)) { correct++; }
    if (crashes(THREAD, false, THREAD_WANT)) { correct++; }
    if (crashes(THREAD, true, THREAD_WANT)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
