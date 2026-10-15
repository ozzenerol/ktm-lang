import cstr, fs, libc, mem, proc, test;

/* A crash report named a package's function by its whole module path
 * (example_com.acme.ktm_thing.thing.Box.get) and a file of a package
 * that ktm.mod `replace`s with a directory by the way there from the
 * program (./../thing/thing.ktm). It now shows thing.Box.get and
 * example.com/acme/ktm-thing/thing.ktm. Compiled and in the JIT,
 * crashes and uncaught errors alike. */

#define DIR "./ktm-841"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) \"$K\" ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
#define ENV "K=\"$(pwd)/../../ktm\"; cd " DIR "/app && "

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

void put(u8 *path, u8 *text) { fs.writeAll(cstr.view(path), cstr.view(text)); }

/* What the program says on stderr, run compiled or in
 * the JIT, with arg as its argument: holds each line of want, in order,
 * and no module path of a package */
void report(bool jit, u8 *arg, u8 *want, u8 *what) {
    u8 *o;
    if (jit) {
        o = proc.shellOutput("%s%s%s", ENV "(" KTM "-jit main.ktm ", arg,
                             " 2>&1 >/dev/null; true) 2>/dev/null").take();
    } else {
        o = proc.shellOutput("%s%s%s", ENV KTM "main.ktm -o app && (./app ", arg,
                             " 2>&1 >/dev/null; true) 2>/dev/null").take();
    }
    bool ok = o != null && libc.strstr(o, "example_com") == null;
    u8 *at = o, *line = want;
    while (ok && *line) {
        u8 *end = line;
        while (*end && *end != '\n') { end++; }
        u8 *found = null, *p;
        for (p = at; *p && !found; p++) {
            if (!cstr.cmpLen(p, line, end - line)) { found = p; }
        }
        if (!found) { ok = false; } else { at = found + (end - line); }
        line = *end ? end + 1 : end;
    }
    check(ok, what);
    if (!ok) { printf("  %s got: %s\n", jit ? "jit" : "aot", o); }
    mem.free(o);
}

#define CRASH_WANT "crash: null pointer dereference\n"                            \
    "  at example.com/acme/ktm-thing/thing.ktm:10 in thing.Box.get\n"            \
    "  called from example.com/acme/ktm-thing/thing.ktm:8 in thing.Box.get\n"    \
    "  called from main.ktm:"

/* the headline names the package's file too (it named the importing
 * file, main.ktm, before v0.22.0) */
#define THROW_WANT "uncaught error 4: no 3 (example.com/acme/ktm-thing/thing.ktm:15)\n" \
    "  at example.com/acme/ktm-thing/thing.ktm:15 in thing.Box.check\n"                  \
    "  called from example.com/me/app/own/own.ktm:8 in own.twice\n"                     \
    "  called from main.ktm:"

i32 main() {
    printf("Test - crash report package names: ");
    proc.shell("rm -rf " DIR " && mkdir -p " DIR "/thing " DIR "/app/own");
    put(DIR "/thing/ktm.mod", "module example.com/acme/ktm-thing\n");
    put(DIR "/thing/thing.ktm",
        "module thing;\n"
        "\n"
        "pub struct Box { i64 *p; };\n"
        "\n"
        "pub i64 Box.get(i64 k)\n"
        "{\n"
        "    if (k > 0) {\n"
        "        return self.get(k - 1) + 1;\n"
        "    }\n"
        "    return *self.p;\n"
        "}\n"
        "\n"
        "pub void Box.check(i64 k)\n"
        "{\n"
        "    if (k == 3) { throw(4, \"no %d\", k); }\n"
        "    printf(\"%d\\n\", k);\n"
        "}\n");
    put(DIR "/app/own/own.ktm",
        "module own;\n"
        "import \"example.com/acme/ktm-thing\";\n"
        "/* recursive, so the call from twice(4) isn't inlined */\n"
        "pub void twice(thing.Box *b, i64 k) {\n"
        "    if (k > 3) {\n"
        "        twice(b, k - 1);\n"
        "    }\n"
        "    b.check(k);\n"
        "}\n");
    put(DIR "/app/ktm.mod", "module example.com/me/app\n"
                            "require example.com/acme/ktm-thing v0.1.0\n"
                            "replace example.com/acme/ktm-thing => ../thing\n");
    put(DIR "/app/main.ktm",
        "import \"example.com/acme/ktm-thing\";\n"
        "import \"example.com/me/app/own\";\n"
        "i32 main(i32 argc, u8 **argv)\n"
        "{\n"
        "    thing.Box b;\n"
        "    if (argc > 1) {\n"
        "        own.twice(&b, 4);\n"
        "    }\n"
        "    printf(\"%d\\n\", b.get(5));\n"
        "    return 0;\n"
        "}\n");
    report(false, "", CRASH_WANT, "crash, compiled");
    report(true, "", CRASH_WANT, "crash, JIT");
    report(false, "x", THROW_WANT, "uncaught error, compiled");
    report(true, "x", THROW_WANT, "uncaught error, JIT");
    proc.shell("rm -rf " DIR);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
