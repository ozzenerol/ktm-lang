import fs, libc, mem, proc, test;

/* `ktm --install-dir=X mod init` (an option before the command) said
 * "Unknown commandline options: mod, init". Options before build, run
 * and test now go to them; mod and get let --install-dir through and
 * name any other. `ktm run --install-dir X` (the value as a word of its
 * own) took X for the program's directory. */

#define DIR "./ktm-842"
#define K "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" "
#define I "\"${KTM_INSTALL_DIR:-/usr/local}\""
#define ENV "K=\"$(pwd)/../../ktm\"; cd " DIR " && "

i64 gCorrect = 0, gTests = 0;

/* cmd, run in DIR with its stderr: exits with rc, and prints want */
void run(u8 *cmd, i64 rc, u8 *want, u8 *what) {
    i64 got = -1;
    u8 *o = proc.shellOutput("%s%s%s", ENV, cmd, " 2>&1; echo \"rc=$?\"").take();
    if (o) {
        u8 *at = libc.strstr(o, "rc=");
        if (at) { got = at[3] - '0'; }
    }
    bool ok = o && got == rc && libc.strstr(o, want) != null;
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n  got: %s\n", what, o); }
    mem.free(o);
}

i32 main() {
    printf("Test - options before a command: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR);
    fs.writeAll(DIR "/main.ktm", "i32 main() { printf(\"hi 842\\n\"); return 0; }\n");

    run(K "--install-dir=" I " mod init example.com/me/t842", 0, "created ktm.mod",
        "mod init after --install-dir=X");
    run("cat ktm.mod", 0, "module example.com/me/t842", "its ktm.mod");
    run(K "--install-dir " I " mod list", 0, "", "mod list after --install-dir X");
    run(K "--install-dir=" I " build && ./t842", 0, "hi 842", "build after --install-dir=X");
    run(K "--install-dir " I " -Werror build -o t842b && ./t842b", 0, "hi 842",
        "build after --install-dir X -Werror");
    run(K "--install-dir " I " run", 0, "hi 842", "run after --install-dir X");
    run(K "run --install-dir " I, 0, "hi 842", "run --install-dir X");
    run(K "--install-dir=" I " test", 0, "[no test files]", "test after --install-dir=X");
    run(K "-Werror mod list", 1, "`ktm mod` doesn't take -Werror", "mod names an option it doesn't take");
    run(K "-Werror get", 1, "`ktm get` doesn't take -Werror", "get names an option it doesn't take");

    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
