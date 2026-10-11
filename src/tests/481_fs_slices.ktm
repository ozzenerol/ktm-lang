import cstr, fs, libc, mem, os, str, test;

extern "c" i32 getpid();

/* fs with String and u8[] (v0.3.0 step 6b): paths are u8[] (a literal, a
 * slice, a String, a C string through cstr.view), fs.writeAll(path, data)
 * takes the data as a u8[], fs.readAll(path) gives a String that is yours
 * (no ptr when the file can't be read), fs.currentDir() a String, fs.ext
 * and fs.cutExt views. String.take() hands a String's bytes over as a C
 * string, and a method can be called on a call's result. A path longer
 * than the system takes fails with ENAMETOOLONG. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

i32 main() {
    printf("Test - fs with String and u8[]: ");
    String dir = str.format("./ktm-481-%d", getpid());
    defer dir.free();
    check(fs.makeDir(dir) == 0, "makeDir of a String");
    String path = str.format("%s/notes.txt", dir);
    defer path.free();

    u8[] text = "0123456789";
    check(fs.writeAll(path, text[2:6]), "writeAll of a sub-slice");
    String got = fs.readAll(path);
    check(got.ptr && got.equals("2345") && got.ptr[got.len] == 0, "readAll gives a String");
    got.free();

    u8 *cpath = path.cstr();
    check(fs.writeAll(cstr.view(cpath), "c path"), "a C path through cstr.view");
    u8 *c = fs.readAll(path).take();
    check(c && !cstr.cmp(c, "c path"), "take() of the call's result");
    mem.free(c);
    check(fs.readAll("./ktm-481-none/x").take() == null, "take() of an unreadable file is null");

    String missing = fs.readAll("./ktm-481-none/x");
    check(missing.ptr == null && missing.len == 0, "a missing file: no ptr");
    fs.writeAll(path, "");
    String empty = fs.readAll(path);
    check(empty.ptr != null && empty.len == 0, "an empty file: a ptr");
    empty.free();

    String cwd = fs.currentDir();
    defer cwd.free();
    u8 buf[4096];
    check(cwd.ptr && cwd.equals(cstr.view(libc.getcwd(buf, sizeof(buf)))), "currentDir");

    check(fs.ext("a/b.tar.gz").equals(".tar.gz") && fs.cutExt("a/b.tar.gz").equals("a/b") &&
          fs.ext("./x").len == 0 && fs.cutExt("noext").equals("noext"), "ext, cutExt");

    String huge;
    defer huge.free();
    for (i64 i = 0; i < 5000; i++) { huge.appendByte('a'); }
    os.setErrno(0);
    check(!fs.writeAll(huge, "x") && os.errno() != 0 && fs.makeDir(huge) == -1, "a path too long");

    fs.FileStat st;
    check(fs.stat(&st, path) == 0 && fs.remove(dir, fs.REMOVE_RECURSE) == 0 &&
          fs.stat(&st, path) == -1, "stat, remove of a String");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
