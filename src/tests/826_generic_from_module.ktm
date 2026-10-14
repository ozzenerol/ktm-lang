import cstr, fs, proc, str, test;

/* A library generic made from inside another module: h.decode<T> calls
 * g.decode<T> (the same name, another module), used by module m with its
 * own type (m.In). The instance names have a dot in their type argument
 * (decode<m.In>), which made them look qualified already: both were
 * declared flat, so g.decode<m.In> found h's and h called itself. Also
 * http.decode<T> (which calls json.decode<T>) from a module, the same
 * from a project's package directory, and "made here" naming the file
 * of the use. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

String gDir;

String sh(u8 *cmd) {
    return proc.shellOutput("K=\"$(pwd)/../../ktm\"; I=\"${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR}\"; "
                            "cd %s && { %s; } 2>&1; echo rc=$?", gDir, cmd);
}

bool has(u8 *cmd, u8 *want, u8 *what) {
    String out = sh(cmd);
    bool ok = str.contains(out[0:], cstr.view(want));
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", out.len, out.ptr); }
    out.free();
    return ok;
}

void put(u8 *path, u8 *text) {
    sh(str.format("mkdir -p $(dirname %s)", path).cstr()).free();
    fs.writeAll(str.format("%s/%s", gDir, path)[0:], cstr.view(text));
}

i32 main() {
    printf("Test - a generic made from inside a module: ");
    String out = proc.shellOutput("mktemp -d");
    gDir = str.from(str.trimEnd(out[0:]));
    out.free();

    /* same-name generics in two modules, made from a third */
    put("a/g/g.ktm", "module g;\npub T decode<T>(i64 n) { T x; x.n = n * 10; return x; }\n");
    put("a/h/h.ktm", "module h;\nimport g;\npub T decode<T>(i64 n) { return g.decode<T>(n + 1); }\n");
    put("a/m/m.ktm",
        "module m;\nimport h;\npub struct In { i64 n; };\n"
        "pub i64 handle(i64 v) { In x = h.decode<In>(v); return x.n; }\n");
    put("a/main.ktm",
        "import m, h;\nstruct In { i64 n; };\n"
        "i32 main() { In y = h.decode<In>(1); printf(\"%d %d\\n\", m.handle(7), y.n); return 0; }\n");
    has("cd a && $K -run $I main.ktm", "80 20\nrc=0", "h.decode -> g.decode from module m (AOT)");
    has("cd a && $K -jit $I main.ktm", "80 20\nrc=0", "the same with -jit");

    /* http.decode<T> -> json.decode<T>, from a module */
    put("b/m/m.ktm",
        "module m;\nimport json, net.http;\n@json pub struct In { i64 n; };\n"
        "pub i64 handle(http.Request *r) { In x = http.decode<In>(r); return x.n; }\n");
    put("b/main.ktm",
        "import m, net.http;\n"
        "i32 main() {\n"
        "    http.Request r = http.testRequest(\"POST\", \"/\", \"{\\\"n\\\": 7}\");\n"
        "    printf(\"%d\\n\", m.handle(&r));\n"
        "    r.free();\n"
        "    return 0;\n"
        "}\n");
    has("cd b && $K -run $I main.ktm", "7\nrc=0", "http.decode<m.In> from a module");
    has("cd b && $K -jit $I main.ktm", "7\nrc=0", "the same with -jit");

    /* from a package directory of a ktm.mod project */
    put("c/ktm.mod", "module example.com/r\n");
    put("c/g/g.ktm", "module g;\npub T decode<T>(i64 n) { T x; x.n = n * 10; return x; }\n");
    put("c/h/h.ktm",
        "module h;\nimport \"example.com/r/g\";\npub T decode<T>(i64 n) { return g.decode<T>(n + 1); }\n");
    put("c/lib/m/m.ktm",
        "module m;\nimport \"example.com/r/h\";\npub struct In { i64 n; };\n"
        "pub i64 handle(i64 v) { In x = h.decode<In>(v); return x.n; }\n");
    put("c/main.ktm",
        "import \"example.com/r/lib/m\";\n"
        "i32 main() { printf(\"%d\\n\", m.handle(2)); return 0; }\n");
    has("cd c && $K run $I .", "30\nrc=0", "from a package directory");

    /* an error in the instance: "made here" is the use's own file */
    put("d/g/g.ktm", "module g;\npub T decode<T>(i64 n) { T x; x.n = n; return x; }\n");
    put("d/h/h.ktm", "module h;\nimport g;\npub T decode<T>(i64 n) { return g.decode<T>(n); }\n");
    put("d/m/m.ktm",
        "module m;\nimport h;\npub struct In { i64 k; };\n"
        "pub i64 handle(i64 v) { In x = h.decode<In>(v); return x.k; }\n");
    put("d/main.ktm", "import m;\ni32 main() { return m.handle(1); }\n");
    has("cd d && $K -run $I main.ktm", "info: in `decode<m.In>`, made here\n --> ./h/h.ktm:3:",
        "made here: the use's file");

    sh("rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
