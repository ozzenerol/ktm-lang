import cstr, flag, fs, mem, proc, str, test, vec;

/* flag (v0.20.0): --name=value, --name value, -n value, -nvalue, -abc,
 * Go's -name, booleans with and without a value, "--", flags and
 * arguments mixed, subcommands, the help text, the mistakes parse refuses,
 * and parseOrExit's exit codes (a program of the test's own). */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

/* argv from words split on spaces (views into line, which must live on) */
vec.Vec<u8 *> gArgv;
u8 **argv(u8 *line) {
    gArgv.free();
    gArgv.push("prog");
    u8 *p = line;
    while (*p) {
        while (*p == ' ') { *p = 0; p++; }
        if (!*p) { break; }
        gArgv.push(p);
        while (*p && *p != ' ') { p++; }
    }
    return gArgv.data;
}

i32 argc() { return (i32)gArgv.len; }

struct Opts {
    flag.Set *fs;
    i64 *port;
    bool *verbose, *quiet, *all;
    f64 *ratio;
    u8[] *db;
};

Opts make() {
    Opts o;
    o.fs = flag.new("prog", "does things");
    o.port = o.fs.integer("port", 8080, "the port", 'p');
    o.verbose = o.fs.boolean("verbose", false, "say more", 'v');
    o.quiet = o.fs.boolean("quiet", false, "say less", 'q');
    o.all = o.fs.boolean("all", true, "everything", 'a');
    o.ratio = o.fs.number("ratio", 0.5, "a ratio");
    o.db = o.fs.string("db", "pg://x", "the database", 'd');
    return o;
}

/* the last parse's set and line, freed by the next */
flag.Set *gPrev;
u8 *gLine;

bool parses(u8 *line, Opts *o) {
    if (gPrev) { gPrev.free(); }
    mem.free(gLine);
    gLine = cstr.dup(line);
    *o = make();
    gPrev = o.fs;
    u8 **av = argv(gLine);
    return o.fs.parse(argc(), av);
}

