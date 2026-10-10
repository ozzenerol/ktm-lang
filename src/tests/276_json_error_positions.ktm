#include "testhelper.ktm"

/* JSON errors gave a byte "position" that was often past the problem
 * (`{"a",1}` skipped to the end looking for ':'; `[trux]` reported an
 * "end of json buffer"), printed a NUL as an empty '' and one message
 * had a stray newline; "JSON must start with '[' or '{'" was stale. They
 * now say what was expected and give the line and column. */

I64 g_bad = 0;

Bool Err(U8 *json, I64 code, U8 *want)
{
  Json *j = JsonParse(json);
  U8 *e = JsonGetStrerror(j);
  Bool ok = !JsonOk(j) && j->state->error == code && e && !StrCmp(e, want);
  if (!ok) "\"%s\": %d \"%s\"\n", json, j->state->error, e;
  Free(e);
  JsonRelease(j);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - JSON error positions: ";

  Json *spaced = JsonParse("{\"a\" : 1}");
  tests++;
  if (Err("{\"a\",1}", JSON_INVALID_KEY_VALUE_SEPARATOR,
          "Expected ':' after an object key, got ',' at line 1, column 5") &&
      Err("{\"a\" 1}", JSON_INVALID_KEY_VALUE_SEPARATOR,
          "Expected ':' after an object key, got '1' at line 1, column 6") &&
      JsonOk(spaced)) correct++;
  JsonRelease(spaced);

  tests++;
  if (Err("[trux]", JSON_INVALID_LITERAL,
          "Invalid literal, expected true, false or null at line 1, column 2") &&
      Err("[nul]", JSON_INVALID_LITERAL,
          "Invalid literal, expected true, false or null at line 1, column 2") &&
      Err("{\"a\":\n [1,\n  fals]}", JSON_INVALID_LITERAL,
          "Invalid literal, expected true, false or null at line 3, column 3")) correct++;

  tests++;
  if (Err("[1,2", JSON_INVALID_ARRAY_CHARACTER,
          "Expected ',' or ']' in an array, got '\\0' at line 1, column 5") &&
      Err("[1 2]", JSON_INVALID_ARRAY_CHARACTER,
          "Expected ',' or ']' in an array, got '2' at line 1, column 4") &&
      Err("{\n\"a\":1,\n}", JSON_INVALID_KEY_TERMINATOR_CHARACTER,
          "Expected '\"' to start an object key, got '}' at line 3, column 1") &&
      Err("", JSON_CANNOT_START_PARSE,
          "Expected a JSON value (object, array, string, number, true, false or null)"
          " at line 1, column 1") &&
      Err("[\"a\\q\"]", JSON_INVALID_ESCAPE_CHARACTER,
          "Invalid escape character 'q' after '\\' at line 1, column 5")) correct++;

  /* a control character is shown as \xNN; no message ends in a newline */
  tests++;
  if (Err("[true\x01]", JSON_INVALID_ARRAY_CHARACTER,
          "Expected ',' or ']' in an array, got '\\x01' at line 1, column 6")) correct++;

  /* success, NULL and inner nodes */
  Json *ok = JsonParse("{\"a\": [1]}");
  tests++;
  if (JsonGetStrerror(ok) == NULL && JsonGetStrerror(NULL) == NULL &&
      JsonGetStrerror(JsonSelect(ok, ".a")) == NULL) correct++;
  JsonPrintError(NULL);
  JsonPrintError(JsonSelect(ok, ".a[0]"));
  JsonRelease(ok);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
