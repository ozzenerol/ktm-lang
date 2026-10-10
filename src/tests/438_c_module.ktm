#include "testhelper.ktm"

/* The `c` module declares the C library: its files hold only extern "c"
 * functions, structs, unions, enums and constants, and `c.write(...)`
 * calls C's write. An extern "c" in any module is emitted with its C
 * name, not a module symbol (_K...), whether pub or not, called or taken
 * the address of. In module c a function with a body, a ktm prototype,
 * a method or a variable is an error. The fixture is
 * src/tests/modules/cmodule; AOT and JIT. */

#define MOD_OUT "./ktm-438-c.out"
#define MOD_BIN "./ktm-438-c.bin"
#define TMP_DIR "./ktm-438-c"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/cmodule/main.ktm"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "hello\n0 16 3 7\n";

Bool Output(U8 *cmd, U8 *want, U8 *not=NULL)
{
  System("rm -f " MOD_OUT " " MOD_BIN);
  System(cmd);
  U8 *out = FileRead(MOD_OUT);
  Bool ok = out && strstr(out, want) != NULL && (!not || !strstr(out, not));
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " MOD_OUT " " MOD_BIN);
  return ok;
}

/* a `c` module with `extra` added: the diagnostics have want */
Bool CModule(U8 *extra, U8 *want)
{
  System("rm -rf " TMP_DIR " && mkdir -p " TMP_DIR "/c");
  U8 *mod = MStrPrint("module c;\npub extern \"c\" u64 strlen(u8 *s);\n%s\n", extra);
  U8 *main = "import c;\ni32 main() { return c.strlen(\"\"); }\n";
  FileWrite(TMP_DIR "/c/c.ktm", mod, StrLen(mod), O_CREAT | O_RDWR | O_TRUNC);
  FileWrite(TMP_DIR "/main.ktm", main, StrLen(main), O_CREAT | O_RDWR | O_TRUNC);
  Bool ok = Output(KTM "-S " TMP_DIR "/main.ktm -o /dev/null > " MOD_OUT " 2>&1", want);
  System("rm -rf " TMP_DIR);
  Free(mod);
  return ok;
}

#define ONLY "error: module c only declares C: extern \"c\" functions, structs, unions, enums and constants, not "

I32 Main()
{
  "Test - the c module: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIXTURE " -o " MOD_BIN " > " MOD_OUT " 2>&1 && " MOD_BIN " >> " MOD_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE " > " MOD_OUT " 2>&1", g_want)) correct++;

  /* C's names: calls to write and abs (net.sock's private extern), the
   * address of strlen; no module symbol for any of them */
  tests++;
  if (Output(KTM "-S " FIXTURE " -o " MOD_BIN " && "
             "grep -qE '(call|bl)[a-z]*[[:space:]]+_?write$' " MOD_BIN " && "
             "grep -qE '(call|bl)[a-z]*[[:space:]]+_?abs$' " MOD_BIN " && "
             "grep -qE '_?strlen@' " MOD_BIN " && "
             "! grep -qE '_K[0-9]+c[0-9]+(write|strlen|clock_gettime)|_K3net4sock3abs' " MOD_BIN " && "
             "echo c-names > " MOD_OUT, "c-names"))
    correct++;

  /* constants, structs, unions, enums are fine */
  tests++;
  if (CModule("pub const i64 K = 3;\npub union U { i64 a; f64 b; };\n", "") ) correct++;
  tests++; if (CModule("pub i64 f() { return 1; }", ONLY "a function with a body")) correct++;
  tests++; if (CModule("pub i64 h(i64 x);", ONLY "a ktm function's prototype")) correct++;
  tests++; if (CModule("i64 g;", ONLY "a variable")) correct++;
  tests++; if (CModule("pub struct S { i64 x; };\npub void S.f() { }", ONLY "a function with a body")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
