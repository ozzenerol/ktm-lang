#include "testhelper.ktm"

/* A global initialised with `null` (or a cast of an integer constant) is
 * a constant: written as data, as `NULL` (0) was, not stored by code that
 * runs before main. The -S output has `.quad 0` for it and no store. */

#define N_SRC "./ktm-432-null.ktm"
#define N_OUT "./ktm-432-null.s"

u8 *g_p = null;
fn(i64) i64 g_fn = null;
i64 *g_cast = (i64 *)0;

i32 Main()
{
  "Test - null global initialisers are constants: ";
  i64 correct = 0, tests = 0;

  tests++; if (g_p == null && g_fn == null && g_cast == null) correct++;

  u8 *src = "u8 *g_p = null;\nfn(i64) i64 g_fn = null;\ni32 main() { return 0; }\n";
  System("rm -f " N_SRC " " N_OUT);
  FileWrite(N_SRC, src, StrLen(src), O_CREAT | O_RDWR | O_TRUNC);
  System("$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
         "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
         "-S " N_SRC " -o " N_OUT " 2>&1");
  u8 *s = FileRead(N_OUT);
  /* no run-time store into either global */
  tests++; if (s && !strstr(s, "g_p(%rip)") && !strstr(s, "g_fn(%rip)") &&
               !strstr(s, "g_p@PAGEOFF") && !strstr(s, "g_fn@PAGEOFF") &&
               !strstr(s, ":lo12:g_p") && !strstr(s, ":lo12:g_fn")) correct++;
  System("rm -f " N_SRC " " N_OUT);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
