import test;

import fs, mem, proc, str;

/* FileRead on a directory took lseek(SEEK_END) as the size, which is
 * LLONG_MAX on ext4: MAlloc(len+100) overflowed and it crashed. Only a
 * regular file is read now; anything else gives null. *_size is set on
 * every return, 0 on failure. Everything happens in a fresh mkdtemp
 * directory. */

extern "c" u8 *mkdtemp(u8 *template);

i32 main() {
    i64 correct = 0, tests = 7, n;
    u8 root[64], *s, *f, *cmd;
    printf("Test - FileRead on a directory: ");

    mem.copy(root, "/tmp/ktm-test-310-XXXXXX", 25);
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    f = str.formatTo(null, "%s/f.txt", root);

    n = 77;
    s = fs.readAll(root, &n);
    if (s == null && n == 0) { correct++; }
    if (fs.readAll(root) == null) { correct++; }
    /* The working directory: /tmp may be tmpfs, this is often ext4 */
    n = 77;
    if (fs.readAll(".", &n) == null && n == 0) { correct++; }

    /* Other failures set the size too */
    n = 77;
    if (fs.readAll(f, &n) == null && n == 0) { correct++; }
    n = 77;
    if (fs.readAll(null, &n) == null && n == 0) { correct++; }

    /* Regular files, empty or not, still read */
    fs.writeAll(f, "", 0);
    n = 77;
    s = fs.readAll(f, &n);
    if (s && n == 0 && s[0] == '\0') { correct++; }
    mem.free(s);
    fs.writeAll(f, "hello\n", 6);
    s = fs.readAll(f, &n);
    if (s && n == 6 && !str.cmp(s, "hello\n")) { correct++; }
    mem.free(s);

    mem.free(f);
    cmd = str.formatTo(null, "rm -rf '%s'", root);
    proc.shell(cmd);
    mem.free(cmd);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
