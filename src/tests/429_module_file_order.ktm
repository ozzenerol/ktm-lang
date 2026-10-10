#include "testhelper.ktm"

/* Modules (M5): the files of a module are read in name order, but every
 * name of every file is visible in any order, types included. The
 * fixture src/tests/modules/fileorder has module shop over three files:
 * a_order.ktm's struct holds a pointer to b_types.ktm's Box (which points
 * back), b_types.ktm's Item by value (which holds c_price.ktm's Price by
 * value), b_types.ktm's enum and its const as an array size; a_order.ktm
 * also has a global sized by that const, a method of Item and functions
 * using Box's method. Before, a_order.ktm's struct failed with "Unexpected
 * type `Box`". AOT, JIT and built with -lib. A cycle of structs holding
 * each other by value is an error; a type that doesn't exist still gives
 * the error it gave. */

#define FO_OUT "./ktm-429-fileorder.out"
#define FO_BIN "./ktm-429-fileorder.bin"
#define FO_DIR "./fo429"
#define FIXTURE "${KTM_TEST_SRC:-.}/modules/fileorder"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "132 5 6 7\n10 6 1\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " FO_OUT " " FO_BIN);
  System(cmd);
  U8 *out = FileRead(FO_OUT);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " FO_OUT " " FO_BIN);
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

/* Module m from the files a.ktm and b.ktm, imported by a program that
 * doesn't use it: compiling it gives `errors` errors, and `want` if not
 * NULL */
Bool Compiles(U8 *a, U8 *b, I64 errors, U8 *want)
{
  System("rm -rf " FO_DIR " && mkdir -p " FO_DIR "/m");
  U8 *main = "import m;\ni32 main() { return 0; }\n";
  FileWrite(FO_DIR "/main.ktm", main, StrLen(main), O_CREAT | O_RDWR | O_TRUNC);
  FileWrite(FO_DIR "/m/a.ktm", a, StrLen(a), O_CREAT | O_RDWR | O_TRUNC);
  FileWrite(FO_DIR "/m/b.ktm", b, StrLen(b), O_CREAT | O_RDWR | O_TRUNC);
  System(KTM "-S " FO_DIR "/main.ktm -o /dev/null > " FO_OUT " 2>&1");
  U8 *out = FileRead(FO_OUT);
  Bool ok = out && Count(out, "error:") == errors && (!want || strstr(out, want));
  if (!ok) "\n%s\n%s\n----\n%s\n", a, b, out;
  Free(out);
  System("rm -rf " FO_DIR " " FO_OUT);
  return ok;
}

i32 Main()
{
  "Test - modules: types of any file, in any order: ";
  i64 correct = 0, tests = 0;

  tests++;
  if (Output(KTM FIXTURE "/main.ktm -o " FO_BIN " > " FO_OUT " 2>&1 && "
             FO_BIN " >> " FO_OUT " 2>&1", g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FIXTURE "/main.ktm > " FO_OUT " 2>&1", g_want)) correct++;

  /* precompiled: built into a library, then imported */
  System("rm -rf " FO_DIR " && mkdir -p " FO_DIR " && cp -r " FIXTURE "/. " FO_DIR "/");
  tests++;
  if (Output(KTM "-lib shop " FO_DIR "/shop > " FO_OUT " 2>&1; "
             "test -f " FO_DIR "/shop/.ktmlib && echo built > " FO_OUT, "built\n"))
    correct++;
  tests++;
  if (Output(KTM FO_DIR "/main.ktm -o " FO_BIN " > " FO_OUT " 2>&1 && "
             FO_BIN " >> " FO_OUT " 2>&1", g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " FO_DIR "/main.ktm > " FO_OUT " 2>&1", g_want)) correct++;
  System("rm -rf " FO_DIR);

  /* structs holding each other by value: one error, at the first */
  tests++;
  if (Compiles("module m;\npub struct A { B b; };\n", "module m;\npub struct B { A a; };\n",
               1, "field `b` of m.A holds a m.B, which is not complete here"))
    correct++;
  /* the same with pointers is fine */
  tests++;
  if (Compiles("module m;\npub struct A { B *b; };\n",
               "module m;\npub struct B { A *a; };\n", 0, NULL))
    correct++;
  /* a type that doesn't exist, used by an earlier file's struct */
  tests++;
  if (Compiles("module m;\npub struct A { Missing x; B *b; };\n",
               "module m;\npub struct B { i64 v; };\n",
               1, "Unexpected type `Missing` while parsing class m.A"))
    correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
