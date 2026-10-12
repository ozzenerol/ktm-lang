import cstr, fs, libc, mem, proc, test;

/* `private` (Y14): a struct field marked `private` can be used anywhere
 * in the module that defines the struct (any of its files, its methods)
 * and nowhere outside: reading, writing, `&`, `->`, initialiser lists
 * that reach it, range-for over it, and through a struct that inherits
 * it. Outside, sizeof, copies and passing by value still work. In the
 * main program `private` fields are an error (nothing to hide from), as
 * is `public` on a field. On a declaration `private` means what leaving
 * out `pub` means; both together are an error. The fixture is
 * src/tests/modules/private.
 * F13: a string literal is never a keyword: `F("private", "")` and
 * `F("public")` are calls with string arguments. */

#define P_OUT "./ktm-428-private.out"
#define P_BIN "./ktm-428-private.bin"
#define P_DIR "./p428"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
                        "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gWant = "3 24 5 hello lit 3\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " P_OUT " " P_BIN);
    proc.shell(cstr.view(cmd));
    u8 *out = fs.readAll(P_OUT).take();
    bool ok = out && !cstr.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    proc.shell("rm -f " P_OUT " " P_BIN);
    return ok;
}

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* A program `main` (and the module `shop` when given) next to a copy of
 * web fails with exactly one error, `want` */
bool fails(u8 *main, u8 *shop, u8 *want) {
    proc.shell("rm -rf " P_DIR " && mkdir -p " P_DIR " && "
                 "cp -r ${KTM_TEST_SRC:-.}/modules/private/web " P_DIR "/");
    fs.writeAll(P_DIR "/main.ktm", cstr.view(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    if (shop) {
        proc.shell("mkdir -p " P_DIR "/shop");
        fs.writeAll(P_DIR "/shop/shop.ktm", cstr.view(shop), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    }
    proc.shell(KTM "-S " P_DIR "/main.ktm -o /dev/null > " P_OUT " 2>&1");
    u8 *out = fs.readAll(P_OUT).take();
    bool ok = out && count(out, "error:") == 1 && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", main, out); }
    mem.free(out);
    proc.shell("rm -rf " P_DIR " " P_OUT);
    return ok;
}

u8 gBuf[64];
u8 *join(u8 *a, u8 *b = "", u8 *c = "") {
    cstr.formatTo(gBuf, "%s|%s|%s", a, b, c);
    return gBuf;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/private/main.ktm"

i32 main() {
    printf("Test - private fields: ");
    i64 correct = 0, tests = 0;

    /* F13 */
    tests++; if (!cstr.cmp(join("private", ""), "private||")) { correct++; }
    tests++; if (!cstr.cmp(join("public"), "public||")) { correct++; }
    tests++; if (!cstr.cmp(join("pub", "private", "public"), "pub|private|public")) { correct++; }
    tests++; if (!cstr.cmp(join("i64", "u8"), "i64|u8|")) { correct++; }

    /* inside web every file uses them, outside the layout still works */
    tests++;
    if (output(KTM FIXTURE " -o " P_BIN " > " P_OUT " 2>&1 && " P_BIN " >> " P_OUT " 2>&1",
                         gWant)) {
        correct++;
                         }
    tests++;
    if (output(KTM "-jit " FIXTURE " > " P_OUT " 2>&1", gWant)) { correct++; }

    tests++;
    if (fails("import web;\ni32 main() { web.Conn *c = web.dial(1); u8 *b = c.buf; return 0; }\n",
                        null, "`buf` is a private field of web.Conn")) {
        correct++;
                        }
    tests++;
    if (fails("import web;\ni32 main() { web.Conn *c = web.dial(1); c->buf = null; return 0; }\n",
                        null, "`buf` is a private field of web.Conn")) {
        correct++;
                        }
    tests++;
    if (fails("import web;\ni32 main() { web.Conn c; i64 *p = &c.used; return 0; }\n",
                        null, "`used` is a private field of web.Conn")) {
        correct++;
                        }
    tests++;
    if (fails("import web;\ni32 main() { web.Conn c = {1, \"x\"}; return 0; }\n",
                        null, "`buf` is a private field of web.Conn")) {
        correct++;
                        }
    tests++;
    if (fails("import web;\ni32 main() { web.Tls t; t.used = 1; return 0; }\n",
                        null, "`used` is a private field of web.Conn")) {
        correct++;
                        }
    tests++;
    if (fails("import web;\ni64 F(web.Bytes *b) { for (auto x : b) {} return 0; }\n"
                        "i32 main() { return 0; }\n",
                        null, "`size` is a private field of web.Bytes")) {
        correct++;
                        }
    tests++;
    if (fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
                        "module shop;\nimport web;\npub void go() { web.Conn *c = web.dial(1); c.used = 2; }\n",
                        "`used` is a private field of web.Conn")) {
        correct++;
                        }
    /* `private` on a function: the same as no `pub` */
    tests++;
    if (fails("import web;\ni32 main() { return web.twice(1); }\n",
                        null, "`twice` is private to module web")) {
        correct++;
                        }
    tests++;
    if (fails("struct S { private i64 x; };\ni32 main() { return 0; }\n", null,
                        "`private` fields are for the structs of a module")) {
        correct++;
                        }
    tests++;
    if (fails("struct S { pub i64 x; };\ni32 main() { return 0; }\n", null,
                        "a field is public unless it is marked `private`")) {
        correct++;
                        }
    tests++;
    if (fails("pub private i64 F() { return 0; }\ni32 main() { return 0; }\n", null,
                        "a declaration is either `pub` or `private`, not both")) {
        correct++;
                        }
    tests++;
    if (fails("private pub i64 F() { return 0; }\ni32 main() { return 0; }\n", null,
                        "a declaration is either `pub` or `private`, not both")) {
        correct++;
                        }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
