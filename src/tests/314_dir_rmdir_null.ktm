#include "testhelper.ktm"

import fs, mem, proc, str;

/* Dir(null) and RmDir(null, RM_RECURSE) passed null to opendir() and
 * segfaulted. Both return -1 now; Dir also returns -1 (not 0, which is
 * an empty directory) for a path it can't open as a directory.
 * Everything happens in a fresh mkdtemp directory. */

extern "c" u8 *mkdtemp(u8 *template);

i32 main() {
    i64 correct = 0, tests = 7;
    u8 root[64], *f, *cmd;
    printf("Test - Dir and RmDir with null: ");

    if (Dir(null) == -1) { correct++; }
    if (fs.removeDir(null, fs.REMOVE_RECURSE) == -1) { correct++; }
    if (fs.removeDir(null) == -1 && fs.removeDir(null, fs.REMOVE_RECURSE|RM_VERBOSE) == -1) { correct++; }
    if (fs.remove(null) == -1) { correct++; }

    str.copy(root, "./ktm-test-314-XXXXXX");
    if (mkdtemp(root)) {
        f = str.formatTo(null, "%s/f", root);
        fs.writeAll(f, "k\n", 2);
        if (Dir(f) == -1) { correct++; }
        mem.free(f);
        f = str.formatTo(null, "%s/empty", root);
        fs.makeDir(f);
        if (Dir(f) == 0) { correct++; }  /* an empty directory is not an error */
        mem.free(f);
        f = str.formatTo(null, "%s/missing", root);
        if (Dir(f) == -1) { correct++; }
        mem.free(f);
        cmd = str.formatTo(null, "rm -rf '%s'", root);
        proc.shell(cmd);
        mem.free(cmd);
    }

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
