import fs, libc, mem, proc, str, test;

/* `#error 1.5 0x10 'a'` printed only "1.5": the message was the first
 * token after `#error`. As in C it must be the rest of the line as
 * written (up to a comment, trimmed), reported at the `#error`, and
 * ktm must exit 1. A lone string literal is shown without its quotes,
 * as before. */

#define ERRDIR_FILE "__145_error_directive.ktm"
#define ERRDIR_OUT  "__145_error_directive.out"

void cleanupErrorDirTest(void) {
    fs.remove(ERRDIR_FILE);
    fs.remove(ERRDIR_OUT);
    fs.remove("./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p++;
    }
    return n;
}

/* Compile `source`. With `count` 0 it must compile cleanly; otherwise
 * ktm must exit 1 with exactly `count` errors, the output containing
 * `want` (when not null). */
bool compile(u8 *source, i64 count, u8 *want) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupErrorDirTest();

    if (!fs.writeAll(ERRDIR_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " ERRDIR_FILE " > " ERRDIR_OUT " 2>&1");

    out = fs.readAll(ERRDIR_OUT);
    if (!out) {
        cleanupErrorDirTest();
        return false;
    }

    if (count == 0) {
        if (res != 0) { ok = false; }
    } else {
        if (res != 1) { ok = false; }
    }
    if (want && !libc.strstr(out, want)) { ok = false; }
    if (countErrors(out) != count) { ok = false; }
    if (!ok) { printf("exit status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupErrorDirTest();
    return ok;
}

bool testWholeLine(void) {
    return compile(
"#error 1.5 0x10 'a'
void main() { printf(\"hi\\n\"); }
", 1, "error: 1.5 0x10 'a'\n --> " ERRDIR_FILE ":1:1\n");
}

bool testLoneString(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
#error \"stop here\"
", 1, "error: stop here\n --> " ERRDIR_FILE ":2:1\n");
}

bool testStringAndMore(void) {
    return compile(
"#error \"a\" and \"b\"
void main() { printf(\"hi\\n\"); }
", 1, "error: \"a\" and \"b\"\n");
}

/* Indented, extra spaces kept inside, trimmed at both ends, and a
 * trailing comment left out */
bool testTrimAndComment(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
  #error   too  many   spaces   // why
", 1, "error: too  many   spaces\n --> " ERRDIR_FILE ":2:3\n");
}

/* A block comment after it may run on to later lines */
bool testBlockComment(void) {
    return compile(
"#error stop /* a comment
that ends here */
void main() { printf(\"hi\\n\"); }
", 1, "error: stop\n --> " ERRDIR_FILE ":1:1\n");
}

/* Comment markers in quotes are part of the message */
bool testQuotedCommentMarkers(void) {
    return compile(
"#error \"// not\" '/*' a comment
void main() { printf(\"hi\\n\"); }
", 1, "error: \"// not\" '/*' a comment\n");
}

/* Nothing after it: the message is "#error", the next line is kept */
bool testEmpty(void) {
    return compile(
"#error
void main() { printf(\"hi\\n\"); }
", 1, "error: #error\n --> " ERRDIR_FILE ":1:1\n");
}

/* Lexing carries on after each one */
bool testTwo(void) {
    return compile(
"#error first one
void main() { printf(\"hi\\n\"); }
#error second one
", 2, "error: second one\n --> " ERRDIR_FILE ":3:1\n");
}

/* At the end of the file, with no newline */
bool testAtEof(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
#error the end", 1, "error: the end\n --> " ERRDIR_FILE ":2:1\n");
}

/* Not reported in a group that is skipped */
bool testSkipped(void) {
    return compile(
"#if 0
#error 1.5 0x10 'a'
#endif
void main() { printf(\"hi\\n\"); }
", 0, null);
}

i32 main() {
    printf("Test - #error directive message: ");
    i64 correct = 0, tests = 0;

    tests++; if (testWholeLine())            { correct++; }
    tests++; if (testLoneString())           { correct++; }
    tests++; if (testStringAndMore())        { correct++; }
    tests++; if (testTrimAndComment())       { correct++; }
    tests++; if (testBlockComment())         { correct++; }
    tests++; if (testQuotedCommentMarkers()) { correct++; }
    tests++; if (testEmpty())                { correct++; }
    tests++; if (testTwo())                  { correct++; }
    tests++; if (testAtEof())                { correct++; }
    tests++; if (testSkipped())              { correct++; }

    cleanupErrorDirTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
