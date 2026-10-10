#include "testhelper.ktm"

/* JsonRelease never freed the parse state JsonParse hangs on the node it
 * returns (32 bytes per parse), and a parse error in an array or object
 * freed only the first item of the list built so far, leaking the rest
 * with their keys and strings. Checked by the heap staying flat. */

/* Bytes in use on the heap (HeapInUse() is in testhelper.ktm) */
I64 InUse()
{
  return HeapInUse();
}

/* Heap bytes left behind by parsing and releasing `text` 2000 times */
I64 Growth(U8 *text, I64 flags=0)
{
  Json *j = JsonParse(text, -1, flags);   /* warm up */
  JsonRelease(j);
  I64 before = InUse();
  for (I64 i = 0; i < 2000; i++) {
    j = JsonParse(text, -1, flags);
    JsonSelect(j, ".a:s");
    JsonRelease(j);
  }
  return InUse() - before;
}

Bool Flat(U8 *text, I64 flags=0)
{
  I64 g = Growth(text, flags);
  if (g >= 1024) "%d bytes left by %s\n", g, text;
  return g < 1024;
}

I32 Main()
{
  "Test - JsonRelease leaks: ";
  I64 correct = 0, tests = 0;

  /* Valid documents: the parse state */
  tests++;
  if (Flat("{\"a\": \"x\\n\", \"b\": [1, 2.5, {\"c\": [true, false, null]}], \"d\": {}}") &&
      Flat("[123456789012345678901234567890, 1.5, \"s\"]", JSON_STRNUM_FLAG) &&
      Flat("\"\\u00e9\\ud83d\\ude00\"") && Flat("42") && Flat("[]"))
    correct++;

  /* Parse errors part way through a list */
  tests++;
  if (Flat("[1, 2") && Flat("[\"a\", \"b\" \"c\"]") &&
      Flat("[\"x\", {\"k\": \"v\", \"q\": [1,2,}]"))
    correct++;
  tests++;
  if (Flat("{\"a\": \"x\", \"b\" 1}") && Flat("{\"a\": \"x\", 5: 1}") &&
      Flat("{\"a\": \"x\" \"b\": 1}") && Flat("{\"a\":1,\"b\":2,}") &&
      Flat("{\"a\": \"x\", \"b\": [1, {\"c\": \"y\"} 2]}"))
    correct++;

  /* Errors are still reported */
  tests++;
  Json *j = JsonParse("{\"a\": \"x\", \"b\" 1}");
  Json *k = JsonParse("[1, 2");
  if (!JsonOk(j) && !JsonOk(k)) correct++;
  JsonRelease(j);
  JsonRelease(k);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
