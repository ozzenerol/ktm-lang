import cstr, fs, libc, test;

/* Pwd() returned getcwd(null,0)'s libc malloc block, and Free of it
 * aborted ("free(): invalid pointer"): Free expects MAlloc's header.
 * fs.currentDir() now gives a String of its own (`defer s.free();`). */

i32 main() {
    i64 correct = 0, tests = 3, i;
    u8 buf[4096];
    printf("Test - fs.currentDir(): ");

    libc.getcwd(buf, sizeof(buf));
    String p = fs.currentDir();
    defer p.free();
    if (p.ptr && p.equals(cstr.view(buf))) { correct++; }
    if (p.ptr && p.ptr[p.len] == 0 && !cstr.cmp(p.cstr(), buf)) { correct++; }

    for (i = 0; i < 100; i++) {
        String again = fs.currentDir();
        again.free();
    }
    correct++;

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
