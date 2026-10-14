module map;
import mem, str;

/* An ordered map from K to V, a red-black tree, as a value type:
 * `ad map.Tree<u8[], i64> t = map.treeNew<u8[], i64>();`. Keys are
 * ordered by map.compare(K, K) (overloads for the integer types, f64 and
 * u8[]/String; a key type of your own adds one), and walked in order with
 * `for (auto n = t.first(); n; n = t.after(n))` (n.key, n.value). u8[] and
 * String keys are copied into the tree and freed with it; String values
 * are the tree's too (freed by delete, clear, free and a set that
 * replaces them); other values are yours. */

const i64 TREE_RED = 0;
const i64 TREE_BLACK = 1;

pub struct TreeNode<K, V> {
        K key;
        V value;
        i64 color;
        TreeNode<K, V> *left, *right, *parent;
};

/* nil is the tree's own black sentinel (made by the first set), the
 * leaf every path ends in: the only node whose left is null */
pub struct Tree<K, V> {
        TreeNode<K, V> *root;
        TreeNode<K, V> *nil;
        i64 len;
};

/* ---- keys: order, and what the tree keeps of one ---- */

pub i64 compare(i64 a, i64 b) { return a < b ? -1 : a > b ? 1 : 0; }
pub i64 compare(u64 a, u64 b) { return a < b ? -1 : a > b ? 1 : 0; }
pub i64 compare(i32 a, i32 b) { return a < b ? -1 : a > b ? 1 : 0; }
pub i64 compare(u32 a, u32 b) { return a < b ? -1 : a > b ? 1 : 0; }
pub i64 compare(f64 a, f64 b) { return a < b ? -1 : a > b ? 1 : 0; }
/* bytes, then length: "ab" < "abc" < "b" */
pub i64 compare(u8[] a, u8[] b) { return str.compare(a, b); }

/* The key as the tree keeps it: a copy for u8[] and String, the key
 * itself for anything else (the generic one; a plain overload wins) */
pub K keyCopy<K>(K k) { return k; }
pub u8[] keyCopy(u8[] k) {
        u8 *p = mem.alloc(k.len + 1);
        if (k.len) { mem.copy(p, k.ptr, k.len); }
        p[k.len] = 0;
        return p[0:k.len];
}
pub String keyCopy(String k) { return k.clone(); }

/* A kept key given back (what keyCopy made): nothing to do but for u8[]
 * and String */
pub void keyFree<K>(K k) {}
pub void keyFree(u8[] k) { mem.free(k.ptr); }
pub void keyFree(String k) { k.free(); }

/* A String value is the map's (or tree's) too: freed when its entry is
 * deleted or the map cleared, and when set() replaces it with another
 * String; any other value is the caller's */
/* A String handed out in a Vec (keys(), values()) as its own copy, as
 * the Vec frees its Strings; anything else as it is */
pub V ownCopy<V>(V v) { return v; }
pub String ownCopy(String v) { return v.clone(); }

pub void valueFree<V>(V v) {}
pub void valueFree(String v) { v.free(); }
pub void valueReplaced<V>(V old, V v) {}
pub void valueReplaced(String old, String v) {
        if (old.ptr != v.ptr) { old.free(); }
}

/* ---- the tree ---- */

/* An empty tree (nothing allocated until the first set) */
pub Tree<K, V> treeNew<K, V>() {
        Tree<K, V> t;
        t.root = null;
        t.nil = null;
        t.len = 0;
        return t;
}

static bool treeIsNil<K, V>(TreeNode<K, V> *n) {
        return n == null || n.left == null;
}

static void treeLeftRot<K, V>(Tree<K, V> *t, TreeNode<K, V> *x) {
        TreeNode<K, V> *y = x.right;
        x.right = y.left;
        if (y.left != t.nil) { y.left.parent = x; }
        y.parent = x.parent;
        if (x.parent == null) { t.root = y; }
        else if (x == x.parent.left) { x.parent.left = y; }
        else { x.parent.right = y; }
        y.left = x;
        x.parent = y;
}

static void treeRightRot<K, V>(Tree<K, V> *t, TreeNode<K, V> *y) {
        TreeNode<K, V> *x = y.left;
        y.left = x.right;
        if (x.right != t.nil) { x.right.parent = y; }
        x.parent = y.parent;
        if (y.parent == null) { t.root = x; }
        else if (y == y.parent.right) { y.parent.right = x; }
        else { y.parent.left = x; }
        x.right = y;
        y.parent = x;
}

