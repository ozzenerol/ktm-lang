import cstr, fs, proc, str, test;

/* Packages (v0.20.0): minimal version selection (a dependency gets at
 * least the version it asks for, in a build and in ktm.mod), ktm mod
 * tidy (requires what the code imports, drops the rest, indirect
 * marks), ktm mod why, list and verify, and one major version per
 * package. Local git repositories, as in test 773. Needs git. */

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
    printf("Test - version selection and ktm mod tidy: ");
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

    release("lib/color", "v1.0.0", "color", "module color;\npub u8 *red() { return \"red10\"; }\n", "");
    release("lib/color", "v1.1.0", "color", "module color;\npub u8 *red() { return \"red11\"; }\n", "");
    release("lib/color", "v2.0.0", "color", "module color;\npub u8 *red() { return \"red20\"; }\n", "");
    release("lib/paint", "v0.1.0", "paint",
            "module paint;\nimport \"example.com/lib/color\";\npub u8 *wall() { return color.red(); }\n",
            "require example.com/lib/color v1.1.0\\n");
    release("lib/unused", "v0.1.0", "unused", "module unused;\npub i64 one() { return 1; }\n", "");
    release("lib/brush", "v0.1.0", "brush", "module brush;\npub u8 *stroke() { return \"stroke\"; }\n",
            "require example.com/lib/color v2.0.0\\n");
    sh("mkdir -p app && cd app && $K mod init example.com/me/app").free();
    fs.writeAll(str.format("%s/app/main.ktm", gDir)[0:],
                "import \"example.com/lib/paint\";\nvoid main() { printf(\"%s\\n\", paint.wall()); }\n");

    /* a direct v1.0.0, then a dependency that needs v1.1.0 */
    has("cd app && $K get example.com/lib/color@v1.0.0 && $K get example.com/lib/paint && cat ktm.mod",
        "\texample.com/lib/color v1.1.0\n", "a dependency raises a requirement to what it needs");
    has("cd app && $K get example.com/lib/color@v1.0.0", "example.com/lib/color stays at v1.1.0: example.com/lib/paint v0.1.0 requires it",
        "a downgrade below a dependency's need is refused");
    /* a hand-edited ktm.mod: the build still uses v1.1.0 */
    sh("cd app && sed -i.bak 's/color v1.1.0/color v1.0.0/' ktm.mod").free();
    has("cd app && $K $I -jit main.ktm", "red11", "the build uses the highest version required");
    has("cd app && $K mod list", "example.com/me/app\nexample.com/lib/color v1.1.0\nexample.com/lib/paint v0.1.0\n",
        "ktm mod list");
    has("cd app && $K mod why example.com/lib/color",
        "example.com/lib/color v1.1.0 <- example.com/lib/paint v0.1.0 <- example.com/me/app (ktm.mod)", "ktm mod why: the chain");
    has("cd app && $K mod why example.com/lib/paint", "main.ktm:1 imports \"example.com/lib/paint\"", "ktm mod why: the import");

    /* tidy: color only indirect, an unused requirement dropped */
    has("cd app && $K get example.com/lib/brush@v0.1.0", "one major version per package", "two majors are refused by get");
    sh("cd app && $K get example.com/lib/unused").free();
    has("cd app && cat ktm.mod", "unused", "(an unused requirement, for tidy to drop)");
    has("cd app && $K mod tidy && cat ktm.mod",
        "require (\n\texample.com/lib/paint v0.1.0\n\texample.com/lib/color v1.1.0 // indirect\n)\n", "tidy");
    has("cd app && $K mod tidy 2>&1 | head -0; cat ktm.sum", "example.com/lib/color v1.1.0 h1:", "tidy keeps the hashes in use");
    has("cd app && grep -c unused ktm.sum", "0", "and drops the others");
    /* tidy adds what the code imports */
    fs.writeAll(str.format("%s/app/more.ktm", gDir)[0:],
                "// another file of the program's directory\nimport c = \"example.com/lib/color\";\n");
    has("cd app && $K mod tidy && cat ktm.mod",
        "\texample.com/lib/color v1.1.0\n\texample.com/lib/paint v0.1.0\n)", "an import makes it direct");
    fs.remove(str.format("%s/app/more.ktm", gDir)[0:]);

    /* verify */
    has("cd app && $K mod verify", "all packages verified", "verify");
    sh("chmod -R u+w cache/example.com/lib/color@v1.1.0 && echo '// edited' >> cache/example.com/lib/color@v1.1.0/color.ktm").free();
    has("cd app && $K mod verify", "example.com/lib/color v1.1.0: CHANGED since it was downloaded", "verify finds an edited file");
    has("cd app && $K mod verify", "rc=1", "and fails");

    /* two majors of one package, met by tidy */
    sh("chmod -R u+w cache/example.com/lib/color@v1.1.0 && rm -rf cache/example.com/lib/color@v1.1.0").free();
    fs.writeAll(str.format("%s/app/main.ktm", gDir)[0:],
                "import \"example.com/lib/paint\";\nimport \"example.com/lib/brush\";\n"
                "void main() { printf(\"%s %s\\n\", paint.wall(), brush.stroke()); }\n");
    has("cd app && $K mod tidy", "one major version per package", "two majors are refused by tidy");

    sh("chmod -R u+w cache; rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
