#include "testhelper.ktm"

import mem, str;

/* StrPrintJoin wrote the NUL only after a character, so an empty result
 * (StrPrint(null, ""), or "%s" of "") was an unterminated MAlloc block:
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
    s = str.formatTo(null, "");
    if (s && str.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    s = str.formatTo(null, "%s", "");
    if (s && str.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    s = str.format("");
    if (s && str.len(s) == 0) { correct++; }
    mem.free(s);

    tests++;
    mem.set(buf, 'y', 16);
    str.formatTo(buf, "");
    if (buf[0] == '\0') { correct++; }

    tests++;
    s = str.formatTo(null, "%s%s", "", "");
    if (s && str.len(s) == 0) { correct++; }
    mem.free(s);

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
