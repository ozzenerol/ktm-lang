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

pub IntSet *intSetNew() {
    IntSet *set = memAlloc(sizeof(IntSet));
    set.tree = rbTreeNew(null,null,&intSetCmp);
    return set;
}

pub i64 intSetSize(IntSet *set) {
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

pub IntSetIter *intSetIterNew(IntSet *set) {
    IntSetIter *it = memAlloc(sizeof(IntSetIter));
    it.sp = 0;
    it.entry = memAlloc(sizeof(IntSetEntry));
    it.stack = memAlloc(sizeof(map.RBNode *) * intSetSize(set));
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

pub IntSetEntry *intSetIterNext(IntSetIter *it) {
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

pub void intSetIterRelease(IntSetIter *iter) {
    if (!iter) { return; }
    memFree(iter.entry);
    memFree(iter.stack);
    memFree(iter);
}

pub bool intSetHas(IntSet *set, i64 key) {
    if (!set) { return false; }
    return set.tree.has((void*)key);
}

pub void intSetAdd(IntSet *set, i64 key) {
    return set.tree.set((void*)key,null);
}

pub void intSetDelete(IntSet *set, i64 key) {
    set.tree.delete((void*)key);
}

pub IntSet *intSetUnion(IntSet *s1, IntSet *s2) {
    IntSet *unionSet = intSetNew();
    IntSetIter *i1 = intSetIterNew(s1);
    IntSetIter *i2 = intSetIterNew(s2);
    IntSetEntry *entry = null;

    while ((entry = intSetIterNext(i1))) {
        intSetAdd(unionSet,entry.key);
    }

    while ((entry = intSetIterNext(i2))) {
        intSetAdd(unionSet,entry.key);
    }

    intSetIterRelease(i1);
    intSetIterRelease(i2);
    return unionSet;
}

pub IntSet *intSetDiff(IntSet *s1, IntSet *s2) {
    IntSet *diffSet = intSetNew();
    IntSetIter *i1 = intSetIterNew(s1);
    IntSetEntry *entry = null;

    while ((entry = intSetIterNext(i1))) {
        if (!intSetHas(s2, (i64)entry.key)) {
            intSetAdd(diffSet,(i64)entry.key);
        }
    }

    intSetIterRelease(i1);
    return diffSet;
}

pub IntSet *intSetIntersection(IntSet *s1, IntSet *s2) {
    IntSet *intersectionSet = intSetNew();
    IntSetIter *i1 = intSetIterNew(s1);
    IntSetIter *i2 = intSetIterNew(s2);
    IntSetEntry *entry = null;

    while ((entry = intSetIterNext(i1))) {
        if (intSetHas(s2,entry.key)) {
            intSetAdd(intersectionSet,entry.key);
        }
    }

    while ((entry = intSetIterNext(i2))) {
        if (intSetHas(s1,entry.key)) {
            intSetAdd(intersectionSet,entry.key);
        }
    }

    intSetIterRelease(i1);
    intSetIterRelease(i2);
    return intersectionSet;
}

pub bool intSetEq(IntSet *s1, IntSet *s2) {
    if (intSetSize(s1) != intSetSize(s2)) { return false; }

    IntSetIter *i1 = intSetIterNew(s1);
    IntSetIter *i2 = intSetIterNew(s2);
    IntSetEntry *entry = null;
    bool retval = true;

    while ((entry = intSetIterNext(i1))) {
        if (!intSetHas(s2,entry.key)) {
            retval = false;
            goto out;
        }
    }

    while ((entry = intSetIterNext(i2))) {
        if (!intSetHas(s1,entry.key)) {
            retval = false;
            goto out;
        }
    }

out:
    intSetIterRelease(i1);
    intSetIterRelease(i2);
    return retval;
}

pub vec.IntVec *intSetToVec(IntSet *set) {
    vec.IntVec *vec = intVecNew();
    IntSetIter *i1 = intSetIterNew(set);
    IntSetEntry *entry = null;
    while ((entry = intSetIterNext(i1))) {
        vec.push(entry.key);
    }
    intSetIterRelease(i1);
    return vec;
}

pub IntSet *intSetSymDiff(IntSet *s1, IntSet *s2) {
    IntSet *symDiffSet = intSetNew();
    IntSetIter *i1 = intSetIterNew(s1);
    IntSetIter *i2 = intSetIterNew(s2);
    IntSetEntry *entry = null;

    /* Add elements from s1 that are not in s2 */
    while ((entry = intSetIterNext(i1))) {
        if (!intSetHas(s2,entry.key)) {
            intSetAdd(symDiffSet,entry.key);
        }
    }

    /* Add elements from s2 that are not in s1 */
    while ((entry = intSetIterNext(i2))) {
        if (!intSetHas(s1,entry.key)) {
            intSetAdd(symDiffSet,entry.key);
        }
    }

    intSetIterRelease(i1);
    intSetIterRelease(i2);
    return symDiffSet;
}

pub u8 *intSetToString(IntSet *set) {
    i64 len = 1;
    u8 *buf = strNew("{");
    IntSetIter *i1 = intSetIterNew(set);
    IntSetEntry *entry = null;
    bool first = true;

    while ((entry = intSetIterNext(i1))) {
        if (first) { buf = strAppendFormatLen(buf, &len, "%d", entry.key); }
        else       { buf = strAppendFormatLen(buf, &len, ", %d", entry.key); }
        first = false;
    }
    intSetIterRelease(i1);
    return strAppendFormatLen(buf, &len, "}");
}

pub void intSetRelease(IntSet *set) {
    if (!set) { return; }
    set.tree.release();
    memFree(set);
}
