#include "testhelper.ktm"

import str;

/* StrTrimEnd on an empty or all-blank string walked back past the start
 * and wrote the NUL before it; StrTrimStart ignored its str_len. Both now
 * stay inside [0, len) and only look at the first str_len bytes when it
 * is given; null returns null with *_new_len = 0. */

bool trimEnd(u8 *in, i64 str_len, u8 *want, i64 wantLen) {
    u8 buf[64];
    buf[0] = '#';                 /* guard byte before the string */
    str.copy(buf + 1, in);
    i64 n = -5;
    u8 *r = str.trimEnd(buf + 1, str_len, &n);
    bool ok = r == buf + 1 && n == wantLen && !str.cmpLen(buf + 1, want, wantLen) &&
                        buf[0] == '#';
    if (ok && wantLen != str.len(in)) { ok = buf[1 + wantLen] == '\0'; }
    if (!ok) { printf("TrimEnd(\"%s\", %d) -> \"%s\" %d\n", in, str_len, buf + 1, n); }
    return ok;
}

bool trimStart(u8 *in, i64 str_len, u8 *want, i64 wantLen) {
    u8 buf[64];
    str.copy(buf, in);
    i64 n = -5;
    u8 *r = str.trimStart(buf, str_len, &n);
    bool ok = r == buf && n == wantLen && !str.cmpLen(buf, want, wantLen);
    if (!ok) { printf("TrimStart(\"%s\", %d) -> \"%s\" %d\n", in, str_len, buf, n); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - string trim bounds: ");

    bool ok = trimEnd("", -1, "", 0) && trimEnd("   ", -1, "", 0) &&
                        trimEnd(" \t\n", -1, "", 0) && trimEnd("ab  ", -1, "ab", 2) &&
                        trimEnd("ab", -1, "ab", 2) && trimEnd("  ab", -1, "  ab", 4) &&
                        trimEnd("ab   cd", 4, "ab", 2) && trimEnd("  x", 2, "", 0);
    tests++; if (ok) { correct++; }

    /* the audit's repro: an all-blank string inside a buffer */
    u8 big[16];
    str.copy(big, "x    ");
    i64 len = 99;
    str.trimEnd(big + 3, -1, &len);
    tests++; if (len == 0 && !str.cmp(big, "x  ")) { correct++; }

    ok = trimStart("", -1, "", 0) && trimStart("   ", -1, "", 0) &&
             trimStart("  ab", -1, "ab", 2) && trimStart("ab", -1, "ab", 2) &&
             trimStart("ab  ", -1, "ab  ", 4);
    tests++; if (ok) { correct++; }

    /* str_len honoured: only the first str_len bytes count */
    u8 b2[32];
    str.copy(b2, "   abc   def");
    len = 0;
    str.trimStart(b2, 6, &len);   /* "   abc" -> "abc" */
    tests++; if (len == 3 && !str.cmp(b2, "abc")) { correct++; }
    else { printf("str_len ignored: \"%s\" %d\n", b2, len); }
    str.copy(b2, "      xyz");
    str.trimStart(b2, 4, &len);   /* all blank within the first 4 */
    tests++; if (len == 0 && b2[0] == '\0') { correct++; }

    len = 7;
    tests++;
    if (str.trimStart(null, -1, &len) == null && len == 0 &&
            str.trimEnd(null) == null) { correct++; }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
