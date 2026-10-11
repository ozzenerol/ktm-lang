import fs, libc, mem, proc, str, test;

/* A block comment left open at the end of a file was silently taken
 * as running to the end of the file, so `void Main() { ... }` followed
 * by `/` `*` never closed compiled and ran. It must be reported at the
 * opener as "unterminated comment" and ktm must exit 1. */

#define COMMENT_FILE "__135_unclosed_comment.ktm"
#define COMMENT_INC  "__135_unclosed_comment.ktmh"
#define COMMENT_OUT  "__135_unclosed_comment.out"

void cleanupCommentTest(void) {
    fs.remove(COMMENT_FILE);
    fs.remove(COMMENT_INC);
    fs.remove(COMMENT_OUT);
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

/* Compile `source` (and `inc` as COMMENT_INC when not null). With
 * `count` 0 it must compile cleanly; otherwise ktm must exit 1 with
 * exactly `count` errors, among them "unterminated comment" at `loc`
 * (" --> file:line:col"). */
bool compile(u8 *source, u8 *inc, i64 count, u8 *loc) {
    u8 *out, *want;
    i64 res;
    bool ok = true;

    cleanupCommentTest();

    if (!fs.writeAll(COMMENT_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }
    if (inc && !fs.writeAll(COMMENT_INC, inc, str.len(inc), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        cleanupCommentTest();
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " COMMENT_FILE " > " COMMENT_OUT " 2>&1");

    out = fs.readAll(COMMENT_OUT);
    if (!out) {
        cleanupCommentTest();
        return false;
    }

    if (count == 0) {
        if (res != 0) { ok = false; }
    } else {
        if (res != 1) { ok = false; }
        want = str.format("error: unterminated comment\n --> %s\n", loc);
        if (!libc.strstr(out, want)) { ok = false; }
        mem.free(want);
    }
    if (countErrors(out) != count) { ok = false; }
    if (!ok) { printf("exit status %d\n%s\n", res, out); }

    mem.free(out);
    cleanupCommentTest();
    return ok;
}

bool testUnclosedAtEof(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
/* never closed
", null, 1, COMMENT_FILE ":2:1");
}

/* Opened mid-line, no newline at all after it */
bool testUnclosedMidLine(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
i64 x;   /* never closed", null, 1, COMMENT_FILE ":2:10");
}

/* `/ * / * * /` is one comment: comments don't nest */
bool testNestedLooking(void) {
    return compile(
"void main() { printf(\"hi\\n\"); } /* /* */
", null, 0, null);
}

bool testOpenerInString(void) {
    return compile(
"void main() { printf(\"/* not a comment\\n\"); }
", null, 0, null);
}

/* A line comment ending the file without a newline is fine */
bool testLineCommentAtEof(void) {
    return compile(
"void main() { printf(\"hi\\n\"); }
// no newline after this", null, 0, null);
}

/* Reported in the included file, then the including file carries on */
bool testUnclosedInInclude(void) {
    return compile(
"#include \"" COMMENT_INC "\"
void main() { printf(\"%d\\n\", x); }
",
"i64 x = 1;
  /* never closed
", 1, COMMENT_INC ":2:3");
}

/* Like C, still reported inside a skipped #if 0 block, where it also
 * swallows the #endif */
bool testUnclosedInIfZero(void) {
    return compile(
"#if 0
/* never closed
#endif
void Main() { \"hi\\n\"; }
", null, 2, COMMENT_FILE ":2:1");
}

i32 main() {
    printf("Test - unclosed block comment: ");
    i64 correct = 0, tests = 0;

    tests++; if (testUnclosedAtEof())     { correct++; }
    tests++; if (testUnclosedMidLine())   { correct++; }
    tests++; if (testNestedLooking())     { correct++; }
    tests++; if (testOpenerInString())    { correct++; }
    tests++; if (testLineCommentAtEof())  { correct++; }
    tests++; if (testUnclosedInInclude()) { correct++; }
    tests++; if (testUnclosedInIfZero())  { correct++; }

    /* The same edge cases in this file itself */
    tests++; /* /* */ correct++;
    tests++; if (str.len("/*") == 2) { correct++; }

    cleanupCommentTest();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
