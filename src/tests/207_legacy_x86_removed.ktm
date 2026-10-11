import test;

import fs, libc, mem, proc, str;

/* The legacy AST-based x86 backend was removed: --use-legacy-x86 must
 * fail with a clear message, not compile with a broken code generator. */

#define RM_SRC "__207_legacy.ktm"
#define RM_BIN "./__207_legacy"
#define RM_OUT "__207_legacy.out"

void cleanup() {
    fs.remove(RM_SRC);
    fs.remove(RM_BIN);
    fs.remove(RM_OUT);
}

u8 *gProgram = "void main() { printf(\"hi\\n\"); }\n";

i32 main() {
    i64 correct = 0, tests = 3;
    printf("Test - --use-legacy-x86 is rejected: ");
    u8 *out = null;
    cleanup();
    if (fs.writeAll(RM_SRC, gProgram, str.len(gProgram), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC)) {
        if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} --use-legacy-x86 "
                             RM_SRC " -o " RM_BIN " > " RM_OUT " 2>&1") != 0) { correct++; }
        out = fs.readAll(RM_OUT);
        if (out && libc.strstr(out, "--use-legacy-x86 was removed")) { correct++; }
        else if (out) { printf("\n%s", out); }
        /* the same program still builds with the default backend */
        if (proc.shell("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                             RM_SRC " -o " RM_BIN " > " RM_OUT " 2>&1") == 0) { correct++; }
    }
    if (out) { mem.free(out); }
    cleanup();
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
