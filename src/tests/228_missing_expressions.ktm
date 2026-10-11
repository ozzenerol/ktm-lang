import test;

import fs, libc, mem, proc, str;

/* Places that need an expression accepted none: `while ()` and
 * `do ... while ();` compiled to nothing, `if ()`, `switch ()` and a
 * `case` range missing an end crashed. Each is now an error at the token
 * where the expression should be. The legal empty forms stay: a bare
 * `case:` (previous value + 1), `for (;;)` and `if (; cond)`.
 * `return;` in a function returning a value keeps its warning, now
 * without printing `'(null)' of type 'void'`. */

#define EE_SRC "./ktm-228-ee.ktm"
#define EE_OUT "./ktm-228-ee.out"

u8 *diagnose(u8 *source, i64 *_status) {
    proc.shell("rm -f " EE_SRC " " EE_OUT);
    fs.writeAll(EE_SRC, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    *_status = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                 "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                 "-jit " EE_SRC " > " EE_OUT " 2>&1");
    u8 *out = fs.readAll(EE_OUT);
    proc.shell("rm -f " EE_SRC " " EE_OUT);
    return out;
}

/* ktm fails (status 1, not a crash) with `want` at `where` */
bool rejected(u8 *source, u8 *want, u8 *where) {
    i64 status;
    u8 *out = diagnose(source, &status);
    bool ok = status == 1 && out && libc.strstr(out, want) && libc.strstr(out, where);
    if (!ok) { printf("got (status %d): '%s'\n", status, out); }
    mem.free(out);
    return ok;
}

u8 *legalSrc =
"i64 F(i64 a=1, i64 b=2, i64 c=3) { return a + b + c; }
void main()
{
  if (; 1) { printf(\"if \"); }
  i64 i = 0;
  for (;;) { if (++i > 2) { break; } }
  for (; i < 5; ) { i++; }
  switch (2) { case: printf(\"zero \"); break; case: printf(\"one \"); break; case: printf(\"two \"); break; }
  printf(\"%d %d\\n\", i, F(10,,20));
}
";

i32 main() {
    printf("Test - missing expressions are errors: ");
    i64 tests = 0, correct = 0, status;
    u8 *out;

    tests++;
    if (rejected("void Main() { while () {} }\n",
                             "Expected the `while` condition, got `)`", "228-ee.ktm:1:22\n")) { correct++; }
    tests++;
    if (rejected("void main() { do {} while (); }\n",
                             "Expected the `while` condition, got `)`", "228-ee.ktm:1:27\n")) { correct++; }
    tests++;
    if (rejected("void Main() { if () {} }\n",
                             "Expected the `if` condition, got `)`", "228-ee.ktm:1:19\n")) { correct++; }
    tests++;
    if (rejected("void main() { if (i64 a = 1; ) {} }\n",
                             "Expected the `if` condition, got `)`", "228-ee.ktm:1:30\n")) { correct++; }
    tests++;
    if (rejected("void Main() { if (i64 a = ) {} }\n",
                             "Expected an initial value, got `)`", "228-ee.ktm:1:27\n")) { correct++; }
    tests++;
    if (rejected("void main() { switch () { case 1: break; } }\n",
                             "Expected the `switch` value, got `)`", "228-ee.ktm:1:23\n")) { correct++; }
    tests++;
    if (rejected("void main() { switch [] { case 1: break; } }\n",
                             "Expected the `switch` value, got `]`", "228-ee.ktm:1:23\n")) { correct++; }
    tests++;
    if (rejected("void main() { switch (1) { case 1 ... : break; } }\n",
                             "Expected the end of the `case` range, got `:`", "228-ee.ktm:1:39\n")) { correct++; }
    tests++;
    if (rejected("void main() { switch (1) { case ... 3: break; } }\n",
                             "Expected a `case` value or `:`, got `...`", "228-ee.ktm:1:35\n")) { correct++; }
    tests++;
    if (rejected("void main() { try { throw(); } catch { } }\n",
                             "Expected the value to `throw`, got `)`", "228-ee.ktm:1:26\n")) { correct++; }
    tests++;
    if (rejected("void main() { (); }\n",
                             "Expected an expression between `(` and `)`", "228-ee.ktm:1:15\n")) { correct++; }

    /* `return;` where a value is expected: still a warning, now readable */
    tests++;
    out = diagnose("i64 F() { return; }\nvoid main() { F(); }\n", &status);
    if (status == 0 && out &&
            libc.strstr(out, "`return` with no value, expected a value of type 'i64'") &&
            !libc.strstr(out, "(null)")) { correct++; }
    else { printf("got: '%s'\n", out); }
    mem.free(out);

    /* The legal empty forms */
    tests++;
    out = diagnose(legalSrc, &status);
    if (status == 0 && out && !str.cmp(out, "if two 5 32\n")) { correct++; }
    else { printf("got (status %d): '%s'\n", status, out); }
    mem.free(out);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
