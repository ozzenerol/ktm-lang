#include "testhelper.ktm"

/* ListMergeAppend/ListMergePrepend with an empty l2 linked the freed l2
 * head into l1 (use after free, endless walks). ListPop, ListCount and
 * ListRelease crashed on NULL while ListEmpty and ListDeque did not. */

List *Make(I64 from, I64 n)
{
  List *l = ListNew();
  for (I64 i = 0; i < n; ++i) ListAppend(l, (from + i)(U0 *));
  return l;
}

/* `l` holds exactly want[0..n), linked correctly both ways */
Bool Holds(List *l, I64 *want, I64 n)
{
  List *it = l->next;
  for (I64 i = 0; i < n; ++i) {
    if (it == l || it->value(I64) != want[i] || it->next->prev != it) return FALSE;
    it = it->next;
  }
  if (it != l) return FALSE;
  it = l->prev;
  for (I64 i = n - 1; i >= 0; --i) {
    if (it == l || it->value(I64) != want[i]) return FALSE;
    it = it->prev;
  }
  return it == l && ListCount(l) == n;
}

/* Merge an n1-list (1..) with an n2-list (100..), check, add one more
 * value at each end so a stale head would show, check again. */
Bool MergeCase(I64 n1, I64 n2, Bool append)
{
  List *l1 = Make(1, n1);
  List *l2 = Make(100, n2);
  I64 want[16];
  I64 n = 0;
  if (append) {
    for (I64 i = 0; i < n1; ++i) want[n++] = 1 + i;
    for (I64 i = 0; i < n2; ++i) want[n++] = 100 + i;
    ListMergeAppend(l1, l2);
  } else {
    for (I64 i = 0; i < n2; ++i) want[n++] = 100 + i;
    for (I64 i = 0; i < n1; ++i) want[n++] = 1 + i;
    ListMergePrepend(l1, l2);
  }
  Bool ok = Holds(l1, want, n);
  ListAppend(l1, 500(U0 *));
  ListPrepend(l1, 0(U0 *));
  I64 want2[18];
  want2[0] = 0;
  for (I64 i = 0; i < n; ++i) want2[i + 1] = want[i];
  want2[n + 1] = 500;
  ok = ok && Holds(l1, want2, n + 2);
  ListRelease(l1, NULL);
  return ok;
}

I32 Main()
{
  I64 correct = 0, tests = 0;
  "Test - list merge with an empty list: ";

  Bool ok = TRUE;
  I64 sizes[3] = {0, 1, 3};
  for (I64 a = 0; a < 3; ++a) {
    for (I64 b = 0; b < 3; ++b) {
      if (!MergeCase(sizes[a], sizes[b], TRUE)) {
        ok = FALSE; "append %d + %d wrong\n", sizes[a], sizes[b];
      }
      if (!MergeCase(sizes[a], sizes[b], FALSE)) {
        ok = FALSE; "prepend %d + %d wrong\n", sizes[a], sizes[b];
      }
    }
  }
  tests++; if (ok) correct++;

  /* Merging into a ListInit (stack) head */
  List stack_head;
  ListInit(&stack_head);
  ListMergeAppend(&stack_head, Make(7, 2));
  ListMergePrepend(&stack_head, ListNew());
  I64 w[2] = {7, 8};
  tests++; if (Holds(&stack_head, w, 2)) correct++;
  ListPop(&stack_head);
  ListPop(&stack_head);

  /* NULL is safe everywhere, like ListEmpty/ListDeque */
  List *l = Make(1, 2);
  ListMergeAppend(l, NULL);
  ListMergePrepend(l, NULL);
  ListMergeAppend(NULL, NULL);
  ListRelease(NULL, NULL);
  tests++;
  if (ListPop(NULL) == NULL && ListCount(NULL) == 0 && ListDeque(NULL) == NULL &&
      ListEmpty(NULL) && ListCount(l) == 2) correct++;
  ListRelease(l, NULL);

  PrintResult(correct, tests);
  "====\n";
  return 0;
}
