#include "testhelper.ktm"

/* Methods (Y10) and modules: the fixture src/tests/modules/methods has
 * module bank with `pub struct Account` and its methods over two files
 * (a method calls one of the other file). Outside, a pub method is called
 * on a `bank.Account` value or pointer with no prefix, its address is
 * `&bank.Account.deposit`, and `bank.Account.deposit(p, x)` calls it
 * explicitly. A method that isn't pub is private to bank; only bank can
 * define methods of its types (not the program, not another module).
 * Also built with -lib: AOT and JIT. */

#define M_OUT "./ktm-427-methods.out"
#define M_BIN "./ktm-427-methods.bin"
#define M_DIR "./m427"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "ann:115:6 bob:4:7\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " M_OUT " " M_BIN);
  System(cmd);
  U8 *out = FileRead(M_OUT);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " M_OUT " " M_BIN);
  return ok;
}

I64 Count(U8 *s, U8 *what)
{
  I64 n = 0;
  while (s && (s = strstr(s, what))) {
    n++;
    s++;
  }
  return n;
}

/* A program `main` (and the module `shop` when given) next to a copy of
 * bank fails with exactly one error, `want` */
Bool Fails(U8 *main, U8 *shop, U8 *want)
{
  System("rm -rf " M_DIR " && mkdir -p " M_DIR " && "
         "cp -r ${KTM_TEST_SRC:-.}/modules/methods/bank " M_DIR "/");
  FileWrite(M_DIR "/main.ktm", main, StrLen(main), O_CREAT | O_RDWR | O_TRUNC);
  if (shop) {
    System("mkdir -p " M_DIR "/shop");
    FileWrite(M_DIR "/shop/shop.ktm", shop, StrLen(shop), O_CREAT | O_RDWR | O_TRUNC);
  }
  System(KTM "-S " M_DIR "/main.ktm -o /dev/null > " M_OUT " 2>&1");
  U8 *out = FileRead(M_OUT);
  Bool ok = out && Count(out, "error:") == 1 && strstr(out, want);
  if (!ok) "\n%s\n----\n%s\n", main, out;
  Free(out);
  System("rm -rf " M_DIR " " M_OUT);
  return ok;
}

#define FIXTURE "${KTM_TEST_SRC:-.}/modules/methods/main.ktm"

i32 Main()
{
  "Test - methods: modules: ";
  i64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIXTURE " -o " M_BIN " > " M_OUT " 2>&1 && " M_BIN " >> " M_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE " > " M_OUT " 2>&1", g_want)) correct++;

  tests++;
  if (Fails("import bank;\ni32 main() { bank.Account *a = bank.open(\"x\"); a.audit(); return 0; }\n",
            NULL, "the method `audit` of `bank.Account` is private to module bank"))
    correct++;
  tests++;
  if (Fails("import bank;\nvoid bank.Account.steal() {}\ni32 main() { return 0; }\n", NULL,
            "methods of `bank.Account` can only be defined in the module that defines it"))
    correct++;
  tests++;
  if (Fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
            "module shop;\nimport bank;\nvoid bank.Account.steal() {}\npub void go() {}\n",
            "methods of `bank.Account` can only be defined in the module that defines it"))
    correct++;
  tests++;
  if (Fails("import shop;\ni32 main() { shop.go(); return 0; }\n",
            "module shop;\npub struct Cart { i64 n; };\nvoid Cart.n() {}\npub void go() {}\n",
            "`shop.Cart` has a field named `n`"))
    correct++;

  /* precompiled */
  System("rm -rf " M_DIR " && mkdir -p " M_DIR " && "
         "cp -r ${KTM_TEST_SRC:-.}/modules/methods/. " M_DIR "/");
  tests++;
  if (Output(KTM "-lib bank " M_DIR "/bank > " M_OUT " 2>&1; "
             "test -f " M_DIR "/bank/.ktmlib && echo built > " M_OUT, "built\n"))
    correct++;
  tests++;
  if (Output(KTM M_DIR "/main.ktm -o " M_BIN " > " M_OUT " 2>&1 && " M_BIN " >> " M_OUT " 2>&1",
             g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " M_DIR "/main.ktm > " M_OUT " 2>&1", g_want)) correct++;
  System("rm -rf " M_DIR);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
