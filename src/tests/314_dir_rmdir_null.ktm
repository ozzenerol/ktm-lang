import cstr, fs, test;

/* fs.removeDir(null, REMOVE_RECURSE) passed null to opendir() and
 * segfaulted. It returns -1 now, as do removeDir and remove of null.
 * (The test also covered Dir, the TempleOS-style listing, which left the
 * standard library: decision 9 of the stdlib map.) */

i32 main() {
    i64 correct = 0, tests = 3;
    printf("Test - removeDir and remove with null: ");

    if (!fs.removeDir(cstr.view(null), fs.REMOVE_RECURSE)) { correct++; }
    if (!fs.removeDir(cstr.view(null)) && !fs.removeDir(cstr.view(null), fs.REMOVE_RECURSE)) { correct++; }
    if (!fs.remove(cstr.view(null))) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
