import cstr, fs, proc, str, test;

/* Packages (v0.20.0): ktm build and ktm run in a project: main.ktm,
 * the binary named after the module, missing packages downloaded (and
 * hashed into ktm.sum) on the way, compiler flags before the directory,
 * the program's arguments and exit code after it. Local git
 * repositories, as in test 773. Needs git. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

String gDir;

/* Runs cmd in gDir with the test's git config, cache and ktm */
String sh(u8 *cmd) {
    return proc.shellOutput("K=\"$(pwd)/../../ktm\"; I=\"${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR}\"; "
                            "cd %s && export GIT_CONFIG_GLOBAL=%s/gitcfg KTM_CACHE=%s/cache && { %s; } 2>&1; echo rc=$?",
                            gDir, gDir, gDir, cmd);
}

bool has(u8 *cmd, u8 *want, u8 *what) {
    String out = sh(cmd);
    bool ok = str.contains(out[0:], cstr.view(want));
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", out.len, out.ptr); }
    out.free();
    return ok;
}

/* A commit of repo with file name.ktm holding body (and a ktm.mod with
 * deps), tagged tag, then its bare copy in repos/ */
void release(u8 *repo, u8 *tag, u8 *name, u8 *body, u8 *deps) {
    String out = sh(str.format(
        "d=work/%s; mkdir -p $d && cd $d && { [ -d .git ] || git init -q; } && "
        "printf 'module example.com/%s\\n%s' > ktm.mod && printf '%%s' '%s' > %s.ktm && "
        "git add -A && git commit -qm %s && git tag -f %s >/dev/null && cd ../../.. && "
        "rm -rf repos/%s && mkdir -p repos/$(dirname %s) && git clone -q --bare $d repos/%s",
        repo, repo, deps, body, name, tag, tag, repo, repo, repo).cstr());
    out.free();
}

i32 main() {
    printf("Test - ktm build and ktm run: ");
    String has_git = proc.shellOutput("command -v git >/dev/null && echo yes");
    if (!str.contains(has_git[0:], "yes")) {
        printf("(no git, skipped) ");
        check(true, "skipped");
        test.printResult(gCorrect, gTests);
        printf("====\n");
        return 0;
    }
    has_git.free();
    String out = proc.shellOutput("mktemp -d");
    gDir = str.from(str.trimEnd(out[0:]));
    out.free();
    String cfg = str.format("[url \"file://%s/repos/\"]\n\tinsteadOf = https://example.com/\n"
                            "[user]\n\tname = t\n\temail = t@t\n[init]\n\tdefaultBranch = main\n"
                            "[protocol \"file\"]\n\tallow = always\n", gDir);
    fs.writeAll(str.format("%s/gitcfg", gDir)[0:], cfg[0:]);
    cfg.free();
    release("lib/color", "v1.1.0", "color", "module color;\npub u8 *red() { return \"red11\"; }\n", "");
    release("lib/paint", "v0.1.0", "paint",
            "module paint;\nimport \"example.com/lib/color\";\npub u8 *wall() { return color.red(); }\n",
            "require example.com/lib/color v1.1.0\\n");
    sh("mkdir -p app && cd app && $K mod init example.com/me/my-app && $K get example.com/lib/paint").free();
    fs.writeAll(str.format("%s/app/main.ktm", gDir)[0:],
                "import \"example.com/lib/paint\";\n"
                "i32 main(i32 argc, u8 **argv) {\n"
                "    printf(\"%s %d %s\\n\", paint.wall(), argc, argc > 1 ? argv[1] : \"-\");\n"
                "    return 7;\n}\n");
    /* an empty cache: build downloads what it needs */
    sh("chmod -R u+w cache && rm -rf cache").free();
    has("cd app && $K build $I && ./my-app x", "red11 2 x\nrc=7", "build: downloads, names the binary after the module");
    has("ls cache/example.com/lib", "color@v1.1.0\npaint@v0.1.0", "the downloads are in the cache");
    has("cd app && $K run $I . first second", "red11 3 first\nrc=7", "run: the program's arguments and exit code");
    has("cd app && $K run $I", "red11 1 -", "run with no directory: the current one");
    has("$K build $I app && ls app", "my-app", "build dir: the binary goes next to main.ktm");
    has("cd app && $K build $I -o other . && ls", "other", "-o names it");
    has("cd app && $K build -Werror $I .", "rc=0", "compiler flags pass through");
    has("mkdir -p empty && cd empty && $K build $I", "no main.ktm in .", "no main.ktm");
    /* ktm.sum still guards the downloads */
    sh("chmod -R u+w cache && rm -rf cache && cd app && sed -i.bak 's|h1:[^ ]*$|h1:AAAA|' ktm.sum").free();
    has("cd app && $K build $I", "doesn't match ktm.sum", "a download that doesn't match ktm.sum stops the build");

    sh("chmod -R u+w cache 2>/dev/null; rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
