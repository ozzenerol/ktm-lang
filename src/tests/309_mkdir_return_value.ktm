import fs, libc, mem, proc, str, test;

/* fs.makeDir's result could not tell success from failure: without recurse
 * it returned 0 on success and also for a missing parent or a read-only
 * directory, -1 only when the path was a file; with recurse it returned
 * true/false and ignored whether the last directory was made. It now
 * returns 0 if the path is a directory afterwards and -1 if not, like
 * Rm and fs.removeDir. Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 chmod(u8 *path, u32 mode);
extern "c" u32 getuid();

u8 *gRoot;
u8 gPath[256];

u8 *P(u8 *name) {
    str.formatTo(gPath, "%s/%s", gRoot, name);
    return gPath;
}

bool isDir(u8 *name) {
    libc.cStat st;
    return libc.stat(P(name), &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFDIR;
}

i64 gCorrect = 0, gTests = 0;

void expect(u8 *name, bool recurse, i64 want) {
    i64 got = fs.makeDir(P(name),, recurse);
    gTests++;
    if (got == want && isDir(name) == (want == 0)) { gCorrect++; }
    else { printf("MkDir(%s, recurse=%d) -> %d, want %d\n", name, recurse, got, want); }
}

i32 main() {
    u8 root[64], *cmd;
    printf("Test - MkDir return value: ");

    str.copy(root, "./ktm-test-309-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, 1);
        printf("====\n");
        return 0;
    }
    gRoot = root;
    fs.writeAll(P("file"), "k\n", 2);

    expect("new", false, 0);
    expect("new", false, 0);          /* already there */
    expect("file", false, -1);        /* a file is in the way */
    expect("no/such", false, -1);     /* missing parent */
    expect("a/b/c", true, 0);
    expect("a/b/c", true, 0);         /* already there */
    expect("t1/t2/", true, 0);        /* trailing '/' */
    expect("file/x/y", true, -1);     /* a file in the middle */
    expect("a/file2/", true, 0);
    fs.writeAll(P("a/f3"), "k\n", 2);
    expect("a/f3", true, -1);         /* a file at the end */

    /* Permissions don't stop root */
    if (getuid() != 0) {
        fs.makeDir(P("ro"));
        chmod(P("ro"), 0x16D);          /* 0555 */
        expect("ro/x", false, -1);
        expect("ro/y", true, -1);
        expect("ro/y/z", true, -1);
        chmod(P("ro"), 0x1ED);          /* 0755 */
    }

    gTests++; if (fs.makeDir(null) == -1) { gCorrect++; }
    gTests++; if (fs.makeDir("") == -1 && fs.makeDir("",, true) == -1) { gCorrect++; }

    cmd = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
