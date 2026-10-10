#include "testhelper.ktm"

/* `extern I64 (*fp)(I64);` didn't parse ("`extern <Type>` must be followed
 * by an identifier, got `(`"), so a program couldn't use a library's
 * public function-pointer global. It now declares one, like
 * `extern I64 x;` does for data. */

#define XF_SRC "__393_xf.ktm"
#define XF_OUT "__393_xf.out"
#define XF_BIN "__393_xf.bin"

I64 g_correct = 0, g_tests = 0;
U0 Check(Bool ok, U8 *what)
{
  g_tests++;
  if (ok) g_correct++;
  else "\n  failed: %s\n", what;
}

/* ktm's output (stdout and stderr) for `src` with `flags`; *_res gets the
 * exit status */
U8 *Ktm(U8 *src, U8 *flags, I64 *_res)
{
  U8 *cmd;
  FileWrite(XF_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
                  "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
                  "%s " XF_SRC " > " XF_OUT " 2>&1", flags);
  *_res = System(cmd);
  Free(cmd);
  return FileRead(XF_OUT);
}

I32 Main()
{
  I64 res;
  U8 *out;
  "Test - extern function pointer: ";

  /* declared extern, then defined in the same file */
  out = Ktm(
"I64 Twice(I64 v) { return v * 2; }
extern I64 (*fp)(I64);
I64 (*fp)(I64) = &Twice;
U0 Main() { \"%d\\n\", fp(4); }
", "-jit", &res);
  Check(res == 0 && out && !StrCmp(out, "8\n"), "extern then definition (JIT)");
  Free(out);

  /* a malformed extern still says what is wrong */
  out = Ktm("extern I64 123;\n", "-jit", &res);
  Check(res != 0 && out &&
        strstr(out, "`extern <Type>` must be followed by an identifier, got `123`"),
        "malformed extern");
  Free(out);
  out = Ktm("extern I64 (*)(I64);\n", "-jit", &res);
  Check(res != 0 && out && strstr(out, "Expected function pointer name"),
        "extern function pointer without a name");
  Free(out);
  System("rm -f " XF_SRC " " XF_OUT " " XF_BIN);

  /* a -lib library's public function pointer, called from a program */
  System("rm -rf ./ktm-393-prefix ./ktm-393-work ./ktm-393-use.ktm ./ktm-393-use");
  Check(System("D=${KTM_INSTALL_DIR:-/usr/local} && H=$PWD/../../ktm && P=$PWD && "
           "mkdir -p ./ktm-393-prefix/include ./ktm-393-prefix/lib ./ktm-393-work && "
           "cp $D/include/std.ktmh ./ktm-393-prefix/include/ && "
           "cp -P $D/lib/libktm.* ./ktm-393-prefix/lib/ && "
           "printf 'I64 LibTriple(I64 x) { return x * 3; }\\n"
           "I64 LibSquare(I64 x) { return x * x; }\\n"
           "public I64 (*triple)(I64) = &LibTriple;\\n"
           "public U0 Ktm393UseSquare() { triple = &LibSquare; }\\n'"
           " > ./ktm-393-work/ktm393.ktm && "
           "cd ./ktm-393-work && "
           "$H -lib ktm393 --install-dir=$P/ktm-393-prefix ktm393.ktm > /dev/null 2>&1") == 0,
        "build the -lib library");
  /* calls it, then sees the library repoint it */
  System("printf '#link <ktm393>\\n"
         "extern I64 (*triple)(I64);\\nU0 Ktm393UseSquare();\\n"
         "I32 Main() { if (triple(4) != 12) return 1; Ktm393UseSquare();"
         " if (triple(5) != 25) return 2; return 0; }\\n'"
         " > ./ktm-393-use.ktm");
  Check(System("../../ktm -jit --install-dir=./ktm-393-prefix ./ktm-393-use.ktm") == 0,
        "library function pointer (JIT)");
  Check(System("../../ktm --install-dir=./ktm-393-prefix ./ktm-393-use.ktm -o ./ktm-393-use && "
               "./ktm-393-use") == 0,
        "library function pointer (AOT)");
  System("rm -rf ./ktm-393-prefix ./ktm-393-work ./ktm-393-use.ktm ./ktm-393-use");

  PrintResult(g_correct, g_tests);
  "====\n";
  return 0;
}
