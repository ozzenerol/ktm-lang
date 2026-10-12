module map;
import libc, mem, rt, str, vec;

/* A hash map from K to V, held by value (like String): make one with
 * map.new<K, V>(), free it with free() (or declare it with `ad`).
 * Iteration (next, keys, values) goes in insertion order. Keys are
 * hashed with map.hashOf and compared with map.keyEq: i64, u64, i32,
 * u32, any pointer, u8[] and String have their own; any other type is
 * hashed and compared by its bytes (initialise a struct key fully). A
 * u8[] or String key is copied, and the map frees its copy; values are
 * yours. */

const i64 EMPTY = -1;
const i64 GONE = -2;

/* ---- keys ---- */
pub u64 hashOf(i64 k) {
    u64 x = (u64)k * 0x9E3779B97F4A7C15;
    return x ^ (x >> 29);
}
pub u64 hashOf(u64 k) { return hashOf((i64)k); }
pub u64 hashOf(i32 k) { return hashOf((i64)k); }
pub u64 hashOf(u32 k) { return hashOf((i64)k); }
pub u64 hashOf(void *k) { return hashOf((i64)k); }
pub u64 hashOf(u8[] k) {
    u64 h = 0xcbf29ce484222325;
    for (i64 i = 0; i < k.len; i++) { h = (h ^ k[i]) * 0x100000001b3; }
    return h ^ (h >> 32);
}
pub u64 hashOf(String k) { return hashOf(k.ptr[0:k.len]); }
/* any other key: its bytes */
pub u64 hashOf<K>(K k) {
    u8 *p = (u8 *)&k;
    return hashOf(p[0:sizeof(K)]);
}

pub bool keyEq(u8[] a, u8[] b) { return str.equals(a, b); }
pub bool keyEq(String a, String b) { return str.equals(a, b); }
pub bool keyEq<K>(K a, K b) { return libc.memcmp(&a, &b, sizeof(K)) == 0; }

/* the map's own copy of a key and freeing it: map.keyCopy/keyFree (tree.ktm) */

/* ---- the map ----
 * Entries are kept in insertion order in `entries`; `slots` is the hash
 * table, each slot the index of an entry (EMPTY, or GONE after a delete
 * so probe chains stay whole). Rebuilt when the slots are 3/4 used. */
pub struct Entry<K, V> {
    K key;
    V value;
    u64 hash;
    bool live;
};

pub struct Map<K, V> {
    Entry<K, V> *entries;
    i64 nentries;      /* entries used, deleted ones included */
    i64 entcap;
    i64 *slots;
    i64 nslots;        /* a power of two */
    i64 len;           /* live entries */
};

pub Map<K, V> new<K, V>(i64 cap = 8) {
    Map<K, V> m;
    i64 n = 8;
    while (n < cap * 2) { n *= 2; }
    m.nslots = n;
    m.slots = mem.alloc(sizeof(i64) * n);
    for (i64 i = 0; i < n; i++) { m.slots[i] = EMPTY; }
    m.entcap = cap > 4 ? cap : 4;
    m.entries = mem.alloc(sizeof(Entry<K, V>) * m.entcap);
    m.nentries = 0;
    m.len = 0;
    return m;
}

/* The slot of `k` (hash h): where it is, or -1 */
i64 Map<K, V>.find(K k, u64 h) {
    i64 mask = self.nslots - 1;
    i64 i = (i64)(h & (u64)mask);
    while (self.slots[i] != EMPTY) {
        i64 e = self.slots[i];
        if (e != GONE && self.entries[e].hash == h && keyEq(self.entries[e].key, k)) {
            return i;
        }
        i = (i + 1) & mask;
    }
    return -1;
}

/* Rebuilt with room for `n` entries: deleted entries dropped, order kept */
void Map<K, V>.rebuild(i64 n) {
    i64 live = 0;
    for (i64 i = 0; i < self.nentries; i++) {
        if (self.entries[i].live) {
            self.entries[live] = self.entries[i];
            live++;
        }
    }
    self.nentries = live;
    if (n > self.entcap) {
        self.entcap = n;
        self.entries = mem.realloc(self.entries, sizeof(Entry<K, V>) * n);
    }
    i64 slots = 8;
    while (slots < n * 2) { slots *= 2; }
    mem.free(self.slots);
    self.nslots = slots;
    self.slots = mem.alloc(sizeof(i64) * slots);
    for (i64 i = 0; i < slots; i++) { self.slots[i] = EMPTY; }
    i64 mask = slots - 1;
    for (i64 e = 0; e < self.nentries; e++) {
        i64 i = (i64)(self.entries[e].hash & (u64)mask);
        while (self.slots[i] != EMPTY) { i = (i + 1) & mask; }
        self.slots[i] = e;
    }
}

