#include "testhelper.ktm"

/* A global pointer initialised with an address (`U8 *p = &g;`, `&arr[2]`,
 * `&Fn`, NULL) was a store in the generated main(), so in a -lib library
 * (no main) it stayed NULL. Constant addresses are now written as
 * relocated data (`.quad sym+off`), and the JIT fills them in once the
 * target has an address. */

#define PG_SRC "__392_pg.ktm"
#define PG_OUT "__392_pg.out"
#define PG_BIN "__392_pg.bin"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

U8 *Slurp(U8 *cmd)
{
  System("rm -f " PG_OUT);
  if (System(cmd) != 0) return NULL;
  return FileRead(PG_OUT);
}

/* `src` with -jit and as a binary prints `want` */
U0 RunsAs(U8 *src, U8 *want, U8 *what)
{
  U8 *out, label[128];
  FileWrite(PG_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  out = Slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -jit " PG_SRC " > " PG_OUT " 2>/dev/null");
  StrPrint(label, "%s (JIT)", what);
  Check(out && !StrCmp(out, want), label);
  if (out && StrCmp(out, want)) "  got: %s", out;
  Free(out);
  out = Slurp("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) ../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} " PG_SRC " -o " PG_BIN " 2>/dev/null && ./" PG_BIN " > " PG_OUT " 2>&1");
  StrPrint(label, "%s (AOT)", what);
  Check(out && !StrCmp(out, want), label);
  if (out && StrCmp(out, want)) "  got: %s", out;
  Free(out);
  System("rm -f " PG_SRC " " PG_OUT " " PG_BIN);
}

I32 Main()
{
  "Test - pointer global initialisers: ";

  RunsAs(
"I64 g = 42;
I64 arr[5] = {10, 20, 30, 40, 50};
class Pt { I64 x, y; };
Pt pt = {7, 8};
static I64 sg = 99;
I64 Twice(I64 v) { return v * 2; }
I64 Thrice(I64 v);
I64 *pg = &g;
I64 *pa = &arr[2];
I64 *pa2 = arr + 4;
I64 *pback = &arr[4] - 3;
U8 *pb = arr;
Pt *ppt = &pt;
I64 *psg = &sg;
I64 (*fp)(I64) = &Twice;
I64 (*later)(I64) = &Thrice;
U8 *np = NULL;
U0 *pf = &printf;
I64 Thrice(I64 v) { return v * 3; }
U0 Main()
{
  \"%d %d %d %d %d %d %d %d %d %d %d\\n\", *pg, *pa, *pa2, *pback, pb[8], ppt->y,
    *psg, fp(21), later(5), np == NULL, pf == &printf;
}
",
"42 30 50 20 20 8 99 42 15 1 1\n", "pointer globals");

  /* a -lib library's pointer globals point at its own data and code */
  System("rm -rf ./ktm-392-prefix ./ktm-392-work ./ktm-392-use.ktm ./ktm-392-use");
  Check(System("D=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
           "mkdir -p ./ktm-392-prefix/include ./ktm-392-prefix/lib ./ktm-392-work && "
           "cp $D/include/std.ktmh ./ktm-392-prefix/include/ && "
           "cp -P $D/lib/libktm.* ./ktm-392-prefix/lib/ && "
           "printf 'static I64 table[4] = {5, 6, 7, 8};\\n"
           "I64 LibTriple(I64 x) { return x * 3; }\\n"
           "public I64 *third = &table[2];\\n"
           "public I64 (*triple)(I64) = &LibTriple;\\n"
           "public U8 *none = NULL;\\n"
           "public I64 Ktm392Check() { return *third + triple(2) + (none == NULL); }\\n'"
           " > ./ktm-392-work/ktm392.ktm && "
           "cd ./ktm-392-work && "
           "$H -lib ktm392 --install-dir=$P/ktm-392-prefix ktm392.ktm > /dev/null 2>&1") == 0,
        "build the -lib library");
  /* 7 + 6 + 1 (triple is checked inside: `extern` of a function pointer
   * doesn't parse) */
  System("printf '#link <ktm392>\\n"
         "extern I64 *third;\\nI64 Ktm392Check();\\n"
         "I32 Main() { if (Ktm392Check() == 14 && *third == 7) return 0; return 1; }\\n'"
         " > ./ktm-392-use.ktm");
  Check(System("../../ktm -jit --install-dir=./ktm-392-prefix ./ktm-392-use.ktm") == 0,
        "-lib pointer globals (JIT)");
  Check(System("../../ktm --install-dir=./ktm-392-prefix ./ktm-392-use.ktm -o ./ktm-392-use && "
               "./ktm-392-use") == 0,
        "-lib pointer globals (AOT)");
  System("rm -rf ./ktm-392-prefix ./ktm-392-work ./ktm-392-use.ktm ./ktm-392-use");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
