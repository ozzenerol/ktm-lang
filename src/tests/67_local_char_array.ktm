#include "testhelper.ktm"

/* `U8 buf[] = "abc";` inside a function used to store the literal's
 * address into the array's first 8 bytes instead of copying the
 * characters, so printing it gave garbage. `U8 buf[8] = "abc";` was
 * rejected outright instead of zero-filling the tail like C. */

class Point
{
  I64 x, y;
};

U8 g_buf[] = "abc";
U8 g_big[8] = "ab";
I64 g_after = 77;

I64 g_writes_ok = 0;

/* Each call must start from a fresh copy, not the previous call's
 * edit (and not the shared literal). */
U0 BumpFirst()
{
  U8 b[] = "abc";
  b[0]++;
  if (!StrCmp(b, "bbc")) g_writes_ok++;
}

I64 g_rec_ok = 0;

U0 Recurse(I64 depth)
{
  U8 r[] = "xyz";
  if (r[0] == 'x' && r[3] == '\0') g_rec_ok++;
  r[0] = '0' + depth;
  if (depth > 0) Recurse(depth - 1);
  if (r[0] == '0' + depth) g_rec_ok++;
}

I32 Main()
{
  I64 correct = 0, tests = 19, i, zeros;

  "Test - local U8 array initialised from a string: ";

  U8 buf[] = "abc";
  if (!StrCmp(buf, "abc")) correct++;
  if (sizeof(buf) == 4) correct++;

  U8 exact[4] = "abc";
  if (!StrCmp(exact, "abc") && sizeof(exact) == 4) correct++;

  U8 big[8] = "abc";
  zeros = 0;
  for (i = 3; i < 8; i++) {
    if (big[i] == 0) zeros++;
  }
  if (!StrCmp(big, "abc") && sizeof(big) == 8) correct++;
  if (zeros == 5) correct++;

  /* Escapes are decoded: 'a', '\t', 'b', NUL. */
  U8 esc[] = "a\tb";
  if (sizeof(esc) == 4 && esc[1] == '\t' && esc[2] == 'b') correct++;

  /* Writable, and only the local copy changes. */
  buf[0] = 'X';
  if (!StrCmp(buf, "Xbc")) correct++;
  U8 *lit = "abc";
  if (!StrCmp(lit, "abc")) correct++;

  BumpFirst;
  BumpFirst;
  if (g_writes_ok == 2) correct++;

  Recurse(3);
  if (g_rec_ok == 8) correct++;

  for (i = 0; i < 3; i++) {
    U8 l[] = "abc";
    if (l[0] == 'a') correct++;
    l[0] = 'Z';
  }

  /* Other initialisers keep working. */
  I64 a[] = {1, 2, 3};
  if (sizeof(a) == 24 && a[0] == 1 && a[2] == 3) correct++;
  I64 b[4] = {1, 2, 3, 4};
  if (b[3] == 4) correct++;
  Point p = {1, 2};
  if (p.x == 1 && p.y == 2) correct++;

  /* Globals: inline bytes, zero-filled tail, neighbour untouched. */
  if (!StrCmp(g_buf, "abc") && sizeof(g_buf) == 4) correct++;
  if (!StrCmp(g_big, "ab") && sizeof(g_big) == 8 && g_big[7] == 0 &&
      g_after == 77) {
    correct++;
  }

  /* Too small an array is still a compile error. */
  System("printf 'U0 Main() { U8 s[3] = \"abc\"; }\\n' > ./ktm-66-small.ktm");
  if (System("../../ktm ${KTM_INSTALL_DIR:+--install-dir=$KTM_INSTALL_DIR} "
             "-jit ./ktm-66-small.ktm 2>/dev/null") != 0) {
    correct++;
  }
  System("rm -f ./ktm-66-small.ktm");

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
