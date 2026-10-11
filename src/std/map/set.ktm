module map;
import mem;

/* Implementation of CLRS redblack tree for set operations can also use the 
    * redblack tree as a hashtable if you want */

const i64 __RB_RED__ = 0;

const i64 __RB_BLACK__ = 1;

pub struct RBNode;

pub struct RBNode {
        void *key;
        void *value;
        i64 color;
        RBNode *left;
        RBNode *right;
        RBNode *parent;
};

/* Each tree has its own sentinel (`nil`), the black leaf every path ends
    * in. The algorithms write its parent, so sharing one between trees would
    * be a data race between threads. It is the only node whose `left` is
    * null. */
pub struct RBTree {
        RBNode *root;
        i64 size;
        void (*free_key)(void *);
        void (*free_value)(void *);
        i64 (*key_cmp)(void *, void *);
        RBNode *nil;
        /* Bumped by every insert of a new key and every delete, so iterators
            * can tell the tree changed under them (their stack may then point at
            * freed nodes) and stop. */
        i64 version;
};

static bool rbIsNil(RBNode *n) {
        return n == null || n.left == null;
}

pub RBTree *rbTreeNew(fn(void *) void free_key, fn(void *) void free_value, 
                                                                        fn(void *, void *) i64 key_cmp) {
        RBTree *tree = memAlloc(sizeof(RBTree));
        RBNode *nil = memAlloc(sizeof(RBNode));
        nil.color = __RB_BLACK__;
        nil.left = null;
        nil.right = null;
        nil.parent = null;
        nil.key = null;
        nil.value = null;
        tree.free_key = free_key;
        tree.free_value = free_value;
        tree.key_cmp = key_cmp;
        tree.nil = nil;
        tree.root = nil;
        tree.size = 0;
        tree.version = 0;
        return tree;
}

static RBNode *rbNodeNew(RBTree *tree, void *key, void *value) {
        RBNode *n = memAlloc(sizeof(RBNode));
        n.key = key;
        n.value = value;
        n.color = __RB_RED__;
        n.right = tree.nil;
        n.left = tree.nil;
        n.parent = null;
        return n;
}

static void rbNodeRelease(RBTree *tree, RBNode *n) {
        if (n && n != tree.nil) {
                if (tree.free_key) { tree.free_key(n.key); }
                if (tree.free_value) { tree.free_value(n.value); }
                memFree(n);
        }
}

static void rbLeftRot(RBTree *tree, RBNode *x) {
        RBNode *y = x.right;
        x.right = y.left;
        if (y.left != tree.nil) {
                y.left.parent = x;
        }
        y.parent = x.parent;
        if (x.parent == null) {
                tree.root = y;
        } else if (x == x.parent.left) {
                x.parent.left = y;
        } else {
                x.parent.right = y;
        }
        y.left = x;
        x.parent = y;
}

static void rbRightRot(RBTree *tree, RBNode *y) {
        RBNode *x = y.left;
        y.left = x.right;
        if (x.right != tree.nil) {
                x.right.parent = y;
        }
        x.parent = y.parent;
        if (y.parent == null) {
                tree.root = x;
        } else if (y == y.parent.right) {
                y.parent.right = x;
        } else {
                y.parent.left = x;
        }
        x.right = y;
        y.parent = x;
}

static void rbInsertFixUp(RBTree *tree, RBNode *z) {
        while (z.parent.color == __RB_RED__) {
                RBNode *y;
                if (z.parent == z.parent.parent.left) {
                        y = z.parent.parent.right;
                        if (y.color == __RB_RED__) {
                                y.color = __RB_BLACK__;
                                z.parent.color = __RB_BLACK__;
                                z.parent.parent.color = __RB_RED__;
                                z = z.parent.parent;
                        } else {
                                if (z == z.parent.right) {
                                        z = z.parent;
                                        rbLeftRot(tree, z);
                                }
                                z.parent.color = __RB_BLACK__;
                                z.parent.parent.color = __RB_RED__;
                                rbRightRot(tree, z.parent.parent);
                        }
                } else {
                        y = z.parent.parent.left;
                        if (y.color == __RB_RED__) {
                                y.color = __RB_BLACK__;
                                z.parent.color = __RB_BLACK__;
                                z.parent.parent.color = __RB_RED__;
                                z = z.parent.parent;
                        } else {
                                if (z == z.parent.left) {
                                        z = z.parent;
                                        rbRightRot(tree, z);
                                }
                                z.parent.color = __RB_BLACK__;
                                z.parent.parent.color = __RB_RED__;
                                rbLeftRot(tree, z.parent.parent);
                        }
                }
                if (z == tree.root) {
                        break;
                }
        }
        tree.root.color = __RB_BLACK__;
}

