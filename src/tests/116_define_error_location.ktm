import cstr, fs, libc, mem, proc, test;

#define DEF_LOC_FILE "__116_define_error_location.ktm"
#define DEF_LOC_OUT  "__116_define_error_location.out"

void cleanupDefLocTest(void) {
    fs.remove(DEF_LOC_FILE);
    fs.remove(DEF_LOC_OUT);
    fs.remove("./a.out");
}

/* Compile `source`, which must fail with `msg` reported at `loc`
 * (" --> file:line:col"), the source excerpt showing `excerpt` and,
 * when not null, the underline `caret` (the `|` and everything after
 * it on the underline's line). */
bool compileFailsAt(u8 *source, u8 *msg, u8 *loc, u8 *excerpt, u8 *caret) {
    u8 *out, *wantLoc, *wantExcerpt, *wantCaret = null;
    i64 res;
    bool ok = true;

    cleanupDefLocTest();

    if (!fs.writeAll(DEF_LOC_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DEF_LOC_FILE " > " DEF_LOC_OUT " 2>&1");
    if (res == 0) {
        cleanupDefLocTest();
        return false;
    }

    out = fs.readAll(DEF_LOC_OUT);
    if (!out) {
        cleanupDefLocTest();
        return false;
    }

    wantLoc = cstr.format("error: %s\n --> " DEF_LOC_FILE ":%s\n", msg, loc);
    wantExcerpt = cstr.format(" |    %s\n", excerpt);
    if (caret) { wantCaret = cstr.format("\n     |%s", caret); }

    if (!libc.strstr(out, wantLoc)) { ok = false; }
    if (!libc.strstr(out, wantExcerpt)) { ok = false; }
    if (wantCaret && !libc.strstr(out, wantCaret)) { ok = false; }
    if (!ok) { printf("%s\n", out); }

    mem.free(wantLoc);
    mem.free(wantExcerpt);
    if (wantCaret) { mem.free(wantCaret); }
    mem.free(out);
    cleanupDefLocTest();
    return ok;
}

bool testDefineAlone(void) {
    return compileFailsAt(
"#define X (zz+1)
void main() {}
",
        "#define X: the value must be a constant expression or a string, made of literals and other macros",
        "1:11", "#define X (zz+1)", "              ^^^^^^\n");
}

/* Indented, with trailing blanks, after a class */
bool testDefineAfterClass(void) {
    return compileFailsAt(
"struct Foo { i64 a; };
  #define X (zz+1)
void main() {}
",
        "#define X: the value must be a constant expression or a string, made of literals and other macros",
        "2:13", "  #define X (zz+1)", "                ^^^^^^\n");
}

bool testDefineNotNumeric(void) {
    return compileFailsAt(
"#define X zz
void main() {}
",
        "Error while parsing #define X; #define must be a numerical expression or a string",
        "1:11", "#define X zz", "              ^^\n");
}

bool testDefineNothing(void) {
    return compileFailsAt(
"#define
void main() {}
",
        "Syntax is: #define <TK_IDENT> <value> got nothing",
        "1:1", "#define", "    ^^^^^^^\n");
}

bool testIfCondition(void) {
    return compileFailsAt(
"i64 g;
#if (zz+
#endif
void main() {}
",
        "#if: the condition must be a constant expression made of literals, macros and defined()",
        "2:5", "#if (zz+", "        ^^^^\n");
}

bool testElifCondition(void) {
    return compileFailsAt(
"#if 0
#elif (1+
#endif
void main() {}
",
        "#elif: the condition must be a constant expression made of literals, macros and defined()",
        "2:7", "#elif (1+", "          ^^^\n");
}

bool testIfExtraToken(void) {
    return compileFailsAt(
"#if 1 2
#endif
void main() {}
",
        "#if: unexpected '2' after the condition",
        "1:7", "#if 1 2", "          ^\n");
}

bool testIfEmpty(void) {
    return compileFailsAt(
"#if
#endif
void main() {}
",
        "a #if must evaluate some expression",
        "1:1", "#if", "    ^^^\n");
}

/* At the #if, not one line past the end of the file */
bool testUnterminatedIf(void) {
    return compileFailsAt(
"void main() {}
#if 0
i64 y;
",
        "Unterminated #if", "2:1", "#if 0", "    ^^^\n");
}

/* The excerpt re-lexes the line: an unknown directive used to make
 * that exit before anything was printed */
bool testUnknownDirective(void) {
    return compileFailsAt(
"#foo bar
void main() {}
",
        "invalid preprocessor directive '#foo'", "1:1", "#foo bar", "    ^^^^\n");
}

bool testIncludeSyntax(void) {
    return compileFailsAt(
"#include 42
void main() {}
",
        "Syntax is: #include \"<value>\" got: TK_I64        42",
        "1:10", "#include 42", null);
}

/* No operand: at the directive, not at the next line's first token */
bool testUndefNothing(void) {
    return compileFailsAt(
"#undef
void main() {}
",
        "Syntax is: #undef <TK_IDENT>", "1:1", "#undef", "    ^^^^^^\n");
}

bool testLinkSyntax(void) {
    return compileFailsAt(
"i64 g;
  #link 42
void main() {}
",
        "Syntax is: #link \"<path>\" or #link <libname> got: TK_I64        42",
        "2:9", "  #link 42", "            ^^\n");
}

i32 main() {
    printf("Test - #define error location: \n");
    i64 tests = 13;
    i64 correct = 0;

    if (testDefineAlone()) { correct++; }
    else { printf("Failed - TestDefineAlone()\n"); }

    if (testDefineAfterClass()) { correct++; }
    else { printf("Failed - TestDefineAfterClass()\n"); }

    if (testDefineNotNumeric()) { correct++; }
    else { printf("Failed - TestDefineNotNumeric()\n"); }

    if (testDefineNothing()) { correct++; }
    else { printf("Failed - TestDefineNothing()\n"); }

    if (testIfCondition()) { correct++; }
    else { printf("Failed - TestIfCondition()\n"); }

    if (testElifCondition()) { correct++; }
    else { printf("Failed - TestElifCondition()\n"); }

    if (testIfExtraToken()) { correct++; }
    else { printf("Failed - TestIfExtraToken()\n"); }

    if (testIfEmpty()) { correct++; }
    else { printf("Failed - TestIfEmpty()\n"); }

    if (testUnterminatedIf()) { correct++; }
    else { printf("Failed - TestUnterminatedIf()\n"); }

    if (testUnknownDirective()) { correct++; }
    else { printf("Failed - TestUnknownDirective()\n"); }

    if (testIncludeSyntax()) { correct++; }
    else { printf("Failed - TestIncludeSyntax()\n"); }

    if (testUndefNothing()) { correct++; }
    else { printf("Failed - TestUndefNothing()\n"); }

    if (testLinkSyntax()) { correct++; }
    else { printf("Failed - TestLinkSyntax()\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
