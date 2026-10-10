#include "testhelper.ktm"

/* JsonParse only accepted a non-empty object or array at the top level:
 * `{}`, `[]`, `42`, `"text"`, `true` and `null` were errors (moving past the
 * last character of the input was treated as running off the end). */

I64 g_correct = 0;

/* Parses `text`, checks it is ok and has type `type` */
Json *Ok(U8 *text, I64 type)
{
  Json *j = JsonParse(text);
  if (JsonOk(j) && j->type == type) {
    g_correct++;
  } else {
    "\n  '%s' failed (type %d)\n", text, j->type;
  }
  return j;
}

U0 Bad(U8 *text)
{
  Json *j = JsonParse(text);
  if (!JsonOk(j)) g_correct++;
  else "\n  '%s' should be rejected\n", text;
  JsonRelease(j);
}

I32 Main()
{
  I64 tests = 41;
  Json *j;
  "Test - JsonParse top-level values: ";

  j = Ok("{}", JSON_OBJECT);
  if (j->object == NULL) g_correct++;
  JsonRelease(j);
  j = Ok("[]", JSON_ARRAY);
  if (j->array == NULL) g_correct++;
  JsonRelease(j);
  JsonRelease(Ok("  { }  ", JSON_OBJECT));
  JsonRelease(Ok("\n[\t]\n", JSON_ARRAY));

  j = Ok("42", JSON_INT);
  if (j->i64 == 42) g_correct++;
  JsonRelease(j);
  j = Ok("7", JSON_INT);       /* single digit */
  if (j->i64 == 7) g_correct++;
  JsonRelease(j);
  j = Ok("-12", JSON_INT);
  if (j->i64 == -12) g_correct++;
  JsonRelease(j);
  j = Ok("2.5", JSON_FLOAT);
  if (j->f64 == 2.5) g_correct++;
  JsonRelease(j);
  j = Ok("\"text\"", JSON_STRING);
  if (!StrCmp(j->str, "text")) g_correct++;
  JsonRelease(j);
  j = Ok("\"\"", JSON_STRING);
  if (!StrCmp(j->str, "")) g_correct++;
  JsonRelease(j);
  j = Ok("true", JSON_BOOL);
  if (j->boolean == TRUE) g_correct++;
  JsonRelease(j);
  j = Ok(" false ", JSON_BOOL);
  if (j->boolean == FALSE) g_correct++;
  JsonRelease(j);
  JsonRelease(Ok("null", JSON_NULL));

  /* Non-empty objects and arrays still work, also as the last thing */
  j = Ok("{\"a\": [1, 2]}", JSON_OBJECT);
  if (JsonSelect(j, ".a[1]:i")->i64 == 2) g_correct++;
  JsonRelease(j);
  j = Ok("[{}, [], \"x\"]", JSON_ARRAY);
  if (j->array->type == JSON_OBJECT && j->array->next->type == JSON_ARRAY) g_correct++;
  JsonRelease(j);
  JsonRelease(Ok("{\"a\": true}", JSON_OBJECT));
  JsonRelease(Ok("[null]", JSON_ARRAY));

  /* The explicit length is respected */
  j = JsonParse("[1]trailing", 3);
  if (JsonOk(j) && j->array->i64 == 1) g_correct++;
  JsonRelease(j);
  j = JsonParse("truex", 4);
  if (JsonOk(j) && j->type == JSON_BOOL) g_correct++;
  JsonRelease(j);

  /* Still errors */
  Bad("");
  Bad("   ");
  Bad("{");
  Bad("[");
  Bad("[1,");
  Bad("\"abc");
  Bad("tru");
  Bad("nul");
  Bad("nulx");
  Bad("x");

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
