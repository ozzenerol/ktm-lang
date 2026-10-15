import cstr, fs, libc, mem, proc, str, test;

/* v0.22.0: a package from the module cache is compiled once into
 * <cache>/.prebuilt/<key> and linked from there by later builds. Here a
 * program uses package a, which uses package b; both have state in a
 * global, which must exist once (a's library doesn't hold a copy of b).
 * The key changes with -D; KTM_PREBUILD=0 and the JIT build from source;
 * a cache where nothing can be written falls back to source. b's generics,
 * made in the program, call b's static method and function (v0.23.1: they
 * were left local to b's library, and the program didn't link). */

#define DIR "./ktm-837"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; export KTM_CACHE=\"$(pwd)/" DIR "/cache\"; cd " DIR "/app && "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

/* the output of a shell command run in the program's directory */
u8 *run(u8 *cmd) {
    return proc.shellOutput("%s%s 2>&1", ENV, cmd).take();
}

bool has(u8 *out, u8 *what) { return out != null && libc.strstr(out, what) != null; }

/* how many prebuilt libraries the cache has */
i64 prebuilt() {
    u8 *o = proc.shellOutput("ls -d " DIR "/cache/.prebuilt/*/.ktmlib 2>/dev/null | wc -l").take();
    i64 n = -1;
    if (o && !str.toInt(str.trim(cstr.view(o)), &n)) { n = -1; }
    mem.free(o);
    return n;
}

#define WANT "a=1 b=2 b.count=3 gen=11,15"

i32 main() {
    printf("Test - package prebuild: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/app " DIR "/cache/example.com/b@v1.0.0 "
               DIR "/cache/example.com/a@v1.0.0");
    put(DIR "/cache/example.com/b@v1.0.0/ktm.mod", "module example.com/b\n");
    put(DIR "/cache/example.com/b@v1.0.0/b.ktm",
        "module b;\n"
        "i64 gCount = 0;\n"
        "pub i64 bump() { gCount++; return gCount; }\n"
        "pub i64 count() { return gCount; }\n"
        /* generics the program instantiates, calling b's static (local)
         * method and function: they must be in b's library all the same */
        "pub struct Box { i64 v; };\n"
        "static i64 Box.twice() { return self.v * 2; }\n"
        "static i64 plus(u8[] *s, u8[] x) { return s.len + x.len; }\n"
        "pub i64 viaGen<T>(Box *bx, T x) { return bx.twice() + (i64)x; }\n"
        "pub i64 Box.via<T>(T x) { u8[] s = \"c\"; return plus(&s, \"ab\") + self.twice() + (i64)x; }\n");
    put(DIR "/cache/example.com/a@v1.0.0/ktm.mod",
        "module example.com/a\nrequire example.com/b v1.0.0\n");
    put(DIR "/cache/example.com/a@v1.0.0/a.ktm",
        "module a;\n"
        "import \"example.com/b\";\n"
        "pub i64 viaB() { return b.bump(); }\n");
    put(DIR "/app/ktm.mod",
        "module example.com/app\n"
        "require example.com/a v1.0.0\n"
        "require example.com/b v1.0.0\n");
    put(DIR "/app/main.ktm",
        "import \"example.com/a\";\n"
        "import \"example.com/b\";\n"
        "i32 main() {\n"
        "    i64 x = a.viaB();\n"
        "    i64 y = b.bump();\n"
        "    b.bump();\n"
        "    b.Box bx;\n"
        "    bx.v = 5;\n"
        "    printf(\"a=%d b=%d b.count=%d gen=%d,%d\\n\", x, y, b.count(), b.viaGen<i64>(&bx, 1), bx.via<i64>(2));\n"
        "    return 0;\n"
        "}\n");

    u8 *o = run(KTM "main.ktm -o app && ./app");
    check(has(o, WANT), "built (a and b prebuilt) and run: one copy of b's global");
    if (!has(o, WANT)) { printf("  got: %s\n", o); }
    mem.free(o);
    check(prebuilt() == 2, "a and b each have a library in the cache");

    o = run(KTM "main.ktm -o app && ./app");
    check(has(o, WANT) && prebuilt() == 2, "built again from the cached libraries");
    mem.free(o);

    o = run(KTM "-DFAST main.ktm -o app && ./app");
    check(has(o, WANT) && prebuilt() == 4, "-D makes libraries of its own");
    mem.free(o);

    proc.shell("rm -rf " DIR "/cache/.prebuilt");
    o = run("KTM_PREBUILD=0 " KTM "main.ktm -o app && ./app");
    check(has(o, WANT) && prebuilt() == 0, "KTM_PREBUILD=0: from source, nothing cached");
    mem.free(o);

    o = run(KTM "-jit main.ktm");
    check(has(o, WANT) && prebuilt() == 0, "the JIT: from source, nothing cached");
    mem.free(o);

    /* .prebuilt a file: nothing can be made there */
    put(DIR "/cache/.prebuilt", "not a directory\n");
    o = run(KTM "main.ktm -o app && ./app");
    check(has(o, WANT), "a cache it can't write to: from source");
    mem.free(o);

    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
