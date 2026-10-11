#include "run_shared.ktm"

import cstr, fs, libc, mem, proc, vec;

void runTest(u8 *filename, i64 idx) {
    /* Use the local build (../../ktm relative to src/tests/) so we
     * always test what's actually been compiled, not whatever older
     * ktm happens to be on PATH (e.g. an installed /usr/local/bin/ktm
     * from a prior version - this trap cost us a session of confused
     * cross-test triage). It compiles from src/tests, so its messages
     * name the file as before; the binary runs in the test's own
     * directory (see RunTests). */
    u8 *bin = cstr.format("%s/b/%d", gRoot, idx);
    u8 *cmd = cstr.format("cd '%s' && '%s' %s '%s' -o '%s'",
                                            gStartDir, gKtm, gKtmFlags, filename, bin);
    auto res = proc.shell(cmd);

    if (res != 0) {
        printf("\033[0;31mFailed to compile\033[0;0m: %s\n",filename);
    } else {
        libc.fflush(null);
        if ((res = proc.shell(bin)) != 0) {
            printf("\033[0;31mFAILED\033[0;0m to run: %s\n",filename);
        }
        fs.remove(bin);
    }
    mem.free(cmd);
    mem.free(bin);
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