static void treeInsertFixUp<K, V>(Tree<K, V> *t, TreeNode<K, V> *z) {
        TreeNode<K, V> *u;
        while (z.parent && z.parent.color == TREE_RED) {
                if (z.parent == z.parent.parent.right) {
                        u = z.parent.parent.left;
                        if (u.color == TREE_RED) {
                                u.color = TREE_BLACK;
                                z.parent.color = TREE_BLACK;
                                z.parent.parent.color = TREE_RED;
                                z = z.parent.parent;
                        } else {
                                if (z == z.parent.left) {
                                        z = z.parent;
                                        treeRightRot(t, z);
                                }
                                z.parent.color = TREE_BLACK;
                                z.parent.parent.color = TREE_RED;
                                treeLeftRot(t, z.parent.parent);
                        }
                } else {
                        u = z.parent.parent.right;
                        if (u.color == TREE_RED) {
                                u.color = TREE_BLACK;
                                z.parent.color = TREE_BLACK;
                                z.parent.parent.color = TREE_RED;
                                z = z.parent.parent;
                        } else {
                                if (z == z.parent.right) {
                                        z = z.parent;
                                        treeLeftRot(t, z);
                                }
                                z.parent.color = TREE_BLACK;
                                z.parent.parent.color = TREE_RED;
                                treeRightRot(t, z.parent.parent);
                        }
                }
                if (z == t.root) { break; }
        }
        t.root.color = TREE_BLACK;
}

static TreeNode<K, V> *treeFind<K, V>(Tree<K, V> *t, K key) {
        TreeNode<K, V> *cur = t.root;
        while (cur && cur != t.nil) {
                i64 c = compare(key, cur.key);
                if (c == 0) { return cur; }
                cur = c < 0 ? cur.left : cur.right;
        }
        return null;
}

/* value for key: replaced if key is there (the stored key stays), added
 * (with a copy of the key) if not */
pub void Tree<K, V>.set(K key, V value) {
        if (!self.nil) {
                self.nil = mem.allocZero(sizeof(TreeNode<K, V>));
                self.nil.color = TREE_BLACK;
                self.root = self.nil;
        }
        TreeNode<K, V> *x = self.root, *y = null;
        i64 c = 0;
        while (x != self.nil) {
                y = x;
                c = compare(key, x.key);
                if (c == 0) {
                        valueReplaced(x.value, value);
                        x.value = value;
                        return;
                }
                x = c < 0 ? x.left : x.right;
        }
        TreeNode<K, V> *z = mem.alloc(sizeof(TreeNode<K, V>));
        z.key = keyCopy(key);
        z.value = value;
        z.color = TREE_RED;
        z.left = self.nil;
        z.right = self.nil;
        z.parent = y;
        if (y == null) { self.root = z; }
        else if (c < 0) { y.left = z; }
        else { y.right = z; }
        self.len++;
        if (z.parent == null) {
                z.color = TREE_BLACK;
                return;
        }
        if (z.parent.parent == null) { return; }
        treeInsertFixUp(self, z);
}

/* key's value into *out and true; false (and *out untouched) when key
 * isn't there */
pub bool Tree<K, V>.get(K key, V *out) {
        TreeNode<K, V> *n = treeFind(self, key);
        if (!n) { return false; }
        *out = n.value;
        return true;
}

/* key's value, or def when key isn't there */
pub V Tree<K, V>.getOr(K key, V def) {
        TreeNode<K, V> *n = treeFind(self, key);
        return n ? n.value : def;
}

pub bool Tree<K, V>.has(K key) {
        return treeFind(self, key) != null;
}

static void treeTransplant<K, V>(Tree<K, V> *t, TreeNode<K, V> *u, TreeNode<K, V> *v) {
        if (u.parent == null) { t.root = v; }
        else if (u == u.parent.left) { u.parent.left = v; }
        else { u.parent.right = v; }
        v.parent = u.parent;
}

static TreeNode<K, V> *treeMin<K, V>(Tree<K, V> *t, TreeNode<K, V> *n) {
        while (n.left != t.nil) { n = n.left; }
        return n;
}

static TreeNode<K, V> *treeMax<K, V>(Tree<K, V> *t, TreeNode<K, V> *n) {
        while (n.right != t.nil) { n = n.right; }
        return n;
}

