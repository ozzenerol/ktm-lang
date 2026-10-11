#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* The lexer silently dropped characters it has no use for (`?`, a
 * backtick, `$` and `@` outside an asm block, non-ASCII bytes), so
 * `i64 x = 2.5 ? 3;` was reported as "Unexpected integer `3`". Each
 * one must be reported where it is as "unexpected character" (a UTF-8
 * character once, not once per byte) and ktm must exit 1, while the
 * same characters in strings, comments, asm blocks and skipped #if
 * groups stay fine. */

#define UNKNOWN_FILE "__143_unknown_char.ktm"
#define UNKNOWN_OUT  "__143_unknown_char.out"

void cleanupUnknownTest(void) {
    fs.remove(UNKNOWN_FILE);
    fs.remove(UNKNOWN_OUT);
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

    cleanupUnknownTest();

    if (!fs.writeAll(UNKNOWN_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " UNKNOWN_FILE " > " UNKNOWN_OUT " 2>&1");

    out = fs.readAll(UNKNOWN_OUT);
    if (!out) {
        cleanupUnknownTest();
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
    cleanupUnknownTest();
    return ok;
}

/* The report: located at the `$`, then the parser's own complaint
 * about the `3` it now sees after `2.5` */
bool testDollarSign(void) {
    return compile(
"void main() { i64 x = 2.5 $ 3; }
", 2, "error: unexpected character '$'\n --> " UNKNOWN_FILE ":1:25\n");
}

/* Stray characters between statements: one error each, nothing else */
bool testBacktick(void) {
    return compile(
"void main() {
  printf(\"hi\\n\"); `
}
", 1, "error: unexpected character '`'\n --> " UNKNOWN_FILE ":2:11\n");
}

bool testDollarAt(void) {
    return compile(
"void Main() {
  $
    @
}
", 2, "error: unexpected character '@'\n --> " UNKNOWN_FILE ":3:5\n");
}

bool testControlChar(void) {
    return compile(
"void main() { printf(\"hi\\n\"); \x01 }
", 1, "error: unexpected character '\\x01'\n --> " UNKNOWN_FILE ":1:21\n");
}

/* A UTF-8 character outside a string is reported once, as itself */
bool testUtf8(void) {
    return compile(
"void main() { printf(\"hi\\n\"); \xe2\x82\xac }
", 1, "error: unexpected character '\xe2\x82\xac'\n --> " UNKNOWN_FILE ":1:21\n");
}

bool testTwoUtf8(void) {
    return compile(
"void main() { printf(\"hi\\n\"); \xc3\xa9\xf0\x9f\x98\x80 }
", 2, "error: unexpected character '\xf0\x9f\x98\x80'\n");
}

/* Fine in strings, character constants and comments */
bool testQuoted(void) {
    return compile(
"// ? ` $ @ \xe2\x82\xac
/* ? ` $ @ \xc3\xa9 */
void main() {
  printf(\"? ` $ @ \xe2\x82\xac\\n\");
  i64 c = '?';
}
", 0, null);
}

/* An #if group that is skipped may hold them, like an x86_64 asm block
 * with `@@1` labels skipped on arm64 */
bool testSkippedGroup(void) {
    return compile(
"#if 0
  JMP @@1 ? ` $ \xe2\x82\xac
#endif
void main() { printf(\"hi\\n\"); }
", 0, null);
}

/* `$` and `@` are fine in an asm block, and `$` inside a name */
bool testAsm(void) {
    return compile(
"#ifdef IS_X86_64
void asmLabels() {
asm {
    JMP @@1
@@1:
    RET
}
}
#endif
void main() { i64 a$b = 1; printf(\"%d\\n\", a$b); }
", 0, null);
}

/* The echoed source line keeps the characters and the re-lexer that
 * echoes it adds no errors of its own */
bool testEcho(void) {
    return compile(
"void main() { i64 x = 1 ? \xe2\x82\xac; }
", 2, "   1 |    void main() { i64 x = 1 ? \xe2\x82\xac; }\n");
}

i32 main() {
    printf("Test - unknown character: ");
    i64 correct = 0, tests = 0;

    tests++; if (testDollarSign()) { correct++; }
    tests++; if (testBacktick())     { correct++; }
    tests++; if (testDollarAt())     { correct++; }
    tests++; if (testControlChar())  { correct++; }
    tests++; if (testUtf8())         { correct++; }
    tests++; if (testTwoUtf8())      { correct++; }
    tests++; if (testQuoted())       { correct++; }
    tests++; if (testSkippedGroup()) { correct++; }
    tests++; if (testAsm())          { correct++; }
    tests++; if (testEcho())         { correct++; }

    cleanupUnknownTest();
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
