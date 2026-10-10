#include "testhelper.ktm"

/* With `#define M "mid"`, `U8 *s = "a" M "b";` was silently left out of
 * -transpile's C: the transpiler keeps #defines, so the parser met an
 * identifier between two strings and gave up on the statement. The
 * strings and string macros are now written as they are (`"a" M "b"`),
 * which C pastes together the same way. */

#define TP_FILE "__229_transpile.ktm"
#define TP_C    "__229_transpile.c"
#define TP_EXE  "__229_transpile.exe"
#define TP_OUT  "__229_transpile.out"
#define TP_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "
#define TP_KTM TP_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "

U0 CleanupTranspilePasteTest(U0)
{
  System("rm -f " TP_FILE " " TP_C " " TP_EXE " " TP_OUT);
}

U8 *tp_source =
"#define M \"mid\"
#define N \"end\"
#define TAB \"\\t\"
#define K 3
#define HALF 0.5
U0 Main()
{
  U8 *s = \"a\" M \"b\";
  U8 *t = M \"x\";
  U8 *u = \"x\" M;
  U8 *v = M N;
  U8 *w = M;
  U8 *e = \"[\" TAB \"\\n]\";
  U8 *p = \"plain\" \" pair\";
  I64 k = K * 2 + 1;
  F64 h = HALF * k;
  \"%s %s %s %s %s %d %.1f\\n\", s, t, u, v, w, k, h;
  \"fmt \" M \" %d\\n\", k;
  \"%s|%s|%d\\n\", \"q\" M N, p, StrLen(e);
}
";

U8 *tp_expected = "amidb midx xmid midend mid 7 3.5\nfmt mid 7\nqmidend|plain pair|4\n";

U0 Main()
{
  "Test - transpile macro pasted into a string: ";
  I64 correct = 0, tests = 0, res;
  U8 *code, *out;

  CleanupTranspilePasteTest;
  FileWrite(TP_FILE, tp_source, StrLen(tp_source), O_CREAT | O_RDWR | O_TRUNC);

  /* What the JIT prints */
  tests++;
  res = System(TP_KTM "-jit " TP_FILE " > " TP_OUT " 2>&1");
  out = FileRead(TP_OUT);
  if (res == 0 && out && !StrCmp(out, tp_expected)) correct++;
  else "jit: %s\n", out;
  Free(out);

  tests++;
  res = System(TP_KTM "-transpile " TP_FILE " > " TP_C " 2>/dev/null");
  code = FileRead(TP_C);
  if (res == 0 && code) correct++;
  if (!code) code = StrNew("");

  /* Every statement is there, the pastes as written */
  tests++;
  if (strstr(code, "unsigned char *s = \"a\" M \"b\";") &&
      strstr(code, "unsigned char *t = M \"x\";") &&
      strstr(code, "unsigned char *u = \"x\" M;") &&
      strstr(code, "unsigned char *v = M N;") &&
      strstr(code, "unsigned char *w = M;") &&
      strstr(code, "unsigned char *e = \"[\" TAB \"\\n]\";") &&
      strstr(code, "unsigned char *p = \"plain pair\";") &&
      strstr(code, "printf(\"fmt \" M \" %d\\n\", k);") &&
      strstr(code, "\"q\" M N")) correct++;
  else "%s\n", code;

  /* ...and the macros they use are defined */
  tests++;
  if (strstr(code, "#define M \"mid\"") && strstr(code, "#define N \"end\"") &&
      strstr(code, "#define TAB \"\\t\"") && strstr(code, "#define K 3")) correct++;

  /* The C compiles and prints what the JIT does (only with a C
   * compiler on the PATH) */
  tests++;
  if (System("command -v cc >/dev/null 2>&1") != 0) {
    correct++;
  } else {
    res = System("cc -w -o " TP_EXE " " TP_C " -lm > " TP_OUT " 2>&1 && " TP_TIMEOUT "./" TP_EXE " > " TP_OUT " 2>&1");
    out = FileRead(TP_OUT);
    if (res == 0 && out && !StrCmp(out, tp_expected)) correct++;
    else "cc: %s\n", out;
    Free(out);
  }

  Free(code);
  CleanupTranspilePasteTest;
  PrintResult(correct, tests);
  "====\n";
}
