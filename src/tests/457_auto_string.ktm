import cstr, fs, libc, proc, test;

/* `auto s = "x";` warned "Incompatible types 'u8[2]' is not assignable to
 * type 'u8[2]'": the variable takes the string's type, then was checked
 * against it. */

#define SRC_FILE "__457_src.ktm"

u8 *runKtm(u8 *src, u8 *flags) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    return proc.shellOutput(cstr.format("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                       "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                       "%s " SRC_FILE " 2>&1", flags));
}

i32 main() {
    i64 correct = 0, tests = 3;
    printf("Test - auto from a string: ");

    u8 *src = "auto g = \"glob\";\n"
              "i32 main() {\n"
              "    auto s = \"x\";\n"
              "    auto t = \"hello\";\n"
              "    if (auto u = \"in if\"; sizeof(u) == 6) { printf(\"%s \", u); }\n"
              "    printf(\"%s %s %s %d\\n\", s, t, g, sizeof(t));\n"
              "    return 0;\n"
              "}\n";
    u8 *out = runKtm(src, "-jit");
    if (out && !cstr.cmp(out, "in if x hello glob 6")) { correct++; }
    else { printf("\n  JIT: [%s]\n", out); }
    out = runKtm(src, "-o __457_bin");
    if (out && !cstr.cmp(out, "")) { correct++; }
    else { printf("\n  AOT: [%s]\n", out); }

    /* a real mismatch still warns */
    out = runKtm("i32 main() { u8 s[2] = \"x\"; i64 *p = s; f64 *q = p; return 0; }\n", "-jit");
    if (out && libc.strstr(out, "is not assignable to type 'f64 *'")) { correct++; }
    else { printf("\n  mismatch: [%s]\n", out); }

    fs.remove(SRC_FILE);
    fs.remove("__457_bin");
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
