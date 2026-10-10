#include "testhelper.ktm"

/* `--target=*-apple-darwin` on a Linux host still wrote the ELF-only
 * `.section .note.GNU-stack` (chosen by the host, not the target), which
 * the Mach-O assembler rejects. Linux targets keep the note, macOS
 * targets don't have it, whatever the host. */

#define CT_FILE "__212_cross.ktm"
#define CT_ASM  "__212_cross.s"
#define CT_OBJ  "__212_cross.o"
#define CT_PROBE "__212_probe.s"
#define CT_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

U0 CleanupCrossTest(U0)
{
  System("rm -f " CT_FILE " " CT_ASM " " CT_OBJ " " CT_PROBE);
}

U8 *ct_source =
"I64 g = 3;
F64 f = 1.5;
U0 Main()
{
  \"%d %f\\n\", g, f;
}
";

/* 1 if the assembly for `target` has the GNU-stack note, 0 if not, -1 if
 * ktm failed */
I64 HasGnuStack(U8 *target)
{
  U8 *cmd = MStrPrint(CT_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} --target=%s -S -o " CT_ASM " " CT_FILE " > /dev/null 2>&1", target);
  I64 res = System(cmd);
  Free(cmd);
  if (res != 0) return -1;
  return System("grep -q 'note.GNU-stack' " CT_ASM) == 0;
}

/* TRUE if clang assembles the output for `target`, or if there is no
 * clang that can assemble for it here (then the check is skipped) */
Bool AssemblesOrSkipped(U8 *target)
{
  U8 *cmd;
  I64 res;
  if (System("command -v clang >/dev/null 2>&1") != 0) return TRUE;
  System("printf '.text\\n' > " CT_PROBE);
  cmd = MStrPrint("clang --target=%s -c " CT_PROBE " -o " CT_OBJ " > /dev/null 2>&1", target);
  res = System(cmd);
  Free(cmd);
  if (res != 0) return TRUE;
  if (HasGnuStack(target) < 0) return FALSE;
  cmd = MStrPrint("clang --target=%s -c " CT_ASM " -o " CT_OBJ " > /dev/null 2>&1", target);
  res = System(cmd);
  Free(cmd);
  return res == 0;
}

U0 Main()
{
  "Test - cross target assembly: ";
  I64 correct = 0, tests = 8;

  CleanupCrossTest;
  FileWrite(CT_FILE, ct_source, StrLen(ct_source), O_CREAT | O_RDWR | O_TRUNC);

  if (HasGnuStack("x86_64-unknown-linux-gnu") == 1) correct++;
  if (HasGnuStack("aarch64-unknown-linux-gnu") == 1) correct++;
  if (HasGnuStack("x86_64-apple-darwin") == 0) correct++;
  if (HasGnuStack("aarch64-apple-darwin") == 0) correct++;

  if (AssemblesOrSkipped("x86_64-apple-darwin")) correct++;
  if (AssemblesOrSkipped("aarch64-apple-darwin")) correct++;
  if (AssemblesOrSkipped("x86_64-unknown-linux-gnu")) correct++;
  if (AssemblesOrSkipped("aarch64-unknown-linux-gnu")) correct++;

  CleanupCrossTest;
  PrintResult(correct, tests);
  "====\n";
}
