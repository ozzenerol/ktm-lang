import test;

import mem, str;

/* StrPrint's buffer grew to ten times its size plus the argument
 * whenever a %s didn't fit, and StrLen was a byte-at-a-time loop that
 * crashed on null. The buffer now doubles, and StrLen is libc's strlen,
 * with null giving 0. */

i32 main() {
    printf("Test - string output growth and StrLen: ");
    i64 correct = 0, tests = 0, i, j;

    /* null is 0 */
    tests++;
    if (str.len(null) == 0 && str.len("") == 0 && str.len("abc") == 3) { correct++; }

    /* Every length, at every alignment */
    tests++;
    u8 *buf = mem.alloc(300);
    bool ok = true;
    for (i = 0; i < 16; i++) {
        for (j = 0; j < 260; j++) {
            mem.set(buf + i, 'a', j);
            buf[i + j] = '\0';
            if (str.len(buf + i) != j) { ok = false; }
        }
    }
    if (ok) { correct++; }
    mem.free(buf);

    /* Long %s arguments: the result is at most about twice its length */
    tests++;
    i64 n = 1000000;
    u8 *big = mem.alloc(n + 1);
    mem.set(big, 'a', n);
    big[n] = '\0';
    u8 *s = str.formatTo(null, "%s%s%s%s", big, big, big, big);
    if (str.len(s) == 4 * n && mem.size(s) <= 2 * str.len(s) + 4096) { correct++; }
    else { printf("StrLen %d MSize %d\n", str.len(s), mem.size(s)); }
    mem.free(s);

    /* Many short pieces still build the right string */
    tests++;
    s = str.formatTo(null, "%s|%s", big + n - 5, "end");
    u8 *c = null;
    for (i = 0; i < 1000; i++) { c = str.appendFormat(c, "%d,", i % 10); }
    if (!str.cmp(s, "aaaaa|end") && str.len(c) == 2000 && c[1998] == '9') { correct++; }
    mem.free(s);
    mem.free(c);
    mem.free(big);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
