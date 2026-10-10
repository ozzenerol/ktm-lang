#include "testhelper.ktm"

/* `0b`/`0B` binary integer literals. Regression: `b`/`B` are hex digits,
 * so `0b1010` was lexed as one token and parsed base 10, which stopped at
 * the `b` and silently gave 0. Hex literals containing `b` digits must be
 * unaffected. */

I32 Main()
{
  "Test - binary literals: ";
  I64 correct = 0, tests = 9;

  if (0b1010 == 10) correct++;
  if (0B11 == 3) correct++;
  if (0b0 == 0) correct++;
  if (0b1 == 1) correct++;

  U64 all = 0b1111111111111111111111111111111111111111111111111111111111111111;
  if (all == U64_MAX) correct++;

  I64 x = 0b100 + 0b1 * 2;
  if (x == 6 && (0b1100 | 0b0011) == 0xF) correct++;

  if (0xB1 == 177) correct++;
  if (0x1b == 27) correct++;
  if (0xBB == 187 && 0XbB == 187) correct++;

  PrintResult(correct,tests);
  "====\n";
  return 0;
}
