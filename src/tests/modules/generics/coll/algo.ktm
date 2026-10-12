module coll;

pub T maxOf<T>(Vec<T> *v) {
    T best = v.get(0);
    for (i64 i = 1; i < v.len; i++) { if (v.get(i) > best) { best = v.get(i); } }
    return best;
}
i64 hidden<T>(T x) { return 1; }
pub i64 count() { Vec<i64> tmp = vecNew<i64>(); tmp.push(1); i64 n = tmp.len; tmp.free(); return n + hidden(2); }
