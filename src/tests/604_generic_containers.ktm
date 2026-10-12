/* The check of the generics design (v0.9.0): a vector and a hash map as
 * the stdlib rework would write them, Vec<T> and Map<K, V> with key
 * hashing through overloads, used with i64, u8[] and f64. */
import mem, str, test;

/* the containers in generics, as the stdlib rework would have them */
pub struct Vec<T> { T *data; i64 len; i64 cap; };
pub Vec<T> vecNew<T>(i64 cap = 8) { Vec<T> v; v.data = mem.alloc(sizeof(T) * cap); v.len = 0; v.cap = cap; return v; }
pub void Vec<T>.push(T x) {
    if (self.len == self.cap) { self.cap *= 2; self.data = mem.realloc(self.data, sizeof(T) * self.cap); }
    self.data[self.len++] = x;
}
pub T Vec<T>.get(i64 i) { return self.data[i]; }
pub void Vec<T>.free() { mem.free(self.data); }

/* keys: one overload per key type */
u64 keyHash(i64 k) { u64 x = (u64)k * 0x9E3779B97F4A7C15; return x ^ (x >> 31); }
u64 keyHash(u8[] k) { u64 h = 0xcbf29ce484222325; for (auto c : k) { h = (h ^ c) * 0x100000001b3; } return h; }
bool keyEq(i64 a, i64 b) { return a == b; }
bool keyEq(u8[] a, u8[] b) { return str.equals(a, b); }

pub struct Map<K, V> { K *keys; V *vals; bool *used; i64 cap; i64 len; };
pub Map<K, V> mapNew<K, V>(i64 cap = 16) {
    Map<K, V> m;
    m.keys = mem.allocZero(sizeof(K) * cap); m.vals = mem.allocZero(sizeof(V) * cap);
    m.used = mem.allocZero(cap); m.cap = cap; m.len = 0;
    return m;
}
pub void Map<K, V>.grow() {
    Map<K, V> bigger = mapNew<K, V>(self.cap * 2);
    for (i64 i = 0; i < self.cap; i++) { if (self.used[i]) { bigger.set(self.keys[i], self.vals[i]); } }
    self.free();
    *self = bigger;
}
pub void Map<K, V>.set(K k, V v) {
    if ((self.len + 1) * 2 > self.cap) { self.grow(); }
    i64 i = (i64)(keyHash(k) & (u64)(self.cap - 1));
    while (self.used[i] && !keyEq(self.keys[i], k)) { i = (i + 1) & (self.cap - 1); }
    if (!self.used[i]) { self.len++; }
    self.used[i] = true; self.keys[i] = k; self.vals[i] = v;
}
pub bool Map<K, V>.get(K k, V *out) {
    i64 i = (i64)(keyHash(k) & (u64)(self.cap - 1));
    while (self.used[i]) {
        if (keyEq(self.keys[i], k)) { *out = self.vals[i]; return true; }
        i = (i + 1) & (self.cap - 1);
    }
    return false;
}
pub void Map<K, V>.free() { mem.free(self.keys); mem.free(self.vals); mem.free(self.used); }

i32 main() {
    printf("Test - generic containers: ");
    i64 correct = 0, tests = 0;
    {
        ad Vec<i64> v = vecNew<i64>();
        for (i64 i = 0; i < 100000; i++) { v.push(i); }
        i64 s = 0;
        for (i64 i = 0; i < v.len; i++) { s += v.get(i); }
        tests++; if (s == 4999950000) { correct++; }
        ad Vec<u8[]> words = vecNew<u8[]>(1);
        words.push("a"); words.push("bb"); words.push("ccc");
        tests++; if (words.len == 3 && words.get(2).len == 3) { correct++; }
    }
    {
        ad Map<i64, i64> m = mapNew<i64, i64>();
        for (i64 i = 0; i < 100000; i++) { m.set(i, i * 3); }
        m.set(5, -1);
        i64 got = 0;
        tests++; if (m.len == 100000 && m.get(777, &got) && got == 2331 && !m.get(-4, &got)) { correct++; }
        tests++; if (m.get(5, &got) && got == -1) { correct++; }
        ad Map<u8[], f64> prices = mapNew<u8[], f64>();
        prices.set("tea", 2.5);
        prices.set("coffee", 3.25);
        f64 p = 0;
        tests++; if (prices.get("coffee", &p) && p == 3.25 && !prices.get("milk", &p)) { correct++; }
    }
    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}