static void treeFixDelete<K, V>(Tree<K, V> *t, TreeNode<K, V> *x) {
        TreeNode<K, V> *w;
        while (x != t.root && x.color == TREE_BLACK) {
                if (x == x.parent.left) {
                        w = x.parent.right;
                        if (w.color == TREE_RED) {
                                w.color = TREE_BLACK;
                                x.parent.color = TREE_RED;
                                treeLeftRot(t, x.parent);
                                w = x.parent.right;
                        }
                        if (w.left.color == TREE_BLACK && w.right.color == TREE_BLACK) {
                                w.color = TREE_RED;
                                x = x.parent;
                        } else {
                                if (w.right.color == TREE_BLACK) {
                                        w.left.color = TREE_BLACK;
                                        w.color = TREE_RED;
                                        treeRightRot(t, w);
                                        w = x.parent.right;
                                }
                                w.color = x.parent.color;
                                x.parent.color = TREE_BLACK;
                                w.right.color = TREE_BLACK;
                                treeLeftRot(t, x.parent);
                                x = t.root;
                        }
                } else {
                        w = x.parent.left;
                        if (w.color == TREE_RED) {
                                w.color = TREE_BLACK;
                                x.parent.color = TREE_RED;
                                treeRightRot(t, x.parent);
                                w = x.parent.left;
                        }
                        if (w.right.color == TREE_BLACK && w.left.color == TREE_BLACK) {
                                w.color = TREE_RED;
                                x = x.parent;
                        } else {
                                if (w.left.color == TREE_BLACK) {
                                        w.right.color = TREE_BLACK;
                                        w.color = TREE_RED;
                                        treeLeftRot(t, w);
                                        w = x.parent.left;
                                }
                                w.color = x.parent.color;
                                x.parent.color = TREE_BLACK;
                                w.left.color = TREE_BLACK;
                                treeRightRot(t, x.parent);
                                x = t.root;
                        }
                }
        }
        x.color = TREE_BLACK;
}

/* key and its value taken out: true, or false if it wasn't there. Other
 * nodes stay where they are, so during a walk take the next node
 * (t.after(n)) before deleting n. */
pub bool Tree<K, V>.delete(K key) {
        TreeNode<K, V> *z = treeFind(self, key);
        if (!z) { return false; }
        TreeNode<K, V> *y = z, *x;
        i64 color = y.color;
        if (z.left == self.nil) {
                x = z.right;
                treeTransplant(self, z, z.right);
        } else if (z.right == self.nil) {
                x = z.left;
                treeTransplant(self, z, z.left);
        } else {
                y = treeMin(self, z.right);
                color = y.color;
                x = y.right;
                if (y != z.right) {
                        treeTransplant(self, y, y.right);
                        y.right = z.right;
                        y.right.parent = y;
                } else {
                        x.parent = y;
                }
                treeTransplant(self, z, y);
                y.left = z.left;
                y.left.parent = y;
                y.color = z.color;
        }
        if (color == TREE_BLACK) { treeFixDelete(self, x); }
        if (self.root == self.nil) { self.root.parent = null; }
        keyFree(z.key);
        valueFree(z.value);
        mem.free(z);
        self.len--;
        return true;
}

/* The node with the smallest key, null for an empty tree */
pub TreeNode<K, V> *Tree<K, V>.first() {
        if (!self.len) { return null; }
        return treeMin(self, self.root);
}

/* The node with the largest key, null for an empty tree */
pub TreeNode<K, V> *Tree<K, V>.last() {
        if (!self.len) { return null; }
        return treeMax(self, self.root);
}

/* The node after n in key order, null after the last */
pub TreeNode<K, V> *Tree<K, V>.after(TreeNode<K, V> *n) {
        if (n.right != self.nil) { return treeMin(self, n.right); }
        TreeNode<K, V> *p = n.parent;
        while (p && n == p.right) {
                n = p;
                p = p.parent;
        }
        return p;
}

/* The node before n in key order, null before the first */
pub TreeNode<K, V> *Tree<K, V>.before(TreeNode<K, V> *n) {
        if (n.left != self.nil) { return treeMax(self, n.left); }
        TreeNode<K, V> *p = n.parent;
        while (p && n == p.left) {
                n = p;
                p = p.parent;
        }
        return p;
}

static void treeFreeNodes<K, V>(Tree<K, V> *t, TreeNode<K, V> *n) {
        while (n != t.nil) {
                treeFreeNodes(t, n.left);
                TreeNode<K, V> *right = n.right;
                keyFree(n.key);
                valueFree(n.value);
                mem.free(n);
                n = right;
        }
}

/* Every node (and kept key) freed; the tree is empty and usable again */
pub void Tree<K, V>.clear() {
        if (!self.nil) { return; }
        treeFreeNodes(self, self.root);
        self.root = self.nil;
        self.len = 0;
}

/* clear(), and the sentinel too: for `ad` and `defer t.free();` */
pub void Tree<K, V>.free() {
        self.clear();
        mem.free(self.nil);
        self.nil = null;
        self.root = null;
}
