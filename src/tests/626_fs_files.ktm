import fs, libc, os, str, test, vec;

extern "c" i32 chmod(u8 *path, u32 mode);

/* fs (v0.10.1): directories (openDir/next, list, walk), join, exists,
 * makeDirAll, copyFile, rename and move */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

struct Seen { i64 files; i64 dirs; i64 links; i64 stopAfter; i64 visits; bool sawInLink; };

bool countEntry(fs.DirEntry *e, void *arg) {
    Seen *s = arg;
    s.visits++;
    if (e.kind == fs.FileKind.file) { s.files++; }
    if (e.kind == fs.FileKind.dir) { s.dirs++; }
    if (e.kind == fs.FileKind.link) { s.links++; }
    if (str.contains(e.path, "/lnk/")) { s.sawInLink = true; }
    return s.stopAfter == 0 || s.visits < s.stopAfter;
}

i32 main() {
    printf("Test - fs openDir, list, walk, join, copyFile, move: ");

    String dir = str.format("./ktm-626-%d", libc.getpid());
    defer dir.free();
    defer fs.remove(dir, fs.REMOVE_RECURSE);   /* runs first */

    /* makeDirAll makes the parents, and is fine with what is there */
    String deep = fs.join(dir, "a/b", "c");
    defer deep.free();
    check(fs.makeDirAll(deep) && fs.exists(deep), "makeDirAll a/b/c");
    check(fs.makeDirAll(deep), "makeDirAll again");
    String noParent = fs.join(dir, "x/y");
    defer noParent.free();
    check(!fs.makeDir(noParent), "makeDir without the parent");

    String f1 = fs.join(dir, "one.txt");
    String f2 = fs.join(dir, "a/two.txt");
    String lnk = fs.join(dir, "lnk");
    defer f1.free();
    defer f2.free();
    defer lnk.free();
    check(fs.writeAll(f1, "hello"), "write one.txt");
    check(fs.writeAll(f2, "two"), "write a/two.txt");
    check(libc.symlink("a", lnk.cstr()) == 0, "symlink lnk -> a");

    /* openDir: each entry's name, kind and joined path, . and .. left out */
    fs.Dir d = fs.openDir(dir);
    check(d.ok(), "openDir ok");
    i64 n = 0, files = 0, dirs = 0, links = 0;
    bool pathsOk = true;
    fs.DirEntry *e;
    while ((e = d.next()) != null) {
        n++;
        if (e.kind == fs.FileKind.file) { files++; }
        if (e.kind == fs.FileKind.dir) { dirs++; }
        if (e.kind == fs.FileKind.link) { links++; }
        String want = fs.join(dir, e.name);
        if (!str.equals(want, e.path)) { pathsOk = false; }
        want.free();
    }
    d.close();
    d.close();
    check(n == 3 && files == 1 && dirs == 1 && links == 1, "entries: a file, a dir, a link");
    check(pathsOk, "path is the directory joined with the name");
    check(d.next() == null, "next() after close");

    /* a trailing / on the directory gives no double slash; ad closes it */
    {
        String slash = str.format("%s/", dir);
        defer slash.free();
        ad fs.Dir d2 = fs.openDir(slash);
        fs.DirEntry *first = d2.next();
        check(first != null && !str.contains(first.path, "//"), "openDir of dir/");
    }

    fs.Dir missing = fs.openDir("./ktm-626-no-such-dir");
    check(!missing.ok() && missing.next() == null && os.errno() == libc.ENOENT,
          "openDir of a missing directory");
    missing.close();

    /* list: the names, sorted */
    bool ok = false;
    vec.Vec<String> names = fs.list(dir, &ok);
    check(ok && names.len == 3 && str.equals(names.get(0), "a") &&
          str.equals(names.get(1), "lnk") && str.equals(names.get(2), "one.txt"), "list sorted");
    fs.freeList(&names);
    names = fs.list("./ktm-626-no-such-dir", &ok);
    check(!ok && names.len == 0, "list of a missing directory");
    fs.freeList(&names);

    /* walk: every entry at any depth, never through the symlink */
    Seen s = {0, 0, 0, 0, 0, false};
    check(fs.walk(dir, &countEntry, &s) == 0, "walk completes");
    check(s.files == 2 && s.dirs == 3 && s.links == 1 && !s.sawInLink,
          "walk: 2 files, a b c dirs, the link not entered");
    Seen stop = {0, 0, 0, 2, 0, false};
    check(fs.walk(dir, &countEntry, &stop) == 1 && stop.visits == 2, "walk stops when visit says so");
    Seen none = {0, 0, 0, 0, 0, false};
    check(fs.walk("./ktm-626-no-such-dir", &countEntry, &none) == -1 && none.visits == 0,
          "walk of a missing directory");

    /* join */
    String j1 = fs.join("a", "b");
    String j2 = fs.join("a/", "b");
    String j3 = fs.join("a", "/abs");
    String j4 = fs.join("", "b");
    String j5 = fs.join("a", "");
    check(str.equals(j1, "a/b") && str.equals(j2, "a/b") && str.equals(j3, "/abs") &&
          str.equals(j4, "b") && str.equals(j5, "a"), "join");
    j1.free(); j2.free(); j3.free(); j4.free(); j5.free();

    /* exists */
    String dangling = fs.join(dir, "dangling");
    defer dangling.free();
    libc.symlink("nowhere", dangling.cstr());
    check(fs.exists(f1) && fs.exists(dir) && !fs.exists("./ktm-626-no-such-dir") &&
          !fs.exists(dangling), "exists");

    /* copyFile: the bytes and the permission bits */
    String copy = fs.join(dir, "copy.txt");
    defer copy.free();
    chmod(f1.cstr(), 0x1E0);   /* 0740 */
    check(fs.copyFile(f1, copy), "copyFile");
    String got = fs.readAll(copy);
    fs.FileStat st;
    check(str.equals(got, "hello") && fs.stat(&st, copy) == 0 && (st.st_mode & 0x1FF) == 0x1E0,
          "copy has the bytes and the mode");
    got.free();
    check(fs.writeAll(copy, "longer old content") && fs.copyFile(f1, copy),
          "copyFile over a longer file");
    got = fs.readAll(copy);
    check(str.equals(got, "hello"), "copyFile replaces what was there");
    got.free();
    check(!fs.copyFile(f1, f1) && os.errno() == libc.EINVAL, "copyFile onto itself is refused");
    got = fs.readAll(f1);
    check(str.equals(got, "hello"), "the file is still whole");
    got.free();
    check(!fs.copyFile(dir, copy) && os.errno() == libc.EISDIR, "copyFile of a directory");
    check(!fs.copyFile("./ktm-626-no-such-file", copy) && os.errno() == libc.ENOENT,
          "copyFile of a missing file");

    /* rename and move */
    String renamed = fs.join(dir, "renamed.txt");
    defer renamed.free();
    check(fs.rename(copy, renamed) && !fs.exists(copy) && fs.exists(renamed), "rename");
    check(!fs.rename(copy, renamed) && os.errno() == libc.ENOENT, "rename of a missing file");
    String moved = str.format("%s/ktm-626-%d-moved", libc.getenv("TMPDIR") ? libc.getenv("TMPDIR") : "/tmp",
                              libc.getpid());
    defer moved.free();
    check(fs.move(renamed, moved) && !fs.exists(renamed) && fs.exists(moved), "move");
    String back = fs.readAll(moved);
    check(str.equals(back, "hello"), "moved file has its bytes");
    back.free();
    fs.remove(moved);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
