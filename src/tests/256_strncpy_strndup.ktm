#include "testhelper.ktm"

import fzf, mem, str;

/* StrNCpy(buf, len) copied exactly len bytes, past the end of a shorter
 * string, returned an uninitialised byte for len 0 (it returned before
 * writing the NUL) and crashed on a negative len. It is now strndup: at
 * most len bytes, stopping at the NUL, always terminated, len < 0 is 0.
 * fzf's internal str_replace called it as if it were C's strncpy (leaking
 * the copy and leaving the result unwritten); that use is fixed too. It
 * is checked through FzF, whose pattern parsing uses it to turn an escaped
 * space (`\ `) into one that is part of the term. */

/* FzF(text, pattern) found a match covering `size` characters */
bool matches(u8 *text, u8 *pattern, i64 size) {
    fzf.Position *pos = fzf.match(text, pattern);
    bool ok = pos && pos.size == size;
    if (pos) { fzf.release(pos); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - StrNCpy strndup semantics: ");

    u8 *a = str.dupLen("hello", 3);
    u8 *b = str.dupLen("hi", 10);
    u8 *c = str.dupLen("hello", 0);
    u8 *d = str.dupLen("hello", -1);
    u8 *e = str.dupLen("hello", 5);
    u8 *f = str.dupLen(null, 4);
    tests++;
    if (!str.cmp(a, "hel") && !str.cmp(b, "hi") && c[0] == '\0' && d[0] == '\0' &&
            !str.cmp(e, "hello") && f[0] == '\0') { correct++; }
    /* only what is needed is allocated, so a short source can't be read past */
    tests++; if (mem.size(b) == 3 && mem.size(a) == 4) { correct++; }
    mem.free(a); mem.free(b); mem.free(c); mem.free(d); mem.free(e); mem.free(f);

    tests++;
    if (matches("xa bx", "a\\ b", 3) && !fzf.match("xabx", "a\\ b") &&
            matches("a b c d", "b\\ c\\ d", 5)) {
        correct++;
            } else { printf("FzF with escaped spaces\n"); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
