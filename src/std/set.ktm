/* Implementation of CLRS redblack tree for set operations can also use the 
 * redblack tree as a hashtable if you want */

#define __RB_RED__   0
#define __RB_BLACK__ 1


class RBNode;
class RBNode
{
  U0 *key;
  U0 *value;
  I64 color;
  RBNode *left;
  RBNode *right;
  RBNode *parent;
};

/* Each tree has its own sentinel (`nil`), the black leaf every path ends
 * in. The algorithms write its parent, so sharing one between trees would
 * be a data race between threads. It is the only node whose `left` is
 * NULL. */
class RBTree
{
  RBNode *root;
  I64 size;
  U0 (*free_key)(U0 *);
  U0 (*free_value)(U0 *);
  I64 (*key_cmp)(U0 *, U0 *);
  RBNode *nil;
  /* Bumped by every insert of a new key and every delete, so iterators
   * can tell the tree changed under them (their stack may then point at
   * freed nodes) and stop. */
  I64 version;
};

static Bool RBIsNil(RBNode *n)
{
  return n == NULL || n->left == NULL;
}

RBTree *RBTreeNew(U0 (*free_key)(U0 *), U0 (*free_value)(U0 *), 
                  I64 (*key_cmp)(U0 *, U0 *))
{
  RBTree *tree = MAlloc(sizeof(RBTree));
  RBNode *nil = MAlloc(sizeof(RBNode));
  nil->color = __RB_BLACK__;
  nil->left = NULL;
  nil->right = NULL;
  nil->parent = NULL;
  nil->key = NULL;
  nil->value = NULL;
  tree->free_key = free_key;
  tree->free_value = free_value;
  tree->key_cmp = key_cmp;
  tree->nil = nil;
  tree->root = nil;
  tree->size = 0;
  tree->version = 0;
  return tree;
}

static RBNode *RBNodeNew(RBTree *tree, U0 *key, U0 *value)
{
  RBNode *n = MAlloc(sizeof(RBNode));
  n->key = key;
  n->value = value;
  n->color = __RB_RED__;
  n->right = tree->nil;
  n->left = tree->nil;
  n->parent = NULL;
  return n;
}

static U0 RBNodeRelease(RBTree *tree, RBNode *n)
{
  if (n && n != tree->nil) {
    if (tree->free_key) tree->free_key(n->key);
    if (tree->free_value) tree->free_value(n->value);
    Free(n);
  }
}

static U0 RBLeftRot(RBTree *tree, RBNode *x)
{
  RBNode *y = x->right;
  x->right = y->left;
  if (y->left != tree->nil) {
    y->left->parent = x;
  }
  y->parent = x->parent;
  if (x->parent == NULL) {
    tree->root = y;
  } else if (x == x->parent->left) {
    x->parent->left = y;
  } else {
    x->parent->right = y;
  }
  y->left = x;
  x->parent = y;
}

static U0 RBRightRot(RBTree *tree, RBNode *y)
{
  RBNode *x = y->left;
  y->left = x->right;
  if (x->right != tree->nil) {
    x->right->parent = y;
  }
  x->parent = y->parent;
  if (y->parent == NULL) {
    tree->root = x;
  } else if (y == y->parent->right) {
    y->parent->right = x;
  } else {
    y->parent->left = x;
  }
  x->right = y;
  y->parent = x;
}

static RBNode *RBGrandParent(RBNode *n)
{
  if (n == NULL || n->parent == NULL) {
    return NULL;
  }
  return n->parent->parent;
}

static U0 RBInsertFixUp(RBTree *tree, RBNode *z)
{
  while (z->parent->color == __RB_RED__) {
    RBNode *y;
    if (z->parent == z->parent->parent->left) {
      y = z->parent->parent->right;
      if (y->color == __RB_RED__) {
        y->color = __RB_BLACK__;
        z->parent->color = __RB_BLACK__;
        z->parent->parent->color = __RB_RED__;
        z = z->parent->parent;
      } else {
        if (z == z->parent->right) {
          z = z->parent;
          RBLeftRot(tree, z);
        }
        z->parent->color = __RB_BLACK__;
        z->parent->parent->color = __RB_RED__;
        RBRightRot(tree, z->parent->parent);
      }
    } else {
      y = z->parent->parent->left;
      if (y->color == __RB_RED__) {
        y->color = __RB_BLACK__;
        z->parent->color = __RB_BLACK__;
        z->parent->parent->color = __RB_RED__;
        z = z->parent->parent;
      } else {
        if (z == z->parent->left) {
          z = z->parent;
          RBRightRot(tree, z);
        }
        z->parent->color = __RB_BLACK__;
        z->parent->parent->color = __RB_RED__;
        RBLeftRot(tree, z->parent->parent);
      }
    }
    if (z == tree->root) {
      break;
    }
  }
  tree->root->color = __RB_BLACK__;
}

