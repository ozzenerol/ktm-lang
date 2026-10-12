import cstr, fs, libc, proc, test, vec;
#include "run_shared.ktm"

/* The test runners ran every *.ktm in src/tests, so a helper or a
 * scratch file left next to the tests ran as one. Only NN_name.ktm files
 * are tests: collectTests() in a directory holding both kinds. */

/* run_shared.ktm calls it for each test; nothing runs here */
void runTest(u8 *name, i64 idx) {}

bool listed(vec.Vec<u8 *> *v, u8 *name) {
    for (auto got : v) {
        if (!cstr.cmp(got, name)) { return true; }
    }
    return false;
}

i32 main() {
    printf("Test - only NN_name.ktm files are tests: ");
    i64 correct = 0, tests = 0;
    u8 *dir = "__494_dir";
    proc.shell("rm -rf __494_dir");
    libc.mkdir(dir, 0x1ED);
    u8 *files[] = { "1_a.ktm", "12_b_c.ktm", "helper.ktm", "run.ktm", "x1_y.ktm",
                    "12.ktm", "7_.ktm", "4_d.ktmx", "5_e.KTM", "8a_f.ktm" };
    for (u8 *f : files) {
        fs.writeAll(cstr.view(cstr.format("%s/%s", dir, f)), cstr.view("x"));
    }
    libc.mkdir("__494_dir/6_dir.ktm", 0x1ED);

    libc.chdir(dir);
    vec.Vec<u8 *> got = collectTests();
    libc.chdir("..");
    proc.shell("rm -rf __494_dir");

    tests++;
    if (got.len == 2 && listed(&got, "1_a.ktm") && listed(&got, "12_b_c.ktm")) { correct++; }
    else {
        printf("\n  collected %d:", got.len);
        for (auto n : got) { printf(" %s", n); }
        printf("\n");
    }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
