import fs, libc, mem, proc, str, test;

/* Assigning a `void *` to a function pointer (or a function pointer to a
 * `void *`) warned "'void *' is not assignable to type '<fn> i64'". C
 * compilers accept this as an extension (POSIX dlsym depends on it), so
 * ktm accepts it silently; other pointer types still warn. */

#define VP_TEST_FILE "__168_void_fnptr.ktm"
#define VP_TEST_OUT  "__168_void_fnptr.out"

i64 inc(i64 x) { return x + 1; }
i64 twice(i64 x) { return x * 2; }

struct C {
    fn(i64 x) i64 f;
};

i64 call(fn(i64 x) i64 fn_, i64 v) { return fn_(v); }

void cleanupVpTest() {
    fs.remove(VP_TEST_FILE);
    fs.remove(VP_TEST_OUT);
    fs.remove("./a.out");
}

/* Compiles `body` inside Main; returns the compiler output (or null) */
u8 *compileOutput(u8 *body) {
    u8 *src = str.format("i64 Inc(i64 x) { return x + 1; }\n"
                                            "struct C { i64 (*f)(i64 x); };\n"
                                            "i64 Call(i64 (*fn)(i64 x), i64 v) { return fn(v); }\n"
                                            "void main()\n{\n  void *v = &Inc;\n  i64 (*fp)(i64 x) = &Inc;\n"
                                            "  C c;\n  i64 *ip = null;\n%s\n}\n", body);
    u8 *out = null;
    cleanupVpTest();
    if (fs.writeAll(VP_TEST_FILE, src, str.len(src), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " VP_TEST_FILE " > " VP_TEST_OUT " 2>&1");
        out = fs.readAll(VP_TEST_OUT);
    }
    mem.free(src);
    cleanupVpTest();
    return out;
}

bool silent(u8 *body) {
    u8 *out = compileOutput(body);
    bool ok = out && !libc.strstr(out, "warning") && !libc.strstr(out, "error");
    if (out && !ok) { printf("%s\n", out); }
    mem.free(out);
    return ok;
}

bool warns(u8 *body) {
    u8 *out = compileOutput(body);
    bool ok = out && libc.strstr(out, "Incompatible types") != null;
    if (out && !ok) { printf("%s\n", out); }
    mem.free(out);
    return ok;
}

i32 main() {
    printf("Test - void * and function pointers: ");
    i64 correct = 0, tests = 0;

    /* Behaviour */
    void *v = &inc;
    fn(i64 x) i64 fp = v;
    tests++; if (fp(1) == 2) { correct++; }
    v = &twice;
    fp = v;
    tests++; if (fp(4) == 8) { correct++; }
    C c;
    c.f = v;
    tests++; if (c.f(5) == 10) { correct++; }
    void *w = c.f;
    fp = w;
    tests++; if (fp(3) == 6) { correct++; }
    tests++; if (call(v, 6) == 12) { correct++; }

    /* No diagnostics */
    tests++; if (silent("  i64 (*g)(i64 x) = v;")) { correct++; }
    tests++; if (silent("  fp = v;")) { correct++; }
    tests++; if (silent("  v = fp;")) { correct++; }
    tests++; if (silent("  void *w = fp;")) { correct++; }
    tests++; if (silent("  c.f = v;  v = c.f;")) { correct++; }
    tests++; if (silent("  Call(v, 1);")) { correct++; }

    /* Other pointer types still warn */
    tests++; if (warns("  fp = ip;")) { correct++; }
    tests++; if (warns("  ip = fp;")) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
