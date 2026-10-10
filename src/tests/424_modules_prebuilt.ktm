#include "testhelper.ktm"

/* Modules (M1): a precompiled module. `ktm -lib geo dir/geo` builds the
 * module into libgeo.a and its shared object in dir/geo and writes the
 * marker dir/geo/.ktmlib. A program importing geo then reads only geo's
 * declarations (no geo code in its own output) and links the library:
 * the archive AOT, the shared object in the JIT. A const of the module
 * still works as an array size. Uses a copy of src/tests/modules/basic. */

#define PRE_OUT "./ktm-424-prebuilt.out"
#define PRE_BIN "./ktm-424-prebuilt.bin"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "3 4\n25 12\n2 4 -2 1 7\n1\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " PRE_OUT " " PRE_BIN);
  System(cmd);
  U8 *out = FileRead(PRE_OUT);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  return ok;
}

I32 Main()
{
  "Test - modules: precompiled: ";
  I64 correct = 0, tests = 0;

  System("rm -rf ./pre && mkdir -p ./pre && cp -r ${KTM_TEST_SRC:-.}/modules/basic/. ./pre/");

  tests++;
  if (Output(KTM "-lib geo ./pre/geo > " PRE_OUT " 2>&1; "
             "test -f ./pre/geo/.ktmlib && test -f ./pre/geo/libgeo.a && echo built > " PRE_OUT,
             "built\n"))
    correct++;
  /* the program has none of geo's code: no `_K3geo...:` label */
  tests++;
  if (Output(KTM "-S ./pre/main.ktm -o- 2>&1 | grep -c '^_K3geo' > " PRE_OUT, "0\n"))
    correct++;
  tests++;
  if (Output(KTM "./pre/main.ktm -o " PRE_BIN " > " PRE_OUT " 2>&1 && " PRE_BIN " >> " PRE_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit ./pre/main.ktm > " PRE_OUT " 2>&1", g_want)) correct++;

  System("rm -rf ./pre " PRE_OUT " " PRE_BIN);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
