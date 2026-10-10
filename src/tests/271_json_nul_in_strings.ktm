#include "testhelper.ktm"

/* A \u0000 in a JSON string or key cut it short: the parser decoded it
 * into a '\0' and everything after it was lost, also in JsonToString.
 * Json now has `str_len` and `key_len` (at the end of the class) with
 * the full length; JsonToString writes the '\0' back as \u0000.
 * JsonSelect still matches a key up to its first '\0'. */

Bool Bytes(U8 *s, I64 len, U8 *want, I64 want_len)
{
  if (len != want_len) {
    "len %d want %d\n", len, want_len;
    return FALSE;
  }
  for (I64 i = 0; i < len; i++) {
    if (s[i] != want[i]) return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  "Test - JSON \\u0000 in strings and keys: ";
  I64 correct = 0, tests = 0;

  Json *j = JsonParse("[\"a\\u0000b\", \"\\u0000\", \"plain\", \"\"]");
  tests++;
  Json *e = j->array;
  if (JsonOk(j) && Bytes(e->str, e->str_len, "a\0b", 3) &&
      Bytes(e->next->str, e->next->str_len, "\0", 1) &&
      e->next->next->str_len == 5 && e->next->next->next->str_len == 0)
    correct++;
  tests++;
  U8 *s = JsonToString(j);
  if (!StrCmp(s, "[\"a\\u0000b\",\"\\u0000\",\"plain\",\"\"]")) correct++;
  else "[%s]\n", s;
  /* ...which parses back the same */
  tests++;
  Json *k = JsonParse(s);
  U8 *s2 = JsonToString(k);
  if (JsonOk(k) && !StrCmp(s, s2)) correct++;
  Free(s);
  Free(s2);
  JsonRelease(k);
  JsonRelease(j);

  /* Keys */
  j = JsonParse("{\"a\\u0000b\": 1, \"c\": \"x\\u0000\"}");
  tests++;
  if (JsonOk(j) && Bytes(j->object->key, j->object->key_len, "a\0b", 3) &&
      j->object->next->key_len == 1 &&
      Bytes(j->object->next->str, j->object->next->str_len, "x\0", 2))
    correct++;
  tests++;
  s = JsonToString(j);
  if (!StrCmp(s, "{\"a\\u0000b\": 1,\"c\": \"x\\u0000\"}")) correct++;
  else "[%s]\n", s;
  Free(s);
  /* A key with a '\0' is selected by what comes before it */
  tests++;
  Json *a = JsonSelect(j, ".a:i");
  if (a && a->i64 == 1) correct++;
  JsonRelease(j);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
