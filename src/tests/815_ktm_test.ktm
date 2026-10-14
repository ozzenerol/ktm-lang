import cstr, fs, proc, str, test;

/* ktm test (v0.21.0) and the testing module: *_test.ktm files found in
 * a directory or under ./..., their void testXxx(testing.T *t) run in
 * order with file:line on failures, -run and -v, exit 1 on a failure,
 * AOT and -jit, a module's tests see its private names, *_test.ktm left
 * out of ktm build, and a ktm.mod project with a dependency. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

String gDir;

/* Runs cmd in gDir with the test's ktm */
String sh(u8 *cmd) {
    return proc.shellOutput("K=\"$(pwd)/../../ktm\"; I=\"${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR}\"; "
                            "cd %s && export KTM_CACHE=%s/cache && { %s; } 2>&1; echo rc=$?",
                            gDir, gDir, cmd);
}

bool has(u8 *cmd, u8 *want, u8 *what) {
    String out = sh(cmd);
    bool ok = str.contains(out[0:], cstr.view(want));
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", out.len, out.ptr); }
    out.free();
    return ok;
}

bool hasNot(u8 *cmd, u8 *unwanted, u8 *what) {
    String out = sh(cmd);
    bool ok = !str.contains(out[0:], cstr.view(unwanted));
    check(ok, what);
    if (!ok) { printf("  got: %.*s\n", out.len, out.ptr); }
    out.free();
    return ok;
}

/* gDir/path holds text (its directory made) */
void put(u8 *path, u8 *text) {
    sh(str.format("mkdir -p $(dirname %s)", path).cstr()).free();
    fs.writeAll(str.format("%s/%s", gDir, path)[0:], cstr.view(text));
}

