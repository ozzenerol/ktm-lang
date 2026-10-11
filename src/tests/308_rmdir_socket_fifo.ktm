import cstr, fs, libc, mem, proc, test;

/* fs.removeDir(path, RM_RECURSE) tested `ent->type & DT_DIR`, but d_type is a
 * number, not a bit mask: a socket (DT_SOCK 12) or block device (DT_BLK
 * 6) matched, was "recursed" into, failed, and stopped the removal. A
 * socket, a FIFO, a symlink and a file must all go. Everything happens
 * in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 mkfifo(u8 *path, u32 mode);
extern "c" i32 symlink(u8 *target, u8 *linkpath);
extern "c" i32 socket(i32 domain, i32 type, i32 protocol);
extern "c" i32 bind(i32 fd, void *addr, u32 len);

u8 *gRoot;
u8 gPath[256];

u8 *P(u8 *name) {
    cstr.formatTo(gPath, "%s/%s", gRoot, name);
    return gPath;
}

bool exists(u8 *name) {
    libc.cStat st;
    return libc.lstat(P(name), &st) == 0;
}

bool isSocket(u8 *name) {
    libc.cStat st;
    return libc.lstat(P(name), &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFSOCK;
}

/* A UNIX socket bound at `path`; sockaddr_un is the family then the
 * path (macOS puts a length byte before a one-byte family) */
bool makeSocket(u8 *path) {
    u8 addr[110];
    i32 fd = libc.socket(1, 1, 0); /* AF_UNIX, SOCK_STREAM */
    if (fd < 0) { return false; }
    mem.set(addr, 0, sizeof(addr));
#ifdef IS_MACOS
    addr[0] = 106;
    addr[1] = 1;
#else
    addr[0] = 1;
    addr[1] = 0;
#endif
    mem.copy(addr + 2, path, cstr.len(path) + 1);
    bool ok = libc.bind(fd, addr, 106) == 0;
    libc.close(fd);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 3;
    u8 root[64], *cmd;
    printf("Test - RmDir removes sockets and FIFOs: ");

    cstr.copy(root, "./ktm-test-308-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    gRoot = root;

    fs.makeDir(P("t"));
    fs.makeDir(P("t/sub"));
    fs.writeAll(P("t/f"), "k\n", 2);
    mkfifo(P("t/fifo"), 0x1A4);
    symlink("sub", P("t/link"));
    makeSocket(P("t/s"));
    makeSocket(P("t/sub/s2"));
    if (isSocket("t/s") && isSocket("t/sub/s2")) { correct++; }

    if (fs.removeDir(P("t"), fs.REMOVE_RECURSE) == 0) { correct++; }
    if (!exists("t")) { correct++; }

    cmd = cstr.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
