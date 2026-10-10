#include "testhelper.ktm"

/* JsonParse ignored its flags argument: JSON_STRNUM_FLAG (numbers kept
 * as their text, JSON_STRNUM / ->strnum) behaved like flags 0. */

Bool StrNumIs(Json *j, U8 *want)
{
  return j && j->type == JSON_STRNUM && !StrCmp(j->strnum, want);
}

Bool ToStringIs(Json *j, U8 *want)
{
  U8 *s = JsonToString(j);
  Bool ok = s && !StrCmp(s, want);
  if (!ok) "got `%s`, want `%s`\n", s, want;
  Free(s);
  return ok;
}

I32 Main()
{
  "Test - JsonParse flags: ";
  I64 correct = 0, tests = 13;
  Json *j, *e;

  U8 *text = "{\"big\": 123456789012345678901234567890, \"f\": 1.10, "
             "\"neg\": -42, \"exp\": 1e+15, \"hex\": -5e-3, \"one\": 7, "
             "\"s\": \"5\", \"arr\": [1, 2.5,3], \"b\": true, \"n\": null}";

  /* Flags 0 and the default parse numbers */
  j = JsonParse(text, -1, 0);
  if (JsonOk(j) && JsonSelect(j, ".neg:i")->i64 == -42 &&
      JsonSelect(j, ".f:f")->f64 == 1.1) correct++;
  JsonRelease(j);
  j = JsonParse(text);
  if (JsonOk(j) && JsonSelect(j, ".one:i")->i64 == 7) correct++;
  JsonRelease(j);

  /* JSON_STRNUM_FLAG keeps every number as written */
  j = JsonParse(text, -1, JSON_STRNUM_FLAG);
  if (JsonOk(j)) correct++;
  if (StrNumIs(JsonSelect(j, ".big"), "123456789012345678901234567890")) correct++;
  if (StrNumIs(JsonSelect(j, ".f"), "1.10") &&
      StrNumIs(JsonSelect(j, ".neg"), "-42") &&
      StrNumIs(JsonSelect(j, ".exp"), "1e+15") &&
      StrNumIs(JsonSelect(j, ".hex"), "-5e-3") &&
      StrNumIs(JsonSelect(j, ".one"), "7")) correct++;
  /* The typed selectors no longer match a number */
  if (!JsonSelect(j, ".one:i") && !JsonSelect(j, ".f:f")) correct++;
  /* Other values are unchanged; a quoted "5" is still a string */
  e = JsonSelect(j, ".s");
  if (e && e->type == JSON_STRING && !StrCmp(e->str, "5") &&
      JsonSelect(j, ".b:b")->boolean == TRUE && JsonSelect(j, ".n:!")) correct++;
  e = JsonSelect(j, ".arr:a");
  if (e && StrNumIs(e->array, "1") && StrNumIs(e->array->next, "2.5") &&
      StrNumIs(e->array->next->next, "3") && !e->array->next->next->next) correct++;
  JsonRelease(j);
  /* JsonToString writes the text back unquoted */
  j = JsonParse("[1, 2.50,-3e2, {\"x\": 1E5}]", -1, JSON_STRNUM_FLAG);
  if (ToStringIs(j, "[1,2.50,-3e2,{\"x\": 1E5}]")) correct++;
  JsonRelease(j);

  /* A top-level number, and the length argument */
  j = JsonParse("  -0.000001  ", -1, JSON_STRNUM_FLAG);
  if (JsonOk(j) && StrNumIs(j, "-0.000001")) correct++;
  JsonRelease(j);
  j = JsonParse("[12345,6]trailing", 9, JSON_STRNUM_FLAG);
  if (JsonOk(j) && StrNumIs(j->array, "12345") &&
      StrNumIs(j->array->next, "6")) correct++;
  JsonRelease(j);

  /* Invalid numbers are still errors */
  j = JsonParse("[1.2.3]", -1, JSON_STRNUM_FLAG);
  if (!JsonOk(j) && j->state->error == JSON_INVALID_NUMBER) correct++;
  JsonRelease(j);
  j = JsonParse("{\"a\": 12z}", -1, JSON_STRNUM_FLAG);
  if (!JsonOk(j) && j->state->error == JSON_INVALID_NUMBER) correct++;
  JsonRelease(j);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
