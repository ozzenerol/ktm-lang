import cstr, fs, libc, mem, proc, test;

/* Modules (M1): the errors, each a fixture under src/tests/modules/errors.
 * A name not marked pub is private to its module (functions, types); a
 * module can't use the main program's names (`helper` is unknown inside
 * geo, reported in geo's file); a missing module, an import cycle, a file
 * whose `module` line doesn't match its directory or that has none, a
 * module found twice (here: next to the program and in KTM_PATH), a
 * declaration hiding an import prefix and `module` in the main program
 * are errors; an import never used is a warning. */

#define ERR_OUT "./ktm-422-modules.out"
#define FIXTURES "${KTM_TEST_SRC:-.}/modules/errors/"

i64 count(u8 *s, u8 *what) {
    i64 n = 0;
    while (s && (s = libc.strstr(s, what))) {
        n++;
        s++;
    }
    return n;
}

/* Compiling fixture `name` reports `errors` errors, `warnings` warnings,
 * and `want` */
bool reports(u8 *name, u8 *env, i64 errors, i64 warnings, u8 *want) {
    u8 *cmd = cstr.format("%s $(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                                            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                                            "-S " FIXTURES "%s/main.ktm -o /dev/null > " ERR_OUT " 2>&1",
                                            env, name);
    proc.shell("rm -f " ERR_OUT);
    proc.shell(cmd);
    u8 *out = fs.readAll(ERR_OUT).take();
    bool ok = out && count(out, "error:") == errors &&
                        count(out, "warning:") == warnings && libc.strstr(out, want);
    if (!ok) { printf("\n%s\n----\n%s\n", name, out); }
    mem.free(out);
    mem.free(cmd);
    proc.shell("rm -f " ERR_OUT);
    return ok;
}

i32 main() {
    printf("Test - modules: errors: ");
    i64 correct = 0, tests = 0;

    tests++; if (reports("private_fn", "", 1, 1, "`square` is private to module geo")) { correct++; }
    tests++; if (reports("private_type", "", 1, 0, "`Hidden` is private to module geo")) { correct++; }
    tests++; if (reports("main_name", "", 1, 1, "`helper` has not been defined")) { correct++; }
    tests++; if (reports("main_name", "", 1, 1, "main_name/geo/geo.ktm:3:30")) { correct++; }
    tests++; if (reports("missing", "", 1, 0, "module nothere not found")) { correct++; }
    tests++; if (reports("cycle", "", 1, 0, "circular import: a -> b -> a")) { correct++; }
    tests++;
    if (reports("wrong_name", "", 1, 0,
                            "this file is in module geo, so it starts with `module geo;`, not `module geom;`")) {
        correct++;
                            }
    tests++;
    if (reports("no_module_line", "", 1, 0, "every file of module geo starts with `module geo;`")) {
        correct++;
    }
    tests++;
    if (reports("twice", "KTM_PATH=" FIXTURES "twice/other", 1, 0, "module dup is found twice")) {
        correct++;
    }
    tests++; if (reports("hides", "", 1, 1, "`geo` hides the module imported as geo")) { correct++; }
    tests++; if (reports("unused", "", 0, 1, "module geo is imported but not used")) { correct++; }
    tests++;
    if (reports("module_in_main", "", 1, 0, "`module foo;` in a file that isn't part of a module")) {
        correct++;
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
