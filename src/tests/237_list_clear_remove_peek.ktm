#include "testhelper.ktm"

/* New list functions: ListClear (free the nodes, keep the head - also a
 * ListInit stack head), ListRemove (unlink + free one node, return its
 * value), ListFirst/ListLast (peek). Checked against an array model. */

I64 g_seed = 7;
I64 NextRand()
{
  g_seed = (g_seed * 6364136223846793005 + 1442695040888963407);
  return (g_seed >> 33) & 0x7FFFFFFF;
}

I64 g_freed, g_freed_sum;
U0 CountFree(U0 *v) { g_freed++; g_freed_sum += v(I64); }

#define MODEL_MAX 4096

/* The list holds model[0..n) in order, linked both ways */
Bool Matches(List *l, I64 *model, I64 n)
{
  List *it = l->next;
  for (I64 i = 0; i < n; ++i) {
    if (it == l || it->value(I64) != model[i] || it->next->prev != it) return FALSE;
    it = it->next;
  }
  if (it != l || l->prev->next != l) return FALSE;
  if (n == 0) return ListFirst(l) == NULL && ListLast(l) == NULL && ListEmpty(l);
  return ListFirst(l)(I64) == model[0] && ListLast(l)(I64) == model[n - 1];
}

List *NodeAt(List *l, I64 idx)
{
  List *it = l->next;
  while (idx-- > 0) it = it->next;
  return it;
}

Bool ModelRun(List *l, I64 ops)
{
  I64 model[MODEL_MAX];
  I64 n = 0, next_val = 1;
  for (I64 op = 0; op < ops; ++op) {
    I64 r = NextRand() % 100;
    if (r < 35 && n < MODEL_MAX) {
      ListAppend(l, next_val(U0 *));
      model[n++] = next_val++;
    } else if (r < 60 && n < MODEL_MAX) {
      ListPrepend(l, next_val(U0 *));
      for (I64 i = n; i > 0; --i) model[i] = model[i - 1];
      model[0] = next_val++;
      n++;
    } else if (r < 95 && n > 0) {
      I64 idx = NextRand() % n;
      I64 v = ListRemove(NodeAt(l, idx))(I64);
      if (v != model[idx]) return FALSE;
      for (I64 i = idx; i < n - 1; ++i) model[i] = model[i + 1];
      n--;
    } else if (r >= 99) {
      g_freed = 0;
      g_freed_sum = 0;
      I64 sum = 0;
      for (I64 i = 0; i < n; ++i) sum += model[i];
      ListClear(l, &CountFree);
      if (g_freed != n || g_freed_sum != sum) return FALSE;
      n = 0;
    }
    if (!Matches(l, model, n)) return FALSE;
  }
  return TRUE;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - list clear/remove/first/last: ";

  List *l = ListNew();
  tests++; if (ModelRun(l, 20000)) correct++;
  else "model mismatch\n";
  ListRelease(l, NULL);

  /* A ListInit head on the stack: clear it, reuse it, clear it again */
  List head;
  ListInit(&head);
  for (I64 i = 1; i <= 5; ++i) ListAppend(&head, i(U0 *));
  g_freed = 0;
  g_freed_sum = 0;
  ListClear(&head, &CountFree);
  Bool ok = g_freed == 5 && g_freed_sum == 15 && ListEmpty(&head) &&
            ListCount(&head) == 0;
  ListAppend(&head, 9(U0 *));
  ok = ok && ListFirst(&head)(I64) == 9 && ListLast(&head)(I64) == 9;
  ListClear(&head);  /* no callback */
  ok = ok && ListEmpty(&head);
  tests++; if (ok) correct++;

  /* Removing the only node, and the guards */
  List *one = ListNew();
  ListAppend(one, 42(U0 *));
  ok = ListRemove(one->next)(I64) == 42 && ListEmpty(one);
  ok = ok && ListRemove(one) == NULL && ListEmpty(one);  /* empty head: untouched */
  ok = ok && ListRemove(NULL) == NULL && ListFirst(NULL) == NULL &&
       ListLast(NULL) == NULL;
  ListClear(NULL, &CountFree);
  ListRelease(one, NULL);
  tests++; if (ok) correct++;

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
