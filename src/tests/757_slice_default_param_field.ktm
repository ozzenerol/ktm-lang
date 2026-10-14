import str, test;

/* A field of a slice parameter with a default value (`b.len` of
 * `u8[] b = ""`) stopped the compiler with "Slice AST_CLASS_REF:
 * unsupported cls kind AST_DEFAULT_PARAM", when the call left it out and
 * when it didn't. Reading .len and .ptr, reslicing it, and a method's
 * default slice. Found writing net.http (ktm-lang-ce). */

i64 f(u8[] a, u8[] b = "") { return a.len * 10 + b.len; }

i64 first(u8[] b = "hey") { return b.len * 1000 + b.ptr[0]; }

i64 shrink(u8[] b = "abcdef") {
    b = b[0:2];
    return b.len + (str.equals(b, "ab") ? 100 : 0);
}

struct Box { i64 n; };

i64 Box.add(u8[] extra = "xy") { return self.n + extra.len; }

i32 main() {
    printf("Test - a field of a slice parameter with a default: ");
    i64 correct = 0, tests = 0;
    tests++; if (f("ab") == 20) { correct++; }
    tests++; if (f("ab", "xyz") == 23) { correct++; }
    tests++; if (first() == 3000 + 'h') { correct++; }
    tests++; if (first("a") == 1000 + 'a') { correct++; }
    tests++; if (shrink() == 102) { correct++; }
    tests++; if (shrink("abq") == 102) { correct++; }
    Box b = {5};
    tests++; if (b.add() == 7) { correct++; }
    tests++; if (b.add("") == 5) { correct++; }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
