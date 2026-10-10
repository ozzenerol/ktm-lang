#include "testhelper.ktm"

/* A JSON string ending in an escaped backslash ("C:\\") was rejected:
 * the end-of-string scan took `\"` for an escaped quote. Raw control
 * characters (bytes below 0x20) were accepted inside strings; RFC 8259
 * requires them escaped, so they are now JSON_INVALID_CONTROL_CHARACTER. */

Bool Parses(U8 *text, U8 *want)
{ // parses, and JsonToString gives `want`
  Json *j = JsonParse(text);
  Bool ok = JsonOk(j);
  if (ok) {
    U8 *s = JsonToString(j);
    ok = !StrCmp(s, want);
    if (!ok) "[%s] gave [%s] want [%s]\n", text, s, want;
    Free(s);
  } else {
    "[%s] rejected: error %d\n", text, j->state->error;
  }
  JsonRelease(j);
  return ok;
}

Bool Rejected(U8 *text, I64 len, I64 error)
{
  Json *j = JsonParse(text, len);
  Bool ok = !JsonOk(j) && j->state->error == error;
  if (!ok) "[%s] error %d want %d\n", text, j->state->error, error;
  JsonRelease(j);
  return ok;
}

I32 Main()
{
  "Test - JSON strings: trailing backslash, control characters: ";
  I64 correct = 0, tests = 0, i;

  tests++;
  if (Parses("{\"path\":\"C:\\\\\"}", "{\"path\": \"C:\\\\\"}") &&
      Parses("[\"\\\\\"]", "[\"\\\\\"]") &&
      Parses("{\"\\\\\":1}", "{\"\\\\\": 1}") &&
      Parses("[\"\\\\\",\"x\"]", "[\"\\\\\",\"x\"]") &&
      Parses("[\"\\\\\\\"\"]", "[\"\\\\\\\"\"]") &&
      Parses("\"a\\\\\\\\b\\\\\"", "\"a\\\\\\\\b\\\\\""))
    correct++;

  /* The value is right too */
  tests++;
  Json *j = JsonParse("{\"p\":\"C:\\\\\",\"q\":\"\\\"x\\\"\"}");
  if (JsonOk(j) && !StrCmp(JsonSelect(j, ".p:s")->str, "C:\\") &&
      !StrCmp(JsonSelect(j, ".q:s")->str, "\"x\"")) correct++;
  JsonRelease(j);

  /* Every raw control character is rejected, in values and keys */
  tests++;
  U8 buf[16];
  Bool ok = TRUE;
  for (i = 1; i < 0x20; i++) {
    StrCpy(buf, "[\"a?b\"]");
    buf[3] = i;
    if (!Rejected(buf, 7, JSON_INVALID_CONTROL_CHARACTER)) ok = FALSE;
    StrCpy(buf, "{\"k?\":1}");
    buf[3] = i;
    if (!Rejected(buf, 8, JSON_INVALID_CONTROL_CHARACTER)) ok = FALSE;
  }
  if (ok) correct++;

  /* Escaped control characters, DEL and UTF-8 are fine */
  tests++;
  if (Parses("[\"a\\tb\\n\\u0001\"]", "[\"a\\tb\\n\\u0001\"]") &&
      Parses("[\"\x7f\xc3\xa9\"]", "[\"\x7f\xc3\xa9\"]"))
    correct++;

  /* The error message names it */
  tests++;
  j = JsonParse("[\"a\tb\"]");
  U8 *e = JsonGetStrerror(j);
  if (e && !StrCmp(e, "Unescaped control character '\\x09' in a string "
                     "(write it as \\u0009) at line 1, column 4")) correct++;
  else "message: %s\n", e;
  Free(e);
  JsonRelease(j);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
