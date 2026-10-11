import cstr, fs, libc, mem, str, test;

/* fs.writeAll's default flags were O_CREAT|O_RDWR without O_TRUNC, so
 * writing a shorter buffer over a file kept the old tail. It takes the
 * data as a u8[] (no separate size to get wrong); a path it can't open
 * fails without creating anything. Everything happens in a fresh mkdtemp
 * directory. */

extern "c" u8 *mkdtemp(u8 *template);

bool exists(String *path) {
    libc.cStat st;
    return libc.lstat(path.cstr(), &st) == 0;
}

bool holds(u8[] path, u8[] want) {
    String got = fs.readAll(path);
    defer got.free();
    bool ok = got.ptr && got.equals(want);
    if (!ok && got.ptr) { printf("got %d bytes '%s'\n", got.len, got); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 8;
    u8 root[64];
    printf("Test - FileWrite truncates: ");

    mem.copy(root, "./ktm-test-302-XXXXXX", 22);
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    String f = str.format("%s/f.txt", root);
    defer f.free();
    String g = str.format("%s/missing/g.txt", root);
    defer g.free();

    if (fs.writeAll(f, "a long first version\n")) { correct++; }
    if (fs.writeAll(f, "short\n") && holds(f, "short\n")) { correct++; }

    /* An empty write leaves an empty file */
    if (fs.writeAll(f, "") && holds(f, "")) { correct++; }

    /* Explicit flags still work: append */
    fs.writeAll(f, "ab");
    if (fs.writeAll(f, "cd", libc.O_CREAT|libc.O_WRONLY|libc.O_APPEND) && holds(f, "abcd")) {
        correct++;
    }
    /* a slice's bytes only, not what follows them */
    u8[] text = "0123456789";
    if (fs.writeAll(f, text[2:5]) && holds(f, "234")) { correct++; }

    /* A path that can't be opened fails and creates nothing */
    if (!fs.writeAll(g, "xyz")) { correct++; }
    if (!exists(&g)) { correct++; }
    if (holds(f, "234")) { correct++; }

    fs.remove(f);
    fs.removeDir(cstr.view(root));
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
