#include "testhelper.ktm"

/* JsonParse(text, len) read past `len`: the string scan, number scan
 * (and strtoll/strtod) and whitespace skip all went on to a NUL. So a
 * string the length cuts off was read to a quote after it, and a number
 * took the digits after it. Strings cut off are now
 * JSON_INVALID_STRING_NOT_TERMINATED. JsonParse(NULL) returns NULL. */

/* Parse the first `len` bytes of `text`, which goes on past them */
Json *Cut(U8 *text, I64 len)
{
  return JsonParse(text, len);
}

Bool Unterminated(U8 *text, I64 len)
{
  Json *j = Cut(text, len);
  Bool ok = !JsonOk(j) && j->state->error == JSON_INVALID_STRING_NOT_TERMINATED;
  if (!ok) "[%s]/%d: ok %d error %d\n", text, len, JsonOk(j), j->state->error;
  JsonRelease(j);
  return ok;
}

I32 Main()
{
  "Test - JsonParse respects len: ";
  I64 correct = 0, tests = 0;
  Json *j;

  /* A string the length cuts off, with its quote just after */
  tests++;
  if (Unterminated("\"abcd\"", 5) && Unterminated("\"ab\\\"\"", 4) &&
      Unterminated("[\"x\",\"yz\"]", 7) && Unterminated("{\"key\":1}", 4) &&
      Unterminated("\"", 1))
    correct++;

  /* A number stops at `len` */
  tests++;
  j = Cut("1234567", 3);
  if (JsonOk(j) && j->type == JSON_INT && j->i64 == 123) correct++;
  JsonRelease(j);
  tests++;
  j = Cut("-12.5e3999", 7);
  if (JsonOk(j) && j->type == JSON_FLOAT && j->f64 == -12500.0) correct++;
  else "%d %d\n", JsonOk(j), j->type;
  JsonRelease(j);
  tests++;
  j = Cut("[1,23]", 4);
  if (!JsonOk(j)) correct++;
  JsonRelease(j);

  /* Whitespace and values at the end of the length */
  tests++;
  j = Cut("[1]  ", 5);
  Json *k = Cut("true!!", 4);
  Json *n = Cut("\"ok\"tail", 4);
  if (JsonOk(j) && JsonOk(k) && k->boolean == TRUE && JsonOk(n) &&
      !StrCmp(n->str, "ok")) correct++;
  JsonRelease(j);
  JsonRelease(k);
  JsonRelease(n);

  /* NULL */
  tests++;
  if (JsonParse(NULL) == NULL && JsonParse(NULL, 5) == NULL) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
