import cstr, fs, libc, mem, proc, test;

/* fs.makeDir's result could not tell success from failure: without recurse
 * it returned 0 on success and also for a missing parent or a read-only
 * directory, -1 only when the path was a file; with recurse it returned
 * true/false and ignored whether the last directory was made. It now
 * returned 0 if the path is a directory afterwards and -1 if not, and
 * since v0.18.0 true and false, like fs.removeDir. Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 chmod(u8 *path, u32 mode);
extern "c" u32 getuid();

u8 *gRoot;
u8 gPath[256];

u8 *P(u8 *name) {
    cstr.formatTo(gPath, "%s/%s", gRoot, name);
    return gPath;
}

bool isDir(u8 *name) {
    libc.cStat st;
    return libc.stat(P(name), &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFDIR;
}

i64 gCorrect = 0, gTests = 0;

void expect(u8 *name, bool recurse, bool want) {
    bool got = fs.makeDir(cstr.view(P(name)),, recurse);
    gTests++;
    if (got == want && isDir(name) == want) { gCorrect++; }
    else { printf("MkDir(%s, recurse=%d) -> %d, want %d\n", name, recurse, got, want); }
}

i32 main() {
    u8 root[64], *cmd;
    printf("Test - MkDir return value: ");

    cstr.copy(root, "./ktm-test-309-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, 1);
        printf("====\n");
        return 0;
    }
    gRoot = root;
    fs.writeAll(cstr.view(P("file")), "k\n");

    expect("new", false, true);
    expect("new", false, true);          /* already there */
    expect("file", false, false);        /* a file is in the way */
    expect("no/such", false, false);     /* missing parent */
    expect("a/b/c", true, true);
    expect("a/b/c", true, true);         /* already there */
    expect("t1/t2/", true, true);        /* trailing '/' */
    expect("file/x/y", true, false);     /* a file in the middle */
    expect("a/file2/", true, true);
    fs.writeAll(cstr.view(P("a/f3")), "k\n");
    expect("a/f3", true, false);         /* a file at the end */

    /* Permissions don't stop root */
    if (getuid() != 0) {
        fs.makeDir(cstr.view(P("ro")));
        chmod(P("ro"), 0x16D);          /* 0555 */
        expect("ro/x", false, false);
        expect("ro/y", true, false);
        expect("ro/y/z", true, false);
        chmod(P("ro"), 0x1ED);          /* 0755 */
    }

    gTests++; if (!fs.makeDir(cstr.view(null))) { gCorrect++; }
    gTests++; if (!fs.makeDir("") && !fs.makeDir("",, true)) { gCorrect++; }

    cmd = cstr.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cstr.view(cmd));
    mem.free(cmd);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
