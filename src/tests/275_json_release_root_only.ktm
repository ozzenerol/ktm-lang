#include "testhelper.ktm"

/* JsonRelease on a node from JsonSelect freed it and every node after
 * it, leaving the document pointing at freed memory. It now only frees a
 * root (what JsonParse returned); NULL or an inner node is ignored.
 * JsonGetStrerror/JsonPrintError crashed on an inner node (no state); they
 * now return NULL / print nothing for NULL or an inner node. valgrind
 * checks the AOT binary for invalid reads and leaks. */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - JsonRelease root only: ";
  Json *d = JsonParse("{\"a\": [1, {\"x\": \"y\"}], \"b\": \"str\", \"c\": {\"k\": null}}");

  Json *a = JsonSelect(d, ".a");
  Json *x = JsonSelect(d, ".a[1].x");
  JsonRelease(a);               /* views: ignored */
  JsonRelease(x);
  JsonRelease(JsonSelect(d, ".c"));
  JsonRelease(NULL);
  U8 *s = JsonToString(d);
  tests++;
  if (!StrCmp(s, "{\"a\": [1,{\"x\": \"y\"}],\"b\": \"str\",\"c\": {\"k\": null}}") &&
      !StrCmp(JsonSelect(d, ".a[1].x")->str, "y")) correct++;
  else "document changed: %s\n", s;
  Free(s);

  /* error functions on NULL and inner nodes */
  tests++;
  if (JsonGetStrerror(NULL) == NULL && JsonGetStrerror(a) == NULL &&
      JsonGetStrerror(d) == NULL) correct++;
  JsonPrintError(NULL);
  JsonPrintError(x);
  JsonRelease(d);

  /* failed parses still release fully (valgrind: no leaks) */
  U8 *bad[4] = {"{\"a\": [1, 2", "[1, {\"k\": tru}]", "{\"a\" 1}", "[\"x\", ]"};
  Bool ok = TRUE;
  for (I64 i = 0; i < 4; ++i) {
    Json *e = JsonParse(bad[i]);
    U8 *msg = JsonGetStrerror(e);
    if (JsonOk(e) || !msg) ok = FALSE;
    Free(msg);
    JsonRelease(e);
  }
  tests++; if (ok) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
