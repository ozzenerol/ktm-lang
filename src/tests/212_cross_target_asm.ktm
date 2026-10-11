#include "testhelper.ktm"

import fs, libc, mem, proc, str;

/* `--target=*-apple-darwin` on a Linux host still wrote the ELF-only
 * `.section .note.GNU-stack` (chosen by the host, not the target), which
 * the Mach-O assembler rejects. Linux targets keep the note, macOS
 * targets don't have it, whatever the host. */

#define CT_FILE "__212_cross.ktm"
#define CT_ASM  "__212_cross.s"
#define CT_OBJ  "__212_cross.o"
#define CT_PROBE "__212_probe.s"
#define CT_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

void cleanupCrossTest(void) {
    proc.shell("rm -f " CT_FILE " " CT_ASM " " CT_OBJ " " CT_PROBE);
}

u8 *ctSource =
"i64 g = 3;
f64 f = 1.5;
void main()
{
  printf(\"%d %f\\n\", g, f);
}
";

/* 1 if the assembly for `target` has the GNU-stack note, 0 if not, -1 if
 * ktm failed */
i64 hasGnuStack(u8 *target) {
    u8 *cmd = str.format(CT_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} --target=%s -S -o " CT_ASM " " CT_FILE " > /dev/null 2>&1", target);
    i64 res = proc.shell(cmd);
    mem.free(cmd);
    if (res != 0) { return -1; }
    return proc.shell("grep -q 'note.GNU-stack' " CT_ASM) == 0;
}

/* true if clang assembles the output for `target`, or if there is no
 * clang that can assemble for it here (then the check is skipped) */
bool assemblesOrSkipped(u8 *target) {
    u8 *cmd;
    i64 res;
    if (proc.shell("command -v clang >/dev/null 2>&1") != 0) { return true; }
    proc.shell("printf '.text\\n' > " CT_PROBE);
    cmd = str.format("clang --target=%s -c " CT_PROBE " -o " CT_OBJ " > /dev/null 2>&1", target);
    res = proc.shell(cmd);
    mem.free(cmd);
    if (res != 0) { return true; }
    if (hasGnuStack(target) < 0) { return false; }
    cmd = str.format("clang --target=%s -c " CT_ASM " -o " CT_OBJ " > /dev/null 2>&1", target);
    res = proc.shell(cmd);
    mem.free(cmd);
    return res == 0;
}

void main() {
    printf("Test - cross target assembly: ");
    i64 correct = 0, tests = 8;

    cleanupCrossTest();
    fs.writeAll(CT_FILE, ctSource, str.len(ctSource), libc.O_CREAT | libc.O_RDWR | libc.O_TRUNC);

    if (hasGnuStack("x86_64-unknown-linux-gnu") == 1) { correct++; }
    if (hasGnuStack("aarch64-unknown-linux-gnu") == 1) { correct++; }
    if (hasGnuStack("x86_64-apple-darwin") == 0) { correct++; }
    if (hasGnuStack("aarch64-apple-darwin") == 0) { correct++; }

    if (assemblesOrSkipped("x86_64-apple-darwin")) { correct++; }
    if (assemblesOrSkipped("aarch64-apple-darwin")) { correct++; }
    if (assemblesOrSkipped("x86_64-unknown-linux-gnu")) { correct++; }
    if (assemblesOrSkipped("aarch64-unknown-linux-gnu")) { correct++; }

    cleanupCrossTest();
    PrintResult(correct, tests);
    printf("====\n");
}
