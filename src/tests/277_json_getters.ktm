#include "testhelper.ktm"

/* New getters: JsonObjectGet (exact key), JsonArrayGet (index) and
 * JsonLen (items of an array/object). Checked against JsonSelect on
 * random documents, plus the cases JsonSelect can't express. */

I64 g_seed = 5;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

/* A random JSON text: keys from a small set, so some repeat */
U8 *RandValue(U8 *s, I64 depth)
{
  I64 r = NextRand() % 10;
  if (depth > 3 || r < 4) {
    switch (NextRand() % 5) {
      case 0: return CatPrint(s, "%d", NextRand() % 1000 - 500);
      case 1: return CatPrint(s, "\"v%d\"", NextRand() % 50);
      case 2: return CatPrint(s, "true");
      case 3: return CatPrint(s, "null");
      default: return CatPrint(s, "%d.5", NextRand() % 100);
    }
  }
  I64 n = NextRand() % 6;
  if (r < 7) {
    s = CatPrint(s, "[");
    for (I64 i = 0; i < n; ++i) {
      if (i) s = CatPrint(s, ",");
      s = RandValue(s, depth + 1);
    }
    return CatPrint(s, "]");
  }
  s = CatPrint(s, "{");
  for (I64 i = 0; i < n; ++i) {
    if (i) s = CatPrint(s, ",");
    s = CatPrint(s, "\"k%d\":", NextRand() % 4);
    s = RandValue(s, depth + 1);
  }
  return CatPrint(s, "}");
}

I64 g_checks = 0;

/* Compare the getters with JsonSelect at `node` and below */
Bool Check(Json *node)
{
  Json *it;
  I64 n = 0;
  if (JsonIsArray(node)) {
    for (it = node->array; it; it = it->next) {
      if (JsonArrayGet(node, n) != it || JsonSelect(node, "[*]", n) != it) return FALSE;
      if (!Check(it)) return FALSE;
      n++;
      g_checks++;
    }
    if (JsonArrayGet(node, n) != NULL || JsonArrayGet(node, -1) != NULL) return FALSE;
  } else if (JsonIsObject(node)) {
    for (it = node->object; it; it = it->next) {
      /* JsonSelect and JsonObjectGet both give the first node with a key */
      U8 sel[32];
      StrPrint(sel, ".%s", it->key);
      if (JsonObjectGet(node, it->key) != JsonSelect(node, sel)) return FALSE;
      if (JsonObjectGet(node, it->key)->key != it->key &&
          StrCmp(JsonObjectGet(node, it->key)->key, it->key)) return FALSE;
      if (!Check(it)) return FALSE;
      n++;
      g_checks++;
    }
    if (JsonObjectGet(node, "nope") != NULL) return FALSE;
  } else {
    if (JsonLen(node) != 0 || JsonArrayGet(node, 0) || JsonObjectGet(node, "k0")) return FALSE;
  }
  return JsonLen(node) == n;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - JSON getters: ";

  Bool ok = TRUE;
  for (I64 i = 0; i < 300 && ok; ++i) {
    U8 *text = RandValue(NULL, 0);
    Json *d = JsonParse(text);
    if (!JsonOk(d) || !Check(d)) {
      "mismatch in %s\n", text;
      ok = FALSE;
    }
    JsonRelease(d);
    Free(text);
  }
  tests++; if (ok && g_checks > 500) correct++;

  /* keys JsonSelect can't spell, duplicates, the empty key */
  Json *d = JsonParse("{\"a.b\": 1, \"x[0]\": 2, \"t:s\": 3, \"\": 4, \"dup\": 5, \"dup\": 6,"
                      " \"arr\": [10, [20, 21], {}], \"e\": []}");
  tests++;
  if (JsonObjectGet(d, "a.b")->i64 == 1 && JsonObjectGet(d, "x[0]")->i64 == 2 &&
      JsonObjectGet(d, "t:s")->i64 == 3 && JsonObjectGet(d, "")->i64 == 4 &&
      JsonObjectGet(d, "dup")->i64 == 5 && !JsonSelect(d, ".a.b") &&
      JsonLen(d) == 8) correct++;

  Json *arr = JsonObjectGet(d, "arr");
  tests++;
  if (JsonLen(arr) == 3 && JsonArrayGet(arr, 0)->i64 == 10 &&
      JsonArrayGet(JsonArrayGet(arr, 1), 1)->i64 == 21 &&
      JsonLen(JsonArrayGet(arr, 2)) == 0 && JsonLen(JsonObjectGet(d, "e")) == 0 &&
      !JsonArrayGet(arr, 3) && !JsonArrayGet(arr, -1) &&
      !JsonArrayGet(arr, 0x7FFFFFFFFFFFFFFF)) correct++;

  /* wrong node types and NULL */
  tests++;
  if (!JsonObjectGet(arr, "0") && !JsonArrayGet(d, 0) && !JsonObjectGet(NULL, "a") &&
      !JsonObjectGet(d, NULL) && !JsonArrayGet(NULL, 0) && JsonLen(NULL) == 0 &&
      JsonLen(JsonArrayGet(arr, 0)) == 0) correct++;

  JsonRelease(d);
  PrintResult(correct, tests);
  "====\n";
  return 0;
}
