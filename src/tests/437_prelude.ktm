#include "testhelper.ktm"

/* The prelude (src/std/prelude, installed as modules/prelude): its pub
 * names, Error and the macros EXIT_OK/EXIT_FAIL and I64_MIN ... U64_MAX,
 * are seen by every file, the program's and every module's, with no
 * import and no prefix, and it is read before std.ktmh. std.ktmh keeps
 * guarded copies for an install without a prelude. `import prelude;` is
 * an error, and a directory called prelude next to the program is not
 * the prelude. The fixture is src/tests/modules/preludeuse; AOT and JIT. */

#define MOD_OUT "./ktm-437-prelude.out"
#define MOD_BIN "./ktm-437-prelude.bin"
#define AT_SRC "./ktm-437-prelude.ktm"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/preludeuse/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "1 383 42\nError 5\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " MOD_OUT " " MOD_BIN);
  System(cmd);
  U8 *out = FileRead(MOD_OUT);
  Bool ok = out && strstr(out, want) != NULL;
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " MOD_OUT " " MOD_BIN);
  return ok;
}

/* the diagnostics for `source` have want in them */
Bool Diagnose(U8 *source, U8 *want)
{
  FileWrite(AT_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  Bool ok = Output(KTM "-S " AT_SRC " -o /dev/null > " MOD_OUT " 2>&1", want);
  System("rm -f " AT_SRC);
  return ok;
}

I32 Main()
{
  "Test - the prelude: ";
  I64 correct = 0, tests = 0;

  /* the program and a module, with a decoy prelude/ beside them */
  tests++;
  if (Output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1", g_want)) correct++;

  /* here too: no import, no prefix */
  Error e;
  e.code = I64_MIN;
  tests++;
  if (e.code == I64_MIN && U64_MAX == -1 && EXIT_OK == 0 && !StrCmp(typeof(e), "Error"))
    correct++;

  tests++;
  if (Diagnose("import prelude;\ni32 main() { return 0; }\n",
               "error: the prelude is always imported: its names need no import and no prefix"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
