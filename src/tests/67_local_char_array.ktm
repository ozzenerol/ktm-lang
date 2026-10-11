import proc, str, test;

/* `u8 buf[] = "abc";` inside a function used to store the literal's
 * address into the array's first 8 bytes instead of copying the
 * characters, so printing it gave garbage. `u8 buf[8] = "abc";` was
 * rejected outright instead of zero-filling the tail like C. */

struct Point {
    i64 x, y;
};

u8 gBuf[] = "abc";
u8 gBig[8] = "ab";
i64 gAfter = 77;

i64 gWritesOk = 0;

/* Each call must start from a fresh copy, not the previous call's
 * edit (and not the shared literal). */
void bumpFirst() {
    u8 b[] = "abc";
    b[0]++;
    if (!str.cmp(b, "bbc")) { gWritesOk++; }
}

i64 gRecOk = 0;

void recurse(i64 depth) {
    u8 r[] = "xyz";
    if (r[0] == 'x' && r[3] == '\0') { gRecOk++; }
    r[0] = '0' + depth;
    if (depth > 0) { recurse(depth - 1); }
    if (r[0] == '0' + depth) { gRecOk++; }
}

i32 main() {
    i64 correct = 0, tests = 19, i, zeros;

    printf("Test - local u8 array initialised from a string: ");

    u8 buf[] = "abc";
    if (!str.cmp(buf, "abc")) { correct++; }
    if (sizeof(buf) == 4) { correct++; }

    u8 exact[4] = "abc";
    if (!str.cmp(exact, "abc") && sizeof(exact) == 4) { correct++; }

    u8 big[8] = "abc";
    zeros = 0;
    for (i = 3; i < 8; i++) {
        if (big[i] == 0) { zeros++; }
    }
    if (!str.cmp(big, "abc") && sizeof(big) == 8) { correct++; }
    if (zeros == 5) { correct++; }

    /* Escapes are decoded: 'a', '\t', 'b', NUL. */
    u8 esc[] = "a\tb";
    if (sizeof(esc) == 4 && esc[1] == '\t' && esc[2] == 'b') { correct++; }

    /* Writable, and only the local copy changes. */
    buf[0] = 'X';
    if (!str.cmp(buf, "Xbc")) { correct++; }
    u8 *lit = "abc";
    if (!str.cmp(lit, "abc")) { correct++; }

    bumpFirst();
    bumpFirst();
    if (gWritesOk == 2) { correct++; }

    recurse(3);
    if (gRecOk == 8) { correct++; }

    for (i = 0; i < 3; i++) {
        u8 l[] = "abc";
        if (l[0] == 'a') { correct++; }
        l[0] = 'Z';
    }

    /* Other initialisers keep working. */
    i64 a[] = {1, 2, 3};
    if (sizeof(a) == 24 && a[0] == 1 && a[2] == 3) { correct++; }
    i64 b[4] = {1, 2, 3, 4};
    if (b[3] == 4) { correct++; }
    Point p = {1, 2};
    if (p.x == 1 && p.y == 2) { correct++; }

    /* Globals: inline bytes, zero-filled tail, neighbour untouched. */
    if (!str.cmp(gBuf, "abc") && sizeof(gBuf) == 4) { correct++; }
    if (!str.cmp(gBig, "ab") && sizeof(gBig) == 8 && gBig[7] == 0 &&
            gAfter == 77) {
        correct++;
    }

    /* Too small an array is still a compile error. */
    proc.shell("printf 'void main() { u8 s[3] = \"abc\"; }\\n' > ./ktm-66-small.ktm");
    if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "-jit ./ktm-66-small.ktm 2>/dev/null") != 0) {
        correct++;
    }
    proc.shell("rm -f ./ktm-66-small.ktm");

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
