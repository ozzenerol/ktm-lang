import fs, libc, mem, proc, str, test;

/* Y8: a case ends at the next label, with no `break` (one still leaves
 * early); stacked empty labels share the code after them. `fallthrough;`
 * as the last statement of a case, or of the case's `{ ... }`, runs on
 * into the next case. It is an error outside a switch, before more of
 * its case, under an `if` or loop, in the last case (no next one), and
 * into or out of a `default` that isn't the last label (its code runs
 * after the cases). `fallthrough` stays usable as a name. */

#define AT_SRC "./ktm-436-fallthrough.ktm"
#define AT_OUT "./ktm-436-fallthrough.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    fs.writeAll(AT_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-S " AT_SRC " -o /dev/null > " AT_OUT " 2>&1");
    u8 *out = fs.readAll(AT_OUT);
    proc.shell("rm -f " AT_SRC " " AT_OUT);
    return out;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm prints `errors` errors, no warnings, and want (if any) */
bool prints(u8 *body, i64 errors, u8 *want=null) {
    u8 *src = str.format("%s\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && count(out, "error:") == errors &&
                        count(out, "warning:") == 0 && (!want || libc.strstr(out, want));
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

#define OUTSIDE "`fallthrough` can only be used in a case of a switch"
#define LAST "`fallthrough` in the last case of a switch: there is no next case"

i64 F(i64 x) {
    i64 r = 0;
    switch (x) {
        case 1: r += 1; fallthrough;
        case 2: r += 10;
        case 3: { r += 100; fallthrough; }
        case 4: r += 1000; fallthrough;
        default: r += 5;
    }
    return r;
}

/* a switch inside a case: each has its own last case */
i64 H(i64 x) {
    switch (x) {
        case 1:
            switch (x) {
                case 1: fallthrough;
                case 2: x += 2;
            }
            fallthrough;
        case 2: x += 10;
    }
    return x;
}

i64 G(i64 fallthrough_) { return fallthrough_ + 1; }

/* no fall-through without it; statements after a block stay in the case */
i64 N(i64 x) {
    i64 r = 0;
    switch (x) {
        case 1: r += 1;
        case 2: { r += 10; } r += 20;
        case 3:
        case 4: r += 100; if (x == 4) { break; } r += 1000;
        default: r += 5;
    }
    return r;
}

i32 main() {
    printf("Test - fallthrough: ");
    i64 correct = 0, tests = 0;

    tests++; if (F(1) == 11 && F(2) == 10 && F(3) == 1105 && F(4) == 1005 && F(9) == 5) { correct++; }
    tests++; if (H(1) == 13 && H(2) == 12) { correct++; }
    tests++; if (G(1) == 2) { correct++; }
    tests++; if (N(1) == 1 && N(2) == 30 && N(3) == 1100 && N(4) == 100 && N(7) == 5) { correct++; }

    /* a keyword: no variable can have its name */
    tests++;
    if (prints("i64 fallthrough = 3;\nvoid f(i64 x) { switch (x) { case 1: fallthrough; case 2: x++; } }",
               1, "`fallthrough` is a keyword: it can't be a name")) {
        correct++;
    }

    tests++; if (prints("void f() { fallthrough; }", 1, OUTSIDE)) { correct++; }
    tests++; if (prints("fallthrough;", 1, OUTSIDE)) { correct++; }
    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: x++; fallthrough; } }", 1, LAST)) {
        correct++;
    }
    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: break; default: fallthrough; } }", 1, LAST)) {
        correct++;
    }
    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: fallthrough; case 2: switch (x) { case 3: fallthrough; } } }",
                         1, LAST)) {
        correct++;
                         }

    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: fallthrough; x++; case 2: x++; } }",
               1, "`fallthrough` must be the last statement of its case")) {
        correct++;
    }
    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: if (x) { fallthrough; } case 2: x++; } }",
               1, "not inside a block or another statement")) {
        correct++;
    }
    tests++;
    if (prints("void f(i64 x) { switch (x) { case 1: x++; fallthrough; default: x--; case 2: x++; } }",
               1, "a `default` that isn't the switch's last label")) {
        correct++;
    }
    tests++;
    if (prints("void f(i64 x) { switch (x) { default: x--; fallthrough; case 2: x++; } }",
               1, "a `default` that isn't the switch's last label")) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
