import cstr, fs, libc, mem, proc, test;

/* Packages (v0.20.0): `import "host/path";` through ktm.mod: a replace
 * with a local directory, the module cache at the required version
 * (KTM_CACHE), a subdirectory package, the project's own packages, an
 * alias, a list, a package that imports another, and the errors. */

#define DIR "./ktm-772"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; export KTM_CACHE=\"$(pwd)/" DIR "/cache\"; cd " DIR "/app && "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

bool prints(u8 *cmd, u8 *want, u8 *what) {
    u8 *out = proc.shellOutput("%s", cmd).take();
    bool ok = out && libc.strstr(out, want) != null;
    check(ok, what);
    if (!ok) { printf("  got: %s\n", out); }
    mem.free(out);
    return ok;
}

/* main.ktm is `src`; its run (JIT) prints want */
void runs(u8 *src, u8 *want, u8 *what) {
    put(DIR "/app/main.ktm", src);
    prints(ENV KTM "-jit main.ktm 2>&1", want, what);
}

i32 main() {
    printf("Test - package imports: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/greet/sub " DIR "/app/util "
               DIR "/cache/example.com/lib/ktm-color@v1.2.0 " DIR "/cache/example.com/lib/ktm-color@v1.1.0");
    put(DIR "/greet/greet.ktm", "module greet;\nimport \"example.com/lib/ktm-color\";\n"
                                "pub i64 twice(i64 x) { return 2 * x; }\n"
                                "pub u8 *colour() { return color.red(); }\n");
    put(DIR "/greet/sub/sub.ktm", "// a subpackage, named by its module line\nmodule helper;\npub i64 three() { return 3; }\n");
    put(DIR "/cache/example.com/lib/ktm-color@v1.2.0/color.ktm", "module color;\npub u8 *red() { return \"red\"; }\n");
    put(DIR "/cache/example.com/lib/ktm-color@v1.1.0/color.ktm", "module color;\npub u8 *red() { return \"old red\"; }\n");
    put(DIR "/app/util/util.ktm", "module util;\npub i64 one() { return 1; }\n");
    put(DIR "/app/ktm.mod",
"module example.com/me/app
ktm 0.19
require (
    github.com/x/greet v0.1.0
    example.com/lib/ktm-color v1.2.0
)
replace github.com/x/greet => ../greet
");

    runs("import \"github.com/x/greet\";\nimport h = \"github.com/x/greet/sub\", \"example.com/lib/ktm-color\";\n"
         "import \"example.com/me/app/util\";\n"
         "void main() { printf(\"%d %d %s %d %s\\n\", greet.twice(21), h.three(), color.red(), util.one(), greet.colour()); }\n",
         "42 3 red 1 red", "replace, cache, subpackage, alias, list, own package, a package's import");
    prints(ENV KTM "main.ktm -o app 2>&1 && ./app", "42 3 red 1 red", "the same compiled");

    runs("import \"github.com/x/greet\";\nvoid main() { greet.nope(); }\n",
         "`greet.nope` has not been defined", "a message names the package as written");
    runs("import \"github.com/x/greet\";\nvoid main() { }\n",
         "package \"github.com/x/greet\" is imported but not used", "an unused package");
    runs("import \"github.com/nobody/x\";\nvoid main() { }\n",
         "\"github.com/nobody/x\" is not in ktm.mod: `ktm get github.com/nobody/x` adds it", "not required");
    runs("import \"github.com/x/greet/nosuch\";\nvoid main() { }\n",
         "\"github.com/x/greet/nosuch\" is not a package: no directory", "no such directory");
    runs("import \"bad path\";\nvoid main() { }\n", "is not a package path", "a bad path");
    runs("import p = \"github.com/x/greet\";\nvoid main() { greet.twice(1); }\n",
         "`greet` has not been defined", "an alias replaces the name");

    /* a replace of one version by another, then a version not downloaded */
    fs.writeAll(DIR "/app/ktm.mod", "module example.com/me/app\nrequire example.com/lib/ktm-color v1.2.0\n"
                "replace example.com/lib/ktm-color v1.2.0 => example.com/lib/ktm-color v1.1.0\n");
    runs("import \"example.com/lib/ktm-color\";\nvoid main() { printf(\"%s\\n\", color.red()); }\n",
         "old red", "a replace by another version");
    fs.writeAll(DIR "/app/ktm.mod", "module example.com/me/app\nrequire example.com/lib/ktm-color v1.3.0\n");
    runs("import \"example.com/lib/ktm-color\";\nvoid main() { }\n",
         "example.com/lib/ktm-color v1.3.0 is not downloaded: `ktm mod download` fetches it", "not downloaded");
    fs.writeAll(DIR "/app/ktm.mod", "module example.com/me/app\nktm 99.0\n");
    runs("import \"example.com/lib/ktm-color\";\nvoid main() { }\n",
         "ktm.mod needs ktm 99.0 or newer", "a newer ktm");
    fs.remove(DIR "/app/ktm.mod");
    runs("import \"example.com/lib/ktm-color\";\nvoid main() { }\n",
         "no ktm.mod: `ktm mod init <path>`", "no ktm.mod");
    proc.shell("rm -rf " DIR);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
