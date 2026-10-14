import fs, libc, proc, str, test;

/* Packages (v0.20.0): ktm mod init writes ktm.mod; ktm mod fmt reads it
 * (from a subdirectory too), rewrites it in the standard layout, and
 * reports a bad file with its line. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what, String *got) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n%.*s\n", what, got.len, got.ptr); }
}

String gDir, gKtm;

/* Runs `ktm <args>` in gDir/<sub>: the output, then "rc=N" */
String ktm(u8 *sub, u8 *args) {
    return proc.shellOutput("cd %s/%s && %s %s 2>&1; echo rc=$?", gDir, sub, gKtm, args);
}

void writeMod(u8[] text) {
    fs.writeAll(str.format("%s/ktm.mod", gDir)[0:], text, libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

String readMod() {
    return fs.readAll(str.format("%s/ktm.mod", gDir)[0:]);
}

void bad(u8[] text, u8[] message) {
    writeMod(text);
    String got = ktm(".", "mod fmt");
    check(str.contains(got[0:], message) && str.contains(got[0:], "rc=1"), "a bad ktm.mod", &got);
    got.free();
}

i32 main() {
    printf("Test - ktm.mod: ");
    String out = proc.shellOutput("mktemp -d");
    gDir = str.from(str.trimEnd(out[0:]));
    out.free();
    out = proc.shellOutput("cd ../.. && pwd");
    gKtm = str.format("%.*s/ktm", str.trimEnd(out[0:]).len, out.ptr);
    out.free();
    proc.shellf("mkdir -p %s/app/sub", gDir);

    String got = ktm("app", "mod init github.com/me/app");
    String mod = fs.readAll(str.format("%s/app/ktm.mod", gDir)[0:]);
    check(str.contains(got[0:], "rc=0") && str.startsWith(mod[0:], "module github.com/me/app\n\nktm 0."),
          "mod init writes ktm.mod", &mod);
    got.free();
    got = ktm("app", "mod init github.com/me/app");
    check(str.contains(got[0:], "ktm.mod already exists") && str.contains(got[0:], "rc=1"), "init twice", &got);
    got.free();
    got = ktm("app/sub", "mod init");
    String mod2 = fs.readAll(str.format("%s/app/sub/ktm.mod", gDir)[0:]);
    check(str.startsWith(mod2[0:], "module sub\n"), "init names the module after the directory", &mod2);
    got.free();
    got = ktm(".", "mod init 'a b'");
    check(str.contains(got[0:], "is not a module path") && str.contains(got[0:], "rc=1"), "a bad module path", &got);
    got.free();
    mod.free();
    mod2.free();

    /* fmt from a subdirectory: comments go, blocks are joined */
    writeMod("module  github.com/me/app // the app\nktm 0.19\nrequire github.com/a/b v1.2.0\nrequire (\n\n"
             "  github.com/c/d v0.1.0-rc.1 // indirect\n)\nreplace github.com/a/b => ../b\n"
             "replace github.com/c/d v0.1.0-rc.1 => github.com/fork/d v0.1.1\n");
    proc.shellf("mkdir -p %s/deep/er", gDir);
    got = ktm("deep/er", "mod fmt");
    mod = readMod();
    check(str.contains(got[0:], "rc=0") && str.equals(mod[0:],
          "module github.com/me/app\n\nktm 0.19\n\nrequire (\n\tgithub.com/a/b v1.2.0\n"
          "\tgithub.com/c/d v0.1.0-rc.1 // indirect\n)\n\nreplace github.com/a/b => ../b\n"
          "replace github.com/c/d v0.1.0-rc.1 => github.com/fork/d v0.1.1\n"), "fmt rewrites it", &mod);
    got.free();
    mod.free();

    bad("module\n", "ktm.mod:1: the module line is");
    bad("ktm 0.1\n", "no module line");
    bad("module a\nrequire foo v1.0.0\n", "ktm.mod:2: `foo` is not a package path");
    bad("module a\nrequire github.com/x/y 1.0\n", "`1.0` is not a version");
    bad("module a\nrequire github.com/x/y v01.0.0\n", "`v01.0.0` is not a version");
    bad("module a\nrequire (\ngithub.com/x/y v1.0.0\n", "ktm.mod:2: the require ( block has no closing )");
    bad("module a\nfoo bar\n", "unknown line `foo`");
    bad("module a\nreplace github.com/x/y => z\n", "a replacement directory starts with");
    bad("module a\nrequire github.com/x/y v1.0.0\nrequire github.com/x/y v1.1.0\n", "ktm.mod:3: github.com/x/y is required twice (line 2)");
    bad("module a\nktm 0.1\nktm 0.2\n", "a second ktm line");

    fs.remove(str.format("%s/ktm.mod", gDir)[0:]);
    got = ktm("deep", "mod fmt");
    check(str.contains(got[0:], "no ktm.mod here") && str.contains(got[0:], "rc=1"), "fmt without ktm.mod", &got);
    got.free();
    got = ktm(".", "mod nope");
    check(str.contains(got[0:], "unknown command `ktm mod nope`") && str.contains(got[0:], "rc=2"), "an unknown command", &got);
    got.free();

    proc.shellf("rm -rf %s", gDir);
    gDir.free();
    gKtm.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
