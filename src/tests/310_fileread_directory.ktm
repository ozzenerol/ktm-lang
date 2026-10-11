import cstr, fs, mem, proc, str, test;

/* fs.readAll on a directory took lseek(SEEK_END) as the size, which is
 * LLONG_MAX on ext4: MAlloc(len+100) overflowed and it crashed. Only a
 * regular file is read now; anything else gives a String with no ptr
 * (and len 0). An empty file gives an empty String that has one.
 * Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);

bool fails(u8[] path) {
    String s = fs.readAll(path);
    defer s.free();
    return s.ptr == null && s.len == 0;
}

i32 main() {
    i64 correct = 0, tests = 7;
    u8 root[64];
    printf("Test - FileRead on a directory: ");

    mem.copy(root, "/tmp/ktm-test-310-XXXXXX", 25);
    if (!mkdtemp(root)) {
        test.printResult(0, tests);
        printf("====\n");
        return 0;
    }
    u8[] dir = cstr.view(root);
    String f = str.format("%s/f.txt", root);
    defer f.free();

    if (fails(dir)) { correct++; }
    /* The working directory: /tmp may be tmpfs, this is often ext4 */
    if (fails(".")) { correct++; }
    /* A file that isn't there, an empty path */
    if (fails(f)) { correct++; }
    u8[] none;
    if (fails(none)) { correct++; }

    /* Regular files, empty or not, still read */
    fs.writeAll(f, "");
    String s = fs.readAll(f);
    if (s.ptr && s.len == 0 && s.ptr[0] == '\0') { correct++; }
    s.free();
    fs.writeAll(f, "hello\n");
    s = fs.readAll(f);
    if (s.ptr && s.len == 6 && s.equals("hello\n")) { correct++; }
    s.free();
    /* a path longer than any the system takes */
    String huge = str.new();
    defer huge.free();
    for (i64 i = 0; i < 5000; i++) { huge.appendByte('a'); }
    if (fails(huge)) { correct++; }

    String cmd = str.format("rm -rf '%s'", root);
    defer cmd.free();
    proc.shell(cmd.cstr());

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
