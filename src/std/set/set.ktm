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
    RBTree *tree; 
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

pub i64 intSize(IntSet *set) {
    if (!set) { return 0; }
    return set.tree.size;
}

pub struct IntSetEntry {
    i64 key;
};

pub struct IntSetIter {
    i64 sp;
    RBNode **stack;
    IntSetEntry *entry; 
    RBTree *tree;
    i64 version; /* tree->version when the iterator was made */
};

pub IntSetIter *intIterNew(IntSet *set) {
    IntSetIter *it = mem.alloc(sizeof(IntSetIter));
    it.sp = 0;
    it.entry = mem.alloc(sizeof(IntSetEntry));
    it.stack = mem.alloc(sizeof(map.RBNode *) * intSize(set));
    it.tree = null;
    it.version = 0;
    if (!set) { return it; } /* yields nothing */
    it.tree = set.tree;
    it.version = set.tree.version;
    map.RBNode *node = set.tree.root;
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

pub bool intHas(IntSet *set, i64 key) {
    if (!set) { return false; }
    return set.tree.has((void*)key);
}

pub void intAdd(IntSet *set, i64 key) {
    return set.tree.set((void*)key,null);
}

pub void intDelete(IntSet *set, i64 key) {
    set.tree.delete((void*)key);
}

pub IntSet *intUnion(IntSet *s1, IntSet *s2) {
    IntSet *unionSet = intNew();
    IntSetIter *i1 = intIterNew(s1);
    IntSetIter *i2 = intIterNew(s2);
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        intAdd(unionSet,entry.key);
    }

    while ((entry = intIterNext(i2))) {
        intAdd(unionSet,entry.key);
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return unionSet;
}

pub IntSet *intDiff(IntSet *s1, IntSet *s2) {
    IntSet *diffSet = intNew();
    IntSetIter *i1 = intIterNew(s1);
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        if (!intHas(s2, (i64)entry.key)) {
            intAdd(diffSet,(i64)entry.key);
        }
    }

    intIterRelease(i1);
    return diffSet;
}

pub IntSet *intIntersection(IntSet *s1, IntSet *s2) {
    IntSet *intersectionSet = intNew();
    IntSetIter *i1 = intIterNew(s1);
    IntSetIter *i2 = intIterNew(s2);
    IntSetEntry *entry = null;

    while ((entry = intIterNext(i1))) {
        if (intHas(s2,entry.key)) {
            intAdd(intersectionSet,entry.key);
        }
    }

    while ((entry = intIterNext(i2))) {
        if (intHas(s1,entry.key)) {
            intAdd(intersectionSet,entry.key);
        }
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return intersectionSet;
}

pub bool intEq(IntSet *s1, IntSet *s2) {
    if (intSize(s1) != intSize(s2)) { return false; }

    IntSetIter *i1 = intIterNew(s1);
    IntSetIter *i2 = intIterNew(s2);
    IntSetEntry *entry = null;
    bool retval = true;

    while ((entry = intIterNext(i1))) {
        if (!intHas(s2,entry.key)) {
            retval = false;
            goto out;
        }
    }

    while ((entry = intIterNext(i2))) {
        if (!intHas(s1,entry.key)) {
            retval = false;
            goto out;
        }
    }

out:
    intIterRelease(i1);
    intIterRelease(i2);
    return retval;
}

pub vec.IntVec *intToVec(IntSet *set) {
    vec.IntVec *vec = vec.intNew();
    IntSetIter *i1 = intIterNew(set);
    IntSetEntry *entry = null;
    while ((entry = intIterNext(i1))) {
        vec.push(entry.key);
    }
    intIterRelease(i1);
    return vec;
}

pub IntSet *intSymDiff(IntSet *s1, IntSet *s2) {
    IntSet *symDiffSet = intNew();
    IntSetIter *i1 = intIterNew(s1);
    IntSetIter *i2 = intIterNew(s2);
    IntSetEntry *entry = null;

    /* Add elements from s1 that are not in s2 */
    while ((entry = intIterNext(i1))) {
        if (!intHas(s2,entry.key)) {
            intAdd(symDiffSet,entry.key);
        }
    }

    /* Add elements from s2 that are not in s1 */
    while ((entry = intIterNext(i2))) {
        if (!intHas(s1,entry.key)) {
            intAdd(symDiffSet,entry.key);
        }
    }

    intIterRelease(i1);
    intIterRelease(i2);
    return symDiffSet;
}

pub u8 *intToStr(IntSet *set) {
    i64 len = 1;
    u8 *buf = str.dup("{");
    IntSetIter *i1 = intIterNew(set);
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

pub void intRelease(IntSet *set) {
    if (!set) { return; }
    set.tree.release();
    mem.free(set);
}
