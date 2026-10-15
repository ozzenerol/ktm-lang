import cstr, fs, libc, mem, proc, test;

/* A library (`ktm -lib`, and a package prebuilt into the module cache)
 * whose own function calls a prebuilt generic on its own type,
 * json.decode<Pt>: the description of Pt that json.decode passes on was
 * filled in by the program's start, which a library hasn't, so it stayed
 * empty ("1:1: a  can't be JSON"; ktm-jwt v0.2.0's jwt.header()). It is
 * filled in by the library module's _init now; in a tree of modules, by
 * every module's. AOT (the JIT builds packages from source). */

#define DIR "./ktm-864"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 120) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; export KTM_CACHE=\"$(pwd)/" DIR "/cache\"; cd " DIR " && "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

/* the output of a shell command run in DIR */
u8 *run(u8 *cmd) {
    return proc.shellOutput("%s%s 2>&1", ENV, cmd).take();
}

bool has(u8 *out, u8 *what) { return out != null && libc.strstr(out, what) != null; }

/* cmd's output holds want; says what it got when not */
void expect(u8 *cmd, u8 *want, u8 *what) {
    u8 *o = run(cmd);
    check(has(o, want), what);
    if (!has(o, want)) { printf("  got: %s\n", o); }
    mem.free(o);
}

#define GEO "module geo;\n"                                         \
    "import json;\n"                                                \
    "@json pub struct Pt { i64 x; i64 y; };\n"                      \
    "pub i64 sumOf(u8[] text) {\n"                                  \
    "    Pt p = json.decode<Pt>(text);\n"                           \
    "    return p.x + p.y;\n"                                       \
    "}\n"

#define MAIN "import geo;\n"                                        \
    "i32 main() {\n"                                                \
    "    printf(\"sum %d\\n\", geo.sumOf(\"{\\\"x\\\": 2, \\\"y\\\": 3}\"));\n" \
    "    return 0;\n"                                               \
    "}\n"

i32 main() {
    printf("Test - a library's own call of a prebuilt generic: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/one/geo " DIR "/tree/geo " DIR "/app "
               DIR "/cache/example.com/geo@v1.0.0");

    /* ktm -lib geo: one module */
    put(DIR "/one/geo/geo.ktm", GEO);
    put(DIR "/one/main.ktm", MAIN);
    expect(KTM "-lib geo one/geo && grep -x init one/geo/.ktmlib", "init", "-lib of one module: its marker says init");
    expect(KTM "one/main.ktm -o one/app && one/app", "sum 5", "-lib of one module: json.decode<Pt> in it works");

    /* ktm -lib name dir: a tree of modules */
    put(DIR "/tree/geo/geo.ktm", GEO);
    put(DIR "/tree/main.ktm", MAIN);
    expect(KTM "-lib shapes tree && grep -x init tree/geo/.ktmlib", "init", "-lib of a tree: the marker says init");
    expect(KTM "tree/main.ktm -o tree/app && tree/app", "sum 5", "-lib of a tree: json.decode<Pt> in it works");

    /* a package from the module cache, prebuilt */
    put(DIR "/cache/example.com/geo@v1.0.0/ktm.mod", "module example.com/geo\n");
    put(DIR "/cache/example.com/geo@v1.0.0/geo.ktm", GEO);
    put(DIR "/app/ktm.mod", "module example.com/app\nrequire example.com/geo v1.0.0\n");
    put(DIR "/app/main.ktm",
        "import \"example.com/geo\";\n"
        "i32 main() {\n"
        "    printf(\"sum %d\\n\", geo.sumOf(\"{\\\"x\\\": 4, \\\"y\\\": 5}\"));\n"
        "    return 0;\n"
        "}\n");
    expect("cd app && " KTM "main.ktm -o app && ./app && ls ../cache/.prebuilt/*/.ktmlib", "sum 9",
           "a prebuilt package: json.decode<Pt> in it works");
    expect("cat cache/.prebuilt/*/.ktmlib", "init", "and its marker says init");

    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
