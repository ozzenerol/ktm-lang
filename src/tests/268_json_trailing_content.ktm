#include "testhelper.ktm"

/* JsonParse accepted text after the top-level value (`[1]x`, `1 2`,
 * `truex`, `{"a":1}}`), skipped anything between a key and its ':'
 * (`{"a" , "b": 1}` was {"a": 1}), and took \v and \f for whitespace.
 * Only JSON's whitespace (space, tab, LF, CR) may follow the value or sit
 * between a key and ':'. */

Bool Err(U8 *text, I64 len, I64 error)
{
  Json *j = JsonParse(text, len);
  Bool ok = !JsonOk(j) && j->state->error == error;
  if (!ok) "[%s]: error %d want %d\n", text, j->state->error, error;
  JsonRelease(j);
  return ok;
}

Bool Ok(U8 *text, U8 *want, I64 len=-1)
{
  Json *j = JsonParse(text, len);
  Bool ok = JsonOk(j);
  if (ok) {
    U8 *s = JsonToString(j);
    ok = !StrCmp(s, want);
    if (!ok) "[%s] gave [%s]\n", text, s;
    Free(s);
  } else {
    "[%s] rejected: %d\n", text, j->state->error;
  }
  JsonRelease(j);
  return ok;
}

I32 Main()
{
  "Test - JSON trailing content and separators: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (Err("[1]x", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("1 2", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("truex", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("nullx", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("\"a\"x", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("{\"a\":1}}", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("[][]", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("[1]//x", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("[1]\x00[2]", 7, JSON_INVALID_JSON_TYPE_CHAR))
    correct++;

  /* Exactly ':' after a key */
  tests++;
  if (Err("{\"a\" , \"b\": 1}", -1, JSON_INVALID_KEY_VALUE_SEPARATOR) &&
      Err("{\"a\" x : 1}", -1, JSON_INVALID_KEY_VALUE_SEPARATOR) &&
      Err("{\"a\" 1}", -1, JSON_INVALID_KEY_VALUE_SEPARATOR) &&
      Err("{\"a\",1}", -1, JSON_INVALID_KEY_VALUE_SEPARATOR))
    correct++;

  /* \v and \f are not whitespace */
  tests++;
  if (Err("[\x0b" "1]", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("[\x0c" "1]", -1, JSON_INVALID_JSON_TYPE_CHAR) &&
      Err("[1]\x0b", -1, JSON_INVALID_JSON_TYPE_CHAR))
    correct++;

  /* Whitespace everywhere it may be */
  tests++;
  if (Ok(" \t\r\n[ 1 , 2 ]\n ", "[1,2]") &&
      Ok("{ \"a\" \t:\r\n 1 , \"b\" : [ ] }", "{\"a\": 1,\"b\": []}") &&
      Ok("{\"a\":1}\n", "{\"a\": 1}") && Ok("1 ", "1") && Ok("true", "true") &&
      Ok("[1]", "[1]") && Ok("{}", "{}") && Ok("[[[]]]", "[[[]]]"))
    correct++;

  /* `len` ends the input: what is after it doesn't count */
  tests++;
  if (Ok("[1]xyz", "[1]", 3) && Ok("{\"a\":1}}", "{\"a\": 1}", 7)) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
