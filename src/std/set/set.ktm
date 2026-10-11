module set;

static RBNode *RBGrandParent(RBNode *n)
{
  if (n == NULL || n->parent == NULL) {
    return NULL;
  }
  return n->parent->parent;
}

public class IntSet
{
  RBTree *tree; 
};

I64 IntSetCmp(U0 *k1, U0 *k2)
{
  I64 _k1 = k1(I64);
  I64 _k2 = k2(I64);
  if (_k1 < _k2) return -1;
  if (_k1 > _k2) return 1;
  return 0;
}

public IntSet *IntSetNew()
{
  IntSet *set = MAlloc(sizeof(IntSet));
  set->tree = RBTreeNew(NULL,NULL,&IntSetCmp);
  return set;
}

public I64 IntSetSize(IntSet *set)
{
  if (!set) return 0;
  return set->tree->size;
}

public class IntSetEntry
{
  I64 key;
};

public class IntSetIter
{
  I64 sp;
  RBNode **stack;
  IntSetEntry *entry; 
  RBTree *tree;
  I64 version; /* tree->version when the iterator was made */
};

public IntSetIter *IntSetIterNew(IntSet *set)
{
  IntSetIter *it = MAlloc(sizeof(IntSetIter));
  it->sp = 0;
  it->entry = MAlloc(sizeof(IntSetEntry));
  it->stack = MAlloc(sizeof(RBNode *) * IntSetSize(set));
  it->tree = NULL;
  it->version = 0;
  if (!set) return it; /* yields nothing */
  it->tree = set->tree;
  it->version = set->tree->version;
  RBNode *node = set->tree->root;
  while (!RBIsNil(node)) {
    it->stack[it->sp++] = node;
    node = node->left;
  }
  return it;
}

public IntSetEntry *IntSetIterNext(IntSetIter *it)
{
  if (!it || it->sp == 0) {
    return NULL;
  }
  if (it->tree->version != it->version) {
    it->sp = 0; /* the set changed: stop for good */
    return NULL;
  }

  RBNode *node = it->stack[--it->sp];
  it->entry->key = node->key(I64);
  auto right_tree = node->right;

  while (!RBIsNil(right_tree)) {
    it->stack[it->sp++] = right_tree;
    right_tree = right_tree->left;
  }

  return it->entry;
}

public U0 IntSetIterRelease(IntSetIter *iter)
{
  if (!iter) return;
  Free(iter->entry);
  Free(iter->stack);
  Free(iter);
}

public Bool IntSetHas(IntSet *set, I64 key)
{
  if (!set) return FALSE;
  return RBTreeHas(set->tree,key(U0*));
}

public U0 IntSetAdd(IntSet *set, I64 key)
{
  return RBTreeInsert(set->tree,key(U0*),NULL);
}

public U0 IntSetDelete(IntSet *set, I64 key)
{
  RBTreeDelete(set->tree,key(U0*));
}

public IntSet *IntSetUnion(IntSet *s1, IntSet *s2)
{
  IntSet *union_set = IntSetNew;
  IntSetIter *i1 = IntSetIterNew(s1);
  IntSetIter *i2 = IntSetIterNew(s2);
  IntSetEntry *entry = NULL;

  while ((entry = IntSetIterNext(i1))) {
    IntSetAdd(union_set,entry->key);
  }
  
  while ((entry = IntSetIterNext(i2))) {
    IntSetAdd(union_set,entry->key);
  }

  IntSetIterRelease(i1);
  IntSetIterRelease(i2);
  return union_set;
}

public IntSet *IntSetDiff(IntSet *s1, IntSet *s2)
{
  IntSet *diff_set = IntSetNew;
  IntSetIter *i1 = IntSetIterNew(s1);
  IntSetEntry *entry = NULL;

  while ((entry = IntSetIterNext(i1))) {
    if (!IntSetHas(s2, entry->key(I64))) {
      IntSetAdd(diff_set,entry->key(I64));
    }
  }
  
  IntSetIterRelease(i1);
  return diff_set;
}

public IntSet *IntSetIntersection(IntSet *s1, IntSet *s2)
{
  IntSet *intersection_set = IntSetNew;
  IntSetIter *i1 = IntSetIterNew(s1);
  IntSetIter *i2 = IntSetIterNew(s2);
  IntSetEntry *entry = NULL;

  while ((entry = IntSetIterNext(i1))) {
    if (IntSetHas(s2,entry->key)) {
      IntSetAdd(intersection_set,entry->key);
    }
  }

  while ((entry = IntSetIterNext(i2))) {
    if (IntSetHas(s1,entry->key)) {
      IntSetAdd(intersection_set,entry->key);
    }
  }

  IntSetIterRelease(i1);
  IntSetIterRelease(i2);
  return intersection_set;
}

public Bool IntSetEq(IntSet *s1, IntSet *s2)
{
  if (IntSetSize(s1) != IntSetSize(s2)) return FALSE;

  IntSetIter *i1 = IntSetIterNew(s1);
  IntSetIter *i2 = IntSetIterNew(s2);
  IntSetEntry *entry = NULL;
  Bool retval = TRUE;

  while ((entry = IntSetIterNext(i1))) {
    if (!IntSetHas(s2,entry->key)) {
      retval = FALSE;
      goto out;
    }
  }

  while ((entry = IntSetIterNext(i2))) {
    if (!IntSetHas(s1,entry->key)) {
      retval = FALSE;
      goto out;
    }
  }

out:
  IntSetIterRelease(i1);
  IntSetIterRelease(i2);
  return retval;
}

public IntVec *IntSetToVec(IntSet *set)
{
  IntVec *vec = IntVecNew;
  IntSetIter *i1 = IntSetIterNew(set);
  IntSetEntry *entry = NULL;
  while ((entry = IntSetIterNext(i1))) {
    IntVecPush(vec,entry->key);
  }
  IntSetIterRelease(i1);
  return vec;
}

public IntSet *IntSetSymDiff(IntSet *s1, IntSet *s2)
{
  IntSet *sym_diff_set = IntSetNew();
  IntSetIter *i1 = IntSetIterNew(s1);
  IntSetIter *i2 = IntSetIterNew(s2);
  IntSetEntry *entry = NULL;

  /* Add elements from s1 that are not in s2 */
  while ((entry = IntSetIterNext(i1))) {
    if (!IntSetHas(s2,entry->key)) {
      IntSetAdd(sym_diff_set,entry->key);
    }
  }

  /* Add elements from s2 that are not in s1 */
  while ((entry = IntSetIterNext(i2))) {
    if (!IntSetHas(s1,entry->key)) {
      IntSetAdd(sym_diff_set,entry->key);
    }
  }

  IntSetIterRelease(i1);
  IntSetIterRelease(i2);
  return sym_diff_set;
}

public U8 *IntSetToString(IntSet *set)
{
  I64 len = 1;
  U8 *buf = StrNew("{");
  IntSetIter *i1 = IntSetIterNew(set);
  IntSetEntry *entry = NULL;
  Bool first = TRUE;

  while ((entry = IntSetIterNext(i1))) {
    if (first) buf = CatLenPrint(buf, &len, "%d", entry->key);
    else       buf = CatLenPrint(buf, &len, ", %d", entry->key);
    first = FALSE;
  }
  IntSetIterRelease(i1);
  return CatLenPrint(buf, &len, "}");
}

public U0 IntSetRelease(IntSet *set)
{
  if (!set) return;
  RBTreeRelease(set->tree);
  Free(set);
}
