import cstr, fs, libc, mem, proc, test;

/* Y13: the body of an if, else, for, while or do is a `{ ... }` block;
 * `else if` stays. A lone statement is "braces required", at the
 * statement, once per body. */

#define BR_SRC "./ktm-444-braces.ktm"
#define BR_OUT "./ktm-444-braces.out"

u8 *diagnose(u8 *source) {
    proc.shell("rm -f " BR_SRC " " BR_OUT);
    fs.writeAll(BR_SRC, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
               "-S " BR_SRC " -o /dev/null > " BR_OUT " 2>&1");
    u8 *out = fs.readAll(BR_OUT);
    proc.shell("rm -f " BR_SRC " " BR_OUT);
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

/* `body` inside a function: ktm prints `errors` errors, each with want */
bool prints(u8 *body, i64 errors, u8 *want = null) {
    u8 *src = cstr.format("void f(i64 x) {\n%s\n}\ni32 main() { return 0; }\n", body);
    u8 *out = diagnose(src);
    bool ok = out && count(out, "error:") == errors &&
              (!want || count(out, want) == errors);
    if (!ok) { printf("\n%s\n----\n%s\n", body, out); }
    mem.free(out);
    mem.free(src);
    return ok;
}

i64 chain(i64 x) {
    if (x == 1) {
        return 10;
    } else if (x == 2) {
        return 20;
    } else {
        return 30;
    }
}

i32 main() {
    printf("Test - braces required: ");
    i64 correct = 0, tests = 0;

    tests++; if (chain(1) == 10 && chain(2) == 20 && chain(3) == 30) { correct++; }
    tests++; if (prints("  if (x) { x++; } else if (x > 1) { x--; } else { x = 0; }", 0)) { correct++; }
    tests++; if (prints("  if (x) x++;", 1, "braces required: the body of `if` goes in `{ ... }`")) { correct++; }
    tests++; if (prints("  if (x) { x++; } else x--;", 1, "the body of `else`")) { correct++; }
    tests++; if (prints("  for (i64 i = 0; i < 3; i++) x++;", 1, "the body of `for`")) { correct++; }
    tests++; if (prints("  i64 a[2];\n  for (i64 v : a) x += v;", 1, "the body of `for`")) { correct++; }
    tests++; if (prints("  while (x) x--;", 1, "the body of `while`")) { correct++; }
    tests++; if (prints("  while (x);", 1, "the body of `while`")) { correct++; }
    tests++; if (prints("  do x--; while (x);", 1, "the body of `do`")) { correct++; }
    /* at the statement */
    tests++; if (prints("  if (x)\n    x++;", 1, "ktm-444-braces.ktm:3:5\n")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
