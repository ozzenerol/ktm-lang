#include "testhelper.ktm"

/* JsonParse rejected every \u escape. They are decoded to UTF-8 now,
 * including surrogate pairs; malformed ones are still errors. */

/* Parses `text` and returns the string at .e, or NULL if it didn't parse */
U8 *ParseE(U8 *text)
{
  Json *j = JsonParse(text);
  if (!JsonOk(j)) {
    JsonRelease(j);
    return NULL;
  }
  Json *e = JsonSelect(j, ".e:s");
  U8 *res = NULL;
  if (e) res = StrNew(e->str);
  JsonRelease(j);
  return res;
}

Bool Is(U8 *text, U8 *want)
{
  U8 *got = ParseE(text);
  Bool ok = got != NULL && !StrCmp(got, want);
  if (!ok) "\n  %s -> '%s', want '%s'\n", text, got, want;
  if (got) Free(got);
  return ok;
}

Bool Rejected(U8 *text)
{
  Json *j = JsonParse(text);
  Bool bad = !JsonOk(j);
  if (!bad) "\n  %s should be rejected\n", text;
  JsonRelease(j);
  return bad;
}

I32 Main()
{
  I64 correct = 0, tests = 20;
  "Test - JsonParse \\u escapes: ";

  if (Is("{\"e\": \"\\u0041\"}", "A")) correct++;
  if (Is("{\"e\": \"x\\u0041y\\u0042\"}", "xAyB")) correct++;
  if (Is("{\"e\": \"\\u00e9\"}", "\xC3\xA9")) correct++;            /* é, 2 bytes */
  if (Is("{\"e\": \"\\u00E9\"}", "\xC3\xA9")) correct++;            /* upper-case hex */
  if (Is("{\"e\": \"\\u007f\\u0080\"}", "\x7F\xC2\x80")) correct++; /* 1/2 byte edge */
  if (Is("{\"e\": \"\\u07FF\\u0800\"}", "\xDF\xBF\xE0\xA0\x80")) correct++;
  if (Is("{\"e\": \"\\u20AC\"}", "\xE2\x82\xAC")) correct++;        /* € */
  if (Is("{\"e\": \"\\uFFFF\"}", "\xEF\xBF\xBF")) correct++;
  if (Is("{\"e\": \"\\uD83D\\uDE00\"}", "\xF0\x9F\x98\x80")) correct++; /* U+1F600 */
  if (Is("{\"e\": \"\\uDBFF\\uDFFF\"}", "\xF4\x8F\xBF\xBF")) correct++; /* U+10FFFF */
  if (Is("{\"e\": \"\\u0022\\\\\\n\"}", "\"\\\n")) correct++;      /* mixed escapes */

  /* In an array, and as an object key */
  Json *j = JsonParse("[\"\\u0041\\u0042\", 1]");
  if (JsonOk(j) && !StrCmp(j->array->str, "AB")) correct++;
  JsonRelease(j);
  j = JsonParse("{\"\\u006By\": 7}");
  if (JsonOk(j) && JsonSelect(j, ".ky:i") && JsonSelect(j, ".ky:i")->i64 == 7) correct++;
  JsonRelease(j);

  /* Still rejected */
  if (Rejected("{\"e\": \"\\u12\"}")) correct++;          /* too short */
  if (Rejected("{\"e\": \"\\u12G4\"}")) correct++;        /* not hex */
  if (Rejected("{\"e\": \"\\uD83D\"}")) correct++;        /* lone high surrogate */
  if (Rejected("{\"e\": \"\\uD83Dx\"}")) correct++;
  if (Rejected("{\"e\": \"\\uD83D\\u0041\"}")) correct++; /* high + non-low */
  if (Rejected("{\"e\": \"\\uDE00\"}")) correct++;        /* lone low surrogate */
  if (Rejected("{\"e\": \"\\x41\"}")) correct++;          /* unknown escape */

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
