import fs, libc, mem, proc, str, test;

/* Rm marked a directory with an internal flag 0x6, which is
 * RM_VERBOSE|RM_FORCE: Rm(file, RM_VERBOSE / RM_FORCE / RM_RECURSE)
 * treated the file as a directory and did not delete it, Rm(dir)
 * printed as if RM_VERBOSE was given, and Rm(missing, RM_FORCE)
 * returned -1 although RM_FORCE means "ignore nonexistent files".
 * Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 dup(i32 fd);
extern "c" i32 dup2(i32 fd, i32 fd2);
extern "c" i32 fflush(void *stream);

u8 *gRoot;
u8 gPath[256];
u8 gOut[256];
i32 gSaved;

u8 *P(u8 *name) {
    str.formatTo(gPath, "%s/%s", gRoot, name);
    return gPath;
}

bool exists(u8 *name) {
    libc.cStat st;
    return libc.lstat(P(name), &st) == 0;
}

void makeFile(u8 *name) {
    fs.writeAll(P(name), "k\n", 2);
}

/* Send stdout to a file until Captured() */
void capture() {
    libc.fflush(null);
    gSaved = dup(1);
    i32 fd = libc.open(gOut, libc.O_WRONLY|libc.O_CREAT|libc.O_TRUNC, 0x1A4);
    dup2(fd, 1);
    libc.close(fd);
}

/* Restore stdout; true if what was printed is `want` (with %s = path) */
bool captured(u8 *want, u8 *path=null) {
    libc.fflush(null);
    dup2(gSaved, 1);
    libc.close(gSaved);
    u8 *got = fs.readAll(gOut), *exp = str.formatTo(null, want, path);
    bool ok = got && !str.cmp(got, exp);
    if (!ok) { printf("printed '%s' want '%s'\n", got, exp); }
    mem.free(got);
    mem.free(exp);
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    u8 root[64], *full;
    i32 r;
    printf("Test - Rm flags: ");

    str.copy(root, "./ktm-test-306-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, 1);
        printf("====\n");
        return 0;
    }
    gRoot = root;
    str.formatTo(gOut, "%s/out.txt", root);
    fs.makeDir(P("w"));
    makeFile("w/a");
    makeFile("w/b");
    makeFile("w/c");
    makeFile("w/d");
    fs.makeDir(P("w/empty"));
    fs.makeDir(P("w/empty2"));
    fs.makeDir(P("w/full"));
    makeFile("w/full/f");
    fs.makeDir(P("w/full/sub"));
    makeFile("w/full/sub/g");

    /* A file goes with any flags, quietly unless RM_VERBOSE */
    capture();
    r = fs.remove(P("w/a"));
    tests++; if (captured("") && r == 0 && !exists("w/a")) { correct++; }
    capture();
    r = fs.remove(P("w/b"), fs.REMOVE_FORCE);
    tests++; if (captured("") && r == 0 && !exists("w/b")) { correct++; }
    capture();
    r = fs.remove(P("w/c"), fs.REMOVE_RECURSE);
    tests++; if (captured("") && r == 0 && !exists("w/c")) { correct++; }
    full = str.dup(P("w/d"));
    capture();
    r = fs.remove(full, RM_VERBOSE);
    tests++; if (captured("Rm '%s'\n", full) && r == 0 && !exists("w/d")) { correct++; }
    mem.free(full);

    /* A missing file: an error, unless RM_FORCE */
    tests++; if (fs.remove(P("w/zz")) == -1) { correct++; }
    capture();
    r = fs.remove(P("w/zz"), fs.REMOVE_FORCE);
    tests++; if (captured("") && r == 0) { correct++; }
    tests++; if (fs.remove(P("w/zz"), fs.REMOVE_FORCE|fs.REMOVE_RECURSE) == 0) { correct++; }
    tests++; if (fs.remove(null) == -1) { correct++; }

    /* An empty directory goes quietly; with RM_VERBOSE it says so */
    capture();
    r = fs.remove(P("w/empty"));
    tests++; if (captured("") && r == 0 && !exists("w/empty")) { correct++; }
    full = str.dup(P("w/empty2"));
    capture();
    r = fs.remove(full, RM_VERBOSE);
    tests++; if (captured("removed dir '%s'\n", full) && r == 0 &&
                             !exists("w/empty2")) { correct++; }
    mem.free(full);

    /* A full directory needs RM_RECURSE */
    capture();
    r = fs.remove(P("w/full"));
    tests++; if (captured("") && r == -1 && exists("w/full/f")) { correct++; }
    tests++; if (fs.remove(P("w/full"), fs.REMOVE_FORCE) == -1 && exists("w/full/f")) { correct++; }
    tests++; if (fs.remove(P("w/full"), fs.REMOVE_RECURSE) == 0 && !exists("w/full")) { correct++; }

    fs.remove(gOut);
    tests++; if (fs.remove(P("w"), fs.REMOVE_RECURSE) == 0 && !exists("w")) { correct++; }
    full = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(full);
    mem.free(full);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
