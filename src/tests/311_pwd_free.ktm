import fs, libc, mem, str, test;

/* Pwd() returned getcwd(null,0)'s libc malloc block, and Free of it
 * aborted ("free(): invalid pointer"): Free expects MAlloc's header. It
 * now returns an MAlloc'd copy. */

extern "c" u8 *getcwd(u8 *buf, u64 size);

i32 main() {
    i64 correct = 0, tests = 4, i;
    u8 buf[4096], small[2], *p;
    printf("Test - Free(Pwd()): ");

    libc.getcwd(buf, sizeof(buf));
    p = fs.currentDir();
    if (p && !str.cmp(p, buf)) { correct++; }
    if (p && mem.size(p) >= str.len(p) + 1) { correct++; }
    mem.free(p);

    for (i = 0; i < 100; i++) { mem.free(fs.currentDir()); }
    correct++;

    /* With a caller's buffer it fills that buffer */
    if (fs.currentDir(buf, sizeof(buf)) == buf && fs.currentDir(small, sizeof(small)) == null) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
