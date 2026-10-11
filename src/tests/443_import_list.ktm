#include "testhelper.ktm"

/* `import a, b, h = net.http;`: one line imports several modules, each
 * with its own alias if it has one (the stdlib map's decision 3). Before,
 * `import` took one module and `,` was "expected `;` after the module
 * name". The modules load one after the other, in the order written,
 * so one listed before a module it imports still finds it loaded. The fixture is
 * src/tests/modules/importlist; AOT and JIT. */

#define OUT "./ktm-443-imports.out"
#define BIN "./ktm-443-imports.bin"
#define FIX "${KTM_TEST_SRC:-.}/modules/importlist"
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
  "Test - import lists: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIX "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "1 2 3\n"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIX "/main.ktm > " OUT " 2>&1", "1 2 3\n")) correct++;
  /* b imports a: listed first, it still sees a loaded (they load one
   * after the other), no "circular import" */
  tests++;
  if (Output(KTM FIX "/reversed.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, "1 2 3\n"))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIX "/reversed.ktm > " OUT " 2>&1", "1 2 3\n")) correct++;
  tests++;
  if (Output(KTM "-S " FIX "/bad.ktm -o /dev/null > " OUT " 2>&1",
             "error: expected `;` or `,` after the module name"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
