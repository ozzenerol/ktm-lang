#include "testhelper.ktm"

/* JsonToString found each float's text by trying %.1g, %.2g, ... up to
 * %.17g until one read back as the same value: up to 17 formats per
 * number (500k floats took over a minute). It now tries %.15g, %.16g and
 * %.17g only (with C's snprintf), and every float still reads back
 * exactly. */

extern "c" F64 strtod(U8 *nptr, U8 **endptr);

Bool SameBits(F64 a, F64 b)
{
  U64 x, y;
  MemCpy(&x, &a, 8);
  MemCpy(&y, &b, 8);
  return x == y;
}

I32 Main()
{
  "Test - JSON float serialisation: ";
  I64 correct = 0, tests = 0, i;

  F64 vals[14];
  vals[0] = 0.1; vals[1] = 1.0 / 3.0; vals[2] = 5e-324; vals[3] = 1.7976931348623157e308;
  vals[4] = 2.2250738585072014e-308; vals[5] = -0.0; vals[6] = 1e21; vals[7] = 1e-7;
  vals[8] = 123456789.123; vals[9] = 9007199254740993.0; vals[10] = -79977321273640.0;
  vals[11] = 0.5; vals[12] = 100.0; vals[13] = -4.6602437438358e-310;

  /* Each one reads back bit for bit, and is written as a float */
  tests++;
  Bool ok = TRUE;
  U8 text[64];
  for (i = 0; i < 14; i++) {
    StrPrint(text, "[%.16e]", vals[i]); /* an exponent, so a float */
    Json *j = JsonParse(text);
    U8 *s = JsonToString(j);
    Json *k = JsonParse(s);
    if (!JsonOk(k) || k->array->type != JSON_FLOAT ||
        !SameBits(k->array->f64, vals[i])) {
      "%.17g gave %s\n", vals[i], s;
      ok = FALSE;
    }
    Free(s);
    JsonRelease(k);
    JsonRelease(j);
  }
  if (ok) correct++;

  /* Short values stay short */
  tests++;
  Json *j = JsonParse("[0.1,0.5,100.0,2.5e-3,1e21,-0.0]");
  U8 *s = JsonToString(j);
  if (!StrCmp(s, "[0.1,0.5,100.0,0.0025,1.0e+21,-0.0]")) correct++;
  else "[%s]\n", s;
  Free(s);
  JsonRelease(j);

  /* Many random bit patterns */
  tests++;
  ok = TRUE;
  U64 seed = 88172645463325252;
  for (i = 0; i < 20000; i++) {
    seed ^= seed << 13;
    seed ^= seed >> 7;
    seed ^= seed << 17;
    F64 f;
    MemCpy(&f, &seed, 8);
    if (((seed >> 52) & 0x7FF) == 0x7FF) continue; /* inf/nan */
    StrPrint(text, "%.16e", f);
    j = JsonParse(text);
    s = JsonToString(j);
    if (!SameBits(strtod(s, NULL), f)) {
      if (ok) "%s -> %s\n", text, s;
      ok = FALSE;
    }
    Free(s);
    JsonRelease(j);
  }
  if (ok) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
