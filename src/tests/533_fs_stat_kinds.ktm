import fs, libc, os, str, test;

extern "c" i32 mkfifo(u8 *path, u32 mode);

/* fs (v0.6.0): lstat/fstat next to stat, FileStat.kind(), realPath and
 * pread/pwrite, so a program never needs libc.cStat's per-OS layout. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s (errno %d)\n", what, os.errno()); }
}

i32 main() {
    printf("Test - fs stat, lstat, kinds, realPath, pread: ");

    String dir = str.format("./ktm-533-%d", libc.getpid());
    defer dir.free();
    check(fs.makeDir(dir) == 0, "makeDir");
    String file = str.format("%s/data.bin", dir);
    String link = str.format("%s/link", dir);
    String dangling = str.format("%s/dangling", dir);
    String fifo = str.format("%s/fifo", dir);
    defer file.free();
    defer link.free();
    defer dangling.free();
    defer fifo.free();

    check(fs.writeAll(file, "0123456789"), "writeAll");
    check(libc.symlink("data.bin", link.cstr()) == 0, "symlink");
    check(libc.symlink("missing", dangling.cstr()) == 0, "dangling symlink");
    check(mkfifo(fifo.cstr(), 0x180) == 0, "mkfifo");

    fs.FileStat st, lst, dst;
    check(fs.stat(&st, file) == 0 && st.kind() == fs.FileKind.file && st.isFile() &&
          st.st_size == 10 && st.st_nlink == 1 && (st.st_mode & 0x1FF) != 0, "stat a file");
    check(st.st_mtim.tv_sec > 1600000000 && st.st_mtim.tv_nsec >= 0 &&
          st.st_mtim.tv_nsec < 1000000000, "mtime seconds and ns");
    check(fs.stat(&dst, dir) == 0 && dst.kind() == fs.FileKind.dir && dst.isDir() &&
          !dst.isFile(), "stat a directory");
    check(st.st_dev == dst.st_dev && st.st_ino != dst.st_ino, "dev and ino");

    check(fs.stat(&lst, link) == 0 && lst.isFile() && lst.st_ino == st.st_ino &&
          lst.st_size == 10, "stat follows a link");
    check(fs.lstat(&lst, link) == 0 && lst.kind() == fs.FileKind.link && lst.isLink() &&
          lst.st_ino != st.st_ino && lst.st_size == 8, "lstat describes the link");
    check(fs.lstat(&lst, file) == 0 && lst.isFile() && lst.st_ino == st.st_ino,
          "lstat of a plain file");
    check(fs.stat(&lst, dangling) == -1 && os.errno() == 2, "stat of a dangling link: ENOENT");
    check(fs.lstat(&lst, dangling) == 0 && lst.isLink(), "lstat of a dangling link");
    check(fs.lstat(&lst, fifo) == 0 && lst.kind() == fs.FileKind.other, "a FIFO is other");
    check(fs.lstat(&lst, "./no-such-ktm-533") == -1 && os.errno() == 2, "lstat missing");

    i32 fd = libc.open(file.cstr(), libc.O_RDWR);
    check(fd >= 0, "open");
    fs.FileStat fst;
    check(fs.fstat(&fst, fd) == 0 && fst.isFile() && fst.st_ino == st.st_ino &&
          fst.st_size == 10, "fstat");
    check(fs.fstat(&fst, -1) == -1, "fstat of a bad fd");

    u8 buf[4];
    check(fs.pread(fd, buf, 3) == 4 && str.equals(buf, "3456"), "pread at 3");
    check(fs.pread(fd, buf, 8) == 2 && buf[0] == '8' && buf[1] == '9', "pread short at the end");
    check(fs.pread(fd, buf, 10) == 0, "pread at the end");
    check(fs.pwrite(fd, "AB", 4) == 2, "pwrite");
    check(fs.pread(fd, buf, 3) == 4 && str.equals(buf, "3AB6"), "pwrite then pread");
    check(libc.lseek(fd, 0, libc.SEEK_CUR) == 0, "the offset never moved");
    libc.close(fd);

    String real = fs.realPath(link);
    String realDir = fs.realPath(dir);
    String viaDots = str.format("%s/../%s/./link", dir, dir[2:]);
    String real2 = fs.realPath(viaDots);
    check(real.ptr != null && real[0] == '/' && str.endsWith(real, "/data.bin") &&
          str.startsWith(real, realDir), "realPath resolves the link");
    check(real2.ptr != null && str.equals(real, real2), "realPath resolves . and ..");
    real.free();
    realDir.free();
    viaDots.free();
    real2.free();
    String none = fs.realPath(dangling);
    check(none.ptr == null && os.errno() == 2, "realPath of a dangling link: null, ENOENT");

    check(fs.remove(dir, fs.REMOVE_RECURSE) == 0, "remove");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
