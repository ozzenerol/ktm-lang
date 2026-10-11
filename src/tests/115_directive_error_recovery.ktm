import cstr, fs, libc, mem, proc, test;

#define DIR_ERR_FILE "__115_directive_error.ktm"
#define DIR_ERR_OUT  "__115_directive_error.out"

#define DIR_MSG_IF_CONST "#if: the condition must be a constant expression made of literals, macros and defined()"

/* A good #if right after a class must keep working */
struct DirErrOk { i64 a; };
#if 1 + 1 == 2
#define DIR_ERR_OK 3
#endif

void cleanupDirErrTest(void) {
    fs.remove(DIR_ERR_FILE);
    fs.remove(DIR_ERR_OUT);
    fs.remove("./a.out");
}

i64 countErrors(u8 *out) {
    i64 n = 0;
    u8 *p = out;
    while ((p = libc.strstr(p, "error: "))) {
        n++;
        p += 7;
    }
    return n;
}

/* Compile `source`, which must fail with exactly `n_expected` errors,
 * `msg` (when not null) among them. */
bool compileFailsExactly(u8 *source, i64 nExpected, u8 *msg) {
    u8 *out;
    i64 res;
    bool ok;

    cleanupDirErrTest();

    if (!fs.writeAll(DIR_ERR_FILE, source, cstr.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " DIR_ERR_FILE " > " DIR_ERR_OUT " 2>&1");
    if (res == 0) {
        cleanupDirErrTest();
        return false;
    }

    out = fs.readAll(DIR_ERR_OUT);
    if (!out) {
        cleanupDirErrTest();
        return false;
    }

    ok = countErrors(out) == nExpected;
    if (msg && !libc.strstr(out, msg)) { ok = false; }
    if (!ok) { printf("%s\n", out); }

    mem.free(out);
    cleanupDirErrTest();
    return ok;
}

/* Run `bad` after a class, after a union and with nothing before it;
 * each must give `msg` and nothing else. Foo is used afterwards, so a
 * class left half-declared by the error (which used to crash the
 * compiler) or a spurious error from its tail fails the case. */
bool errorOnlyEverywhere(u8 *name, u8 *bad, u8 *msg) {
    u8 *src;
    bool ok = true;

    src = cstr.format("struct Foo { i64 a; };\n%svoid main() { Foo f; f.a = 1; }\n", bad);
    if (!compileFailsExactly(src, 1, msg)) {
        printf("Failed - %s after a class\n", name);
        ok = false;
    }
    mem.free(src);

    src = cstr.format("union Foo { i64 a; u8 b; };\n%svoid main() { Foo f; f.a = 1; }\n", bad);
    if (!compileFailsExactly(src, 1, msg)) {
        printf("Failed - %s after a union\n", name);
        ok = false;
    }
    mem.free(src);

    src = cstr.format("%sstruct Foo { i64 a; };\nvoid main() { Foo f; f.a = 1; }\n", bad);
    if (!compileFailsExactly(src, 1, msg)) {
        printf("Failed - %s with nothing before it\n", name);
        ok = false;
    }
    mem.free(src);
    return ok;
}

bool testIfBadCondition(void) {
    return errorOnlyEverywhere("#if (zz+", "#if (zz+\ni64 y;\n#endif\n",
                                                         DIR_MSG_IF_CONST);
}

bool testElifBadCondition(void) {
    return errorOnlyEverywhere("#elif (1+", "#if 0\n#elif (1+\ni64 y;\n#endif\n",
            "#elif: the condition must be a constant expression made of literals, macros and defined()");
}

bool testIfEmpty(void) {
    return errorOnlyEverywhere("#if", "#if\ni64 y;\n#endif\n",
                                                         "a #if must evaluate some expression");
}

bool testIfExtraToken(void) {
    return errorOnlyEverywhere("#if 1 2", "#if 1 2\ni64 y;\n#endif\n",
                                                         "#if: unexpected '2' after the condition");
}

bool testIfBadBackslash(void) {
    return errorOnlyEverywhere("#if 1 \\ 2", "#if 1 \\ 2\ni64 y;\n#endif\n",
                                                         "Invalid use of '\\' should be");
}

bool testIncludeBadSyntax(void) {
    return errorOnlyEverywhere("#include 42", "#include 42\n",
                                                         "Syntax is: #include \"<value>\" got: TK_I64");
}

bool testIncludeMissingFile(void) {
    return errorOnlyEverywhere("#include missing",
                                                         "#include \"__115_no_such_file.ktm\"\n",
                                                         "#include: cannot open '__115_no_such_file.ktm'");
}

bool testLinkBadSyntax(void) {
    return errorOnlyEverywhere("#link 42", "#link 42\n",
                                                         "Syntax is: #link \"<path>\" or #link <libname> got: TK_I64");
}

bool testUndefBadSyntax(void) {
    return errorOnlyEverywhere("#undef 42", "#undef 42\n",
                                                         "Syntax is: #undef <TK_IDENT>");
}

/* `#undef` with nothing after it must not swallow the next line */
bool testUndefNothing(void) {
    return errorOnlyEverywhere("#undef", "#undef\ni64 y;\n",
                                                         "Syntax is: #undef <TK_IDENT>");
}

/* nor take the next line's first word when it is an identifier
 * (`void` is one) */
bool testUndefNothingVoid(void) {
    return errorOnlyEverywhere("#undef", "#undef\nvoid F() {}\n",
                                                         "Syntax is: #undef <TK_IDENT>");
}

bool testError(void) {
    return errorOnlyEverywhere("#error", "#error \"stop here\" and more\n",
                                                         "error: \"stop here\" and more\n");
}

bool testUnterminatedIf(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#if 0
i64 y;
", 1, "Unterminated #if");
}

bool testBadNumber(void) {
    return errorOnlyEverywhere("0b12", "i64 q = 0b12;\n",
                                                         "malformed numeric literal");
}

bool testLongCharConst(void) {
    return errorOnlyEverywhere("'abcdefghij'", "i64 c = 'abcdefghij';\n",
                                                         "a character literal holds one character");
}

bool testBadEscape(void) {
    return errorOnlyEverywhere("\"\\q\"", "u8 *s = \"\\q\";\n",
                                                         "Invalid escape character: '\\q'");
}

bool testDotDot(void) {
    return errorOnlyEverywhere("..", "void F(i64 a, ..args) {}\n",
                                                         "`..` is an invalid token sequence");
}

/* A failed #if condition is taken as false: the #else branch is used,
 * so `y` is declared and Main has no error of its own. */
bool testIfFailedTakesElse(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#if (zz+
i64 y = 1;
#else
i64 y = 2;
#endif
void main() { Foo f; f.a = y; }
", 1, DIR_MSG_IF_CONST);
}

/* Several bad directives in a row are each reported once */
bool testSeveral(void) {
    return compileFailsExactly(
"struct Foo { i64 a; };
#if (zz+
#endif
#undef 1
union Bar { i64 a; u8 b; };
#include 7

struct Baz { i64 b; };
void main() { Foo f; Bar b; Baz z; f.a = 1; b.a = 2; z.b = 3; }
", 3, "Syntax is: #include");
}

i32 main() {
    printf("Test - directive errors after a class: \n");
    i64 tests = 20;
    i64 correct = 0;

    if (testIfBadCondition()) { correct++; }
    if (testElifBadCondition()) { correct++; }
    if (testIfEmpty()) { correct++; }
    if (testIfExtraToken()) { correct++; }
    if (testIfBadBackslash()) { correct++; }
    if (testIncludeBadSyntax()) { correct++; }
    if (testIncludeMissingFile()) { correct++; }
    if (testLinkBadSyntax()) { correct++; }
    if (testUndefBadSyntax()) { correct++; }
    if (testUndefNothing()) { correct++; }
    if (testUndefNothingVoid()) { correct++; }
    if (testError()) { correct++; }
    if (testBadNumber()) { correct++; }
    if (testLongCharConst()) { correct++; }
    if (testBadEscape()) { correct++; }
    if (testDotDot()) { correct++; }

    if (testUnterminatedIf()) { correct++; }
    else { printf("Failed - TestUnterminatedIf()\n"); }

    if (testIfFailedTakesElse()) { correct++; }
    else { printf("Failed - TestIfFailedTakesElse()\n"); }

    if (testSeveral()) { correct++; }
    else { printf("Failed - TestSeveral()\n"); }

    DirErrOk ok;
    ok.a = DIR_ERR_OK;
    if (ok.a == 3) { correct++; }
    else { printf("Failed - #if after a class\n"); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
