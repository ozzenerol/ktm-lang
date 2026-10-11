import fs, libc, proc, str, test;

/* Arithmetic on a function pointer (`f + 0`) got through the type check
 * and crashed the compiler in codegen ("Unhandled Ir type: function").
 * It points at code, not at elements: `+`, `-`, `+=`, `++` and `--` on
 * one are type errors; comparing it still works. */

#define SRC_FILE "__491_src.ktm"

u8 *compileErr(u8 *body) {
    u8 *src = str.format("i64 one(i64 x) { return x + 1; }\n"
                         "i32 main() {\n    fn(i64) i64 f = &one;\n    %s\n"
                         "    return 0;\n}\n", body);
    fs.writeAll(SRC_FILE, src, str.len(src));
    return proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                            "-jit " SRC_FILE " 2>&1");
}

i32 main() {
    printf("Test - arithmetic on a function pointer: ");
    i64 correct = 0, tests = 0;

    u8 *bad[] = {
        "auto g = f + 0;", "`+` operator cannot be applied to a function",
        "auto g = 1 + f;", "`+` operator cannot be applied",
        "i64 d = f - f;", "`-` operator cannot be applied to a function",
        "f += 1;", "`+=` operator cannot be applied to a function",
        "f++;", "Cannot increment or decrement function pointer `f`",
        "--f;", "Cannot increment or decrement function pointer `f`",
    };
    for (i64 i = 0; i < 12; i += 2) {
        tests++;
        u8 *out = compileErr(bad[i]);
        if (out && libc.strstr(out, bad[i + 1]) && !libc.strstr(out, "Unhandled")) { correct++; }
        else { printf("\n  %s: [%s]\n", bad[i], out); }
    }
    tests++;
    u8 *out = compileErr("printf(\"%d %d\", f == &one, f(1));");
    if (out && !str.cmp(out, "1 2")) { correct++; }
    else { printf("\n  compare: [%s]\n", out); }
    fs.remove(SRC_FILE);

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
