#include "testhelper.ktm"

/* `ktm -lib name dir`, dir not module name's directory: every module
 * under dir (net/http is net.http) goes into one library, dir/libname.a
 * and its shared object, and each module's directory gets a .ktmlib
 * marker naming them relative to it, so the tree can move. The top's
 * own files and a top-level prelude/ are not built. The object file is
 * a temporary one (none is left in the tree) and the cc/ar commands are
 * printed only with -v. One module's library (`-lib geo dir/geo`) has a
 * relative marker too. The fixture is src/tests/modules/libtree, copied
 * first; AOT and JIT. */

#define OUT "./ktm-439-tree.out"
#define BIN "./ktm-439-tree.bin"
#define TREE "./ktm-439-tree"
#define MOVED "./ktm-439-moved"
#define KTM "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) " \
            "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U8 *g_want = "9 5 4\n";

Bool Output(U8 *cmd, U8 *want)
{
  System("rm -f " OUT " " BIN);
  System(cmd);
  U8 *out = FileRead(OUT);
  Bool ok = out && !StrCmp(out, want);
  if (!ok) "\n%s\n----\n%s\n", cmd, out;
  Free(out);
  System("rm -f " OUT " " BIN);
  return ok;
}

I32 Main()
{
  "Test - one library from a tree of modules: ";
  I64 correct = 0, tests = 0;

  System("rm -rf " TREE " " MOVED " && mkdir -p " TREE
         " && cp -r ${KTM_TEST_SRC:-.}/modules/libtree/. " TREE "/");

  /* quiet, one library, a relative marker per module, no object file */
  tests++;
  if (Output(KTM "-lib tree " TREE " > " OUT " 2>&1", "")) correct++;
  tests++;
  if (Output("cat " TREE "/geo/.ktmlib " TREE "/net/http/.ktmlib | grep static > " OUT,
             "static ../libtree.a\nstatic ../../libtree.a\n"))
    correct++;
  tests++;
  if (Output("ls " TREE "/libtree.a > /dev/null && test ! -e " TREE "/prelude/.ktmlib && "
             "test -z \"$(find " TREE " -name '*.o')\" && echo ok > " OUT, "ok\n"))
    correct++;

  /* the program links it: none of the modules' code is compiled in */
  tests++;
  if (Output(KTM "-S " TREE "/main.ktm -o " BIN " && grep -c '^_K3geo4area:' " BIN " > " OUT,
             "0\n"))
    correct++;
  tests++;
  if (Output(KTM TREE "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " TREE "/main.ktm > " OUT " 2>&1", g_want)) correct++;

  /* the markers are relative: the tree still works moved */
  System("mv " TREE " " MOVED);
  tests++;
  if (Output(KTM MOVED "/main.ktm -o " BIN " > " OUT " 2>&1 && " BIN " >> " OUT, g_want))
    correct++;
  tests++;
  if (Output(KTM "-jit " MOVED "/main.ktm > " OUT " 2>&1", g_want)) correct++;

  /* -v prints the commands; one module's library, relative too */
  System("rm -f " MOVED "/geo/.ktmlib " MOVED "/net/http/.ktmlib " MOVED "/libtree.*");
  tests++;
  if (Output(KTM "-v -lib geo " MOVED "/geo 2>&1 | grep -c 'ar rcs' > " OUT, "1\n")) correct++;
  tests++;
  if (Output("grep static " MOVED "/geo/.ktmlib > " OUT, "static libgeo.a\n")) correct++;

  /* a directory that is neither */
  tests++;
  if (Output(KTM "-lib nothing " MOVED "/geo 2>&1 | grep -c 'is neither module nothing.s "
             "directory (one ending in nothing) nor a tree with modules under it' > " OUT, "1\n"))
    correct++;

  System("rm -rf " TREE " " MOVED " " OUT " " BIN);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
