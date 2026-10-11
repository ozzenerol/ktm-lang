import fs, libc, mem, str, test;

/* FileWrite's default flags were O_CREAT|O_RDWR without O_TRUNC, so
 * writing a shorter buffer over a file kept the old tail. A negative
 * size or a null buffer must fail without creating the file. Everything
 * happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);

u8 gPath[128];

bool exists(u8 *path) {
    libc.cStat st;
    return libc.lstat(path, &st) == 0;
}

bool holds(u8 *path, u8 *want) {
    i64 size = -1;
    u8 *got = fs.readAll(path, &size);
    bool ok = got && size == str.len(want) && !str.cmp(got, want);
    if (!ok && got) { printf("got %d bytes '%s'\n", size, got); }
    if (got) { mem.free(got); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 10;
    u8 root[64], *f, *g;
    printf("Test - FileWrite truncates: ");

    str.copy(root, "./ktm-test-302-XXXXXX");
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    f = str.formatTo(null, "%s/f.txt", root);
    g = str.formatTo(null, "%s/g.txt", root);

    if (fs.writeAll(f, "a long first version\n", 21)) { correct++; }
    if (fs.writeAll(f, "short\n", 6) && holds(f, "short\n")) { correct++; }

    /* An empty write leaves an empty file */
    if (fs.writeAll(f, "", 0) && holds(f, "")) { correct++; }

    /* Explicit flags still work: append */
    fs.writeAll(f, "ab", 2);
    if (fs.writeAll(f, "cd", 2, libc.O_CREAT|libc.O_WRONLY|libc.O_APPEND) && holds(f, "abcd")) {
        correct++;
    }

    /* A negative size or null buffer fails and creates nothing */
    if (!fs.writeAll(g, "xyz", -1)) { correct++; }
    if (!exists(g)) { correct++; }
    if (!fs.writeAll(g, null, 3)) { correct++; }
    if (!exists(g)) { correct++; }
    /* ... and leaves an existing file alone */
    if (!fs.writeAll(f, null, 0) && !fs.writeAll(f, "zz", -5)) { correct++; }
    if (holds(f, "abcd")) { correct++; }

    fs.remove(f);
    fs.remove(g);
    fs.removeDir(root);
    mem.free(f);
    mem.free(g);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
