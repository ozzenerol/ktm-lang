#include "testhelper.ktm"

import fs, mem, proc, str;

/* An empty main source file stepped past its terminator once the
 * stdlib header pushed in front of it was done, so ktm lexed whatever
 * memory followed the 1-byte buffer: random errors over a garbage
 * source line ("Type `F` has not been declared"). An empty file is
 * valid input and must be treated like one holding only a comment:
 * no output, no errors, the same exit status every time. */

#define EMPTY_FILE "__148_empty.ktm"
#define EMPTY_INC  "__148_empty.ktmh"
#define EMPTY_OUT  "__148_empty.out"

void cleanupEmptyTest(void) {
    fs.remove(EMPTY_FILE);
    fs.remove(EMPTY_INC);
    fs.remove(EMPTY_OUT);
    fs.remove("./a.out");
}

/* Write `printf_fmt` (a printf format, so "" makes an empty file) to
 * `file` through the shell */
bool writeFile(u8 *file, u8 *printfFmt) {
    u8 *cmd = str.format("printf '%s' > %s", printfFmt, file);
    i64 res = proc.shell(cmd);
    mem.free(cmd);
    return res == 0;
}

/* Run ktm with `flags` on EMPTY_FILE; returns the exit code and
 * leaves the output in `*_out` (null if it could not be read) */
i64 runKtm(u8 *flags, u8 **_out) {
    u8 *cmd = str.format(
            "T=; command -v timeout >/dev/null 2>&1 && T='timeout 10'; "
            "$T ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
            "%s " EMPTY_FILE " > " EMPTY_OUT " 2>&1", flags);
    i64 res = proc.shell(cmd);
    mem.free(cmd);
    *_out = fs.readAll(EMPTY_OUT);
    return res;
}

/* What `ktm -jit` prints for a file holding only a comment: it has no
 * code and no Main */
u8 *gCommentOut = null;

/* The exit status of `ktm -jit` on a file holding only a comment: what
 * a program with no code and no Main does */
i64 commentOnlyStatus(void) {
    u8 *out;
    i64 res;
    cleanupEmptyTest();
    if (!writeFile(EMPTY_FILE, "// nothing here\\n")) { return -1; }
    res = runKtm("-jit", &out);
    gCommentOut = out;
    cleanupEmptyTest();
    return res;
}

/* `content` as the main file: `-tokens` prints nothing, and `-jit`
 * (several times, the bug read whatever happened to be in memory)
 * prints and exits like the comment-only file */
bool testNoCode(u8 *name, u8 *content, i64 wantStatus) {
    u8 *out;
    i64 res, i;
    bool ok = true;

    cleanupEmptyTest();
    if (!writeFile(EMPTY_FILE, content)) { return false; }

    for (i = 0; i < 3; i++) {
        res = runKtm("-jit", &out);
        if (!out || !gCommentOut || res != wantStatus ||
                str.cmp(out, gCommentOut)) {
            printf("%s, -jit run %d: status %d (want %d)\n%s\n", name, i, res,
                wantStatus, out);
            ok = false;
        }
        if (out) { mem.free(out); }
    }

    res = runKtm("-tokens", &out);
    if (!out || res != 0 || str.len(out) != 0) {
        printf("%s, -tokens: status %d\n%s\n", name, res, out);
        ok = false;
    }
    if (out) { mem.free(out); }

    cleanupEmptyTest();
    return ok;
}

/* An empty file included from a real program */
bool testEmptyInclude(void) {
    u8 *out;
    i64 res;
    bool ok = true;

    cleanupEmptyTest();
    if (!writeFile(EMPTY_INC, "")) { return false; }
    if (!writeFile(EMPTY_FILE,
                "#include \"" EMPTY_INC "\"\\nvoid main() { printf(\"ok\\\\n\"); }\\n")) {
        cleanupEmptyTest();
        return false;
    }
    res = runKtm("-jit", &out);
    if (!out || res != 0 || str.cmp(out, "ok\n")) {
        printf("empty include: status %d\n%s\n", res, out);
        ok = false;
    }
    if (out) { mem.free(out); }
    cleanupEmptyTest();
    return ok;
}

i32 main() {
    printf("Test - empty source file: ");
    i64 correct = 0, tests = 0;
    i64 status = commentOnlyStatus();

    tests++; if (testNoCode("empty", "", status))                  { correct++; }
    tests++; if (testNoCode("newline", "\\n", status))             { correct++; }
    tests++; if (testNoCode("whitespace", "  \\t \\n \\n  ", status)) { correct++; }
    tests++; if (testNoCode("comment", "/* nothing */", status))   { correct++; }
    tests++; if (testEmptyInclude())                                 { correct++; }

    cleanupEmptyTest();
    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
