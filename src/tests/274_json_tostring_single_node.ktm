#include "testhelper.ktm"

/* JsonToString on a node from JsonSelect also printed the nodes after it
 * and its key ("b": 2,"c": 3 for .b). It now serialises only that node's
 * value; a whole document prints as before. */

Bool Is(Json *n, U8 *want)
{
  U8 *s = JsonToString(n);
  Bool ok = !StrCmp(s, want);
  if (!ok) "got %s, want %s\n", s, want;
  Free(s);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - JsonToString single node: ";
  Json *d = JsonParse("{\"a\": 1, \"b\": [2, {\"x\": null}, \"s\"], \"c\": {\"k\": true}, \"d\": 1.5}");

  tests++;
  if (Is(d, "{\"a\": 1,\"b\": [2,{\"x\": null},\"s\"],\"c\": {\"k\": true},\"d\": 1.5}")) correct++;

  tests++;
  if (Is(JsonSelect(d, ".a"), "1") && Is(JsonSelect(d, ".b"), "[2,{\"x\": null},\"s\"]") &&
      Is(JsonSelect(d, ".b[0]"), "2") && Is(JsonSelect(d, ".b[1]"), "{\"x\": null}") &&
      Is(JsonSelect(d, ".b[2]"), "\"s\"") && Is(JsonSelect(d, ".c"), "{\"k\": true}") &&
      Is(JsonSelect(d, ".c.k"), "true") && Is(JsonSelect(d, ".d"), "1.5") &&
      Is(JsonSelect(d, ".b[1].x"), "null")) correct++;

  /* the selected node is left as it was */
  Json *b = JsonSelect(d, ".b");
  U8 *again = JsonToString(d);
  tests++;
  if (b->next && !StrCmp(b->key, "b") &&
      !StrCmp(again, "{\"a\": 1,\"b\": [2,{\"x\": null},\"s\"],\"c\": {\"k\": true},\"d\": 1.5}"))
    correct++;
  Free(again);

  /* top-level arrays and scalars */
  Json *arr = JsonParse("[[1, 2], [], {}]");
  Json *num = JsonParse("42");
  tests++;
  if (Is(arr, "[[1,2],[],{}]") && Is(JsonSelect(arr, "[0]"), "[1,2]") &&
      Is(JsonSelect(arr, "[1]"), "[]") && Is(JsonSelect(arr, "[2]"), "{}") &&
      Is(num, "42")) correct++;

  JsonRelease(arr);
  JsonRelease(num);
  JsonRelease(d);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