pub void rbTreeInsert(RBTree *tree, void *key, void *value) {
        if (!tree) { return; }
        RBNode *x = tree.root;
        RBNode *y = null;
        RBNode *z = null;
        i64 cmp = 0;

        while (x != tree.nil) {
                y = x;
                cmp = tree.key_cmp(key, x.key);

                if (cmp < 0) {
                        x = x.left;
                } else if (cmp > 0) {
                        x = x.right;
                } else {
                        /* Updating an existing key keeps the stored key, like StrMapAdd: with
                            * a key destructor the duplicate key is freed, and with a value
                            * destructor the replaced value is freed (unless it is the same
                            * pointer). */
                        if (tree.free_key && x.key != key) { tree.free_key(key); }
                        if (tree.free_value && x.value != value) { tree.free_value(x.value); }
                        x.value = value;
                        return;
                }
        }

        z = rbNodeNew(tree,key,value);
        z.parent = y;

        if (y == null) {
                tree.root = z;
        } else if (tree.key_cmp(z.key, y.key) < 0) {
                y.left = z;
        } else {
                y.right = z;
        }
        tree.size++;
        tree.version++;

        if (z.parent == null) {
                z.color = __RB_BLACK__;
                return;
        }

        if (z.parent.parent == null) {
                return;
        }

        rbInsertFixUp(tree,z);
}

static RBNode *rbNodeFind(RBTree *tree, RBNode *n, void *key) {
        RBNode *cur = n;
        i64 cmp = 0;

        while (cur && cur != tree.nil) {
                cmp = tree.key_cmp(key, cur.key);
                if (cmp == 0) {
                        return cur;
                } else if (cmp < 0) {
                        cur = cur.left;
                } else {
                        cur = cur.right;
                }
        }
        return null;
}

pub void *rbTreeGet(RBTree *tree, void *key) {
        if (!tree) { return null; }
        RBNode *n = rbNodeFind(tree,tree.root,key);
        if (n) { return n.value; }
        return n;
}

pub bool rbTreeHas(RBTree *tree, void *key) {
        if (!tree) { return false; }
        RBNode *n = rbNodeFind(tree, tree.root, key);
        return n != null;
}

static void rbTransplant(RBTree *tree, RBNode *u, RBNode *v) {
        if (u.parent == null) {
                tree.root = v;
        } else if (u == u.parent.left) {
                u.parent.left = v;
        } else {
                u.parent.right = v;
        }
        v.parent = u.parent;
}

static RBNode *rbNodeMin(RBTree *tree, RBNode *n) {
        while (n.left != tree.nil) { n = n.left; }
        return n;
}

static void rbTreeFixDelete(RBTree *tree, RBNode *x) {
        RBNode *w = null;
        while (x != null && x != tree.root && x.color == __RB_BLACK__) {
                if (x == x.parent.left) {
                        w = x.parent.right;
                        if (w.color == __RB_RED__) {
                                w.color = __RB_BLACK__;
                                x.parent.color = __RB_RED__;
                                rbLeftRot(tree, x.parent);
                                w = x.parent.right;
                        }
                        if (w.left.color == __RB_BLACK__ && w.right.color == __RB_BLACK__) {
                                w.color = __RB_RED__;
                                x = x.parent;
                        } else {
                                if (w.right.color == __RB_BLACK__) {
                                        w.left.color = __RB_BLACK__;
                                        w.color = __RB_RED__;
                                        rbRightRot(tree, w);
                                        w = x.parent.right;
                                }
                                w.color = x.parent.color;
                                x.parent.color = __RB_BLACK__;
                                w.right.color = __RB_BLACK__;
                                rbLeftRot(tree, x.parent);
                                x = tree.root;
                        }
                } else {
                        w = x.parent.left;
                        if (w.color == __RB_RED__) {
                                w.color = __RB_BLACK__;
                                x.parent.color = __RB_RED__;
                                rbRightRot(tree, x.parent);
                                w = x.parent.left;
                        }
                        if (w.right.color == __RB_BLACK__ && w.left.color == __RB_BLACK__) {
                                w.color = __RB_RED__;
                                x = x.parent;
                        } else {
                                if (w.left.color == __RB_BLACK__) {
                                        w.right.color = __RB_BLACK__;
                                        w.color = __RB_RED__;
                                        rbLeftRot(tree, w);
                                        w = x.parent.left;
                                }
                                w.color = x.parent.color;
                                x.parent.color = __RB_BLACK__;
                                w.left.color = __RB_BLACK__;
                                rbRightRot(tree, x.parent);
                                x = tree.root;
                        }
                }
        }
        x.color = __RB_BLACK__;
}

