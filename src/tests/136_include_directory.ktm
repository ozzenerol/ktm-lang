#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `#include "."` (a directory) hung forever: open() succeeds on a
 * directory and lexReadfile looped while read() kept failing with
 * EISDIR. It must be reported at the #include and lexing carry on, and
 * a directory given as the source file must be an error, not a hang. */

#define INCDIR_FILE "__136_include_directory.ktm"
#define INCDIR_INC  "__136_include_directory.ktmh"
#define INCDIR_DIR  "__136_include_directory.d"
#define INCDIR_OUT  "__136_include_directory.out"
/* ktm only takes a source file by its extension */
#define INCDIR_SRCDIR "__136_include_directory_dir.ktm"

/* `timeout` is coreutils; without it the command just runs */
#define INCDIR_KTM "$(command -v timeout >/dev/null && echo timeout 10) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void cleanupIncDirTest(void) {
    proc.shell("chmod 644 " INCDIR_INC " 2>/dev/null; rm -rf " INCDIR_FILE " " INCDIR_INC " " INCDIR_DIR " " INCDIR_SRCDIR " " INCDIR_OUT " ./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p++;
    }
    return n;
}

/* Run ktm with `args` > INCDIR_OUT. With `count` 0 it must compile
 * cleanly; otherwise it must exit 1 with `want` in its output and, when
 * `count` > 0, exactly `count` errors. */
bool runKtm(u8 *args, i64 count, u8 *want) {
    u8 *cmd, *out;
    i64 res;
    bool ok = true;

    cmd = str.format(INCDIR_KTM "%s > " INCDIR_OUT " 2>&1", args);
    res = proc.shell(cmd);
    mem.free(cmd);

    out = fs.readAll(INCDIR_OUT);
    if (!out) { return false; }

    if (count == 0) {
        if (res != 0) { ok = false; }
    } else {
        /* System returns the exit code; `timeout` exits 124 on a hang */
        if (res != 1) { ok = false; }
        if (!libc.strstr(out, want)) { ok = false; }
        if (count > 0 && countErrors(out) != count) { ok = false; }
    }
    if (!ok) { printf("exit status %d\n%s\n", res, out); }

    mem.free(out);
    return ok;
}

bool compile(u8 *source, i64 count, u8 *want) {
    bool ok;
    if (!fs.writeAll(INCDIR_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    ok = runKtm(INCDIR_FILE, count, want);
    fs.remove(INCDIR_FILE);
    return ok;
}

/* Reported at the #include, and the rest of the file is still lexed and
 * parsed: the bad declaration after it is reported too */
bool testIncludeDot(void) {
    return compile(
"#include \".\"
NoSuchType y;
void main() { printf(\"hi\\n\"); }
", 2, "error: #include: cannot read '.'");
}

bool testIncludeDirectory(void) {
    bool ok;
    proc.shell("mkdir -p " INCDIR_DIR);
    ok = compile(
"#include \"" INCDIR_DIR "\"
void main() { printf(\"hi\\n\"); }
", 1, "error: #include: cannot read '" INCDIR_DIR "'");
    proc.shell("rm -rf " INCDIR_DIR);
    return ok;
}

/* An unreadable file is caught before the read (as "cannot open"); root
 * can read it anyway, so the check is skipped there */
bool testIncludeUnreadable(void) {
    bool ok = true;
    if (proc.shell("test \"$(id -u)\" = 0") == 0) { return true; }
    proc.shell("echo 'i64 x = 1;' > " INCDIR_INC " && chmod 000 " INCDIR_INC);
    ok = compile(
"#include \"" INCDIR_INC "\"
void main() { printf(\"hi\\n\"); }
", 1, "error: #include: cannot open '" INCDIR_INC "'");
    proc.shell("chmod 644 " INCDIR_INC "; rm -f " INCDIR_INC);
    return ok;
}

/* An empty file is a valid (empty) include */
bool testIncludeEmpty(void) {
    bool ok;
    proc.shell(": > " INCDIR_INC);
    ok = compile(
"#include \"" INCDIR_INC "\"
void main() { printf(\"hi\\n\"); }
", 0, null);
    fs.remove(INCDIR_INC);
    return ok;
}

/* The source file itself being a directory */
bool testSourceIsDirectory(void) {
    bool ok;
    proc.shell("mkdir -p " INCDIR_SRCDIR);
    ok = runKtm(INCDIR_SRCDIR, -1,
                            "Failed to read file '" INCDIR_SRCDIR "': Is a directory");
    proc.shell("rm -rf " INCDIR_SRCDIR);
    return ok;
}

i32 main() {
    printf("Test - #include of a directory: ");
    i64 correct = 0, tests = 0;

    cleanupIncDirTest();
    tests++; if (testIncludeDot())        { correct++; }
    tests++; if (testIncludeDirectory())  { correct++; }
    tests++; if (testIncludeUnreadable()) { correct++; }
    tests++; if (testIncludeEmpty())      { correct++; }
    tests++; if (testSourceIsDirectory()) { correct++; }
    cleanupIncDirTest();

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
