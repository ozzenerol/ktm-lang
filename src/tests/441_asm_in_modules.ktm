#include "testhelper.ktm"

/* A module's asm blocks and `_extern` bindings: a module's file is read
 * once per pass (declarations, then in full), and an asm block was
 * "already defined" the second time, so the stdlib's asm (mem, math,
 * coro, ...) could not be modules. Now the declarations pass learns its
 * functions and only the full pass emits the block (once). A binding is
 * the module's name (`m.twice`), pub or private like any declaration.
 * The fixture is src/tests/modules/asmmod; AOT and JIT. */

#define OUT "./ktm-441-asm.out"
#define BIN "./ktm-441-asm.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/asmmod"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " OUT " " BIN);
  System(cmd);
  U8 *out = FileRead(OUT);
  Bool ok = out && strstr(out, want) != NULL;
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " OUT " " BIN);
  return ok;
}

I32 Main()
{
  "Test - asm in modules: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "10 12\n"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "10 12\n")) correct++;
  /* the block is emitted once */
  tests++;
  if (Output(KTM "-S " FIX "/main.ktm -o " BIN " && grep -c '^_DOUBLE_IT:' " BIN " > " OUT, "1\n"))
    correct++;
  tests++;
  if (Output(KTM "-S " FIX "/priv.ktm -o /dev/null > " OUT " 2>&1",
             "error: `hidden` is private to module m"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