pub void rbTreeDelete(RBTree *tree, void *key) {
        if (!tree) { return; }
        RBNode *z = rbNodeFind(tree, tree.root, key);
        RBNode *y = null;
        RBNode *x = null;
        i64 originalColor = -1;

        if (z == null) {
                return;
        }

        y = z;
        originalColor = y.color;

        if (z.left == tree.nil) {
                x = z.right;
                rbTransplant(tree, z, z.right);
        } else if (z.right == tree.nil) {
                x = z.left;
                rbTransplant(tree, z, z.left);
        } else {
                y = rbNodeMin(tree, z.right);
                originalColor = y.color;
                x = y.right;
                if (y != z.right) {
                        rbTransplant(tree, y, y.right);
                        y.right = z.right;
                        y.right.parent = y;
                } else {
                        x.parent = y;
                }
                rbTransplant(tree, z, y);
                y.left = z.left;
                y.left.parent = y;
                y.color = z.color;
        }

        if (originalColor == __RB_BLACK__) {
                rbTreeFixDelete(tree, x);
        }

        rbNodeRelease(tree, z);
        tree.size--;
        tree.version++;
}

pub void rbTreeForEach(RBTree *tree,
                                                                    void *userData,
                                                                    fn(void *userData,
                                                                                                                                void *key, 
                                                                                                                                void *value) void callback) {
        if (!tree) { return; }
        RBNode **stack = memAlloc(sizeof(RBNode*)*tree.size);
        i64 sp = 0, version = tree.version;
        RBNode *cur = tree.root;

        while (cur) {
                while (!rbIsNil(cur)) {
                        stack[sp++] = cur;
                        cur = cur.left;
                }

                if (sp == 0) { break; }
                cur = stack[--sp];
                callback(userData,cur.key,cur.value);
                /* The callback inserted or deleted: stop before touching the stack,
                    * which was sized for the old tree and may hold freed nodes. */
                if (tree.version != version) { break; }
                cur = cur.right;
        }

        memFree(stack);
}

pub struct RBIter {
        i64 sp;
        RBNode **stack;
        RBNode *cur;
        RBTree *tree;
        i64 version; /* tree->version when the iterator was made */
};

pub RBIter *rbIterNew(RBTree *tree) {
        RBIter *it = memAlloc(sizeof(RBIter));
        it.sp = 0;
        it.cur = null;
        it.tree = tree;
        it.version = 0;
        if (!tree) {
                it.stack = null;
                return it; /* yields nothing */
        }
        it.version = tree.version;
        it.stack = memAlloc(sizeof(RBNode*)*tree.size);
        RBNode *node = tree.root;
        while (!rbIsNil(node)) {
                it.stack[it.sp++] = node;
                node = node.left;
        }
        it.cur = tree.root;
        return it;
}

pub void rbIterRelease(RBIter *it) {
        if (!it) { return; }
        memFree(it.stack);
        memFree(it);
}

pub RBNode *rbIterNext(RBIter *it) {
        if (!it || it.sp == 0) {
                return null;
        }
        if (it.tree.version != it.version) {
                it.sp = 0; /* the tree changed: stop for good */
                return null;
        }

        RBNode *node = it.stack[--it.sp];
        auto rightTree = node.right;

        while (!rbIsNil(rightTree)) {
                it.stack[it.sp++] = rightTree;
                rightTree = rightTree.left;
        }
        return node;
}

pub void rbTreeRelease(RBTree *tree)
{// Free every node, its key and value (with the tree's destructors, if
    // set) and the tree itself
        if (!tree) { return; }
        RBIter *it = rbIterNew(tree);
        RBNode *node = null;
        /* RBIterNext has already pushed the node's right subtree, so the node
            * can be freed straight away */
        while ((node = rbIterNext(it)) != null) {
                rbNodeRelease(tree, node);
        }
        rbIterRelease(it);
        memFree(tree.nil);
        memFree(tree);
}
