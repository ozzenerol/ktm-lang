module map;

public class RBNode;

public class RBNode
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
public class RBTree
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

public RBTree *RBTreeNew(U0 (*free_key)(U0 *), U0 (*free_value)(U0 *), 
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

public U0 RBTreeInsert(RBTree *tree, U0 *key, U0 *value)
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

public U0 *RBTreeGet(RBTree *tree, U0 *key)
{
  if (!tree) return NULL;
  RBNode *n = RBNodeFind(tree,tree->root,key);
  if (n) return n->value;
  return n;
}

public Bool RBTreeHas(RBTree *tree, U0 *key)
{
  if (!tree) return FALSE;
  RBNode *n = RBNodeFind(tree, tree->root, key);
  return n != NULL;
}

public U0 RBTreeDelete(RBTree *tree, U0 *key)
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

public U0 RBTreeForEach(RBTree *tree,
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

public class RBIter
{
  I64 sp;
  RBNode **stack;
  RBNode *cur;
  RBTree *tree;
  I64 version; /* tree->version when the iterator was made */
};

public RBIter *RBIterNew(RBTree *tree)
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

public U0 RBIterRelease(RBIter *it)
{
  if (!it) return;
  Free(it->stack);
  Free(it);
}

public RBNode *RBIterNext(RBIter *it)
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

public U0 RBTreeRelease(RBTree *tree)
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
