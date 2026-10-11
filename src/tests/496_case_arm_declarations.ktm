import cstr, fs, libc, mem, proc, str, test;

/* A declaration directly in a case arm (`case 1: i64 a = 2; ...`) was a
 * syntax error ("Unexpected character `:`"), and inside json's parser it
 * ran the compiler out of memory. An arm is now a block of its own:
 * declarations, `defer` (run at the arm's end) and a scope, so two arms
 * can use the same name and one arm's variable can't be used in the next.
 * `fallthrough` keeps its rules. */

#define SRC_FILE "__496_src.ktm"

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

String gOut;

void show(i64 k)
{
    switch (k) {
        case 1:
            i64 x = 1;
            gOut.appendFormat("a%d ", x);
            fallthrough;
        case 2:
            i64 x = 2;
            defer gOut.appendFormat("(deferred %d) ", x);
            gOut.appendFormat("b%d ", x);
        case 3:
        case 4:
            i64 y = k;
            switch (y) {
                case 3:
                    i64 z = 30;
                    gOut.appendFormat("c%d ", z);
                default:
                    gOut.append("d ");
            }
        case 5: {
            i64 w = 5;
            gOut.appendFormat("e%d ", w);
        }
        default:
            String s = str.format("f%d ", k);
            defer s.free();
            gOut.append(s);
    }
    gOut.append("| ");
}

u8 *ktmOut(u8 *src) {
    fs.writeAll(SRC_FILE, cstr.view(src));
    u8 *out = proc.shellOutput("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                               "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                               "-S -o /dev/null " SRC_FILE " 2>&1");
    fs.remove(SRC_FILE);
    return out;
}

i32 main() {
    printf("Test - declarations in case arms: ");
    for (i64 i = 1; i <= 6; i++) {
        show(i);
    }
    check(gOut.equals("a1 b2 (deferred 2) | b2 (deferred 2) | c30 | d | e5 | f6 | "),
          "arms with declarations, defer, a nested switch");
    if (!gOut.equals("a1 b2 (deferred 2) | b2 (deferred 2) | c30 | d | e5 | f6 | ")) {
        printf("  got: %s\n", gOut);
    }
    gOut.free();

    u8 *out = ktmOut("void main()\n{\n  switch (1) {\n    case 1:\n      i64 x = 1;\n"
                     "      printf(\"%d\", x);\n    case 2:\n      printf(\"%d\", x);\n  }\n}\n");
    check(out && libc.strstr(out, "error: Variable or function `x` has not been defined"),
          "an arm's variable is not in the next arm");
    mem.free(out);
    out = ktmOut("void main()\n{\n  switch (1) {\n    case 1:\n      i64 x = 1;\n"
                 "      fallthrough;\n      x++;\n    case 2:\n      printf(\"2\");\n  }\n}\n");
    check(out && libc.strstr(out, "error: `fallthrough` must be the last statement of its case"),
          "fallthrough is still last");
    mem.free(out);

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
