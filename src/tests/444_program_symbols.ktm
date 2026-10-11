import fs, libc, mem, proc, str, test;

/* A program's own function named like a C library or libktm symbol
 * (`read`, `environ`, `free`, ...) used to be that symbol: it replaced
 * the C one in the binary, so the library's own calls went to it (a
 * shell named its environment helper `environ()`, and dlsym("environ")
 * then returned the function: every spawn crashed). A main-program
 * function now has its own symbol, `_K0<len><name>` (`_K04read`), so the
 * program's read() is its own and the library's calls to read, write,
 * malloc, free, time and environ reach the C library. AOT and JIT; the
 * binary has no plain `read` or `environ` of its own (nm, if there). */

#define PS_SRC "./ktm-444-prog.ktm"
#define PS_BIN "./ktm-444-prog.bin"
#define PS_OUT "./ktm-444-prog.out"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

u8 *gProgram =
"import fs, libc, mem, proc, str;\n"
"\n"
"i64 read(i64 x) { return x + 1; }\n"
"i64 write(i64 x) { return x + 2; }\n"
"u8 **environ() { return null; }\n"
"i64 free(i64 x) { return x + 3; }\n"
"i64 time(i64 x) { return x + 4; }\n"
"i64 exit(i64 x) { return x + 5; }\n"
"i64 malloc(i64 x) { return x + 6; }\n"
"\n"
"i32 main() {\n"
"    u8 *p = mem.alloc(16);\n"
"    str.copy(p, \"abc\");\n"
"    fs.writeAll(\"./ktm-444-prog.txt\", p, 3);\n"
"    u8 *back = fs.readAll(\"./ktm-444-prog.txt\");\n"
"    fs.remove(\"./ktm-444-prog.txt\");\n"
"    i64 rc = proc.shell(\"exit 7\");\n"
"    printf(\"%d %d %d %d %d %d %d %s %d %d\\n\", read(1), write(1), environ() == null,\n"
"           free(1), time(1), exit(1), malloc(1), back, rc, libc.getenv(\"HOME\") != null);\n"
"    mem.free(back);\n"
"    mem.free(p);\n"
"    return 0;\n"
"}\n";

u8 *gWant = "2 3 1 4 5 6 7 abc 7 1\n";

bool output(u8 *cmd, u8 *want) {
    proc.shell("rm -f " PS_OUT);
    proc.shell(cmd);
    u8 *out = fs.readAll(PS_OUT);
    bool ok = out && !str.cmp(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", cmd, out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - a program's functions have their own symbols: ");
    i64 correct = 0, tests = 0;
    fs.writeAll(PS_SRC, gProgram, str.len(gProgram), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    tests++;
    if (output(KTM PS_SRC " -o " PS_BIN " > " PS_OUT " 2>&1 && " PS_BIN " >> " PS_OUT " 2>&1", gWant)) { correct++; }
    tests++;
    if (output(KTM "-jit " PS_SRC " > " PS_OUT " 2>&1", gWant)) { correct++; }

    /* the binary defines _K04read and _K07environ, no read or environ */
    tests++;
    if (output("command -v nm >/dev/null 2>&1 || { echo ok > " PS_OUT "; exit 0; }; "
               "nm " PS_BIN " | grep -qE ' T _?_K04read$' && "
               "nm " PS_BIN " | grep -qE ' T _?_K07environ$' && "
               "! nm " PS_BIN " | grep -qE ' [TtD] _?(read|environ|malloc)$' && echo ok > " PS_OUT, "ok\n")) {
        correct++;
    }

    proc.shell("rm -f " PS_SRC " " PS_BIN " " PS_OUT);
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
