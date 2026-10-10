#include "testhelper.ktm"

/* JsonSelect: a '*' in an object path copied the wrong buffer, so ".*"
 * and ".price_*" never matched; a selector ending in '.' or '[' read past
 * its NUL; junk like "[1abc]", ":ixyz" and "..z" was accepted; a NULL
 * document or selector crashed. */

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - JsonSelect wildcards and bounds: ";
  Json *d = JsonParse("{\"name\": \"ktm\", \"price_3\": 2, \"items\": [10, 20, {\"k\": [true]}],"
                      " \"nested\": {\"price_9\": 9.5}}");

  Json *n = JsonSelect(d, ".*", "name");
  Json *p3 = JsonSelect(d, ".price_*", "3");
  Json *it = JsonSelect(d, ".items[*]", 1);
  Json *deep = JsonSelect(d, ".*[*].*[*]:b", "items", 2, "k", 0);
  Json *p9 = JsonSelect(d, ".nested.price_*:f", "9");
  tests++;
  if (n && JsonIsString(n) && !StrCmp(n->str, "ktm") && p3 && p3->i64 == 2 &&
      it && it->i64 == 20 && deep && deep->boolean && p9 && p9->f64 == 9.5) correct++;
  else "wildcards: %p %p %p %p %p\n", n, p3, it, deep, p9;

  /* plain paths still work */
  tests++;
  if (JsonSelect(d, ".items[0]")->i64 == 10 && JsonSelect(d, ".items[2].k[0]:b") &&
      JsonSelect(d, ".name:s") && !JsonSelect(d, ".name:i") &&
      !JsonSelect(d, ".items[3]") && !JsonSelect(d, ".missing") &&
      JsonSelect(d, ".items:a") && JsonSelect(d, ".nested:o")) correct++;

  /* malformed selectors are rejected (and never read past the end) */
  tests++;
  if (!JsonSelect(d, ".") && !JsonSelect(d, "[") && !JsonSelect(d, ".items[") &&
      !JsonSelect(d, ".items.") && !JsonSelect(d, ".items[1abc]") &&
      !JsonSelect(d, ".items[-1]") && !JsonSelect(d, ".items[]") &&
      !JsonSelect(d, ".items[ 1]") && !JsonSelect(d, ".name:sxyz") &&
      !JsonSelect(d, ".name:") && !JsonSelect(d, "..name") &&
      !JsonSelect(d, ".items]") && !JsonSelect(d, "name") && !JsonSelect(d, "") &&
      !JsonSelect(d, ".items[1") && !JsonSelect(d, ".items[*]", -1) &&
      !JsonSelect(d, ":*")) correct++;

  /* NULL document, selector or wildcard string */
  U8 *no_str = NULL;
  tests++;
  if (!JsonSelect(NULL, ".name") && !JsonSelect(d, NULL) &&
      !JsonSelect(d, ".*", no_str)) correct++;

  /* a component longer than the 256-byte path limit */
  U8 *long_sel = MAlloc(300);
  long_sel[0] = '.';
  MemSet(long_sel + 1, 'a', 280);
  long_sel[281] = '\0';
  tests++; if (!JsonSelect(d, long_sel)) correct++;
  Free(long_sel);

  JsonRelease(d);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
