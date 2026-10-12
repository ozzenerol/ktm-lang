module set;
import map, mem, str, vec;

pub struct IntSet {
    map.Tree<i64, bool> tree;
};

pub IntSet *intNew() {
    IntSet *set = mem.alloc(sizeof(IntSet));
    set.tree = map.treeNew<i64, bool>();
    return set;
}

pub i64 IntSet.size() {
    if (!self) { return 0; }
    return self.tree.len;
}

pub struct IntSetEntry {
    i64 key;
};

/* A walk in key order; adding or deleting other keys during it is fine,
 * deleting the key it is on is not */
pub struct IntSetIter {
    map.TreeNode<i64, bool> *next;
    IntSetEntry *entry;
    map.Tree<i64, bool> *tree;
};

pub IntSetIter *IntSet.iterNew() {
    IntSetIter *it = mem.alloc(sizeof(IntSetIter));
    it.entry = mem.alloc(sizeof(IntSetEntry));
    it.tree = self ? &self.tree : null;
    it.next = self ? self.tree.first() : null;
    return it;
}

pub IntSetEntry *intIterNext(IntSetIter *it) {
    if (!it || !it.next) {
        return null;
    }
    it.entry.key = it.next.key;
    it.next = it.tree.after(it.next);
    return it.entry;
}

pub void intIterRelease(IntSetIter *iter) {
    if (!iter) { return; }
    mem.free(iter.entry);
    mem.free(iter);
}

pub bool IntSet.has(i64 key) {
    if (!self) { return false; }
    return self.tree.has(key);
}

pub void IntSet.add(i64 key) {
    self.tree.set(key, true);
}

pub void IntSet.delete(i64 key) {
    self.tree.delete(key);
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
    vec.IntVec *vecVal = vec.intNew();
    IntSetIter *i1 = self.iterNew();
    IntSetEntry *entry = null;
    while ((entry = intIterNext(i1))) {
        vecVal.push(entry.key);
    }
    intIterRelease(i1);
    return vecVal;
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

pub String IntSet.toStr() {
    String out = str.from("{");
    IntSetIter *i1 = self.iterNew();
    IntSetEntry *entry = null;
    bool first = true;

    while ((entry = intIterNext(i1))) {
        if (!first) { out.append(", "); }
        out.appendFormat("%d", entry.key);
        first = false;
    }
    intIterRelease(i1);
    out.append("}");
    return out;
}

pub void IntSet.release() {
    if (!self) { return; }
    self.tree.free();
    mem.free(self);
}
