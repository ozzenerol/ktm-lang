import cstr, fs, libc, mem, proc, test;

/* v0.22.0: the condition of an if, while, do-while, for and switch can be
 * written without parentheses, as in Go: `if x > 0 {`. Both forms mean
 * the same. A condition can start with a `(` of its own
 * (`if (a + b) * 2 > c {`). Without parentheses the condition runs up to
 * the `{` of the body; mistakes are reported once, at the right token. */

#define NP_SRC "./ktm-833-noparen.ktm"
#define NP_OUT "./ktm-833-noparen.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " NP_SRC " " NP_OUT);
    fs.writeAll(NP_SRC, cstr.view(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " NP_SRC " -o /dev/null > " NP_OUT " 2>&1");
    u8 *out = fs.readAll(NP_OUT).take();
    proc.shell("rm -f " NP_SRC " " NP_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while s && (s = libc.strstr(s, what)) {
        n++;
        s++;
    }
    return n;
}

/* `body` inside a function: ktm prints `errors` errors, each with want */
bool prints(u8 *body, i64 errors, u8 *want = null) {
    u8 *src = cstr.format("void f(i64 x) {\n%s\n}\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && count(out, "error:") == errors &&
              (!want || count(out, want) == errors);
    if !ok { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

/* the same function in both forms */
i64 classify(i64 n) {
    if n < 0 {
        return -1;
    } else if n == 0 {
        return 0;
    } else {
        return 1;
    }
}

i64 classifyParens(i64 n) {
    if (n < 0) {
        return -1;
    } else if (n == 0) {
        return 0;
    } else {
        return 1;
    }
}

i32 main() {
    printf("Test - conditions without parentheses: ");
    i64 a = 1, b = 2, n = 3;

    check(classify(-5) == -1 && classify(0) == 0 && classify(9) == 1 &&
          classify(9) == classifyParens(9) && classify(-5) == classifyParens(-5),
          "if / else if / else, with and without parentheses");

    i64 hit = 0;
    if (a + b) * 2 > 5 { hit++; }
    if (a) < (b) { hit++; }
    if (a + b) == n && (n > 0) { hit++; }
    if (n > 2) { hit++; }
    check(hit == 4, "a condition starting with a `(` of its own");

    hit = 0;
    if i64 m = n * 2; m == 6 { hit = m; }
    check(hit == 6, "if with an init statement: if i64 m = n * 2; m == 6 {");

    i64 i = 0;
    while i < 5 { i++; }
    i64 j = 0;
    while (j < 5) { j++; }
    check(i == 5 && j == 5, "while with and without parentheses");

    do { i--; } while i > 0;
    do { j--; } while (j > 0);
    check(i == 0 && j == 0, "do-while with and without parentheses");

    i64 sum = 0;
    for i64 k = 0; k < 4; k++ { sum += k; }
    for (i64 k = 0; k < 4; k++) { sum += k; }
    check(sum == 12, "for with and without parentheses");

    i64 k = 0;
    for k < 7 { k++; }
    check(k == 7, "for cond { }: a while loop");
    for { k++; if k == 10 { break; } }
    check(k == 10, "for { }: for ever, until a break");
    for ;; { k++; if k == 12 { break; } }
    i = 9;
    for k = 0; k < 3; k++, i-- { }
    check(k == 3 && i == 6, "for ;; { } and several steps");

    i64 arr[3] = {7, 8, 9};
    sum = 0;
    for auto x : arr { sum += x; }
    for (auto x : arr) { sum += x; }
    check(sum == 48, "range for with and without parentheses");

    i64 seen = 0;
    switch n { case 3: seen++; break; default: seen = 100; }
    switch (n) { case 3: seen++; break; }
    switch (n) + 1 { case 4: seen++; break; }
    check(seen == 3, "switch with and without parentheses");

    /* nested, mixed */
    sum = 0;
    for i64 p = 0; p < 3; p++ {
        for (i64 q = 0; q < 3; q++) {
            if p == q { sum++; }
        }
    }
    check(sum == 3, "nested loops in both forms");

    /* mistakes: one error each, at the right token */
    check(prints("  if x x++;", 1, "Expected ';' or `{` after the `if` condition, got `x`"), "if without braces or parentheses");
    check(prints("  if (x) x++;", 1, "braces required: the body of `if` goes in `{ ... }`"), "if (x) without braces: still braces required");
    check(prints("  while x y { }", 1, "expected `{` after the `while` condition, got `y`"), "while: something after the condition");
    check(prints("  if { }", 1, "Expected the `if` condition, got `{`"), "if { }: no condition");
    check(prints("  while { x++; } x++;", 1, "Expected the `while` condition, got `{`"), "while { }: no condition");
    check(prints("  switch { }", 1, "Expected the `switch` value, got `{`"), "switch { }: no value");
    check(prints("  if x) { }", 1, "Expected ';' or `{` after the `if` condition, got `)`"), "a stray `)`");
    check(prints("  if (x { }", 1, "Expected ';' or ')' in `if (...)`, got `{`"), "a missing `)`");
    check(prints("  if x + * { x++; } else { x--; }\n  x++;", 1, "Expected expression after unary"), "an error in a bare condition is reported once");
    check(prints("  while x == ) { x++; }\n  nope++;", 2), "and the parse goes on after it");
    check(prints("  for i64 i = 0; i < 3; i++ x++;", 1, "expected `{` after the `for` condition, got `x`"), "for: no braces");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
