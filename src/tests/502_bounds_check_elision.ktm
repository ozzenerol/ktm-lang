import cstr, fs, mem, proc, test;

/* Bounds-check elision (v0.3.1): in `for (i64 i = 0; i < s.len; i++)` an
 * `s[i]` in the body is in range, so it is not checked, when i and s are
 * locals that nothing else changes: not assigned in the body, no label in
 * it, and no address taken in the function. Every other index keeps its
 * check, and a wrong one still throws. */

#define SRC_FILE "__502_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* How many bounds checks `body` (a function taking i64[] s) compiles to */
i64 checksIn(u8 *body) {
    u8 *src = cstr.format("i64 f(i64[] s, i64[] o)\n{\n  i64 t = 0;\n%s\n  return t;\n}\n"
                          "void main() {}\n", body);
    fs.writeAll(SRC_FILE, cstr.view(src));
    mem.free(src);
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o- " SRC_FILE " 2>/dev/null | grep -c ktmBoundsIndex").take();
    fs.remove(SRC_FILE);
    i64 n = out ? cstr.toInt(out) : -1;
    mem.free(out);
    return n;
}

i64 sum(i64[] s) {
    i64 t = 0;
    for (i64 i = 0; i < s.len; i++) {
        t += s[i];
        s[i] = 0;               /* a write is unchecked too */
    }
    return t;
}

i64 nested(i64[] s) {
    i64 t = 0;
    for (i64 i = 0; i < s.len; ++i) {
        for (i64 j = 0; j < s.len; j += 1) {
            if (j == 2) { continue; }
            t += s[i] * s[j];
        }
        if (t > 1000) { break; }
    }
    return t;
}

i64 overrun(i64[] s) {
    i64 t = 0;
    for (i64 i = 0; i <= s.len; i++) { t += s[i]; }   /* checked: throws */
    return t;
}

i32 main() {
    printf("Test - bounds-check elision: ");
    i64 a[5] = {1, 2, 3, 4, 5};
    check(sum(a) == 15 && a[0] == 0 && a[4] == 0, "an elided loop reads and writes");
    i64 b[4] = {1, 2, 3, 4};
    check(nested(b) == 10 * (1 + 2 + 4), "nested loops, continue and break");
    bool threw = false;
    try {
        i64 c[3] = {1, 2, 3};
        overrun(c);
    } catch (Error e) {
        threw = e.code == ERR_BOUNDS;
    }
    check(threw, "i <= s.len still throws");

    check(checksIn("  for (i64 i = 0; i < s.len; i++) { t += s[i]; }") == 0, "i++ elided");
    check(checksIn("  for (i64 i = 0; i < s.len; i += 1) { s[i] = t; }") == 0, "i += 1 elided");
    check(checksIn("  i64 i;\n  for (i = 0; i < s.len; ++i) { t += s[i]; }") == 0,
          "i declared before the loop");
    check(checksIn("  for (i64 i = 0; i < s.len; i++) { t += s[i]; i++; }") > 0, "i changed: checked");
    check(checksIn("  for (i64 i = 0; i < s.len; i++) { t += s[i]; s = o; }") > 0, "s changed: checked");
    check(checksIn("  i64[] *p = &s;\n  for (i64 i = 0; i < s.len; i++) { t += s[i]; }") > 0,
          "s's address taken: checked");
    check(checksIn("  for (i64 i = 0; i < s.len; i++) { t += s[i]; }\n  i64[] *p = &s;") > 0,
          "s's address taken after the loop: checked");
    check(checksIn("  for (i64 i = 0; i < s.len; i++) { t += o[i]; }") > 0, "another slice: checked");
    check(checksIn("  for (i64 i = 1; i < s.len; i++) { t += s[i - 1]; }") > 0, "another index: checked");
    check(checksIn("  for (i64 i = 0; i < s.len; i++) { again: t += s[i]; }") > 0, "a label: checked");
    check(checksIn("  for (i64 i = -1; i < s.len; i++) { t += s[i]; }") > 0, "a negative start: checked");
    check(checksIn("  for (i32 i = 0; i < s.len; i++) { t += s[i]; }") > 0, "a 32-bit index: checked");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
