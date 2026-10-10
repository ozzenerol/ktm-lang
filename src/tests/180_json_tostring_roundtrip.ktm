#include "testhelper.ktm"

/* JsonToString printed an empty object or array as `{(nil)}` / `[(nil)]`
 * and wrote strings with %Q (DolDoc quoting: `$` doubled, `\v`, raw
 * control characters), so its output couldn't always be parsed back. */

I64 g_correct = 0;

U0 Expect(U8 *got, U8 *want)
{
  if (!StrCmp(got, want)) g_correct++;
  else "\n  got  %s\n  want %s\n", got, want;
}

/* Parse -> string -> parse -> string must give the same text both times */
U0 RoundTrip(U8 *text, U8 *want)
{
  Json *a = JsonParse(text);
  if (!JsonOk(a)) {
    "\n  '%s' does not parse\n", text;
    return;
  }
  U8 *s1 = JsonToString(a);
  Expect(s1, want);
  Json *b = JsonParse(s1);
  if (JsonOk(b)) {
    U8 *s2 = JsonToString(b);
    Expect(s2, s1);
    Free(s2);
  } else {
    "\n  output '%s' does not parse back\n", s1;
  }
  Free(s1);
  JsonRelease(a);
  JsonRelease(b);
}

I32 Main()
{
  I64 tests = 17;
  "Test - JsonToString output parses back: ";

  RoundTrip("{\"a\": {}, \"b\": []}", "{\"a\": {},\"b\": []}");
  RoundTrip("[[], {}, [[]], {\"x\": {}}]", "[[],{},[[]],{\"x\": {}}]");
  RoundTrip("{\"s\": \"tab\\there\"}", "{\"s\": \"tab\\there\"}");
  RoundTrip("{\"s\": \"\\b\\f\\n\\r\\t\"}", "{\"s\": \"\\b\\f\\n\\r\\t\"}");
  RoundTrip("{\"s\": \"q\\\"b\\\\s\\/\"}", "{\"s\": \"q\\\"b\\\\s/\"}");
  RoundTrip("{\"$k%\": \"$1 100% {}[]\"}", "{\"$k%\": \"$1 100% {}[]\"}");
  RoundTrip("{\"k\\n\": 1}", "{\"k\\n\": 1}");

  /* Other control characters become \u00XX; DEL and UTF-8 stay as they are */
  Json *j = JsonParse("{\"s\": \"x\"}");
  Free(j->object->str);
  U8 *ctl = StrNew("a\x01b\x1Fc\x0B\x7F\xC3\xA9");
  j->object->str = ctl;
  j->object->str_len = StrLen(ctl); /* the length JsonToString writes */
  U8 *out = JsonToString(j);
  Expect(out, "{\"s\": \"a\\u0001b\\u001fc\\u000b\x7F\xC3\xA9\"}");
  Free(out);
  JsonRelease(j);

  /* A top-level object or array that has become empty */
  j = JsonParse("{\"a\": 1}");
  JsonRelease(j->object);
  j->object = NULL;
  out = JsonToString(j);
  Expect(out, "{}");
  Free(out);
  JsonRelease(j);
  j = JsonParse("[1]");
  JsonRelease(j->array);
  j->array = NULL;
  out = JsonToString(j);
  Expect(out, "[]");
  Free(out);
  JsonRelease(j);

  PrintResult(g_correct, tests);
  "====\n";
  return 0;
}
