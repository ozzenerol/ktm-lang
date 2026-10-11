import test;

import fs, libc, mem, proc, str;

/* MkDir(path,,true) copied each prefix of path into a DIR_MAXPATHLEN
 * (256 on Linux, 1024 on macOS) stack buffer without a bound, so a
 * longer path smashed the stack. Everything happens in a fresh mkdtemp
 * directory. The path stays under macOS's PATH_MAX of 1024. */

extern "c" u8 *mkdtemp(u8 *template);

bool isDir(u8 *p) {
    libc.cStat st;
    return libc.stat(p, &st) == 0 && (st.st_mode & libc.S_IFMT) == libc.S_IFDIR;
}

i32 main() {
    i64 correct = 0, tests = 4, i;
    u8 root[64], *p, *q;
    printf("Test - MkDir recursive on a long path: ");

    str.copy(root, "./ktm-test-303-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }

    /* 18 levels of 48 characters: ~900 bytes */
    p = str.dup(root);
    for (i = 0; i < 18; i++) {
        p = str.appendFormat(p, "/%s", "dddddddddddddddddddddddddddddddddddddddddddddddd");
    }
    if (str.len(p) > 800 && str.len(p) < 1000) { correct++; }
    fs.makeDir(p,, true);
    if (isDir(p)) { correct++; }

    /* Again with a trailing '/', over the existing directories */
    q = str.formatTo(null, "%s/e/", p);
    fs.makeDir(q,, true);
    if (isDir(q)) { correct++; }

    /* A short one still works */
    mem.free(q);
    q = str.formatTo(null, "%s/x/y/z", root);
    fs.makeDir(q,, true);
    if (isDir(q)) { correct++; }

    mem.free(q);
    mem.free(p);
    q = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(q);
    mem.free(q);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
