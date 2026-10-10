#include "testhelper.ktm"

/* Deeply nested JSON (80000 `[`) overflowed the stack: the parser,
 * JsonToString and JsonRelease recurse once per level. Arrays and
 * objects may now nest JSON_MAX_DEPTH (512) deep; deeper input is the
 * error JSON_NESTING_TOO_DEEP. */

/* `n` levels of arrays, or of objects {"a": ...} when `obj` */
U8 *Nest(I64 n, Bool obj)
{
  U8 *s = MAlloc(6 * n + 2), *p = s;
  I64 i;
  for (i = 0; i < n; i++) {
    if (obj) { MemCpy(p, "{\"a\":", 5); p += 5; }
    else *p++ = '[';
  }
  *p++ = '1';
  U8 close = ']';
  if (obj) close = '}';
  for (i = 0; i < n; i++) *p++ = close;
  *p = '\0';
  return s;
}

I64 ParseErr(I64 n, Bool obj)
{ // the error (JSON_OK on success), after a JsonToString if it parsed
  U8 *s = Nest(n, obj);
  Json *j = JsonParse(s);
  I64 err = j->state->error;
  if (JsonOk(j)) {
    U8 *o = JsonToString(j);
    if (StrLen(o) < n) err = -1;
    Free(o);
  }
  JsonRelease(j);
  Free(s);
  return err;
}

I32 Main()
{
  "Test - JSON nesting limit: ";
  I64 correct = 0, tests = 0;

  tests++;
  if (JSON_MAX_DEPTH == 512 && ParseErr(1, FALSE) == JSON_OK &&
      ParseErr(512, FALSE) == JSON_OK && ParseErr(512, TRUE) == JSON_OK)
    correct++;
  tests++;
  if (ParseErr(513, FALSE) == JSON_NESTING_TOO_DEEP &&
      ParseErr(513, TRUE) == JSON_NESTING_TOO_DEEP)
    correct++;
  /* Far deeper input is an error, not a crash */
  tests++;
  if (ParseErr(80000, FALSE) == JSON_NESTING_TOO_DEEP &&
      ParseErr(80000, TRUE) == JSON_NESTING_TOO_DEEP &&
      ParseErr(300000, FALSE) == JSON_NESTING_TOO_DEEP)
    correct++;

  /* Siblings don't add up: only nesting counts */
  tests++;
  U8 *wide = MAlloc(4 * 2000 + 3), *p = wide;
  *p++ = '[';
  for (I64 i = 0; i < 2000; i++) { MemCpy(p, "[1],", 4); p += 4; }
  p[-1] = ']';
  *p = '\0';
  Json *j = JsonParse(wide);
  if (JsonOk(j)) correct++;
  JsonRelease(j);
  Free(wide);

  /* The message */
  tests++;
  U8 *s = Nest(600, FALSE);
  j = JsonParse(s);
  U8 *e = JsonGetStrerror(j);
  if (e && !StrNCmp(e, "Arrays and objects nested more than 512 deep", 44)) correct++;
  else "message: %s\n", e;
  Free(e);
  JsonRelease(j);
  Free(s);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
