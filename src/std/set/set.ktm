module set;
import map, mem, str, vec;

static bool rbIsNil(map.RBNode *n) {
    return n == null || n.left == null;
}

static map.RBNode *rbGrandParent(map.RBNode *n) {
    if (n == null || n.parent == null) {
        return null;
    }
    return n.parent.parent;
}

pub struct IntSet {
    map.RBTree *tree; 
};

i64 intSetCmp(void *k1, void *k2) {
    i64 _k1 = (i64)k1;
    i64 _k2 = (i64)k2;
    if (_k1 < _k2) { return -1; }
    if (_k1 > _k2) { return 1; }
    return 0;
}

pub IntSet *intNew() {
    IntSet *set = mem.alloc(sizeof(IntSet));
    set.tree = map.rbNew(null,null,&intSetCmp);
    return set;
}

pub i64 IntSet.size() {
    if (!self) { return 0; }
    return self.tree.size;
}

pub struct IntSetEntry {
    i64 key;
};

pub struct IntSetIter {
    i64 sp;
    map.RBNode **stack;
    IntSetEntry *entry; 
    map.RBTree *tree;
    i64 version; /* tree->version when the iterator was made */
};

pub IntSetIter *IntSet.iterNew() {
    IntSetIter *it = mem.alloc(sizeof(IntSetIter));
    it.sp = 0;
    it.entry = mem.alloc(sizeof(IntSetEntry));
    it.stack = mem.alloc(sizeof(map.RBNode *) * self.size());
    it.tree = null;
    it.version = 0;
    if (!self) { return it; } /* yields nothing */
    it.tree = self.tree;
    it.version = self.tree.version;
    map.RBNode *node = self.tree.root;
    while (!rbIsNil(node)) {
        it.stack[it.sp++] = node;
        node = node.left;
    }
    return it;
}

pub IntSetEntry *intIterNext(IntSetIter *it) {
    if (!it || it.sp == 0) {
        return null;
    }
    if (it.tree.version != it.version) {
        it.sp = 0; /* the set changed: stop for good */
        return null;
    }

    map.RBNode *node = it.stack[--it.sp];
    it.entry.key = (i64)node.key;
    auto rightTree = node.right;

    while (!rbIsNil(rightTree)) {
        it.stack[it.sp++] = rightTree;
        rightTree = rightTree.left;
    }

    return it.entry;
}

pub void intIterRelease(IntSetIter *iter) {
    if (!iter) { return; }
    mem.free(iter.entry);
    mem.free(iter.stack);
    mem.free(iter);
}

pub bool IntSet.has(i64 key) {
    if (!self) { return false; }
    return self.tree.has((void*)key);
}

pub void IntSet.add(i64 key) {
    return self.tree.set((void*)key,null);
}

pub void IntSet.delete(i64 key) {
    self.tree.delete((void*)key);
}

pub IntSet *IntSet.unionWith(IntSet *s2) {
    IntSet *unionSet = intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetIter *i2 = s2.iterNew();
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        unionSet.add(entry.key);
    }

    while ((entry = intIterNext(i2))) {
        unionSet.add(entry.key);
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return unionSet;
}

pub IntSet *IntSet.diff(IntSet *s2) {
    IntSet *diffSet = intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        if (!s2.has((i64)entry.key)) {
            diffSet.add((i64)entry.key);
        }
    }

    intIterRelease(i1);
    return diffSet;
}

pub IntSet *IntSet.intersection(IntSet *s2) {
    IntSet *intersectionSet = intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetIter *i2 = s2.iterNew();
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        if (s2.has(entry.key)) {
            intersectionSet.add(entry.key);
        }
    }

    while ((entry = intIterNext(i2))) {
        if (self.has(entry.key)) {
            intersectionSet.add(entry.key);
        }
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return intersectionSet;
}

pub bool IntSet.eq(IntSet *s2) {
    if (self.size() != s2.size()) { return false; }

    IntSetIter *i1 = self.iterNew();
    IntSetIter *i2 = s2.iterNew();
    IntSetEntry *entry = null;
    bool retval = true;

    while ((entry = intIterNext(i1))) {
        if (!s2.has(entry.key)) {
            retval = false;
            goto out;
        }
    }

    while ((entry = intIterNext(i2))) {
        if (!self.has(entry.key)) {
            retval = false;
            goto out;
        }
    }

out:
    intIterRelease(i1);
    intIterRelease(i2);
    return retval;
}

pub vec.IntVec *IntSet.toVec() {
    vec.IntVec *vec = vec.intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetEntry *entry = null;
    while ((entry = intIterNext(i1))) {
        vec.push(entry.key);
    }
    intIterRelease(i1);
    return vec;
}

pub IntSet *IntSet.symDiff(IntSet *s2) {
    IntSet *symDiffSet = intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetIter *i2 = s2.iterNew();
    IntSetEntry *entry = null;

    /* Add elements from s1 that are not in s2 */
    while ((entry = intIterNext(i1))) {
        if (!s2.has(entry.key)) {
            symDiffSet.add(entry.key);
        }
    }

    /* Add elements from s2 that are not in s1 */
    while ((entry = intIterNext(i2))) {
        if (!self.has(entry.key)) {
            symDiffSet.add(entry.key);
        }
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return symDiffSet;
}

pub u8 *IntSet.toStr() {
    i64 len = 1;
    u8 *buf = str.dup("{");
    IntSetIter *i1 = self.iterNew();
    IntSetEntry *entry = null;
    bool first = true;

    while ((entry = intIterNext(i1))) {
        if (first) { buf = str.appendFormatLen(buf, &len, "%d", entry.key); }
        else       { buf = str.appendFormatLen(buf, &len, ", %d", entry.key); }
        first = false;
    }
    intIterRelease(i1);
    return str.appendFormatLen(buf, &len, "}");
}

pub void IntSet.release() {
    if (!self) { return; }
    self.tree.release();
    mem.free(self);
}
