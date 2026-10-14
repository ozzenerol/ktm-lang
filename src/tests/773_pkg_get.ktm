import cstr, fs, proc, str, test;

/* Packages (v0.20.0): ktm get and ktm mod download against local git
 * repositories (git's url.insteadOf maps https://example.com/ to them):
 * @latest, a version, a requirement's requirement (indirect), upgrade
 * and downgrade, @none, the cache, ktm.sum's hashes (the same as Go's
 * h1:), a moved tag refused, and the errors. Needs git. */

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
                            "cd %s && export GIT_CONFIG_GLOBAL=%s/gitcfg KTM_CACHE=%s/cache && %s 2>&1; echo rc=$?",
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
    printf("Test - ktm get: ");
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

    release("lib/color", "v1.0.0", "color", "module color;\npub u8 *red() { return \"red1\"; }\n", "");
    release("lib/color", "v1.1.0", "color", "module color;\npub u8 *red() { return \"red11\"; }\n", "");
    release("lib/color", "v1.2.0-rc.1", "color", "module color;\npub u8 *red() { return \"rc\"; }\n", "");
    release("lib/paint", "v0.1.0", "paint",
            "module paint;\nimport \"example.com/lib/color\";\npub u8 *wall() { return color.red(); }\n",
            "require example.com/lib/color v1.1.0\\n");
    sh("mkdir -p app && cd app && $K mod init example.com/me/app").free();
    fs.writeAll(str.format("%s/app/main.ktm", gDir)[0:],
                "import \"example.com/lib/paint\";\nvoid main() { printf(\"%s\\n\", paint.wall()); }\n");

    has("cd app && $K get example.com/lib/paint", "added example.com/lib/paint v0.1.0", "get @latest");
    has("cat app/ktm.mod", "\texample.com/lib/paint v0.1.0\n\texample.com/lib/color v1.1.0 // indirect\n",
        "the requirement's requirement, indirect (v1.1.0: not the prerelease)");
    has("cd app && $K $I -jit main.ktm", "red11", "the program uses them");
    has("ls -d cache/example.com/lib/*", "cache/example.com/lib/color@v1.1.0\ncache/example.com/lib/paint@v0.1.0",
        "the cache");
    has("ls -l cache/example.com/lib/color@v1.1.0/color.ktm", "-r--r--r--", "the cache is read-only");
    /* ktm.sum's hash is Go's h1: sha256 of "<sha256>  <file>" lines */
    String want = sh("python3 -c 'import hashlib,os,base64\n"
                     "r=\"cache/example.com/lib/color@v1.1.0\"\n"
                     "fs=sorted(os.path.relpath(os.path.join(d,f),r) for d,_,n in os.walk(r) for f in n)\n"
                     "s=\"\".join(hashlib.sha256(open(os.path.join(r,f),\"rb\").read()).hexdigest()+\"  \"+f+\"\\n\" for f in fs)\n"
                     "print(base64.b64encode(hashlib.sha256(s.encode()).digest()).decode())'");
    u8[] h = want[0:str.find(want[0:], "\n")];
    String line = str.format("example.com/lib/color v1.1.0 h1:%.*s\n", h.len, h.ptr);
    has("cat app/ktm.sum", line.cstr(), "ktm.sum has the h1: hash");
    line.free();
    want.free();

    has("cd app && $K get example.com/lib/color@v1.0.0", "example.com/lib/color v1.1.0 -> v1.0.0", "a downgrade");
    has("cd app && $K get example.com/lib/color@v1.2.0-rc.1", "v1.0.0 -> v1.2.0-rc.1", "a prerelease, asked for");
    has("cd app && $K get example.com/lib/color@none", "removed example.com/lib/color v1.2.0-rc.1", "@none");
    has("cd app && $K get example.com/lib/nope", "can't read https://example.com/lib/nope", "no such repository");
    has("cd app && $K get example.com/lib/color@v9.9.9", "can't fetch example.com/lib/color v9.9.9", "no such version");
    has("cd app && $K get example.com/lib/color@1.0", "is not a version", "a bad version");
    has("cd app && $K get color", "is not a package path", "a bad path");

    /* a moved tag: other files under the same version are refused */
    sh("cd app && $K get example.com/lib/color@v1.1.0").free();
    release("lib/color", "v1.1.0", "color", "module color;\npub u8 *red() { return \"evil\"; }\n", "");
    sh("chmod -R u+w cache && rm -rf cache/example.com/lib/color@v1.1.0").free();
    has("cd app && $K mod download", "example.com/lib/color v1.1.0 doesn't match ktm.sum", "a moved tag is refused");
    has("cd app && $K mod download", "rc=1", "with a failure");
    has("cd app && sed -i.bak '/color v1.1.0/d' ktm.sum && $K mod download && $K $I -jit main.ktm", "evil",
        "accepted once its line is deleted");

    sh("chmod -R u+w cache; rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
