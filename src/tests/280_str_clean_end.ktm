import mem, str, test;

/* StrCleanEnd(str, chars) removes every character in `chars` from the
 * end of str, in place, stops at the first one that isn't and returns the
 * new length. */

bool clean(u8 *in, u8 *chars, u8 *want) {
    u8 buf[64];
    buf[0] = '#';                 /* guard byte before the string */
    str.copy(buf + 1, in);
    i64 n = str.trimEndChars(buf + 1, chars);
    bool ok = n == str.len(want) && !str.cmp(buf + 1, want) && buf[0] == '#';
    if (!ok) { printf("Clean(\"%s\", \"%s\") -> \"%s\" %d\n", in, chars, buf + 1, n); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - StrCleanEnd: ");

    tests++;
    if (clean("exit\r\n", "\r\n", "exit") && clean("exit\n", "\r\n", "exit") &&
            clean("exit\n\r\n\n", "\r\n", "exit") && clean("exit", "\r\n", "exit")) {
        correct++;
            }

    tests++;   /* only the end, and only the given characters */
    if (clean("\na\nb  \n", "\n", "\na\nb  ") && clean("x..!!", "!.", "x") &&
            clean("a b ", "\n", "a b ")) {
        correct++;
            }

    tests++;   /* everything removed, empty input */
    if (clean("\r\n\r\n", "\r\n", "") && clean("", "\r\n", "")) {
        correct++;
    }

    tests++;   /* nothing to remove with no characters */
    if (clean("abc\n", "", "abc\n") && clean("abc\n", null, "abc\n")) {
        correct++;
    }

    tests++;
    if (str.trimEndChars(null, "\n") == 0 && str.trimEndChars(null, null) == 0) {
        correct++;
    }

    tests++;   /* a read() buffer: NUL it, then drop the line ending */
    u8 buffer[16];
    mem.copy(buffer, "test\r\n", 6);
    buffer[6] = '\0';
    i64 len = str.trimEndChars(buffer, "\r\n");
    if (len == 4 && !str.cmp(buffer, "test")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
