import test;

import fs, libc, mem, proc, str;

/* Array types were named with their size in bytes (`i64 arr[2]` was
 * `i64[16]`, `i64 g[2][3]` was `i64[24][48]`), and a string literal's type
 * counted its escapes as written (`"a\tb"` was `u8[5]`). Types now show
 * the element counts as declared, and a string the bytes it holds. */

#define AT_TEST_FILE "__171_array_type_names.ktm"
#define AT_TEST_OUT  "__171_array_type_names.out"

struct P {
    i64 x, y;
};

void cleanupAtTest() {
    fs.remove(AT_TEST_FILE);
    fs.remove(AT_TEST_OUT);
    fs.remove("./a.out");
}

bool outputHas(u8 *src, u8 *expected) {
    u8 *out;
    bool ok = false;
    cleanupAtTest();
    if (fs.writeAll(AT_TEST_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " AT_TEST_FILE " > " AT_TEST_OUT " 2>&1");
        out = fs.readAll(AT_TEST_OUT);
        if (out) {
            ok = libc.strstr(out, expected) != null;
            if (!ok) { printf("%s\n", out); }
            mem.free(out);
        }
    }
    cleanupAtTest();
    return ok;
}

i32 main() {
    printf("Test - array type names: ");
    i64 correct = 0, tests = 0;

    i64 arr[2];
    i64 g[2][3];
    P ps[4];
    u8 buf[10];
    f64 fs[3][1][2];

    tests++; if (!str.cmp(typeof(arr), "i64[2]")) { correct++; }
    tests++; if (!str.cmp(typeof(g), "i64[2][3]")) { correct++; }
    tests++; if (!str.cmp(typeof(g[1]), "i64[3]")) { correct++; }
    tests++; if (!str.cmp(typeof(ps), "P[4]")) { correct++; }
    tests++; if (!str.cmp(typeof(buf), "u8[10]")) { correct++; }
    tests++; if (!str.cmp(typeof(fs), "f64[3][1][2]")) { correct++; }
    tests++; if (sizeof(arr) == 16 && sizeof(g) == 48 && sizeof(g[1]) == 24) { correct++; }

    /* String literals: the bytes they hold, NUL included */
    tests++; if (!str.cmp(typeof("abc"), "u8[4]")) { correct++; }
    tests++; if (!str.cmp(typeof("a\tb\n"), "u8[5]")) { correct++; }
    tests++; if (!str.cmp(typeof("\\\""), "u8[3]")) { correct++; }
    tests++; if (!str.cmp(typeof(""), "u8[1]")) { correct++; }
    tests++; if (sizeof("a\tb\n") == 5) { correct++; }
    /* The type agrees with sizeof for \x escapes too */
    u8 *hexType = str.format("u8[%d]", sizeof("\x42\tq"));
    tests++; if (!str.cmp(typeof("\x42\tq"), hexType)) { correct++; }
    mem.free(hexType);

    /* Diagnostics */
    tests++;
    if (outputHas("void main()\n{\n  i64 arr[2];\n  f64 f = arr;\n}\n",
                                "'i64[2]' is not assignable to type 'f64'")) { correct++; }
    tests++;
    if (outputHas("void main()\n{\n  i64 g[2][3];\n  f64 f = g;\n}\n",
                                "'i64[2][3]' is not assignable to type 'f64'")) { correct++; }
    tests++;
    if (outputHas("void main()\n{\n  f64 f = 1.0;\n  f = f + \"a\\tb\";\n}\n",
                                "`f64 + u8[4]` is invalid")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
