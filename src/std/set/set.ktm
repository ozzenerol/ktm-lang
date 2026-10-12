module set;
import map, vec;

/* A set of T, a hash set as a value type: `ad set.Set<i64> seen =
 * set.new<i64>();`. Values are hashed and compared like map.Map's keys
 * (map.hashOf / map.keyEq: overloads for the integer types, pointers,
 * u8[] and String, and a byte-wise fallback for anything else; add
 * overloads for a type of your own), and u8[] / String values are copied
 * into the set and freed with it. Walk it in the order values were added
 * with next(): `i64 it = 0; T v; while (s.next(&it, &v)) { ... }`. */
pub struct Set<T> {
    map.Map<T, bool> m;
    i64 len;
};

/* An empty set, with room for cap values before it grows */
pub Set<T> new<T>(i64 cap = 8) {
    Set<T> s;
    s.m = map.new<T, bool>(cap);
    s.len = 0;
    return s;
}

/* v added: true if it is new, false if it was there already */
pub bool Set<T>.add(T v) {
    if (self.m.has(v)) { return false; }
    self.m.set(v, true);
    self.len++;
    return true;
}

pub bool Set<T>.has(T v) {
    return self.m.has(v);
}

/* v taken out: true if it was there */
pub bool Set<T>.delete(T v) {
    if (!self.m.delete(v)) { return false; }
    self.len--;
    return true;
}

/* The next value from *it (start at 0) into *v, in the order they were
 * added; false at the end */
pub bool Set<T>.next(i64 *it, T *v) {
    return self.m.next(it, v, null);
}

/* The values, in the order they were added, as a new Vec (yours to
 * free; u8[] values are views of the set's own copies) */
pub vec.Vec<T> Set<T>.items() {
    return self.m.keys();
}

/* A new set: the values in this one or in other */
pub Set<T> Set<T>.unionWith(Set<T> *other) {
    Set<T> out = new<T>(self.len + other.len);
    i64 it = 0;
    T v;
    while (self.next(&it, &v)) { out.add(v); }
    it = 0;
    while (other.next(&it, &v)) { out.add(v); }
    return out;
}

/* A new set: the values in this one and not in other */
pub Set<T> Set<T>.diff(Set<T> *other) {
    Set<T> out = new<T>(self.len);
    i64 it = 0;
    T v;
    while (self.next(&it, &v)) {
        if (!other.has(v)) { out.add(v); }
    }
    return out;
}

/* A new set: the values in both */
pub Set<T> Set<T>.intersection(Set<T> *other) {
    Set<T> out = new<T>(self.len);
    i64 it = 0;
    T v;
    while (self.next(&it, &v)) {
        if (other.has(v)) { out.add(v); }
    }
    return out;
}

/* A new set: the values in exactly one of the two */
pub Set<T> Set<T>.symDiff(Set<T> *other) {
    Set<T> out = self.diff(other);
    i64 it = 0;
    T v;
    while (other.next(&it, &v)) {
        if (!self.has(v)) { out.add(v); }
    }
    return out;
}

/* Every value of this set is in other */
pub bool Set<T>.isSubset(Set<T> *other) {
    if (self.len > other.len) { return false; }
    i64 it = 0;
    T v;
    while (self.next(&it, &v)) {
        if (!other.has(v)) { return false; }
    }
    return true;
}

/* The same values (in any order) */
pub bool Set<T>.equals(Set<T> *other) {
    return self.len == other.len && self.isSubset(other);
}

/* Empty again, keeping its room */
pub void Set<T>.clear() {
    self.m.clear();
    self.len = 0;
}

pub void Set<T>.free() {
    self.m.free();
    self.len = 0;
}
