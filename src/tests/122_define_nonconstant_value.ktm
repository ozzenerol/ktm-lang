#include "testhelper.ktm"

import fs, libc, mem, proc, str;

#define NONCONST_FILE "__122_define_nonconstant_value.ktm"
#define NONCONST_OUT  "__122_define_nonconstant_value.out"

/* Defines that still have to work */
#define NC_INT   2
#define NC_SUM   (NC_INT + 3)
#define NC_FLT   (1.5 * 2)
#define NC_STR   "he" "llo"
#define NC_PSTR  ("abc")
#define NC_CAST  ((u8)300 + 1)

#define NC_MSG "the value must be a constant expression or a string, made of literals and other macros"

void cleanupNonConstTest(void) {
    fs.remove(NONCONST_FILE);
    fs.remove(NONCONST_OUT);
    fs.remove("./a.out");
}

/* Compile `source`, which must fail (exit status 1) with `msg` reported
 * at `loc` (" --> file:line:col") and, when `use_loc` is not null, with
 * `X` reported as undefined at `use_loc`: lexing carried on past the
 * define and left X undefined. The old path exited from inside the
 * evaluator with "ERROR: Expected integer expression" and no location. */
bool compileFailsAt(u8 *source, u8 *msg, u8 *loc, u8 *useLoc) {
    u8 *out, *want, *wantUse = null;
    i64 res;
    bool ok = true;

    cleanupNonConstTest();

    if (!fs.writeAll(NONCONST_FILE, source, str.len(source), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        return false;
    }

    res = proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " NONCONST_FILE " > " NONCONST_OUT " 2>&1; exit $(( $? == 1 ? 7 : 0 ))");
    if (res == 0) { ok = false; }

    out = fs.readAll(NONCONST_OUT);
    if (!out) {
        cleanupNonConstTest();
        return false;
    }

    want = str.format("error: %s\n --> " NONCONST_FILE ":%s\n", msg, loc);
    if (useLoc) {
        wantUse = str.format("error: Variable or function `X` has not been defined\n --> "
                                                 NONCONST_FILE ":%s\n", useLoc);
    }
    if (!libc.strstr(out, want)) { ok = false; }
    if (wantUse && !libc.strstr(out, wantUse)) { ok = false; }
    if (libc.strstr(out, "Expected integer expression")) { ok = false; }
    if (libc.strstr(out, "Expected float expression")) { ok = false; }
    if (!ok) { printf("%s\n", out); }

    mem.free(want);
    if (wantUse) { mem.free(wantUse); }
    mem.free(out);
    cleanupNonConstTest();
    return ok;
}

/* The report: an int macro plus a string */
bool testIntMacroPlusString(void) {
    return compileFailsAt(
"const i64 Y = 2;
#define X (Y + \"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "2:11", "3:21");
}

bool testIntPlusNegString(void) {
    return compileFailsAt(
"#define X (1 + -\"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "1:11", "2:21");
}

bool testIntTimesCastString(void) {
    return compileFailsAt(
"#define X (3 * (i64)\"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "1:11", "2:21");
}

bool testFloatPlusString(void) {
    return compileFailsAt(
"#define X (2.5 + \"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "1:11", "2:21");
}

bool testFloatMacroPlusStringMacro(void) {
    return compileFailsAt(
"const f64 F = 1.5;
#define S \"s\"
#define X (F * S)
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "3:11", "4:21");
}

bool testStringMinusInt(void) {
    return compileFailsAt(
"#define X (\"a\" - 1)
void main() { printf(\"%d\\n\", X); }
",
        "#define X expected string but got: AST_BINOP", "1:11", "2:21");
}

/* `-` on a string is a type error now (test 134), as in `1 + -"s"` */
bool testNegString(void) {
    return compileFailsAt(
"#define X (-\"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X: " NC_MSG, "1:11", "2:21");
}

bool testCastString(void) {
    return compileFailsAt(
"#define X ((i64)\"s\")
void main() { printf(\"%d\\n\", X); }
",
        "#define X expected string but got: AST_CAST", "1:11", "2:21");
}

/* Both bad defines reported, lexing carried on after each */
bool testTwoBadDefines(void) {
    return compileFailsAt(
"#define A (1 + \"s\")
#define B (2 * -\"t\")
const i64 X = (A + B);
void main() { printf(\"%d\\n\", X); }
",
        "#define B: " NC_MSG, "2:11", null);
}

i32 main() {
    printf("Test - #define with a non-constant value: \n");
    i64 tests = 12;
    i64 correct = 0;

    if (testIntMacroPlusString()) { correct++; }
    else { printf("Failed - TestIntMacroPlusString()\n"); }

    if (testIntPlusNegString()) { correct++; }
    else { printf("Failed - TestIntPlusNegString()\n"); }

    if (testIntTimesCastString()) { correct++; }
    else { printf("Failed - TestIntTimesCastString()\n"); }

    if (testFloatPlusString()) { correct++; }
    else { printf("Failed - TestFloatPlusString()\n"); }

    if (testFloatMacroPlusStringMacro()) { correct++; }
    else { printf("Failed - TestFloatMacroPlusStringMacro()\n"); }

    if (testStringMinusInt()) { correct++; }
    else { printf("Failed - TestStringMinusInt()\n"); }

    if (testNegString()) { correct++; }
    else { printf("Failed - TestNegString()\n"); }

    if (testCastString()) { correct++; }
    else { printf("Failed - TestCastString()\n"); }

    if (testTwoBadDefines()) { correct++; }
    else { printf("Failed - TestTwoBadDefines()\n"); }

    if (NC_SUM == 5 && NC_CAST == 45) { correct++; }
    else { printf("Failed - int defines: %d %d\n", NC_SUM, NC_CAST); }

    if (NC_FLT == 3.0) { correct++; }
    else { printf("Failed - float define: %f\n", NC_FLT); }

    if (!str.cmp(NC_STR, "hello") && !str.cmp(NC_PSTR, "abc")) { correct++; }
    else { printf("Failed - string defines: %s %s\n", NC_STR, NC_PSTR); }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
