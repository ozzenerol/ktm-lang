#include "testhelper.ktm"

/* `-transpile` wrote the classes first and the unions after them, so a
 * class holding a named union by value used it before its typedef, and
 * it turned F32 into double. Each class or union is now written after
 * the ones it holds by value (members, array members, members of an
 * anonymous nested union), and F32 is float. */

#define TR_FILE "__199_transpile.ktm"
#define TR_C    "__199_transpile.c"
#define TR_EXE  "__199_transpile.exe"
#define TR_OUT  "__199_transpile.out"
/* `timeout` is coreutils; without it the command just runs */
#define TR_TIMEOUT "$(command -v timeout >/dev/null 2>&1 && echo timeout 60) "

U0 CleanupTranspileTest(U0)
{
  System("rm -f " TR_FILE " " TR_C " " TR_EXE " " TR_OUT);
}

U8 *tr_source =
"class Zeta
{
  F32 z;
  I32 pad;
};

union Word
{
  I64 i;
  F64 f;
  Zeta zeta;
};

class Yak
{
  Zeta zeta;
  Word word;
  F32 scale;
};

class Apple
{
  Yak yak;
  union {
    Word inner;
    I64 raw;
  };
  Apple *next;
};

union Holder
{
  Apple apple;
  I64 tag;
};

class Base
{
  Holder holder;
  Yak yaks[2];
  F32 ratio;
};

U0 Main()
{
  Base b;
  b.holder.apple.yak.zeta.z = 2.5;
  b.holder.apple.yak.word.zeta.pad = 4;
  b.holder.apple.inner.i = 9;
  b.holder.apple.next = NULL;
  b.ratio = 0.25;
  \"%.2f %d %d %.2f\\n\", b.holder.apple.yak.zeta.z,
    b.holder.apple.yak.word.zeta.pad, b.holder.apple.inner.i, b.ratio;
}
";

/* `first` appears in `code`, and before `second` */
Bool Before(U8 *code, U8 *first, U8 *second)
{
  U8 *a = strstr(code, first), *b = strstr(code, second);
  if (!a || !b || a > b) {
    "\"%s\" is not before \"%s\"\n", first, second;
    return FALSE;
  }
  return TRUE;
}

U0 Main()
{
  "Test - transpile nested class order: ";
  I64 correct = 0, tests = 0, res;
  U8 *code, *out;

  CleanupTranspileTest;
  FileWrite(TR_FILE, tr_source, StrLen(tr_source), O_CREAT | O_RDWR | O_TRUNC);
  res = System(TR_TIMEOUT "../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} -transpile " TR_FILE " > " TR_C " 2>/dev/null");
  code = FileRead(TR_C);

  tests++;
  if (res == 0 && code) correct++;
  if (!code) code = StrNew("");

  /* Every by-value dependency comes first, through unions, arrays and
   * an anonymous union */
  tests++;
  if (Before(code, "typedef struct Zeta {", "typedef union Word {") &&
      Before(code, "typedef union Word {", "typedef struct Yak {") &&
      Before(code, "typedef struct Yak {", "typedef struct Apple {") &&
      Before(code, "typedef struct Apple {", "typedef union Holder {") &&
      Before(code, "typedef union Holder {", "typedef struct Base {")) {
    correct++;
  }

  /* Each written once */
  tests++;
  U8 *w = strstr(code, "typedef union Word {");
  if (w && !strstr(w + 1, "typedef union Word {")) correct++;

  /* F32 is float, F64 double */
  tests++;
  if (strstr(code, "float z;") && strstr(code, "float scale;") &&
      strstr(code, "float ratio;") && strstr(code, "double f;")) {
    correct++;
  }

  /* A pointer to the class being written still uses its tag */
  tests++;
  if (strstr(code, "struct Apple *next;")) correct++;

  /* The C compiles and prints what the JIT does (only with a C
   * compiler on the PATH) */
  tests++;
  res = System("command -v cc >/dev/null 2>&1");
  if (res != 0) {
    correct++;
  } else {
    res = System("cc -w -o " TR_EXE " " TR_C " -lm > " TR_OUT " 2>&1 && " TR_TIMEOUT "./" TR_EXE " > " TR_OUT " 2>&1");
    out = FileRead(TR_OUT);
    if (res == 0 && out && !StrCmp(out, "2.50 4 9 0.25\n")) {
      correct++;
    } else if (out) {
      "%s\n", out;
    }
    if (out) Free(out);
  }

  Free(code);
  CleanupTranspileTest;
  PrintResult(correct, tests);
  "====\n";
}
