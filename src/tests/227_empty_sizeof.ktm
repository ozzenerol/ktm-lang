#include "testhelper.ktm"

/* `sizeof()` (and `sizeof;`, `alignof()`) segfaulted under -jit, AOT and
 * -transpile: the missing operand parsed as NULL. It is now an error at
 * the token where the operand should be. */

#define SZ_SRC "./ktm-227-sz.ktm"
#define SZ_OUT "./ktm-227-sz.out"

/* Run ktm with `flags` on `source`; `*_status` gets its exit status */
U8 *Diagnose(U8 *source, U8 *flags, I64 *_status)
{
  System("rm -f " SZ_SRC " " SZ_OUT);
  FileWrite(SZ_SRC, source, StrLen(source), O_CREAT | O_RDWR | O_TRUNC);
  U8 *cmd = MStrPrint("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "%s " SZ_SRC " > " SZ_OUT " 2>&1", flags);
  *_status = System(cmd);
  Free(cmd);
  U8 *out = FileRead(SZ_OUT);
  System("rm -f " SZ_SRC " " SZ_OUT);
  return out;
}

/* Fails with status 1 (not a crash) under -jit, AOT and -transpile, and
 * the -jit output has `want` and `where` */
Bool Rejected(U8 *source, U8 *want, U8 *where)
{
  I64 status, aot_status, tr_status;
  U8 *out = Diagnose(source, "-jit", &status);
  U8 *aot = Diagnose(source, "-S -o /dev/null", &aot_status);
  U8 *tr = Diagnose(source, "-transpile", &tr_status);
  Bool ok = status == 1 && aot_status == 1 && tr_status == 1 && out &&
            strstr(out, want) && strstr(out, where);
  if (!ok) "got %d %d %d: '%s'\n", status, aot_status, tr_status, out;
  Free(out);
  Free(aot);
  Free(tr);
  return ok;
}

#define NEED "`sizeof` needs a type or an expression"

class Sz { I64 a; U8 b; };

I32 Main()
{
  "Test - sizeof with no operand: ";
  I64 tests = 0, correct = 0;

  tests++;
  if (Rejected("U0 Main() { I64 x = sizeof(); }\n", NEED,
               "227-sz.ktm:1:29\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 x = 1 + sizeof() * 2; }\n", NEED,
               "227-sz.ktm:1:34\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 a[sizeof()]; }\n", NEED,
               "227-sz.ktm:1:27\n")) correct++;
  tests++;
  if (Rejected("I64 g = sizeof();\nU0 Main() {}\n", NEED,
               "227-sz.ktm:1:17\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 x = sizeof; }\n", NEED,
               "227-sz.ktm:1:27\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 x = sizeof(()); }\n", NEED,
               "227-sz.ktm:1:31\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 x = alignof(); }\n",
               "`alignof` needs a type or an expression",
               "227-sz.ktm:1:30\n")) correct++;
  tests++;
  if (Rejected("U0 Main() { I64 x = sizeof(", "Unexpected end of input",
               "227-sz.ktm:1")) correct++;
  /* Recovery goes on to the next error */
  tests++;
  I64 status;
  U8 *out = Diagnose("U0 Main()\n{\n  I64 x = sizeof();\n  I64 y = sizeof();\n}\n",
                     "-jit", &status);
  if (status == 1 && out && strstr(out, "227-sz.ktm:3:19\n") &&
      strstr(out, "227-sz.ktm:4:19\n")) correct++;
  else "got: '%s'\n", out;
  Free(out);

  /* The valid forms are unchanged */
  tests++;
  I64 v = 5;
  I64 arr[sizeof(I64)];
  if (sizeof(I64) == 8 && sizeof(v) == 8 && sizeof v == 8 &&
      sizeof("abc") == 4 && sizeof(Sz) == 16 && sizeof(I64 *) == 8 &&
      sizeof(v) * 2 == 16 && sizeof(arr) == 64 && alignof(I64) == 8 &&
      alignof(v) == 8) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
