import cstr, fs, libc, mem, proc, test;

/* String literals are read-only data. A write through one was only a
 * crash in a compiled program, and the JIT kept literals in writable
 * memory, so there the write went through and changed the literal for
 * every later use. Now a write the compiler can see is an error: through
 * the literal itself, or through a local initialised from one that is
 * never given anything else (`u8[] a = "abc"; a[0] = 'x';`, also through
 * a sub-slice, with ++ or +=). A local that is reassigned, or whose
 * address is taken, is left alone. A write it can't see (through a
 * parameter) faults at run time in the JIT as in a compiled program. */

#define SRC_FILE "__495_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

/* ktm's output for `src` (-jit, or a compile with -S when `jit` is false) */
u8 *ktmOut(u8 *src, bool jit) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "%s " SRC_FILE " 2>&1; echo \"exit=$?\"",
                               jit ? "-jit" : "-S -o /dev/null");
    fs.remove(SRC_FILE);
    return out;
}

bool has(u8 *out, u8 *what) {
    return out && libc.strstr(out, what) != null;
}

void errors(u8 *body, u8 *want, u8 *what) {
    u8 *src = cstr.format("import str;\nvoid f(i64 i)\n{\n%s\n}\nvoid main() { f(0); }\n", body);
    u8 *out = ktmOut(src, false);
    check(has(out, want), what);
    if (!has(out, want)) { printf("  got: %s\n", out); }
    mem.free(out);
    mem.free(src);
}

void compiles(u8 *body, u8 *what) {
    u8 *src = cstr.format("import str;\nvoid f(i64 i)\n{\n%s\n}\nvoid main() { f(0); }\n", body);
    u8 *out = ktmOut(src, false);
    check(has(out, "exit=0") && !has(out, "error"), what);
    if (!has(out, "exit=0")) { printf("  got: %s\n", out); }
    mem.free(out);
    mem.free(src);
}

i32 main() {
    printf("Test - writes through string literals: ");

    u8 *local = "`a` is a string literal, which is read-only: copy it with str.from(...)";
    errors("  u8 x = (\"abc\"[1] = 'x');",
           "error: a string literal is read-only: copy it with str.from(...)", "the literal itself");
    errors("  u8[] a = \"abc\";\n  a[0] = 'x';", local, "a local");
    errors("  u8[] a = \"abc\";\n  a[1:][0] = 'x';", local, "through a sub-slice");
    errors("  u8[] a = \"abc\";\n  a[i]++;", local, "++");
    errors("  u8[] a = \"abc\";\n  a[0] += 1;", local, "+=");
    errors("  u8[] a = \"abc\";\n  for (i64 k = 0; k < 2; k++) {\n    a[k] = 'x';\n  }", local, "in a loop");

    compiles("  u8[] a = \"abc\";\n  String s = str.from(\"xyz\");\n  defer s.free();\n"
             "  if (i) { a = s; }\n  a[0] = 'y';", "reassigned somewhere: left to run time");
    compiles("  u8[] a = \"abc\";\n  u8[] *p = &a;\n  a[0] = 'y';", "address taken: left to run time");
    compiles("  String s = str.from(\"abc\");\n  defer s.free();\n  u8[] a = s;\n  a[0] = 'y';\n"
             "  u8 b[] = \"abc\";\n  u8[] c = b;\n  c[0] = 'y';", "a String's and an array's bytes");
    compiles("  u8[] a = \"abc\";\n  u8 c = a[0];\n  printf(\"%c\", c);", "reading is fine");

    /* At run time, in the JIT: a fault, not a changed literal */
    u8 *out = ktmOut("void poke(u8[] s) { s[0] = 'X'; }\n"
                     "void main()\n{\n  poke(\"literal\");\n  printf(\"%s\\n\", \"literal\");\n}\n", true);
    check(!has(out, "Xiteral") && !has(out, "exit=0"), "the JIT faults");
    mem.free(out);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