/* k set to v (a new key added, or its value replaced) */
pub void Map<K, V>.set(K k, V v) {
    u64 h = hashOf(k);
    i64 at = self.find(k, h);
    if (at >= 0) {
        self.entries[self.slots[at]].value = v;
        return;
    }
    if ((self.nentries + 1) * 4 > self.nslots * 3 || self.nentries == self.entcap) {
        self.rebuild(self.len * 2 + 8);
    }
    i64 e = self.nentries;
    self.entries[e].key = keyCopy(k);
    self.entries[e].value = v;
    self.entries[e].hash = h;
    self.entries[e].live = true;
    self.nentries++;
    self.len++;
    i64 mask = self.nslots - 1;
    i64 i = (i64)(h & (u64)mask);
    while (self.slots[i] != EMPTY && self.slots[i] != GONE) { i = (i + 1) & mask; }
    self.slots[i] = e;
}

/* k's value into *out: false (and *out untouched) when k isn't there */
pub bool Map<K, V>.get(K k, V *out) {
    i64 at = self.find(k, hashOf(k));
    if (at < 0) { return false; }
    if (out) { *out = self.entries[self.slots[at]].value; }
    return true;
}

/* k's value, or `def` when k isn't there */
pub V Map<K, V>.getOr(K k, V def) {
    i64 at = self.find(k, hashOf(k));
    return at < 0 ? def : self.entries[self.slots[at]].value;
}

/* A pointer to k's value, to change it in place (null when k isn't
 * there; valid until the map changes) */
pub V *Map<K, V>.ref(K k) {
    i64 at = self.find(k, hashOf(k));
    return at < 0 ? null : &self.entries[self.slots[at]].value;
}

pub bool Map<K, V>.has(K k) {
    return self.find(k, hashOf(k)) >= 0;
}

/* k removed: false when it wasn't there */
pub bool Map<K, V>.delete(K k) {
    i64 at = self.find(k, hashOf(k));
    if (at < 0) { return false; }
    i64 e = self.slots[at];
    keyFree(self.entries[e].key);
    self.entries[e].live = false;
    self.slots[at] = GONE;
    self.len--;
    return true;
}

/* The next entry from *it (start at 0), in insertion order: its key and
 * value into *k and *v (either may be null); false at the end.
 *     i64 it = 0; u8[] k; i64 v;
 *     while (m.next(&it, &k, &v)) { ... } */
pub bool Map<K, V>.next(i64 *it, K *k, V *v) {
    while (*it < self.nentries) {
        i64 e = *it;
        *it = e + 1;
        if (self.entries[e].live) {
            if (k) { *k = self.entries[e].key; }
            if (v) { *v = self.entries[e].value; }
            return true;
        }
    }
    return false;
}

/* The keys (views of the map's own copies) and the values, in insertion
 * order, as a new Vec */
pub vec.Vec<K> Map<K, V>.keys() {
    vec.Vec<K> out = vec.new<K>(self.len + 1);
    for (i64 e = 0; e < self.nentries; e++) {
        if (self.entries[e].live) { out.push(self.entries[e].key); }
    }
    return out;
}

pub vec.Vec<V> Map<K, V>.values() {
    vec.Vec<V> out = vec.new<V>(self.len + 1);
    for (i64 e = 0; e < self.nentries; e++) {
        if (self.entries[e].live) { out.push(self.entries[e].value); }
    }
    return out;
}

/* Empty again, keeping its room */
pub void Map<K, V>.clear() {
    for (i64 e = 0; e < self.nentries; e++) {
        if (self.entries[e].live) { keyFree(self.entries[e].key); }
    }
    for (i64 i = 0; i < self.nslots; i++) { self.slots[i] = EMPTY; }
    self.nentries = 0;
    self.len = 0;
}

pub void Map<K, V>.free() {
    self.clear();
    mem.free(self.entries);
    mem.free(self.slots);
    self.entries = null;
    self.slots = null;
    self.nslots = 0;
    self.entcap = 0;
}
