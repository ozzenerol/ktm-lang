#include "testhelper.ktm"

extern "c" I32 dup(I32 fd);
extern "c" I32 dup2(I32 fd, I32 fd2);
extern "c" I32 fflush(U0 *stream);

/* Character constants take the same escapes as strings. `'\b'` used to
 * be 92 (the backslash), as were `'\a'` and any unknown escape, and
 * `'\033'` was the three characters 0, '3', '3'. A character constant
 * used as a statement prints itself: `'\"';` broke the assembly and
 * `'%';` was handed to printf as a format. */

I32 Main()
{
  "Test - char const escapes: ";
  I64 correct = 0, tests = 0;

  tests++;
  if ('\b' == 8 && '\a' == 7 && '\f' == 12 && '\n' == 10 && '\r' == 13 &&
      '\t' == 9 && '\v' == 11 && '\0' == 0)
    correct++;

  tests++;
  if ('\\' == 92 && '\'' == 39 && '\"' == 34 && '\`' == '`' && '\d' == '$')
    correct++;

  /* Hex takes at most two digits, octal at most three */
  tests++;
  if ('\x41' == 65 && '\x7' == 7 && '\X4a' == 0x4A && '\x41B' == 0x4241 &&
      '\033' == 27 && '\0' == 0 && '\101' == 'A' && '\08' == 0x3800 &&
      '\1234' == 0x3453)
    correct++;

  /* Multi-character constants, first character in the low byte */
  tests++;
  if ('AB' == 0x4241 && 'A\bB' == 0x420841 && '\b\b' == 0x0808 &&
      'a\'b' == 0x622761 && '\x01\x02' == 0x0201 && '\\\n' == 0x0A5C)
    correct++;

  /* The same bytes as the string forms */
  tests++;
  U8 *s = "\b$\f\n\r\t\v\\'\"\x41\033";
  if (s[0] == '\b' && s[1] == '$' && s[2] == '\f' && s[3] == '\n' &&
      s[4] == '\r' && s[5] == '\t' && s[6] == '\v' && s[7] == '\\' &&
      s[8] == '\'' && s[9] == '\"' && s[10] == '\x41' && s[11] == '\033' &&
      s[12] == 0)
    correct++;

  tests++;
  U8 c = '\b';
  if (c == 8 && '\b' + 0 == "\b"[0]) correct++;

  /* A character constant statement prints its characters */
  tests++;
  U8 *path = "__191_char_const_out.txt";
  Rm(path);
  fflush(NULL);
  I32 saved = dup(1), fd = open(path, O_WRONLY | O_CREAT, 0x1A4);
  dup2(fd, 1);
  close(fd);
  '\"';
  '%';
  'a%db';
  '\\\b';
  '\x01\x7F';
  '\n';
  fflush(NULL);
  dup2(saved, 1);
  close(saved);
  I64 size = 0;
  U8 *out = FileRead(path, &size);
  if (out && size == 11 && out[0] == '"' && out[1] == '%' && out[2] == 'a' &&
      out[3] == '%' && out[4] == 'd' && out[5] == 'b' && out[6] == '\\' &&
      out[7] == 8 && out[8] == 1 && out[9] == 0x7F && out[10] == '\n')
    correct++;
  Free(out);
  Rm(path);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
