import fs, libc, mem, proc, str, test;

/* On arm64 Linux cStat had x86_64's struct stat layout, so st_mode,
 * st_size and the times were read from the wrong offsets: a regular file
 * didn't look regular and FileRead, Rm and MkDir misbehaved. */

extern "c" u8 *mkdtemp(u8 *template);

i32 main() {
    i64 correct = 0, tests = 0;
    u8 root[64], *f, *d, *cmd;
    libc.cStat st;
    printf("Test - cStat layout: ");

    str.copy(root, "./ktm-test-293-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, 1);
        printf("====\n");
        return 0;
    }
    f = str.formatTo(null, "%s/file", root);
    d = str.formatTo(null, "%s/dir", root);
    fs.writeAll(f, "hello world\n", 12);
    fs.makeDir(d);

    tests++;
    if (libc.stat(f, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFREG) { correct++; }
    tests++;
    if (st.st_size == 12) { correct++; }
    tests++;
    if ((st.st_mode & 0x1FF) != 0 && st.st_nlink == 1) { correct++; }

    tests++;
    if (libc.stat(d, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFDIR) { correct++; }

    tests++;
    i32 fd = libc.open(f, libc.O_RDONLY);
    if (fd >= 0 && libc.fstat(fd, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFREG &&
            st.st_size == 12) { correct++; }
    if (fd >= 0) { libc.close(fd); }

    tests++;
    i64 len = -1;
    u8 *buf = fs.readAll(f, &len);
    if (buf && len == 12 && !str.cmpLen(buf, "hello world\n", 12)) { correct++; }
    mem.free(buf);

#ifdef IS_LINUX
    tests++;
#ifdef IS_ARM_64
    if (sizeof(libc.cStat) == 128) correct++;
#else
    if (sizeof(libc.cStat) == 144) { correct++; }
#endif
#endif

    cmd = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);
    mem.free(f);
    mem.free(d);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
