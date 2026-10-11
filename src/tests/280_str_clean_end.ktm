import str, test;

/* str.trimEnd(s, chars) drops every byte in `chars` from the end of s
 * (whitespace without chars), stops at the first one that isn't, and
 * returns a view of s: nothing is written (it was str.trimEndChars, which
 * wrote a NUL into a u8 *). */

bool clean(u8[] in, u8[] chars, u8[] want) {
    u8[] r = str.trimEnd(in, chars);
    bool ok = r.equals(want) && (!in.len || r.ptr == in.ptr);
    if (!ok) { printf("trimEnd(\"%s\", \"%s\") -> \"%s\"\n", in, chars, r); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - str.trimEnd with characters: ");

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
    u8[] none;
    if (clean("abc\n", "", "abc\n") && clean("abc\n", none, "abc\n")) {
        correct++;
    }

    tests++;   /* without characters: whitespace */
    if (str.trimEnd("line \t\r\n").equals("line") && str.trimEnd(none).len == 0) {
        correct++;
    }

    tests++;   /* a buffer read into: the line without its ending */
    u8 buffer[16] = {'t', 'e', 's', 't', '\r', '\n'};
    u8[] got = buffer[0:6];
    if (got.trimEnd("\r\n").equals("test") && buffer[4] == '\r') { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
