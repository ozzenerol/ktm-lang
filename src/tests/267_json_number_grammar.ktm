#include "testhelper.ktm"

/* JSON numbers follow RFC 8259 now: -?(0|[1-9][0-9]*)(.[0-9]+)?
 * ([eE][+-]?[0-9]+)?. `-`, `+`, `.` gave -3, -5 and -2; `01 +1 .5 1. 0x1F
 * 1e 1e2.5 -abc --1` gave 0 and `1a 1-2` gave 1, also with
 * JSON_STRNUM_FLAG, which wrote the bad text back out. An integer outside
 * I64 saturated silently; it is now a JSON_FLOAT, as in JavaScript (with
 * STRNUM the text is kept). 1e400 is inf, and JsonToString writes inf and
 * nan as null. */

Bool Bad(U8 *text, I64 flags=0)
{
  Json *j = JsonParse(text, -1, flags);
  Bool ok = !JsonOk(j) && j->state->error == JSON_INVALID_NUMBER;
  if (!ok) "[%s] flags %d: error %d\n", text, flags, j->state->error;
  JsonRelease(j);
  return ok;
}

Bool Num(U8 *text, I64 type, U8 *out, I64 flags=0)
{ // parses as one value of `type`, written back as `out`
  Json *j = JsonParse(text, -1, flags);
  Bool ok = JsonOk(j) && j->type == type;
  if (ok) {
    U8 *s = JsonToString(j);
    ok = !StrCmp(s, out);
    if (!ok) "[%s] gave [%s] want [%s]\n", text, s, out;
    Free(s);
  } else {
    "[%s] error %d type %d\n", text, j->state->error, j->type;
  }
  JsonRelease(j);
  return ok;
}

I32 Main()
{
  "Test - JSON number grammar: ";
  I64 correct = 0, tests = 0, i;
  U8 *bad[22];
  bad[0] = "-"; bad[1] = "+"; bad[2] = "."; bad[3] = "01"; bad[4] = "+1";
  bad[5] = ".5"; bad[6] = "1."; bad[7] = "0x1F"; bad[8] = "1e"; bad[9] = "1e2.5";
  bad[10] = "-abc"; bad[11] = "--1"; bad[12] = "1a"; bad[13] = "1-2";
  bad[14] = "[1e+]"; bad[15] = "[-01]"; bad[16] = "[1.]"; bad[17] = "{\"a\":-}";
  bad[18] = "[1+2]"; bad[19] = "[1e2e3]"; bad[20] = "[0.e1]"; bad[21] = "[-.5]";

  tests++;
  Bool ok = TRUE;
  for (i = 0; i < 22; i++) {
    if (!Bad(bad[i]) || !Bad(bad[i], JSON_STRNUM_FLAG)) ok = FALSE;
  }
  if (ok) correct++;

  /* Valid numbers */
  tests++;
  if (Num("0", JSON_INT, "0") && Num("-0", JSON_INT, "0") &&
      Num("-1", JSON_INT, "-1") && Num("1.5", JSON_FLOAT, "1.5") &&
      Num("-0.0", JSON_FLOAT, "-0.0") && Num("1e22", JSON_FLOAT, "1.0e+22") &&
      Num("1E+30", JSON_FLOAT, "1.0e+30") && Num("1e-2", JSON_FLOAT, "0.01") &&
      Num("0e0", JSON_FLOAT, "0.0") && Num("[0,-0.5,10]", JSON_ARRAY, "[0,-0.5,10]") &&
      Num("{\"a\":1 , \"b\":2}", JSON_OBJECT, "{\"a\": 1,\"b\": 2}"))
    correct++;

  /* I64 limits stay integers, beyond them floats */
  tests++;
  if (Num("9223372036854775807", JSON_INT, "9223372036854775807") &&
      Num("-9223372036854775808", JSON_INT, "-9223372036854775808") &&
      Num("9223372036854775808", JSON_FLOAT, "9.223372036854776e+18") &&
      Num("-9223372036854775809", JSON_FLOAT, "-9.223372036854776e+18") &&
      Num("123456789012345678901234567890", JSON_FLOAT, "1.2345678901234568e+29"))
    correct++;

  /* With STRNUM the text is kept as written */
  tests++;
  if (Num("[123456789012345678901234567890,1e400,-0.0]", JSON_ARRAY,
          "[123456789012345678901234567890,1e400,-0.0]", JSON_STRNUM_FLAG))
    correct++;

  /* inf is written as null */
  tests++;
  Json *j = JsonParse("[1e400,-1e400]");
  if (JsonOk(j) && j->array->type == JSON_FLOAT && j->array->f64 > 1e308 &&
      j->array->next->f64 < -1e308) correct++;
  U8 *s = JsonToString(j);
  tests++;
  if (!StrCmp(s, "[null,null]")) correct++;
  else "[%s]\n", s;
  Free(s);
  JsonRelease(j);

  /* `len` ends the number even with more digits in the buffer */
  tests++;
  j = JsonParse("1234567", 3);
  if (JsonOk(j) && j->type == JSON_INT && j->i64 == 123) correct++;
  JsonRelease(j);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