U0 RBTreeInsert(RBTree *tree, U0 *key, U0 *value)
{
  if (!tree) return;
  RBNode *x = tree->root;
  RBNode *y = NULL;
  RBNode *z = NULL;
  I64 cmp = 0;

  while (x != tree->nil) {
    y = x;
    cmp = tree->key_cmp(key, x->key);

    if (cmp < 0) {
      x = x->left;
    } else if (cmp > 0) {
      x = x->right;
    } else {
      /* Updating an existing key keeps the stored key, like StrMapAdd: with
       * a key destructor the duplicate key is freed, and with a value
       * destructor the replaced value is freed (unless it is the same
       * pointer). */
      if (tree->free_key && x->key != key) tree->free_key(key);
      if (tree->free_value && x->value != value) tree->free_value(x->value);
      x->value = value;
      return;
    }
  }

  z = RBNodeNew(tree,key,value);
  z->parent = y;

  if (y == NULL) {
    tree->root = z;
  } else if (tree->key_cmp(z->key, y->key) < 0) {
    y->left = z;
  } else {
    y->right = z;
  }
  tree->size++;
  tree->version++;

  if (z->parent == NULL) {
    z->color = __RB_BLACK__;
    return;
  }

  if (z->parent->parent == NULL) {
    return;
  }

  RBInsertFixUp(tree,z);
}

static RBNode *RBNodeFind(RBTree *tree, RBNode *n, U0 *key)
{
  RBNode *cur = n;
  I64 cmp = 0;

  while (cur && cur != tree->nil) {
    cmp = tree->key_cmp(key, cur->key);
    if (cmp == 0) {
      return cur;
    } else if (cmp < 0) {
      cur = cur->left;
    } else {
      cur = cur->right;
    }
  }
  return NULL;
}

U0 *RBTreeGet(RBTree *tree, U0 *key)
{
  if (!tree) return NULL;
  RBNode *n = RBNodeFind(tree,tree->root,key);
  if (n) return n->value;
  return n;
}

Bool RBTreeHas(RBTree *tree, U0 *key)
{
  if (!tree) return FALSE;
  RBNode *n = RBNodeFind(tree, tree->root, key);
  return n != NULL;
}

static U0 RBTransplant(RBTree *tree, RBNode *u, RBNode *v)
{
  if (u->parent == NULL) {
    tree->root = v;
  } else if (u == u->parent->left) {
    u->parent->left = v;
  } else {
    u->parent->right = v;
  }
  v->parent = u->parent;
}

static RBNode *RBNodeMin(RBTree *tree, RBNode *n)
{
  while (n->left != tree->nil) n = n->left;
  return n;
}

static U0 RBTreeFixDelete(RBTree *tree, RBNode *x)
{
  RBNode *w = NULL;
  while (x != NULL && x != tree->root && x->color == __RB_BLACK__) {
    if (x == x->parent->left) {
      w = x->parent->right;
      if (w->color == __RB_RED__) {
        w->color = __RB_BLACK__;
        x->parent->color = __RB_RED__;
        RBLeftRot(tree, x->parent);
        w = x->parent->right;
      }
      if (w->left->color == __RB_BLACK__ && w->right->color == __RB_BLACK__) {
        w->color = __RB_RED__;
        x = x->parent;
      } else {
        if (w->right->color == __RB_BLACK__) {
          w->left->color = __RB_BLACK__;
          w->color = __RB_RED__;
          RBRightRot(tree, w);
          w = x->parent->right;
        }
        w->color = x->parent->color;
        x->parent->color = __RB_BLACK__;
        w->right->color = __RB_BLACK__;
        RBLeftRot(tree, x->parent);
        x = tree->root;
      }
    } else {
      w = x->parent->left;
      if (w->color == __RB_RED__) {
        w->color = __RB_BLACK__;
        x->parent->color = __RB_RED__;
        RBRightRot(tree, x->parent);
        w = x->parent->left;
      }
      if (w->right->color == __RB_BLACK__ && w->left->color == __RB_BLACK__) {
        w->color = __RB_RED__;
        x = x->parent;
      } else {
        if (w->left->color == __RB_BLACK__) {
          w->right->color = __RB_BLACK__;
          w->color = __RB_RED__;
          RBLeftRot(tree, w);
          w = x->parent->left;
        }
        w->color = x->parent->color;
        x->parent->color = __RB_BLACK__;
        w->left->color = __RB_BLACK__;
        RBRightRot(tree, x->parent);
        x = tree->root;
      }
    }
  }
  x->color = __RB_BLACK__;
}

U0 RBTreeDelete(RBTree *tree, U0 *key)
{
  if (!tree) return;
  RBNode *z = RBNodeFind(tree, tree->root, key);
  RBNode *y = NULL;
  RBNode *x = NULL;
  I64 original_color = -1;

  if (z == NULL) {
    return;
  }

  y = z;
  original_color = y->color;

  if (z->left == tree->nil) {
    x = z->right;
    RBTransplant(tree, z, z->right);
  } else if (z->right == tree->nil) {
    x = z->left;
    RBTransplant(tree, z, z->left);
  } else {
    y = RBNodeMin(tree, z->right);
    original_color = y->color;
    x = y->right;
    if (y != z->right) {
      RBTransplant(tree, y, y->right);
      y->right = z->right;
      y->right->parent = y;
    } else {
      x->parent = y;
    }
    RBTransplant(tree, z, y);
    y->left = z->left;
    y->left->parent = y;
    y->color = z->color;
  }

  if (original_color == __RB_BLACK__) {
    RBTreeFixDelete(tree, x);
  }

  RBNodeRelease(tree, z);
  tree->size--;
  tree->version++;
}

