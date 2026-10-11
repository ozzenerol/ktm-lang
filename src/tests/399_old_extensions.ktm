import cstr, fs, libc, proc, test;

/* ktm sources are .ktm and headers .ktmh. The old .HC/.HH names (and any
 * other extension) are a clean break: as the input file they stop the
 * compiler with a message saying so, and an #include of such a file is an
 * error. .ktm/.ktmh includes and an include without an extension work. */

#define KTM "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

void put(u8 *name, u8 *text) {
    fs.writeAll(name, text, cstr.len(text));
}

/* The output of the shell command, or null on a failed exit. */
bool has(u8 *out, u8 *what) {
    return out && libc.strstr(out, what) != null;
}

i32 main() {
    i64 correct = 0, tests = 0;
    u8 *out;
    printf("Test - .ktm / .ktmh only: ");

    put("__399_ok.ktmh", "i64 seven() { return 7; }\n");
    put("__399_noext", "i64 eight() { return 8; }\n");
    put("__399_main.ktm", "#include \"__399_ok.ktmh\"\n#include \"__399_noext\"\nvoid main() { printf(\"%d\\n\", seven() + eight()); }\n");
    put("__399_old.HC", "void main() { printf(\"old\\n\"); }\n");
    put("__399_inc_old.ktm", "#include \"__399_old.HC\"\nvoid main() {}\n");
    put("__399_inc_c.ktm", "#include \"__399_x.c\"\nvoid main() {}\n");
    put("__399_x.c", "int x;\n");

    tests++;
    out = proc.shellOutput(KTM "-jit __399_main.ktm 2>&1");
    if (out && !cstr.cmp(out, "15")) { correct++; }
    else { printf("\n  .ktm with .ktmh and extensionless includes: [%s]\n", out); }

    tests++;
    out = proc.shellOutput(KTM "__399_old.HC -o __399_old 2>&1; echo \"rc=$?\"");
    if (has(out, "`__399_old.HC`: ktm sources use .ktm (headers .ktmh)") && has(out, "rc=1")) { correct++; }
    else { printf("\n  .HC input is rejected: [%s]\n", out); }

    tests++;
    out = proc.shellOutput(KTM "-jit __399_old.HC 2>&1; echo \"rc=$?\"");
    if (has(out, "ktm sources use .ktm") && has(out, "rc=1")) { correct++; }
    else { printf("\n  .HC input is rejected under -jit: [%s]\n", out); }

    tests++;
    out = proc.shellOutput(KTM "-jit __399_inc_old.ktm 2>&1");
    if (has(out, "#include: `") && has(out, "__399_old.HC`: ktm sources use .ktm (headers .ktmh)")) { correct++; }
    else { printf("\n  #include of a .HC file is an error: [%s]\n", out); }

    tests++;
    out = proc.shellOutput(KTM "-jit __399_inc_c.ktm 2>&1");
    if (has(out, "__399_x.c`: ktm sources use .ktm")) { correct++; }
    else { printf("\n  #include of a .c file is an error: [%s]\n", out); }

    tests++;   /* after the input, a .HC word is just an argument for the program */
    put("__399_args.ktm", "i32 main(i32 argc, u8 **argv) { printf(\"%s\\n\", argv[1]); return 0; }\n");
    out = proc.shellOutput(KTM "-jit __399_args.ktm notes.HC 2>&1");
    if (out && !cstr.cmp(out, "notes.HC")) { correct++; }
    else { printf("\n  a .HC program argument after the input: [%s]\n", out); }

    proc.shell("rm -rf __399_*");   /* the files this test wrote */
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
