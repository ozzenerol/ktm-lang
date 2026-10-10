#include "testhelper.ktm"

/* A scalar global's initialiser (`I32 g = 7;`) was a store in the
 * generated main(): on arm64 that code ran over x0/x1 before Main read
 * argc/argv (and on x86_64 too once an initialiser called a function),
 * and in a -lib library, which has no main, the global stayed 0. Constant
 * initialisers are now static data, and the generated main passes argc
 * and argv on to Main. */

#define GI_SRC  "__385_gi.ktm"
#define GI_OUT  "__385_gi.out"
#define GI_BIN  "__385_gi.bin"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U8 *Slurp(U8 *cmd)
{
  System("rm -f " GI_OUT);
  if (System(cmd) != 0) return NULL;
  return FileRead(GI_OUT);
}

/* `src` built with -jit and as a binary, run with the arguments
 * "one two", prints `want` both ways */
U0 RunsAs(U8 *src, U8 *want, U8 *what)
{
  U8 *out, label[128];
  FileWrite(GI_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  out = Slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " GI_SRC " one two > " GI_OUT " 2>&1");
  StrPrint(label, "%s (JIT)", what);
  Check(out && !StrCmp(out, want), label);
  if (out && StrCmp(out, want)) "  got: %s", out;
  Free(out);
  out = Slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " GI_SRC " -o " GI_BIN " && ./" GI_BIN " one two > " GI_OUT " 2>&1");
  StrPrint(label, "%s (AOT)", what);
  Check(out && !StrCmp(out, want), label);
  if (out && StrCmp(out, want)) "  got: %s", out;
  Free(out);
  System("rm -f " GI_SRC " " GI_OUT " " GI_BIN);
}

I32 Main()
{
  "Test - scalar global initialisers: ";

  /* every scalar type, converted the way an assignment converts (a Bool
   * is 0 or 1) */
  RunsAs(
"I8   a = -5;
I16  b = 300 * 100;
I32  c = 7;
I64  d = -3000000000;
U8   e = 300;
Bool f = TRUE;
F64  g = 1.5;
F32  h = 2.25;
I64  i = 2.9;
U8  *j = NULL;
U64  k = 0xFFFFFFFFFFFFFFFF;
Bool l = 5;
Bool m = 0.5;
I32 Main(I32 argc, U8 **argv)
{
  \"%d %s %s|%d %d %d %d %d %d %.2f %.2f %d %d %x %d %d\\n\", argc, argv[1], argv[2],
    a, b, c, d, e, f, g, h, i, j == NULL, k, l, m;
  return 0;
}
",
"3 one two|-5 30000 7 -3000000000 44 1 1.50 2.25 2 1 ffffffffffffffff 1 1\n",
    "argc/argv with initialised globals");

  /* initialisers that run at startup, in order, around constant ones */
  RunsAs(
"I64 calls;
I64 Next() { return ++calls; }
I64 first = Next();
I32 seven = 7;
I64 second = Next() * 10 + seven;
\"top-level %d\\n\", calls;
I64 third = Next();
I32 Main(I64 argc, U8 **argv)
{
  \"%d %s|%d %d %d\\n\", argc, argv[2], first, second, third;
  return 0;
}
",
"top-level 2\n3 two|1 27 3\n",
    "non-constant initialisers keep their order");

  /* Main without parameters still runs after the initialisers */
  RunsAs(
"I64 n = Abs(-4);
U0 Main() { \"%d\\n\", n; }
",
"4\n", "Main without parameters");

  /* a -lib library's initialised globals have their values */
  System("rm -rf ./ktm-385-prefix ./ktm-385-work ./ktm-385-use.ktm ./ktm-385-use");
  Check(System("D=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
           "mkdir -p ./ktm-385-prefix/include ./ktm-385-prefix/lib ./ktm-385-work && "
           "cp $D/include/std.ktmh ./ktm-385-prefix/include/ && "
           "cp -P $D/lib/libktm.* ./ktm-385-prefix/lib/ && "
           "printf 'static I32 hidden = -1;\\npublic I64 shown = 42;\\n"
           "public F64 ratio = 0.5;\\n"
           "public I64 Ktm385Sum() { return hidden + shown; }\\n'"
           " > ./ktm-385-work/ktm385.ktm && "
           "cd ./ktm-385-work && "
           "$H -lib ktm385 --install-dir=$P/ktm-385-prefix ktm385.ktm > /dev/null 2>&1") == 0,
        "build the -lib library");
  System("printf '#link <ktm385>\\n"
         "extern I64 shown;\\nextern F64 ratio;\\nI64 Ktm385Sum();\\n"
         "I32 Main() { if (Ktm385Sum() == 41 && shown == 42 && ratio == 0.5) return 0; return 1; }\\n'"
         " > ./ktm-385-use.ktm");
  Check(System("../../ktm -jit --install-dir=./ktm-385-prefix ./ktm-385-use.ktm") == 0,
        "-lib globals (JIT)");
  Check(System("../../ktm --install-dir=./ktm-385-prefix ./ktm-385-use.ktm -o ./ktm-385-use && "
               "./ktm-385-use") == 0,
        "-lib globals (AOT)");
  System("rm -rf ./ktm-385-prefix ./ktm-385-work ./ktm-385-use.ktm ./ktm-385-use");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
