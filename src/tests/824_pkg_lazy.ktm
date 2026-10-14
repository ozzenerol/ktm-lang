import cstr, fs, proc, str, test;

/* Lazy loading (v0.21.0, Go 1.17's pruning): a module is downloaded, and
 * its requirements read, only when it provides a package the build
 * imports. core's root package imports util; its core/db package imports
 * heavy. A project importing only core never fetches heavy (its
 * repository can be gone), and ktm.mod lists only what is needed; once it
 * imports core/db, the build fetches heavy, heavy's requirements count
 * (util goes to v1.1.0) and tidy records it `// indirect`. Local git
 * repositories, as in test 776. Needs git. */

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

bool hasNot(u8 *cmd, u8 *unwanted, u8 *what) {
    String out = sh(cmd);
    bool ok = !str.contains(out[0:], cstr.view(unwanted));
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", out.len, out.ptr); }
    out.free();
    return ok;
}

/* gDir/path holds text */
void put(u8 *path, u8 *text) {
    sh(str.format("mkdir -p $(dirname %s)", path).cstr()).free();
    fs.writeAll(str.format("%s/%s", gDir, path)[0:], cstr.view(text));
}

/* Commits work/<repo> (its files written already, ktm.mod with deps),
 * tags it and puts its bare copy in repos/ */
void release(u8 *repo, u8 *tag, u8 *deps) {
    put(str.format("work/%s/ktm.mod", repo).cstr(),
        str.format("module example.com/%s\n%s", repo, deps).cstr());
    String out = sh(str.format(
        "d=work/%s; cd $d && { [ -d .git ] || git init -q; } && "
        "git add -A && git commit -qm %s && git tag -f %s >/dev/null && cd ../../.. && "
        "rm -rf repos/%s && mkdir -p repos/$(dirname %s) && git clone -q --bare $d repos/%s",
        repo, tag, tag, repo, repo, repo).cstr());
    out.free();
}

i32 main() {
    printf("Test - lazy package loading: ");
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

    put("work/lib/util/util.ktm", "module util;\npub u8 *v() { return \"util10\"; }\n");
    release("lib/util", "v1.0.0", "");
    put("work/lib/util/util.ktm", "module util;\npub u8 *v() { return \"util11\"; }\n");
    release("lib/util", "v1.1.0", "");
    put("work/lib/heavy/heavy.ktm",
        "module heavy;\nimport \"example.com/lib/util\";\npub u8 *h() { return util.v(); }\n");
    release("lib/heavy", "v1.0.0", "require example.com/lib/util v1.1.0\n");
    put("work/lib/core/core.ktm",
        "module core;\nimport \"example.com/lib/util\";\npub u8 *c() { return util.v(); }\n");
    put("work/lib/core/db/db.ktm",
        "module db;\nimport \"example.com/lib/core\";\nimport \"example.com/lib/heavy\";\n"
        "pub u8 *d() { return heavy.h(); }\n");
    put("work/lib/core/core_test.ktm", "module core;\nimport \"example.com/lib/heavy\";\n");
    release("lib/core", "v1.0.0",
            "require (\n\texample.com/lib/heavy v1.0.0\n\texample.com/lib/util v1.0.0\n)\n");

    /* only core's root package: heavy is never fetched */
    put("app/main.ktm",
        "import \"example.com/lib/core\";\n"
        "i32 main() { printf(\"%s\\n\", core.c()); return 0; }\n");
    has("cd app && $K mod init example.com/me/app && $K get example.com/lib/core",
        "added example.com/lib/core v1.0.0", "get core");
    hasNot("ls cache/example.com/lib", "heavy", "heavy isn't downloaded");
    has("ls cache/example.com/lib", "core@v1.0.0\nutil@v1.0.0", "core and what its root package imports");
    has("cat app/ktm.mod",
        "\texample.com/lib/core v1.0.0\n\texample.com/lib/util v1.0.0 // indirect\n)", "ktm.mod: what is needed");
    hasNot("cat app/ktm.mod app/ktm.sum", "heavy", "no heavy line in ktm.mod or ktm.sum");
    /* the build doesn't need heavy's repository at all */
    sh("mv repos/lib/heavy repos/heavy.away").free();
    has("cd app && $K build $I && ./app", "util10\nrc=0", "build and run without heavy");
    has("cd app && $K mod list", "example.com/me/app\nexample.com/lib/core v1.0.0\nexample.com/lib/util v1.0.0\nrc=0",
        "mod list: what the build uses");
    has("cd app && $K mod why example.com/lib/heavy",
        "example.com/lib/heavy v1.0.0 <- example.com/lib/core v1.0.0 <- example.com/me/app (ktm.mod)\n"
        "but no package the project imports needs it", "mod why: required, not needed");
    has("cd app && $K mod verify", "all packages verified", "mod verify: what is needed");
    has("cd app && $K mod download", "rc=0", "mod download: what is needed");
    hasNot("ls cache/example.com/lib", "heavy", "still no heavy");
    sh("mv repos/heavy.away repos/lib/heavy").free();

    /* core/db imports heavy: the build fetches it, and heavy needs util v1.1.0 */
    put("app/main.ktm",
        "import \"example.com/lib/core/db\";\n"
        "i32 main() { printf(\"%s\\n\", db.d()); return 0; }\n");
    has("cd app && $K build $I && ./app", "downloading example.com/lib/heavy v1.0.0", "the build fetches heavy");
    has("cd app && ./app", "util11\nrc=0", "heavy's requirement raises util");
    has("cat app/ktm.sum", "example.com/lib/heavy v1.0.0 h1:", "heavy's hash recorded");
    has("cd app && $K mod tidy && cat ktm.mod",
        "\texample.com/lib/core v1.0.0\n\texample.com/lib/heavy v1.0.0 // indirect\n"
        "\texample.com/lib/util v1.1.0 // indirect\n)", "tidy records heavy and util v1.1.0");

    /* back to core alone: tidy drops heavy, util stays where it is */
    put("app/main.ktm",
        "import \"example.com/lib/core\";\n"
        "i32 main() { printf(\"%s\\n\", core.c()); return 0; }\n");
    has("cd app && $K mod tidy", "dropped example.com/lib/heavy v1.0.0 (no imported package needs it)",
        "tidy drops what nothing needs");
    has("cat app/ktm.mod", "\texample.com/lib/util v1.1.0 // indirect\n)", "no version goes down");
    hasNot("cat app/ktm.sum", "heavy", "and its ktm.sum line");

    /* get of a package (not the repository) fetches what it imports */
    put("app2/main.ktm", "i32 main() { return 0; }\n");
    sh("chmod -R u+w cache && rm -rf cache").free();
    has("cd app2 && $K mod init example.com/me/app2 && $K get example.com/lib/core/db && cat ktm.mod",
        "\texample.com/lib/core v1.0.0\n\texample.com/lib/heavy v1.0.0 // indirect\n"
        "\texample.com/lib/util v1.1.0 // indirect\n)", "get core/db: heavy and util v1.1.0");
    has("ls cache/example.com/lib", "heavy@v1.0.0", "heavy downloaded for core/db");

    sh("chmod -R u+w cache 2>/dev/null; rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
