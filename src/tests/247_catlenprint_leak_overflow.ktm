#include "testhelper.ktm"

/* CatLenPrint never freed the string it formats into, so every call
 * leaked a block (JsonToString, built on it, leaked one per piece), and
 * CatLenPrint(NULL, ...) allocated no room for the '\0' it then wrote. */

/* Bytes in use on the heap (HeapInUse() is in testhelper.ktm) */
I64 InUse()
{
  return HeapInUse();
}

I32 Main()
{
  "Test - CatLenPrint leak and overflow: ";
  I64 correct = 0, tests = 0, len, i, before;
  U8 *s;

  /* No leak: 20000 appends only grow the one string, so once it is
   * freed the heap is back where it was (each leaked temporary was 256+
   * bytes, 5 MB in all). The string is built once first so the
   * allocator's own bookkeeping is in place, and some slack is allowed:
   * macOS's allocator keeps ~100 KB of the freed blocks counted. */
  tests++;
  I64 pass, grew = 0;
  for (pass = 0; pass < 2; pass++) {
    before = InUse();
    len = 0;
    s = MAlloc(16);
    s[0] = '\0';
    for (i = 0; i < 20000; i++) {
      s = CatLenPrint(s, &len, "%d,", i % 10);
    }
    Free(s);
    grew = InUse() - before;
  }
  if (grew < 512 * 1024 && len == 40000) correct++;
  else "heap grew %d (len %d)\n", grew, len;

  /* The text is right */
  tests++;
  len = 0;
  s = CatLenPrint(NULL, &len, "hello");
  if (len == 5 && !StrCmp(s, "hello") && MSize(s) >= len + 1) correct++;
  else "len %d MSize %d\n", len, MSize(s);
  s = CatLenPrint(s, &len, " %s %d", "world", 42);
  tests++;
  if (len == 14 && !StrCmp(s, "hello world 42") && MSize(s) >= len + 1) correct++;
  Free(s);

  /* From NULL, every length lands with room for the '\0' */
  tests++;
  Bool ok = TRUE;
  for (i = 0; i < 300; i++) {
    len = 0;
    s = CatLenPrint(NULL, &len, "%*s", i, "");
    if (len != i || MSize(s) < i + 1 || s[i] != '\0') ok = FALSE;
    Free(s);
  }
  if (ok) correct++;

  /* JsonToString no longer leaks */
  tests++;
  Json *j = JsonParse("{\"a\":[1,2,3,true,null],\"b\":\"x\",\"c\":{\"d\":1.5}}");
  s = JsonToString(j);
  Free(s);
  before = InUse();
  for (i = 0; i < 2000; i++) {
    s = JsonToString(j);
    Free(s);
  }
  if (InUse() - before < 4096) correct++;
  else "JsonToString heap grew %d\n", InUse() - before;
  JsonRelease(j);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
