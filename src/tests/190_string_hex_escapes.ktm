#include "testhelper.ktm"

/* `\x` takes at most two hex digits and every string literal ends in a
 * NUL that sizeof counts. Compiled programs used to hand `"\x01b"` to
 * the assembler as written, which read it as the one byte 0x1B (the
 * JIT gave 1, 'b'); `"\x42"` got no NUL and sizeof 1; `"\033"` had
 * sizeof 4 and `"\vA"` became the one byte 0xBA. */

U8 *g_p = "\x01b";
U8 g_a[] = "\x01b";
U8 g_one[] = "\x42";
U8 g_pad[6] = "\x41\x42";
U8 g_v[] = "\vA";

Bool Bytes(U8 *s, U8 *want, I64 n)
{
  I64 i;
  for (i = 0; i < n; i++)
    if (s[i] != want[i]) return FALSE;
  return TRUE;
}

I32 Main()
{
  "Test - string hex escapes: ";
  I64 correct = 0, tests = 0;
  U8 want[8];

  /* A hex digit after the two-digit escape is its own character */
  tests++;
  U8 *s = "\x01b";
  want[0] = 1; want[1] = 'b'; want[2] = 0;
  if (Bytes(s, want, 3) && StrLen(s) == 2) correct++;

  tests++;
  if (sizeof("\x01b") == 3 && sizeof("\x42") == 2 && sizeof("") == 1) correct++;

  tests++;
  s = "\x41BC\x4g\x7Fz";
  want[0] = 'A'; want[1] = 'B'; want[2] = 'C'; want[3] = 4;
  want[4] = 'g'; want[5] = 0x7F; want[6] = 'z'; want[7] = 0;
  if (Bytes(s, want, 8) && sizeof("\x41BC\x4g\x7Fz") == 8) correct++;

  /* Adjacent literals are joined first */
  tests++;
  s = "\x01" "b" "\x2" "3";
  want[0] = 1; want[1] = 'b'; want[2] = 2; want[3] = '3'; want[4] = 0;
  if (Bytes(s, want, 5) && sizeof("\x01" "b") == 3) correct++;

  /* A NUL inside the string */
  tests++;
  s = "a\x00" "b\0c";
  want[0] = 'a'; want[1] = 0; want[2] = 'b'; want[3] = 0; want[4] = 'c';
  want[5] = 0;
  if (Bytes(s, want, 6) && sizeof("a\x00" "b\0c") == 6) correct++;

  /* Octal escapes take up to three octal digits */
  tests++;
  s = "\033[\08\1234";
  want[0] = 27; want[1] = '['; want[2] = 0; want[3] = '8'; want[4] = 0x53;
  want[5] = '4'; want[6] = 0;
  if (Bytes(s, want, 7) && sizeof("\033") == 2) correct++;

  /* \v followed by a hex digit, other escapes next to digits */
  tests++;
  s = "\vA\tB\n1\\2\"3";
  want[0] = 11; want[1] = 'A'; want[2] = '\t'; want[3] = 'B';
  want[4] = '\n'; want[5] = '1'; want[6] = '\\'; want[7] = '2';
  if (Bytes(s, want, 8) && s[8] == '"' && s[9] == '3' && s[10] == 0) correct++;

  /* Arrays initialised from strings, local and global */
  tests++;
  U8 a[] = "\x01b";
  want[0] = 1; want[1] = 'b'; want[2] = 0;
  if (sizeof(a) == 3 && Bytes(a, want, 3)) correct++;

  tests++;
  U8 one[] = "\x42";
  if (sizeof(one) == 2 && one[0] == 'B' && one[1] == 0) correct++;

  tests++;
  if (Bytes(g_p, want, 3) && Bytes(g_a, want, 3) && sizeof(g_a) == 3)
    correct++;

  tests++;
  if (sizeof(g_one) == 2 && g_one[0] == 'B' && g_one[1] == 0) correct++;

  tests++;
  if (sizeof(g_pad) == 6 && g_pad[0] == 'A' && g_pad[1] == 'B' &&
      g_pad[2] == 0 && g_pad[5] == 0)
    correct++;

  tests++;
  U8 v[] = "\vA";
  if (sizeof(v) == 3 && v[0] == 11 && v[1] == 'A' && v[2] == 0 &&
      sizeof(g_v) == 3 && g_v[0] == 11 && g_v[1] == 'A' && g_v[2] == 0)
    correct++;

  tests++;
  s = "\a1\d2";
  if (s[0] == 7 && s[1] == '1' && s[2] == '$' && s[3] == '2' && s[4] == 0)
    correct++;

  /* Quotes and backticks inside a string */
  tests++;
  s = "it\'s `q` \`r\`";
  if (!StrCmp(s, "it's `q` `r`") && sizeof("it\'s") == 5) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
