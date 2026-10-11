import fs, libc, mem, proc, str, test;

/* Rm(link, RM_RECURSE) on a symlink to a directory stat()ed the target,
 * then emptied the directory the link points to. A symlink inside a
 * tree was fine, but Rm(link) and Rm(dangling link) failed. Everything
 * here happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);
extern "c" i32 symlink(u8 *target, u8 *linkpath);

u8 *gRoot;
u8 gPath[512];

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

i32 main() {
    i64 correct = 0, tests = 13;
    u8 root[64];
    printf("Test - Rm does not follow symlinks: ");

    str.copy(root, "./ktm-test-300-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    gRoot = root;

    fs.makeDir(P("target"));
    makeFile("target/keep1");
    makeFile("target/keep2");
    fs.makeDir(P("tree"));
    makeFile("tree/f");
    symlink("../target", P("tree/inner"));
    symlink("target", P("link"));
    symlink("target", P("link2"));
    symlink("target", P("link3"));
    symlink("nowhere", P("dangling"));

    /* The link goes, the directory it points to stays full */
    if (fs.remove(P("link"), fs.REMOVE_RECURSE) == 0) { correct++; }
    if (!exists("link")) { correct++; }
    if (exists("target/keep1") && exists("target/keep2")) { correct++; }

    /* Without flags too */
    if (fs.remove(P("link2")) == 0) { correct++; }
    if (!exists("link2") && exists("target/keep1")) { correct++; }

    /* A dangling link can be removed */
    if (fs.remove(P("dangling")) == 0) { correct++; }
    if (!exists("dangling")) { correct++; }

    /* A link inside a tree is removed, not followed */
    if (fs.remove(P("tree"), fs.REMOVE_RECURSE) == 0) { correct++; }
    if (!exists("tree")) { correct++; }
    if (exists("target/keep1") && exists("target/keep2")) { correct++; }

    /* RmDir refuses a symlink: it is not a directory */
    if (fs.removeDir(P("link3"), fs.REMOVE_RECURSE) == -1) { correct++; }
    if (exists("link3") && exists("target/keep1")) { correct++; }

    /* The real directory still goes */
    fs.remove(P("link3"));
    if (fs.remove(P("target"), fs.REMOVE_RECURSE) == 0 && !exists("target")) { correct++; }

    u8 *cmd = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
