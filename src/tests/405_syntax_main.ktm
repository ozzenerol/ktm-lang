import cstr, fs, libc, proc, test;

/* ktm syntax v0.2: the entry point can be `main` in lowercase: `i32 main()`,
 * `i32 main(i32 argc, u8 **argv)` or `void main()`, with the same behaviour
 * as Main (its return value is the exit code, the arguments arrive, a
 * void main exits with 0, file-scope initialisers run first). A program
 * can't define both main and Main. Each program runs compiled and in the
 * JIT. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void put(u8 *name, u8 *text) {
    fs.writeAll(cstr.view(name), cstr.view(text));
}

/* Output and exit code of the program in file, run with args, both ways:
 * true when both print `want` and exit with `code`. */
bool runs(u8 *file, u8 *want, i64 code) {
    u8 *jit = proc.shellOutput(KTM "-jit %s one two; echo \"rc=$?\"", file);
    u8 *aot = proc.shellOutput(KTM "%s -o __405_bin && ./__405_bin one two; echo \"rc=$?\"", file);
    u8 *expect = cstr.format("%s\nrc=%d", want, code);
    bool ok = jit && aot && !cstr.cmp(jit, expect) && !cstr.cmp(aot, expect);
    if (!ok) { printf("\n  %s: want [%s]\n    jit [%s]\n    aot [%s]\n", file, expect, jit, aot); }
    return ok;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - lowercase main: ");

    tests++;
    put("__405_a.ktm", "i32 main()\n{\n  printf(\"plain\\n\");\n  return 3;\n}\n");
    if (runs("__405_a.ktm", "plain", 3)) { correct++; }

    tests++;   /* argc/argv: the JIT's argv[0] is the source, AOT's the binary */
    put("__405_b.ktm", "i32 main(i32 argc, u8 **argv)\n{\n  printf(\"%d %s %s\\n\", argc, argv[1], argv[2]);\n  return argc;\n}\n");
    if (runs("__405_b.ktm", "3 one two", 3)) { correct++; }

    tests++;
    put("__405_c.ktm", "void main()\n{\n  printf(\"void\\n\");\n}\n");
    if (runs("__405_c.ktm", "void", 0)) { correct++; }

    tests++;   /* file-scope initialisers run first, then main with its arguments */
    put("__405_d.ktm", "i64 g = 4;\ng = g * 10;\ni32 main(i32 argc, u8 **argv)\n{\n  printf(\"%d %s\\n\", g, argv[2]);\n  return 7;\n}\n");
    if (runs("__405_d.ktm", "40 two", 7)) { correct++; }

    tests++;   /* a prototype first, and main called by that name from elsewhere */
    put("__405_e.ktm", "i32 main();\ni64 calls = 0;\nvoid Again() { if (calls < 2) { main(); } }\ni32 main()\n{\n  calls++;\n  Again();\n  if (calls == 2) {\n    printf(\"calls %d\\n\", calls);\n    calls = 99;\n  }\n  return 0;\n}\n");
    if (runs("__405_e.ktm", "calls 2", 0)) { correct++; }

    tests++;   /* one entry point only */
    put("__405_f.ktm", "void Main() {}\nvoid main() {}\n");
    u8 *err = proc.shellOutput(KTM "-jit __405_f.ktm 2>&1");
    if (err && libc.strstr(err, "both `main` and `Main` are defined")) { correct++; }
    else { printf("\n  main and Main: [%s]\n", err); }

    proc.shell("rm -rf __405_*");   /* the files this test wrote */
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
