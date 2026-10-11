#include "testhelper.ktm"

import mem, set, str, vec;

/* IntSetRelease(null) and IntSetSize(null) crashed. The IntSet functions
 * that only read a set now treat null as the empty set, and the release
 * functions ignore null, like the vector and map APIs. */

i32 main() {
    printf("Test - IntSet null safety: ");
    i64 correct = 0, tests = 0;
    set.IntSet *s = set.intNew(), *r;
    set.IntSetIter *it;
    vec.IntVec *v;
    u8 *str;

    set.IntSet.release(null);
    tests++; correct++;
    set.intIterRelease(null);
    tests++; correct++;
    tests++; if (set.IntSet.size(null) == 0) { correct++; }
    tests++; if (!set.IntSet.has(null, 0)) { correct++; }
    tests++; if (set.intIterNext(null) == null) { correct++; }

    it = set.IntSet.iterNew(null);
    tests++; if (it != null && set.intIterNext(it) == null) { correct++; }
    set.intIterRelease(it);

    s.add(3);
    s.add(-1);

    r = s.unionWith(null);
    tests++; if (r.eq(s)) { correct++; }
    r.release();
    r = set.IntSet.unionWith(null, null);
    tests++; if (r.size() == 0) { correct++; }
    r.release();
    r = s.diff(null);
    tests++; if (r.eq(s)) { correct++; }
    r.release();
    r = set.IntSet.diff(null, s);
    tests++; if (r.size() == 0) { correct++; }
    r.release();
    r = s.intersection(null);
    tests++; if (r.size() == 0) { correct++; }
    r.release();
    r = set.IntSet.symDiff(null, s);
    tests++; if (r.eq(s)) { correct++; }
    r.release();

    tests++; if (set.IntSet.eq(null, null)) { correct++; }
    tests++; if (!s.eq(null) && !set.IntSet.eq(null, s)) { correct++; }

    v = set.IntSet.toVec(null);
    tests++; if (v != null && v.size == 0) { correct++; }
    v.release();

    str = set.IntSet.toStr(null);
    tests++; if (str != null && !str.cmp(str, "{}")) { correct++; }
    mem.free(str);

    /* Non-null sets are unaffected. */
    tests++; if (s.size() == 2 && s.has(3) && !s.has(4)) { correct++; }
    s.release();

    PrintResult(correct, tests);
    printf("====\n");
    return 0;
}
