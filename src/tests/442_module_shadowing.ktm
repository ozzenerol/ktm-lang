#include "testhelper.ktm"

/* Go's rule for an import prefix: a local or a parameter named like one
 * (`u8 *str`, `i64 geo`) shadows the module for its scope instead of
 * being an error, and `geo.name` inside that scope is the error "`geo`
 * is a local i64, it hides the module geo". Outside the scope the module
 * is there again, and in the local's own initialiser (its scope starts
 * after its declaration). A file-scope name like a prefix is still an error
 * (test 422). The fixture is src/tests/modules/shadow; AOT and JIT. */

#define OUT "./ktm-442-shadow.out"
#define BIN "./ktm-442-shadow.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/shadow"
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
  "Test - a local may shadow an import prefix: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "2 8 4\n"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "2 8 4\n")) correct++;
  /* the local's scope starts after its declaration: its initialiser
   * still sees the module (`i64 geo = geo.one() + 1;`) */
  tests++;
  if (Output(KTM FIX "/init.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "2\n"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIX "/init.ktm > " OUT " 2>&1", "2\n")) correct++;
  tests++;
  if (Output(KTM "-S " FIX "/local.ktm -o /dev/null > " OUT " 2>&1",
             "error: `geo` is a local u8 *, it hides the module geo"))
    correct++;
  tests++;
  if (Output(KTM "-S " FIX "/param.ktm -o /dev/null > " OUT " 2>&1",
             "error: `geo` is a local i64, it hides the module geo"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
