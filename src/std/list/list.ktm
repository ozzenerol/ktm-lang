module list;

public class List
{
  U0 *value;
  List *next, *prev;
};

public U0 ListInit(List *l)
{
  l->value = NULL;
  l->next = l->prev = l;
}

public List *ListNew()
{
  List *ll = MAlloc(sizeof(List));
  ll->value = NULL;
  ll->next = ll->prev = ll;
  return ll;
}

public U0 ListPrepend(List *head, U0 *value)
{
  List *node = ListNew();
  node->value = value;
  node->prev = head;
  node->next = head->next;
  head->next->prev = node;
  head->next = node;
}

public Bool ListEmpty(List *l)
{
  return (l == NULL || l->next == l);
}

public U0 ListAppend(List *head, U0 *value)
{
  List *tail = head->prev;
  List *node = ListNew();
  node->value = value;
  node->prev = tail;
  node->next = head;
  tail->next = node;
  head->prev = node;
}

public U0 *ListDeque(List *l)
{
  if (!l) return NULL;
  List *ll = l(List *);
  if (ll->next == ll) {
    return NULL;
  }
  List *node = ll->next;
  U0 *val = node->value;
  node->next->prev = ll;
  ll->next = ll->next->next;
  Free(node);
  return val;
}

public U0 ListInsertBefore(List *ll, List *new_node)
{
  new_node->next = ll;
  new_node->prev = ll->prev;
  ll->prev->next = new_node;
  ll->prev = new_node;
}

public U0 ListInsertValueBefore(List *ll, U0 *value)
{
  List *prev = ListNew();
  prev->value = value;
  ListInsertBefore(ll,prev);
}

public U0 *ListPop(List *l) {
  if (!l) return NULL;
  List *ll = l(List *);
  if (ll->next == ll) {
    return NULL;
  }
  List *node = ll->prev;
  U0 *val = node->value;
  node->prev->next = ll;
  ll->prev = ll->prev->prev;
  Free(node);
  return val;
}

public U0 ListRelease(List *ll, U0 (*FreeValue)(U0 *arg))
{
  if (!ll) return;
  List *node = ll->next;
  List *next;
  while (node != ll) {
    next = node->next;
    if (FreeValue) {
      FreeValue(node->value);
    }
    Free(node);
    node = next;
  }
  Free(ll);
}

public U0 ListClear(List *l, U0 (*free_value)(U0 *value)=NULL)
{// Free every node (calling free_value on each value, if given) and keep
 // the head, empty: works on a ListInit head too. NULL is ignored.
  if (!l) return;
  List *node = l->next;
  List *next;
  while (node != l) {
    next = node->next;
    if (free_value) {
      free_value(node->value);
    }
    Free(node);
    node = next;
  }
  l->next = l->prev = l;
}

public U0 *ListRemove(List *node)
{// Unlink `node` from its list, free it and return its value. Never pass
 // the list's head; an empty head (linked to itself) is left alone and
 // NULL is returned, as for a NULL node.
  if (!node || node->next == node) return NULL;
  U0 *value = node->value;
  node->prev->next = node->next;
  node->next->prev = node->prev;
  Free(node);
  return value;
}

public U0 *ListFirst(List *l)
{// The first value, without removing it; NULL if the list is empty or NULL
  if (!l || l->next == l) return NULL;
  return l->next->value;
}

public U0 *ListLast(List *l)
{// The last value, without removing it; NULL if the list is empty or NULL
  if (!l || l->prev == l) return NULL;
  return l->prev->value;
}

public I64 ListCount(List *ll)
{
  if (!ll || ll == ll->next) return 0;
  auto node = ll->next;
  I64 count = 0;
  while (node != ll) {
    count++;
    node = node->next;
  }
  return count;
}

/* Move the nodes of l2 to the start of l1 and Free l2's head
 * Example
 * l1 = [1,2,3]
 * l2 = [4,5,6]
 * ListMergePrepend(l1,l2);
 * l1 = [4,5,6,1,2,3] (and linked the otherway too)
 * l2 has been freed and must not be used again. An empty l2 is just
 * freed; with l1 or l2 NULL nothing happens.
 */
public U0 ListMergePrepend(List *l1, List *l2)
{
  if (!l1 || !l2) return;
  if (l2->next == l2) {
    Free(l2);
    return;
  }
  List *h1 = l1->next;

  List *h2 = l2->next;
  List *t2 = l2->prev;

  l1->next = h2;
  h2->prev = l1;
  t2->next = h1;
  h1->prev = t2;
  Free(l2);
}

/* Move the nodes of l2 to the end of l1 and Free l2's head.
 * Example
 * l1 = [1,2,3]
 * l2 = [4,5,6]
 * ListMergeAppend(l1,l2);
 * l1 = [1,2,3,4,5,6] (and linked the otherway too)
 * l2 has been freed and must not be used again. An empty l2 is just
 * freed; with l1 or l2 NULL nothing happens.
 * */
public U0 ListMergeAppend(List *l1, List *l2)
{
  if (!l1 || !l2) return;
  if (l2->next == l2) {
    Free(l2);
    return;
  }
  List *t1 = l1->prev;

  List *h2 = l2->next;
  List *t2 = l2->prev;

  h2->prev = t1;
  t1->next = h2;
  t2->next = l1;
  l1->prev = t2;
  Free(l2);
}
