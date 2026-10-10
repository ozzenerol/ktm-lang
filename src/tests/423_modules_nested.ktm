#include "testhelper.ktm"

/* Modules (M1): nested modules and aliases. `import net.http;` loads
 * the directory net/http (its files start with `module http;`) and its
 * names are `http.name`; `import h = net.http;` makes them `h.name` as
 * well. A nested module imports its parent like any other module, and a
 * type of one module is used through another's functions. The symbols
 * stay apart: `_K3net4port`, `_K3net4http3get`. The fixture is
 * src/tests/modules/nested; AOT and JIT. */

#define MOD_OUT "./ktm-423-modules.out"
#define MOD_BIN "./ktm-423-modules.bin"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/nested/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "3 80\n82 84 80\n84\n";

Bool Output(U8 *cmd)
{
  System("rm -f " MOD_OUT " " MOD_BIN);
  System(cmd);
  U8 *out = FileRead(MOD_OUT);
  Bool ok = out && !StrCmp(out, g_want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " MOD_OUT " " MOD_BIN);
  return ok;
}

I32 Main()
{
  "Test - modules: nested modules and aliases: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
