#include "run_shared.ktm"

import cstr, mem, proc, vec;

void runTest(u8 *filename, i64 idx) {
    /* Use the local build (../../ktm relative to src/tests/) so we
     * always test what's actually been compiled, not whatever older
     * ktm happens to be on PATH (e.g. an installed /usr/local/bin/ktm
     * from a prior version - this trap cost us a session of confused
     * cross-test triage). The program runs in the test's own directory
     * (see RunTests), so the file is given by its absolute path. */
    u8 *path = filename;
    if (path[0] != '/') { path = cstr.format("%s/%s", gStartDir, filename); }
    u8 *cmd = cstr.format("'%s' -jit %s '%s'", gKtm, gKtmFlags, path);
    auto res = proc.shell(cstr.view(cmd));

    if (res != 0) {
        printf("\033[0;31mFailed to compile\033[0;0m: %s\n",filename);
    }
    mem.free(cmd);
}

i32 main(i32 argc, u8 **argv) {
    /* Argv contract:
     *   ./run                     -> host arch, all tests
     *   ./run --target=<triple>   -> cross to <triple>, all tests
     *   ./run <file.ktm>           -> host arch, single file
     *   ./run --target=<triple> <file.ktm>
     *                             -> cross to <triple>, single file
     *
     * Disambiguation is by the --target= prefix so the existing
     * single-file workflow keeps working unchanged. */
    gTarget = "";
    i64 argIdx = 1;
    if (argc >= 2 && cstr.cmpLen(argv[1], "--target=", 9) == 0) {
        gTarget = argv[1] + 9;
        argIdx = 2;
    }

    vec.PtrVec *vecVal;
    if (argc > argIdx) {
        vecVal = vec.ptrNew();
        vecVal.push(cstr.dup(argv[argIdx]));
    } else {
        vecVal = collectTests();
    }

    runTests(vecVal);

    vecVal.release(&mem.free);
    return 0;
}
