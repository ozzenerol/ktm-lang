import test;

import fs, libc, mem, proc, str;

/* The block comment scan started on the opener's `*`, so `/` `*` `/`
 * opened and closed a comment in three characters and what followed
 * was compiled as code. As in C, the `*` of the opener can't also be
 * the start of the closing `*` `/`: the comment runs on to a later
 * `*` `/`. */

#define SLASH_FILE "__147_comment_slash.ktm"
#define SLASH_OUT  "__147_comment_slash.out"

void cleanupSlashTest(void) {
    fs.remove(SLASH_FILE);
    fs.remove(SLASH_OUT);
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

    cleanupSlashTest();

    if (!fs.writeAll(SLASH_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " SLASH_FILE " > " SLASH_OUT " 2>&1");

    out = fs.readAll(SLASH_OUT);
    if (!out) {
        cleanupSlashTest();
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
    cleanupSlashTest();
    return ok;
}

bool testSlashStarSlash(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
/*/ x
*/
", 0, null);
}

/* Code between it and the real end is commented out */
bool testSpansLines(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
/*/
i64 x = ;
void F( {
/ */
", 0, null);
}

/* `/` `*` `/` `*` `/` is one whole comment */
bool testFiveChars(void) {
    return compile(
"void main() { /*/*/ printf(\"hi\\n\"); }
", 0, null);
}

bool testShortComments(void) {
    return compile(
"void main() { i64 a /**/ = 1 /***/ + /****/ 2; printf(\"%d\\n\", a); }
", 0, null);
}

/* Left open at the end of the file it is still reported */
bool testUnterminatedAtEof(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
/*/", 1, "error: unterminated comment\n --> " SLASH_FILE ":2:1\n");
}

bool testUnterminatedLine(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
  /*/ never closed
", 1, "error: unterminated comment\n --> " SLASH_FILE ":2:3\n");
}

i32 main() {
    printf("Test - comment star slash: ");
    i64 correct = 0, tests = 0;

    tests++; if (testSlashStarSlash())    { correct++; }
    tests++; if (testSpansLines())        { correct++; }
    tests++; if (testFiveChars())         { correct++; }
    tests++; if (testShortComments())     { correct++; }
    tests++; if (testUnterminatedAtEof()) { correct++; }
    tests++; if (testUnterminatedLine())  { correct++; }

    /* The same in this file itself */
    i64 n = 1 /**/ + /***/ 1;
    tests++; if (n == 2) { correct++; }
    tests++; /*/ correct--; */ correct++;
    tests++; /*/*/ correct++;

    cleanupSlashTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
