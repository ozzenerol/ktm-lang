import cstr, fs, libc, mem, proc, test;

/* Shlurp("cmd") without format arguments used to run the command
 * through System(), so the output went to the terminal and the exit
 * status came back as a pointer. It also wrote to one fixed temporary
 * file it never removed, read output[-1] for empty output and leaked
 * the formatted command. Shlurp returns what the command printed to
 * stdout, minus one trailing newline; a failing command gives whatever
 * it printed (stderr is not captured); a null format gives null. */

/* The result is `expected`; frees it */
bool is(String got, u8[] expected) {
    bool ok;
    if (!got.ptr) {
        printf("got null, want '%s'\n", expected);
        return false;
    }
    ok = got.equals(expected);
    if (!ok) { printf("got '%s', want '%s'\n", got, expected); }
    got.free();
    return ok;
}

extern "c" u8 *getenv(u8 *name);
extern "c" i32 setenv(u8 *name, u8 *value, i32 overwrite);

/* Number of Shlurp temporary files in $TMPDIR (the test's own) */
i64 tempFiles() {
    u8 *out = proc.shellOutput("ls \"${TMPDIR:-/tmp}\" | grep -c '^ktm-shlurp-'").take();
    i64 n = -1;
    if (out) {
        n = cstr.toInt(out);
        mem.free(out);
    }
    return n;
}

i32 main() {
    printf("Test - Shlurp: ");
    i64 tests = 16, correct = 0, before, i;
    u8 *out;

    before = tempFiles();

    /* Without format arguments: captured, not printed */
    if (is(proc.shellOutput("echo hi"), "hi")) { correct++; }
    else { printf("Failed - Shlurp without arguments\n"); }

    /* With arguments */
    if (is(proc.shellOutput("echo %s %d", "two", 2), "two 2")) { correct++; }
    else { printf("Failed - Shlurp with arguments\n"); }

    /* The command is formatted even without arguments: %% is one % */
    if (is(proc.shellOutput("printf '%%s\\n' 50%%"), "50%")) { correct++; }
    else { printf("Failed - Shlurp formats without arguments\n"); }

    /* Multi-line output keeps inner newlines, loses only the last one */
    if (is(proc.shellOutput("printf 'a\\nb\\nc\\n'"), "a\nb\nc")) { correct++; }
    else { printf("Failed - Shlurp multi-line output\n"); }
    if (is(proc.shellOutput("printf 'x\\n\\n'"), "x\n")) { correct++; }
    else { printf("Failed - Shlurp removes only one trailing newline\n"); }
    if (is(proc.shellOutput("printf 'no newline'"), "no newline")) { correct++; }
    else { printf("Failed - Shlurp output without a newline\n"); }

    /* Every command of a list is captured */
    if (is(proc.shellOutput("echo one; echo two"), "one\ntwo")) { correct++; }
    else { printf("Failed - Shlurp command list\n"); }

    /* Empty output is an empty string */
    if (is(proc.shellOutput("true"), "")) { correct++; }
    else { printf("Failed - Shlurp empty output\n"); }
    if (is(proc.shellOutput("printf '%%s' ''"), "")) { correct++; }
    else { printf("Failed - Shlurp empty output with arguments\n"); }

    /* A failing command: what it printed to stdout, stderr isn't captured */
    if (is(proc.shellOutput("echo partial; ls /ktm-81-does-not-exist 2>/dev/null; exit 3"),
                 "partial")) { correct++; }
    else { printf("Failed - Shlurp failing command\n"); }
    if (is(proc.shellOutput("false"), "")) { correct++; }
    else { printf("Failed - Shlurp failing command without output\n"); }

    /* null format */
    if (!proc.shellOutput(null).ptr) { correct++; }
    else { printf("Failed - Shlurp(null)\n"); }

    /* Larger output */
    out = proc.shellOutput("seq 1 %d", 5000).take();
    if (out && cstr.len(out) == 23892 && cstr.cmpLen(out, "1\n2\n3\n", 6) == 0
            && out[cstr.len(out) - 1] == '0') { correct++; }
    else { printf("Failed - Shlurp large output\n"); }
    mem.free(out);

    /* No temporary file is left behind, even after many calls */
    for (i = 0; i < 20; i++) { mem.free(proc.shellOutput("echo %d", i).take()); }
    if (before >= 0 && tempFiles() == before) { correct++; }
    else { printf("Failed - Shlurp left temporary files: %d before, %d after\n",
                 before, tempFiles());
    }

    /* Shlurp reads the command's output through a pipe: no temporary file
     * at all, not even while TempFiles' own Shlurp runs */
    if (before == 0) { correct++; }
    else { printf("Failed - Shlurp made a temporary file in $TMPDIR (%d there)\n", before); }

    /* A TMPDIR with a space in it changes nothing */
    u8 *tmpdir = libc.getenv("TMPDIR");
    if (!tmpdir) { tmpdir = "/tmp"; }
    tmpdir = cstr.dup(tmpdir);
    fs.makeDir("./ktm-81 tmp");
    setenv("TMPDIR", "./ktm-81 tmp", 1);
    if (is(proc.shellOutput("echo spaced"), "spaced")) { correct++; }
    else { printf("Failed - Shlurp with a space in TMPDIR\n"); }
    setenv("TMPDIR", tmpdir, 1);
    mem.free(tmpdir);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
