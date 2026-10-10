#include "testhelper.ktm"

/* `U8 buf[] = "abc"` at file scope was emitted with no section
 * directive by the AOT backends, so it landed in whatever section the
 * global before it left open: after the string literals, in a
 * read-only section, where writing to it crashed. It now goes in
 * .data like other initialised globals. */

U8 g_first[] = "abc";
U8 g_padded[8] = "de";
static U8 g_static[] = "xyz";
U8 *g_ptr = "lit";
U8 g_after_ptr[] = "mno";
U8 g_esc[] = "a\tb";

I32 Main()
{
  I64 correct = 0, tests = 0;

  "Test - file-scope char arrays initialised from a string are writable: ";

  g_first[0] = 'X';
  tests++; if (!StrCmp(g_first, "Xbc")) correct++;
  g_padded[2] = 'f';
  g_padded[6] = 'Y';
  tests++; if (!StrCmp(g_padded, "def") && g_padded[6] == 'Y' && !g_padded[7]) correct++;
  g_static[2] = 'Z';
  tests++; if (!StrCmp(g_static, "xyZ")) correct++;
  tests++; if (!StrCmp(g_ptr, "lit")) correct++;
  g_after_ptr[1] = 'N';
  tests++; if (!StrCmp(g_after_ptr, "mNo")) correct++;
  tests++; if (sizeof(g_first) == 4 && sizeof(g_padded) == 8) correct++;
  g_esc[0] = 'A';
  tests++; if (!StrCmp(g_esc, "A\tb")) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
