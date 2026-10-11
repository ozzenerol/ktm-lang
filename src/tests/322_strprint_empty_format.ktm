import cstr, mem, test;

/* cstr.formatArgs wrote the NUL only after a character, so an empty result
 * (cstr.formatTo(null, ""), or "%s" of "") was an unterminated MAlloc block:
 * whatever was in it came out (garbage on arm64 Linux, where Shlurp("")
 * ran it as a command; test 316 covers Shlurp). */

i32 main() {
    i64 correct = 0, tests = 0, i;
    u8 *s, *junk, buf[16];
    printf("Test - StrPrint with an empty result: ");

    /* Fill freed blocks with junk so a reused one isn't zero by chance */
    for (i = 0; i < 64; i++) {
        junk = mem.alloc(256);
        mem.set(junk, 'x', 256);
        mem.free(junk);
    }

    tests++;
    s = cstr.formatTo(null, "");
    if (s && cstr.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    s = cstr.formatTo(null, "%s", "");
    if (s && cstr.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    s = cstr.format("");
    if (s && cstr.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    mem.set(buf, 'y', 16);
    cstr.formatTo(buf, "");
    if (buf[0] == '\0') { correct++; }

    tests++;
    s = cstr.formatTo(null, "%s%s", "", "");
    if (s && cstr.len(s) == 0) { correct++; }
    mem.free(s);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