U0 RBTreeForEach(RBTree *tree,
                 U0 *user_data,
                 U0 (*callback)(U0 *user_data,
                                U0 *key, 
                                U0 *value))
{
  if (!tree) return;
  RBNode **stack = MAlloc(sizeof(RBNode*)*tree->size);
  I64 sp = 0, version = tree->version;
  RBNode *cur = tree->root;

  while (cur) {
    while (!RBIsNil(cur)) {
      stack[sp++] = cur;
      cur = cur->left;
    }

    if (sp == 0) break;
    cur = stack[--sp];
    callback(user_data,cur->key,cur->value);
    /* The callback inserted or deleted: stop before touching the stack,
     * which was sized for the old tree and may hold freed nodes. */
    if (tree->version != version) break;
    cur = cur->right;
  }

  Free(stack);
}

class RBIter
{
  I64 sp;
  RBNode **stack;
  RBNode *cur;
  RBTree *tree;
  I64 version; /* tree->version when the iterator was made */
};

RBIter *RBIterNew(RBTree *tree)
{
  RBIter *it = MAlloc(sizeof(RBIter));
  it->sp = 0;
  it->cur = NULL;
  it->tree = tree;
  it->version = 0;
  if (!tree) {
    it->stack = NULL;
    return it; /* yields nothing */
  }
  it->version = tree->version;
  it->stack = MAlloc(sizeof(RBNode*)*tree->size);
  RBNode *node = tree->root;
  while (!RBIsNil(node)) {
    it->stack[it->sp++] = node;
    node = node->left;
  }
  it->cur = tree->root;
  return it;
}

U0 RBIterRelease(RBIter *it)
{
  if (!it) return;
  Free(it->stack);
  Free(it);
}

RBNode *RBIterNext(RBIter *it)
{
  if (!it || it->sp == 0) {
    return NULL;
  }
  if (it->tree->version != it->version) {
    it->sp = 0; /* the tree changed: stop for good */
    return NULL;
  }

  RBNode *node = it->stack[--it->sp];
  auto right_tree = node->right;

  while (!RBIsNil(right_tree)) {
    it->stack[it->sp++] = right_tree;
    right_tree = right_tree->left;
  }
  return node;
}

U0 RBTreeRelease(RBTree *tree)
{// Free every node, its key and value (with the tree's destructors, if
 // set) and the tree itself
  if (!tree) return;
  RBIter *it = RBIterNew(tree);
  RBNode *node = NULL;
  /* RBIterNext has already pushed the node's right subtree, so the node
   * can be freed straight away */
  while ((node = RBIterNext(it)) != NULL) {
    RBNodeRelease(tree, node);
  }
  RBIterRelease(it);
  Free(tree->nil);
  Free(tree);
}

class IntSet
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

IntSet *IntSetNew()
{
  IntSet *set = MAlloc(sizeof(IntSet));
  set->tree = RBTreeNew(NULL,NULL,&IntSetCmp);
  return set;
}

I64 IntSetSize(IntSet *set)
{
  if (!set) return 0;
  return set->tree->size;
}

class IntSetEntry
{
  I64 key;
};

class IntSetIter
{
  I64 sp;
  RBNode **stack;
  IntSetEntry *entry; 
  RBTree *tree;
  I64 version; /* tree->version when the iterator was made */
};

IntSetIter *IntSetIterNew(IntSet *set)
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

IntSetEntry *IntSetIterNext(IntSetIter *it)
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

U0 IntSetIterRelease(IntSetIter *iter)
{
  if (!iter) return;
  Free(iter->entry);
  Free(iter->stack);
  Free(iter);
}

Bool IntSetHas(IntSet *set, I64 key)
{
  if (!set) return FALSE;
  return RBTreeHas(set->tree,key(U0*));
}

U0 IntSetAdd(IntSet *set, I64 key)
{
  return RBTreeInsert(set->tree,key(U0*),NULL);
}

U0 IntSetDelete(IntSet *set, I64 key)
{
  RBTreeDelete(set->tree,key(U0*));
}

IntSet *IntSetUnion(IntSet *s1, IntSet *s2)
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

IntSet *IntSetDiff(IntSet *s1, IntSet *s2)
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

IntSet *IntSetIntersection(IntSet *s1, IntSet *s2)
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

Bool IntSetEq(IntSet *s1, IntSet *s2)
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

IntVec *IntSetToVec(IntSet *set)
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

IntSet *IntSetSymDiff(IntSet *s1, IntSet *s2)
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

U8 *IntSetToString(IntSet *set)
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


U0 IntSetRelease(IntSet *set)
{
  if (!set) return;
  RBTreeRelease(set->tree);
  Free(set);
}

