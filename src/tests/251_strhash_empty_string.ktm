#include "testhelper.ktm"

/* StrHash("") read the byte after the terminating NUL (the x86_64 asm
 * loaded 1[RDI] first, the arm64 version started at index 1). It also had
 * no final mixing: h*31+c keeps similar keys ("key1", "key2", ...) in
 * neighbouring slots of a power-of-two table. It is now h*31+c from 0
 * followed by the murmur3 64-bit finaliser, the same on every target;
 * "" hashes to 0. */

U64 ModelHash(U8 *s)
{
  U64 h = 0;
  for (I64 i = 0; s[i]; ++i) h = h * 31 + s[i];
  h ^= h >> 33;
  h *= 0xFF51AFD7ED558CCD;
  h ^= h >> 33;
  h *= 0xC4CEB9FE1A85EC53;
  h ^= h >> 33;
  return h;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - StrHash: ";

  /* "" on a 1-byte block: nothing past it is read (valgrind checks the
   * AOT binary), and the value is fixed */
  U8 *empty = MAlloc(1);
  empty[0] = '\0';
  tests++; if (StrHash(empty) == 0 && StrHash("") == 0) correct++;
  Free(empty);

  /* Known values, worked out independently */
  U8 hi[4];
  hi[0] = 0xFF; hi[1] = 0x80; hi[2] = 'z'; hi[3] = '\0';
  tests++;
  if (StrHash("a") == 0x685FDF50E51FA977 && StrHash("ab") == 0xF0F422B68702A7AE &&
      StrHash("hello world") == 0x68C76B672FED4A5E &&
      StrHash("key12345") == 0x17528DBD2BD11F26 &&
      StrHash(hi) == 0x01A56E86E1D7631F) correct++;
  else "StrHash(\"a\") = %x\n", StrHash("a");

  /* Same as the model for many strings (asm on x86_64, ktm on arm64) */
  Bool ok = TRUE;
  U8 buf[64];
  for (I64 i = 0; i < 2000; ++i) {
    StrPrint(buf, "key%d/%x", i, i * 7919);
    if (StrHash(buf) != ModelHash(buf)) ok = FALSE;
  }
  tests++; if (ok) correct++;

  /* Spread: 100000 "key%d" in 262144 slots, linear probing like StrMap.
   * Without the finaliser this averaged 9.6 probes (max 288). */
  I64 cap = 262144, n = 100000, total = 0, worst = 0;
  Bool *taken = CAlloc(cap);
  for (I64 i = 0; i < n; ++i) {
    StrPrint(buf, "key%d", i);
    I64 idx = (StrHash(buf) & (cap - 1))(I64), probes = 1;
    while (taken[idx]) { idx = (idx + 1) & (cap - 1); probes++; }
    taken[idx] = TRUE;
    total += probes;
    if (probes > worst) worst = probes;
  }
  Free(taken);
  tests++; if (total < n * 2 && worst < 40) correct++;
  else "avg probes %d/100, max %d\n", total * 100 / n, worst;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
