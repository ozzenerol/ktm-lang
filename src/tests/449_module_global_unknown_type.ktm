import cstr, fs, libc, mem, proc, test;

/* F16: a module global whose type doesn't exist was reported twice: the
 * module's file is read in its declarations pass and in its full pass, and
 * both reported it. A diagnostic identical to one already given (the same
 * message at the same place) is now given once. */

#define D "./ktm-449"

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* ktm `flags` on main.ktm: the error is there, once */
bool once(u8 *flags) {
    u8 *cmd = cstr.format("cd " D " && $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                         "../../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                         "%s main.ktm > out 2>&1", flags);
    proc.shell(cmd);
    mem.free(cmd);
    u8 *out = fs.readAll(D "/out");
    bool ok = out && count(out, "error:") == 1 &&
              count(out, "Type `Nope` has not been declared") == 1 &&
              libc.strstr(out, "m/m.ktm:2:5\n");
    if (!ok) { printf("\n%s: %s\n", flags, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - a module's unknown global type is reported once: ");
    i64 correct = 0, tests = 0;
    proc.shell("rm -rf " D " && mkdir -p " D "/m");
    u8 *mod = "module m;\npub Nope g;\npub i64 h = 1;\n";
    u8 *main = "import m;\ni32 main() { return m.h; }\n";
    fs.writeAll(D "/m/m.ktm", mod, cstr.len(mod), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);
    fs.writeAll(D "/main.ktm", main, cstr.len(main), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    tests++; if (once("-S -o /dev/null")) { correct++; }
    tests++; if (once("-jit")) { correct++; }

    proc.shell("rm -rf " D);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
