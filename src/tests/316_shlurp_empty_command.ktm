import cstr, fs, libc, mem, proc, test;

/* Shlurp wrapped the command as "{ cmd\n}": an empty command made "{ }",
 * and a command ending in a backslash swallowed the "}", both shell
 * syntax errors on stderr. The command now goes on its own line, then a
 * blank line and ":". Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 dup(i32 fd);
extern "c" i32 dup2(i32 fd, i32 fd2);
extern "c" i32 fflush(void *stream);

u8 gErr[256];
i32 gSaved;

void captureErr() {
    libc.fflush(null);
    gSaved = dup(2);
    i32 fd = libc.open(gErr, libc.O_WRONLY|libc.O_CREAT|libc.O_TRUNC, 0x1A4);
    dup2(fd, 2);
    libc.close(fd);
}

u8 *capturedErr() {
    libc.fflush(null);
    dup2(gSaved, 2);
    libc.close(gSaved);
    return fs.readAll(gErr);
}

bool quiet(u8 *cmd, u8 *want) {
    captureErr();
    u8 *got = proc.shellOutput(cmd), *err = capturedErr();
    bool ok = got && !cstr.cmp(got, want) && err && !cstr.cmp(err, "");
    if (!ok) { printf("Shlurp(\"%s\"): got '%s' want '%s', stderr '%s'\n",
                     cmd, got, want, err);
    }
    mem.free(got);
    mem.free(err);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 6;
    u8 root[64], *cmd;
    printf("Test - Shlurp empty command: ");

    cstr.copy(root, "./ktm-test-316-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    cstr.formatTo(gErr, "%s/err.txt", root);

    if (quiet("", "")) { correct++; }
    if (quiet("   ", "")) { correct++; }
    if (quiet("echo a \\", "a")) { correct++; }
    if (quiet("echo a # comment", "a")) { correct++; }
    if (quiet("echo a; echo b", "a\nb")) { correct++; }
    if (quiet("echo x\necho y", "x\ny")) { correct++; }

    cmd = cstr.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
