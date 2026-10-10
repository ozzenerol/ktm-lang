#include "testhelper.ktm"

/* ktm syntax v0.2: true and false are bool literals (1 and 0) and null
 * is a null pointer (0 as a void *, so it converts to any pointer and to
 * function pointers, like C's NULL). TRUE, FALSE and NULL still work. */

class Node
{
  I64 v;
  Node *next;
};

Bool g_on = true;
Bool g_off = false;
U8 *g_str = null;
Node *g_node = null;
U0 (*g_fn)(I64) = null;

Bool Even(I64 x)
{
  if (x % 2) return false;
  return true;
}

Node *Find(Node *n, I64 v)
{
  for (; n != null; n = n->next) if (n->v == v) return n;
  return null;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - true, false and null: ";

  tests++;
  if (true == 1 && false == 0 && true == TRUE && false == FALSE && null == NULL)
    correct++;
  else "\n  values\n";

  tests++;   /* globals initialised with them */
  if (g_on && !g_off && g_str == null && g_node == null && g_fn == null) correct++;
  else "\n  globals\n";

  tests++;   /* locals of every pointer kind, without a cast or a warning */
  Bool b = true;
  U8 *s = null;
  Node *n = null;
  U0 (*fn)() = null;
  I64 **pp = null;
  if (b && !s && !n && !fn && !pp) correct++;
  else "\n  locals\n";

  tests++;   /* returned, compared, in conditions */
  Node a, c;
  a.v = 1;
  a.next = &c;
  c.v = 2;
  c.next = null;
  I64 loops = 0;
  while (false) loops++;
  if (true && !false) loops += 10;
  if (Even(4) == true && Even(3) == false && Find(&a, 2) == &c &&
      Find(&a, 9) == null && loops == 10) correct++;
  else "\n  expressions\n";

  tests++;   /* arithmetic with bool, sizeof */
  I64 i = true + true + false;
  if (i == 2 && sizeof(true) == 1 && sizeof(null) == 8) correct++;
  else "\n  arithmetic: %d %d %d\n", i, sizeof(true), sizeof(null);

  tests++;   /* null is a pointer: it can be passed where one is expected */
  Free(null);
  if (StrLen(StrPrint(null, "%d", true)) == 1) correct++;
  else "\n  as an argument\n";

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
