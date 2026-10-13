import fs, libc, os, test;

/* fs.makeDir, makeDirAll, remove, removeDir, rename, move, copyFile and
 * changeDir return a bool, true = done, as fs.writeAll and fs.exists do
 * (they returned i32 0 on success, so the user's
 * `if (!fs.makeDir(dir)) throw ...` threw when it worked). A failure is
 * false with os.errno() set. */

#define ROOT "./ktm-726"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

/* the user's pattern */
bool made(u8[] dir) {
    try {
        if (!fs.makeDir(dir)) { throw(1, "can't make %s", dir); }
    } catch {
        return false;
    }
    return true;
}

i32 main()
{
    printf("Test - fs calls return true when done: ");
    fs.remove(ROOT, fs.REMOVE_RECURSE | fs.REMOVE_FORCE);

    check(made(ROOT), "if (!fs.makeDir(d)) doesn't throw on success");
    check(made(ROOT), "nor when it already is a directory");
    check(!made(ROOT "/no/such"), "it throws on failure");
    os.setErrno(0);
    check(!fs.makeDir(ROOT "/no/such") && os.errno() == libc.ENOENT, "makeDir: false, ENOENT");

    check(fs.makeDirAll(ROOT "/a/b") && fs.exists(ROOT "/a/b"), "makeDirAll: true");
    fs.writeAll(ROOT "/f", "x");
    os.setErrno(0);
    check(!fs.makeDirAll(ROOT "/f/g") && os.errno() != 0, "makeDirAll: false through a file");

    check(fs.copyFile(ROOT "/f", ROOT "/g") && fs.exists(ROOT "/g"), "copyFile: true");
    os.setErrno(0);
    check(!fs.copyFile(ROOT "/missing", ROOT "/h") && os.errno() == libc.ENOENT, "copyFile: false, ENOENT");

    check(fs.rename(ROOT "/g", ROOT "/h") && !fs.exists(ROOT "/g"), "rename: true");
    os.setErrno(0);
    check(!fs.rename(ROOT "/g", ROOT "/i") && os.errno() == libc.ENOENT, "rename: false, ENOENT");

    check(fs.move(ROOT "/h", ROOT "/i") && fs.exists(ROOT "/i"), "move: true");
    os.setErrno(0);
    check(!fs.move(ROOT "/h", ROOT "/j") && os.errno() == libc.ENOENT, "move: false, ENOENT");

    check(fs.remove(ROOT "/i") && !fs.exists(ROOT "/i"), "remove: true");
    os.setErrno(0);
    check(!fs.remove(ROOT "/i") && os.errno() == libc.ENOENT, "remove: false, ENOENT");
    check(fs.remove(ROOT "/i", fs.REMOVE_FORCE), "remove of a missing path, forced: true");

    check(fs.removeDir(ROOT "/a/b") && !fs.exists(ROOT "/a/b"), "removeDir: true");
    os.setErrno(0);
    check(!fs.removeDir(ROOT "/a/b") && os.errno() == libc.ENOENT, "removeDir: false, ENOENT");

    check(fs.changeDir(ROOT) && fs.exists("f"), "changeDir: true");
    check(fs.changeDir(".."), "changeDir back");
    os.setErrno(0);
    check(!fs.changeDir(ROOT "/no/such") && os.errno() == libc.ENOENT, "changeDir: false, ENOENT");

    check(fs.remove(ROOT, fs.REMOVE_RECURSE) && !fs.exists(ROOT), "remove recursive: true");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
