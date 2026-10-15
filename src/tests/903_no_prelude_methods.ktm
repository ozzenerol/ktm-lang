import cstr, fs, libc, mem, proc, str, test;

/* v0.25.0: a program could give String (and Error) methods of its own,
 * `void String.shout()`, though no other library type takes methods from
 * outside its module: the prelude's structs have no module part in their
 * names, so they passed for the program's own. Their methods are now the
 * standard library's only, through a type alias too, with a hint to write
 * a function. The library's String methods and a program's own structs
 * are as before. */

#define PM_SRC "./ktm-903.ktm"
#define PM_OUT "./ktm-903.out"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* ktm -jit on `src`: its output, *res the exit status */
u8 *ktm(u8 *src, i64 *res) {
    fs.writeAll(PM_SRC, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    *res = proc.shell("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                      "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit "
                      PM_SRC " > " PM_OUT " 2>&1");
    return fs.readAll(PM_OUT).take();
}

bool fails(u8 *src, u8 *want) {
    i64 res;
    u8 *out = ktm(src, &res);
    bool ok = res != 0 && out && libc.strstr(out, want);
    if (!ok) { printf("\n  got: %s\n", out); }
    mem.free(out);
    return ok;
}

struct Mine { i64 x; };
void Mine.bump() { self.x++; }

i32 main() {
    printf("Test - no methods on prelude types: ");

    check(fails("void String.shout() {\n}\nvoid main() {}\n",
                "methods of `String` can only be defined by the standard library: "
                "write a function that takes it, as `shout(String *s)`"),
          "a method on String");
    check(fails("void String.shout() {\n}\nvoid main() {}\n", "ktm-903.ktm:1:6"),
          "the error is at String");
    check(fails("type Text = String;\nbool Text.blank() { return self.len == 0; }\nvoid main() {}\n",
                "methods of `String` can only be defined by the standard library"),
          "through a type alias");
    check(fails("void Error.describe() {\n}\nvoid main() {}\n",
                "methods of `Error` can only be defined by the standard library"),
          "a method on Error");

    /* what still works */
    Mine m;
    m.bump();
    check(m.x == 1, "a program's own struct");
    scoped String s = str.from("ok");
    s.append("!");
    check(s.equals("ok!"), "the library's String methods");

    proc.shell("rm -f " PM_SRC " " PM_OUT);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
