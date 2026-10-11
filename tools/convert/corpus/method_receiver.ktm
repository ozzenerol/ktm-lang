// map: a container function is a method call only on a pointer of its type
U0 Main()
{
  List *l = ListNew;
  ListAppend(l, NULL);
  U0 *p = ListFirst(l);
  p = ListFirst(NULL);
  U0 *raw = l;
  ListCount(raw);
  IntVec *v = IntVecNew;
  IntVecPush(v, 3);
  IntVecPush(NULL, 4);
}