i32 main() {
    printf("Test - flag: ");
    Opts o;
    check(parses("", &o) && *o.port == 8080 && !*o.verbose && *o.all && *o.ratio == 0.5 &&
          str.equals(*o.db, "pg://x") && o.fs.nargs() == 0 && !o.fs.given("port"), "defaults");
    check(parses("--port=90 --verbose --ratio 1.25 --db pg://y a b", &o) && *o.port == 90 && *o.verbose &&
          *o.ratio == 1.25 && str.equals(*o.db, "pg://y") && o.fs.nargs() == 2 && str.equals(o.fs.arg(1), "b") &&
          o.fs.given("port") && !o.fs.given("quiet") && str.equals(o.fs.arg(5), ""), "long forms");
    check(parses("-p 70 -dpg://z -vq file", &o) && *o.port == 70 && str.equals(*o.db, "pg://z") && *o.verbose &&
          *o.quiet && str.equals(o.fs.arg(0), "file"), "short forms, -abc");
    check(parses("one -port 5 two --all=false three", &o) && *o.port == 5 && !*o.all && o.fs.nargs() == 3 &&
          str.equals(o.fs.arg(2), "three"), "Go's -name, a bool's =false, flags between arguments");
    check(parses("-v -- --port=1 -q", &o) && *o.verbose && !*o.quiet && *o.port == 8080 && o.fs.nargs() == 2 &&
          str.equals(o.fs.arg(0), "--port=1"), "-- ends the flags");
    check(parses("- x", &o) && o.fs.nargs() == 2 && str.equals(o.fs.arg(0), "-"), "- alone is an argument");

    check(!parses("--nope", &o) && str.equals(o.fs.error(), "unknown flag --nope"), "an unknown flag");
    check(!parses("--port=x8", &o) && str.equals(o.fs.error(), "--port: `x8` isn't a whole number"), "a bad number");
    check(!parses("-p", &o) && str.equals(o.fs.error(), "-p needs a value"), "a missing value");
    check(!parses("--ratio=abc", &o) && !parses("--verbose=maybe", &o) && !parses("-vx", &o) &&
          str.equals(o.fs.error(), "unknown flag -x in -vx"), "more mistakes");
    check(!parses("-h", &o) && o.fs.helpAsked() && !parses("x --help", &o) && o.fs.helpAsked(), "-h and --help");

    /* the help text */
    parses("", &o);
    String u = o.fs.usage();
    check(str.equals(u[0:],
        "usage: prog [flags] [arguments]\n"
        "\ndoes things\n"
        "\nflags:\n"
        "  -p, --port int      the port (default 8080)\n"
        "  -v, --verbose       say more\n"
        "  -q, --quiet         say less\n"
        "  -a, --all           everything (default true)\n"
        "      --ratio number  a ratio (default 0.5)\n"
        "  -d, --db text       the database (default \"pg://x\")\n"
        "  -h, --help          show this help\n"), "usage()");
    if (!gCorrect) { printf("%.*s", u.len, u.ptr); }
    u.free();

    /* subcommands */
    flag.Set *app = flag.new("app");
    bool *dry = app.boolean("dry-run", false, "change nothing", 'n');
    flag.Set *serve = app.command("serve", "serves the API");
    i64 *port = serve.integer("port", 80, "the port");
    flag.Set *migrate = app.command("migrate", "runs migrations");
    u8 *line = cstr.dup("-n serve --port 81 extra");
    argv(line);
    defer mem.free(line);
    check(app.parse(argc(), gArgv.data) && *dry &&
          app.chosen() == serve && *port == 81 && serve.nargs() == 1 && str.equals(serve.arg(0), "extra") &&
          app.nargs() == 0, "a subcommand with its own flags");
    check(migrate.nargs() == 0, "the other subcommand untouched");
    String su = serve.usage();
    check(str.startsWith(su[0:], "usage: app serve [flags] [arguments]\n"), "a subcommand's usage() names the program");
    su.free();
    app.free();
    app = flag.new("app");
    app.command("serve", "serves");
    serve = app.chosen();
    u8 *line2 = cstr.dup("srv");
    argv(line2);
    defer mem.free(line2);
    check(!app.parse(argc(), gArgv.data) && str.equals(app.error(), "unknown command `srv`") && !serve,
          "an unknown subcommand");
    String cu = app.usage();
    check(str.find(cu[0:], "commands:\n  serve  serves\n") >= 0 && str.find(cu[0:], "<command>") >= 0, "commands in usage()");
    cu.free();
    app.free();

    /* parseOrExit: help exits 0 to stdout, a mistake 2 to stderr */
    fs.writeAll("__800_prog.ktm",
        "import flag;\n"
        "i32 main(i32 argc, u8 **argv) {\n"
        "    flag.Set *fs = flag.new(\"tool\");\n"
        "    i64 *n = fs.integer(\"n\", 1, \"how many\");\n"
        "    fs.parseOrExit(argc, argv);\n"
        "    printf(\"n=%d\\n\", *n);\n"
        "    fs.free();\n"
        "    return 0;\n"
        "}\n");
    String w = proc.shellOutput("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -o __800_prog __800_prog.ktm 2>&1 && "
                                "./__800_prog --n 3; echo rc=$?; ./__800_prog -h | head -1; echo rc=$?; "
                                "./__800_prog --n=q 2>&1 >/dev/null | head -1; ./__800_prog --n=q >/dev/null 2>&1; echo rc=$?");
    u8[] want = "n=3\nrc=0\nusage: tool [flags] [arguments]\nrc=0\ntool: --n: `q` isn't a whole number\nrc=2";
    check(str.equals(str.trimEnd(w[0:]), want), "parseOrExit");
    if (!str.equals(str.trimEnd(w[0:]), want)) {
        printf("  got: %.*s\n", w.len, w.ptr);
    }
    w.free();
    fs.remove("__800_prog.ktm");
    fs.remove("__800_prog");
    gArgv.free();
    gPrev.free();
    mem.free(gLine);
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
