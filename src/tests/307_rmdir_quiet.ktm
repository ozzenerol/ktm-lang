import cstr, fs, libc, mem, proc, test;

/* fs.removeDir(path, RM_RECURSE) without RM_VERBOSE printed "Error Rm: <file>"
 * for every file it deleted successfully: the else of
 * `if (unlink(..) == 0 && verbose)` caught the quiet successes too. It
 * now prints nothing without RM_VERBOSE and the right line with it.
 * Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 mkfifo(u8 *path, u32 mode);
extern "c" i32 dup(i32 fd);
extern "c" i32 dup2(i32 fd, i32 fd2);
extern "c" i32 fflush(void *stream);

u8 *gRoot;
u8 gPath[256];
u8 gOut[256];
i32 gSaved;

u8 *P(u8 *name) {
    cstr.formatTo(gPath, "%s/%s", gRoot, name);
    return gPath;
}

bool exists(u8 *name) {
    libc.cStat st;
    return libc.lstat(P(name), &st) == 0;
}

void makeTree(u8 *top) {
    u8 name[64];
    fs.makeDir(cstr.view(P(top)));
    fs.writeAll(cstr.view(P(cstr.formatTo(name, "%s/f1", top))), "k\n", 2);
    mkfifo(P(cstr.formatTo(name, "%s/fifo", top)), 0x1A4);
    fs.makeDir(cstr.view(P(cstr.formatTo(name, "%s/sub", top))));
    fs.writeAll(cstr.view(P(cstr.formatTo(name, "%s/sub/f2", top))), "k\n", 2);
}

void capture() {
    libc.fflush(null);
    gSaved = dup(1);
    i32 fd = libc.open(gOut, libc.O_WRONLY|libc.O_CREAT|libc.O_TRUNC, 0x1A4);
    dup2(fd, 1);
    libc.close(fd);
}

/* Restore stdout and return what was printed */
u8 *captured() {
    libc.fflush(null);
    dup2(gSaved, 1);
    libc.close(gSaved);
    u8 *got = fs.readAll(gOut).take();
    fs.remove(gOut);
    return got;
}

/* `out` has the line `fmt` (with %s = the path of `name`) */
bool hasLine(u8 *out, u8 *fmt, u8 *name) {
    u8 line[512];
    cstr.formatTo(line, fmt, P(name));
    return out && libc.strstr(out, line) != null;
}

i32 main() {
    i64 correct = 0, tests = 2;
    u8 root[64], *out;
    i32 r;
    printf("Test - removeDir is quiet: ");

    cstr.copy(root, "./ktm-test-307-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    gRoot = root;
    cstr.formatTo(gOut, "%s/out.txt", root);

    makeTree("q");
    capture();
    r = fs.removeDir(cstr.view(P("q")), fs.REMOVE_RECURSE);
    out = captured();
    if (r == 0 && !exists("q")) { correct++; }
    if (out && !cstr.cmp(out, "")) { correct++; }
    else { printf("printed '%s'\n", out); }
    mem.free(out);

    out = cstr.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cstr.view(out));
    mem.free(out);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
