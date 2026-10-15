import cstr, fs, libc, mem, proc, test;

/* v0.24.0: `ad` is spelled `scoped`. `scoped T name = value;` frees name
 * at the end of its scope (test 580 has the rules). `ad` still works, with
 * a warning at it that says to write `scoped`, except in the files of a
 * package in the module cache, which their authors update. -Werror makes
 * the warning an error. Like `ad`, `scoped` is an ordinary name anywhere
 * but in front of a declaration. */

#define SC_DIR "./ktm-895-work"
#define SC_SRC SC_DIR "/prog.ktm"
#define SC_OUT SC_DIR "/out"
#define SC_CACHE SC_DIR "/cache"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* ktm's output (stdout and stderr) running `path` with `flags`, *res its
 * exit status; KTM_CACHE is SC_CACHE */
u8 *ktm(u8 *path, u8 *flags, i64 *res) {
    u8 *cmd = cstr.format("KTM_CACHE=" SC_CACHE " $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                          "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                          "%s %s > " SC_OUT " 2>&1", flags, path);
    *res = proc.shell(cstr.view(cmd));
    mem.free(cmd);
    return fs.readAll(SC_OUT).take();
}

void write(u8[] path, u8 *src) {
    fs.writeAll(path, cstr.view(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
}

/* A program whose String, declared with `kw`, is freed at the end of its
 * block: it prints "hi 2" */
u8 *uses(u8 *kw) {
    return cstr.format("import str;\n"
                       "void main() {\n"
                       "    {\n"
                       "        %s String s = str.from(\"hi\");\n"
                       "        printf(\"%%s %%d\\n\", s, s.len);\n"
                       "    }\n"
                       "}\n", kw);
}

i32 main() {
    i64 res;
    u8 *out;
    printf("Test - scoped keyword: ");
    proc.shell("rm -rf " SC_DIR " && mkdir -p " SC_CACHE "/example.com/pkg@v1.0.0");

    u8 *src = uses("scoped");
    write(SC_SRC, src);
    mem.free(src);
    out = ktm(SC_SRC, "-jit", &res);
    check(res == 0 && out && !cstr.cmp(out, "hi 2\n"), "scoped frees, no warning (JIT)");
    mem.free(out);
    out = ktm(SC_SRC, "-run", &res);
    check(res == 0 && out && !cstr.cmp(out, "hi 2\n"), "scoped frees, no warning (AOT)");
    mem.free(out);

    /* the old spelling: works, warns at the word */
    src = uses("ad");
    write(SC_SRC, src);
    out = ktm(SC_SRC, "-jit", &res);
    check(res == 0 && out && libc.strstr(out, "hi 2\n") &&
          libc.strstr(out, "`ad` is now `scoped`: write `scoped` in front of the declaration") &&
          libc.strstr(out, "prog.ktm:4:9"), "ad warns at its line and column, and still works");
    mem.free(out);
    out = ktm(SC_SRC, "-Werror -jit", &res);
    check(res != 0 && out && libc.strstr(out, "`ad` is now `scoped`") && !libc.strstr(out, "hi 2"),
          "-Werror makes it an error");
    mem.free(out);

    /* a package's own file in the module cache: no warning */
    write(SC_CACHE "/example.com/pkg@v1.0.0/old.ktm", src);
    mem.free(src);
    out = ktm(SC_CACHE "/example.com/pkg@v1.0.0/old.ktm", "-jit", &res);
    check(res == 0 && out && !cstr.cmp(out, "hi 2\n"), "no warning in the module cache");
    mem.free(out);

    /* `scoped` (and `ad`) stay ordinary names */
    write(SC_SRC, "i64 scoped(i64 v) { return v + 1; }\n"
                  "void main() {\n"
                  "    i64 ad = 2;\n"
                  "    i64 n = scoped(ad);\n"
                  "    printf(\"%d\\n\", n);\n"
                  "}\n");
    out = ktm(SC_SRC, "-jit", &res);
    check(res == 0 && out && !cstr.cmp(out, "3\n"), "scoped and ad as names");
    mem.free(out);
    write(SC_SRC, "struct S { i64 scoped; };\n"
                  "void main() {\n"
                  "    S s;\n"
                  "    i64 scoped = 4;\n"
                  "    s.scoped = scoped + 1;\n"
                  "    printf(\"%d\\n\", s.scoped);\n"
                  "}\n");
    out = ktm(SC_SRC, "-jit", &res);
    check(res == 0 && out && !cstr.cmp(out, "5\n"), "a field and a local named scoped");
    mem.free(out);

    /* the messages say scoped */
    write(SC_SRC, "void main() { scoped i64 n = 5; }\n");
    out = ktm(SC_SRC, "-jit", &res);
    check(res != 0 && out && libc.strstr(out, "`scoped` frees its variable with `free()`"),
          "a type with no free()");
    mem.free(out);
    write(SC_SRC, "import str;\nstruct R { scoped String name; };\nvoid main() {}\n");
    out = ktm(SC_SRC, "-jit", &res);
    check(res != 0 && out && libc.strstr(out, "`scoped` goes on a local variable, not a struct field"),
          "scoped on a field");
    mem.free(out);
    write(SC_SRC, "import str;\nstruct R { ad String name; };\nvoid main() {}\n");
    out = ktm(SC_SRC, "-jit", &res);
    check(res != 0 && out && libc.strstr(out, "`ad` goes on a local variable, not a struct field"),
          "ad on a field names ad");
    mem.free(out);

    proc.shell("rm -rf " SC_DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