i32 main() {
    printf("Test - ktm test: ");
    String out = proc.shellOutput("mktemp -d");
    gDir = str.from(str.trimEnd(out[0:]));
    out.free();

    /* a module with tests: one fails, one sees a private function */
    put("app/geo/geo.ktm",
        "module geo;\n"
        "pub i64 area(i64 w, i64 h) { return w * h; }\n"
        "pub u8 *kind(i64 w, i64 h) { return w == h ? \"square\" : \"rect\"; }\n"
        "i64 clamp(i64 n) { return n < 0 ? 0 : n; }\n");
    put("app/geo/geo_test.ktm",
        "module geo;\n"
        "import testing;\n"
        "void testArea(testing.T *t) {\n"
        "    t.equal(area(3, 4), 12);\n"
        "}\n"
        "void testKind(testing.T *t) {\n"
        "    t.equal(kind(2, 2), \"square\");\n"
        "    t.equal(kind(2, 3), \"square\");\n"
        "    t.check(area(1, 1) == 2, \"area(1, 1) is %d\", area(1, 1));\n"
        "}\n"
        "void testClamp(testing.T *t) { t.equal(clamp(-5), 0); }\n"
        "/* void testCommented(testing.T *t) { } */\n"
        "void helper(testing.T *t) { }\n"
        "void testerNot(testing.T *t) { t.fail(\"not a test\"); }\n"
        "pub i64 onlyInTests() { return 1; }\n");
    /* the program's own tests: no module line, only pub names */
    put("app/main.ktm",
        "import geo;\n"
        "i32 main() { printf(\"%d\\n\", geo.area(2, 3)); return 0; }\n");
    put("app/main_test.ktm",
        "import geo, testing;\n"
        "void testOutside(testing.T *t) { t.equal(geo.area(2, 3), 6); t.log(\"seen with -v\"); }\n");
    /* fatal, skip, a throw, bools, floats, String */
    put("app/more/more.ktm", "module more;\npub i64 one() { return 1; }\n");
    put("app/more/more_test.ktm",
        "module more;\n"
        "import str, testing;\n"
        "enum E { boom };\n"
        "void testFatal(testing.T *t) { t.fatal(\"stop %d\", 1); t.fail(\"not reached\"); }\n"
        "void testSkip(testing.T *t) { t.skip(\"no database\"); }\n"
        "void testThrow(testing.T *t) { throw(E.boom, \"kaboom\"); }\n"
        "void testTypes(testing.T *t) {\n"
        "    t.equal(one() == 1, false);\n"
        "    t.equal(1.5, 2.5);\n"
        "    String s = str.from(\"ab\");\n"
        "    t.equal(s, \"ac\");\n"
        "    s.free();\n"
        "    u8 *n = null;\n"
        "    t.equal(n, \"x\");\n"
        "    t.equal(\"same\", \"same\");\n"
        "}\n");

    has("cd app && $K test $I geo",
        "--- FAIL: testKind (0.00s)\n"
        "    geo_test.ktm:8: got \"rect\", want \"square\"\n"
        "    geo_test.ktm:9: area(1, 1) is 1\n"
        "FAIL\nFAIL\tgeo\t", "a failing test: its failures with file:line");
    has("cd app && $K test $I geo", "(2 passed, 1 failed)\nrc=1", "the summary, exit 1");
    hasNot("cd app && $K test $I geo", "not a test", "testerNot and helper aren't tests");
    has("cd app && $K test $I -run Clamp geo", "ok  \tgeo\t", "-run picks tests by name");
    has("cd app && $K test $I -run Clamp geo", "(1 passed)\nrc=0", "-run: only that one, exit 0");
    has("cd app && $K test $I -v -run Area geo",
        "=== RUN   testArea\n--- PASS: testArea (0.00s)\nPASS\nok", "-v shows each test");
    has("cd app && $K test $I -v .", "    main_test.ktm:2: seen with -v\n", "-v shows the log; tests next to main.ktm");
    has("cd app && $K test $I -jit -run Kind geo", "geo_test.ktm:8: got \"rect\"", "-jit");
    has("cd app && $K test $I ./...", "ok  \t.\t", "./...: the top directory");
    has("cd app && $K test $I ./...", "FAIL\tgeo\t", "./...: a directory under it");
    has("cd app && $K test $I ./...", "FAIL\tmore\t", "./...: every directory");
    has("cd app && $K test $I more",
        "--- FAIL: testFatal (0.00s)\n    more_test.ktm:4: stop 1\n--- FAIL: testThrow",
        "fatal ends a test");
    has("cd app && $K test $I more", "more_test.ktm:6: threw 0: kaboom", "a throw fails the test");
    has("cd app && $K test $I more",
        "    more_test.ktm:8: got 1, want 0\n"
        "    more_test.ktm:9: got 1.500000, want 2.500000\n"
        "    more_test.ktm:11: got \"ab\", want \"ac\"\n"
        "    more_test.ktm:14: got null, want \"x\"\n", "equal on comparisons, floats, String, null");
    has("cd app && $K test $I more", "(0 passed, 3 failed, 1 skipped)", "skip counts apart");
    has("cd app && $K test $I -v -run Skip more", "--- SKIP: testSkip", "-v shows a skip");
    has("cd app && $K test $I -jit -Memsafe -run Types more", "(0 passed, 1 failed)\nrc=1", "-jit -Memsafe");
    hasNot("cd app && $K test $I -jit -Memsafe -run Types more", "never freed", "-Memsafe: nothing left unfreed");
    /* the build leaves *_test.ktm out */
    has("cd app && $K build $I && ./app", "6\nrc=0", "ktm build doesn't read *_test.ktm");
    put("app2/main.ktm", "import geo;\ni32 main() { return geo.onlyInTests(); }\n");
    sh("cp -R app/geo app2/").free();
    has("cd app2 && $K build $I", "onlyInTests", "a name only *_test.ktm declares isn't there for a build");
    /* mistakes */
    has("cd app && mkdir -p none && $K test $I none", "?   \tnone\t[no test files]\nrc=0", "no test files");
    has("cd app && $K test $I nowhere", "nowhere: not a directory", "not a directory");
    put("bad/bad_test.ktm", "import testing;\nvoid testBad(testing.T *t) { t.equal(nope, 1); }\n");
    has("$K test $I bad", "FAIL\tbad\t[build failed or exited early]\nrc=1", "a test that doesn't compile");

    /* a ktm.mod project with a dependency (a local replace), tests in a
     * nested module that imports it */
    put("color/ktm.mod", "module example.com/lib/color\n");
    put("color/color.ktm", "module color;\npub u8 *red() { return \"red\"; }\n");
    put("proj/main.ktm", "i32 main() { return 0; }\n");
    put("proj/api/v1/v1.ktm",
        "module v1;\nimport \"example.com/lib/color\";\npub u8 *paint() { return color.red(); }\n");
    put("proj/api/v1/v1_test.ktm",
        "module v1;\nimport fs, testing;\nvoid testPaint(testing.T *t) { t.equal(paint(), \"red\"); }\n"
        "void testFiles(testing.T *t) {\n"
        "    String a = fs.readAll(\"testdata/x.txt\");\n"
        "    String b = fs.readAll(\"../../top.txt\");\n"
        "    t.equal(a, \"in testdata\\n\");\n"
        "    t.equal(b, \"at the root\\n\");\n"
        "    a.free();\n"
        "    b.free();\n"
        "}\n");
    /* each directory's tests run in it, wherever ktm test starts */
    put("proj/api/v1/testdata/x.txt", "in testdata\n");
    put("proj/top.txt", "at the root\n");
    sh("cd proj && $K mod init example.com/me/proj && "
       "printf 'require example.com/lib/color v1.0.0\\nreplace example.com/lib/color => ../color\\n' >> ktm.mod").free();
    has("cd proj && $K test $I ./...", "ok  \tapi/v1\t", "a ktm.mod project: its packages");
    has("cd proj/api/v1 && $K test $I -jit", "(2 passed)\nrc=0", "from inside the project");
    has("cd proj && $K test $I -v ./...", "--- PASS: testFiles", "testdata/ and ../ found from the root");
    has("cd proj/api && $K test $I ./...", "ok  \tv1\t", "and from a directory above");

    sh("rm -rf $(pwd)").free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
