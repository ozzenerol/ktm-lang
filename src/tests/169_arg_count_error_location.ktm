import fs, libc, mem, proc, str, test;

/* A wrong argument count was underlined from the first `(` to the first
 * `)` of the line, so `void Main() { i64 x = One(1); i64 y = Two(1); }`
 * underlined `Main()`. It is reported at the call itself, from the name
 * to the closing `)`. */

#define AC_TEST_FILE "__169_arg_count_loc.ktm"
#define AC_TEST_OUT  "__169_arg_count_loc.out"

void cleanupAcTest() {
    fs.remove(AC_TEST_FILE);
    fs.remove(AC_TEST_OUT);
    fs.remove("./a.out");
}

/* Compiles `body` (line 6 of the file) and checks the output has both
 * `where` (file:line:col) and `underline` (the caret line's carets). */
bool errorAt(u8 *body, u8 *where, u8 *underline) {
    u8 *src = str.format("i64 One(i64 a) { return a; }\n"
                                            "i64 Two(i64 a, i64 b) { return a + b; }\n"
                                            "i64 Va(i64 a, ...) { return a; }\n"
                                            "struct C { fn(i64 a) i64 f; };\n"
                                            "C c;\n"
                                            "void main() { %s }\n", body);
    u8 *out;
    bool ok = false;
    cleanupAcTest();
    if (fs.writeAll(AC_TEST_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AC_TEST_FILE " > " AC_TEST_OUT " 2>&1") != 0) {
            out = fs.readAll(AC_TEST_OUT);
            if (out) {
                ok = libc.strstr(out, "Unexpected number of arguments") && libc.strstr(out, where) &&
                         libc.strstr(out, underline);
                if (!ok) { printf("%s\n", out); }
                mem.free(out);
            }
        }
    }
    mem.free(src);
    cleanupAcTest();
    return ok;
}

i32 main() {
    printf("Test - argument count error location: ");
    i64 correct = 0, tests = 0;

    /* `void Main() { ` is 12 columns */
    tests++;
    if (errorAt("i64 x = One(1); i64 y = Two(1);", ":6:39", "|                                          ^^^^^^\n")) { correct++; }
    tests++;
    if (errorAt("i64 y = Two (1, 2, 3);", ":6:23", "|                          ^^^^^^^^^^^^^\n")) { correct++; }
    tests++;
    if (errorAt("fn(i64 a, i64 b) i64 fp = &Two; i64 y = fp(1);", ":6:55", "^^^^^\n")) { correct++; }
    tests++;
    if (errorAt("c.f = &One; i64 y = c.f();", ":6:37", "|                                        ^^^\n")) { correct++; }
    tests++;
    if (errorAt("i64 y = 1 + Two;", ":6:27", "|                              ^^^\n")) { correct++; }
    tests++;
    if (errorAt("i64 x = One(1); i64 y = Va();", ":6:39", "^^^^\n")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
