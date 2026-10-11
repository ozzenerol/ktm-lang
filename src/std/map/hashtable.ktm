module map;
import libc, mem, str, vec;

const i64 HT_LOAD = 60;

const i64 HT_MIN_CAPACITY = 8;

const i64 HT_MAX_CAPACITY = (1 << 28);

/* Deletion leaves a tombstone: the node stays in its slot with `order` set
    * to HT_DELETED so probe chains running through it are not cut short. For a
    * live node `order` is its position in the map's `indexes` vector, which is
    * how a delete removes it from iteration. Tombstones are dropped whenever the
    * table is rebuilt; the rebuild is triggered by `indexes->size`, which counts
    * live entries plus at least one hole per tombstone, so the table always
    * keeps an empty slot and every probe terminates. */
pub struct IntMapNode {
        i64 key;
        void *value;
        i64 order;
};

pub struct StrMapNode {
        u8[] key; /* the map's own copy, with a NUL after it (key.ptr is a C string) */
        void *value;
        i64 order;
};

pub struct IntMap {
        u64 size;     /* How many entries are in the hashtable */
        u64 capacity; /* How much capacity we have in the entries array */
        u64 mask;     /* Used for hashing, as the capacity is always a power of 2
                                                                    * we can use fast modulo of `<int> & capacity-1`. */
        vec.IntVec *indexes; /* Where all of the values are in the entries array, in 
                                                                                * insertion order. Means we can iterate over the HashTable 
                                                                                * quickly at the cost of memory */
        u64 threshold; /* rebuild threashold */
        fn(void *value) void freeValue; /* User defined callback for freeing values */
        IntMapNode **entries; /* The slots */
};

pub struct StrMap {
        u64 size;     /* How many entries are in the hashtable */
        u64 capacity; /* How much capacity we have in the entries array */
        u64 mask;     /* Used for hashing, as the capacity is always a power of 2
                                                                    * we can use fast modulo of `<int> & capacity-1`. */
        vec.IntVec *indexes; /* Where all of the values are in the entries array, in 
                                                                    * insertion order. Means we can iterate over the HashTable 
                                                                    * quickly at the cost of memory */
        u64 threshold; /* rebuild threashold */
        fn(void *_value) void freeValue; /* User defined callback for freeing values */
        StrMapNode **entries; /* The slots */
};

u64 roundUpToNextPowerOf2(u64 v) {
        v--;
        v |= v >> 1;
        v |= v >> 2;
        v |= v >> 4;
        v |= v >> 8;
        v |= v >> 16;
        v |= v >> 32;
        v++;
        return v;
}

static u64 htCapacity(u64 capacity)
{/* The probe loops use `idx & mask`, so the capacity, the mask and the size
        * of the entries array must all be the same power of two. Round any
        * requested size up to one (and to at least HT_MIN_CAPACITY, which also
        * keeps 0 and 1 from producing a zero mask or a zero threshold).
        * The request is only a starting size: one that is negative (`-1` passed
        * for the u64) gets HT_MIN_CAPACITY, and one above HT_MAX_CAPACITY gets
        * HT_MAX_CAPACITY; past 2^63 the rounding would wrap to 0. The map still
        * grows past HT_MAX_CAPACITY as entries are added. */
        if ((i64)capacity < HT_MIN_CAPACITY) {
                capacity = HT_MIN_CAPACITY;
        } else if (capacity > HT_MAX_CAPACITY) {
                capacity = HT_MAX_CAPACITY;
        }
        return roundUpToNextPowerOf2(capacity);
}

static u64 htFmix64(u64 h)
{/* murmur3's 64-bit finaliser: every bit of `h` affects every bit of the
        * result, so keys that differ only in bits above the mask still land in
        * different slots. */
        h ^= h >> 33;
        h *= 0xff51afd7ed558ccd;
        h ^= h >> 33;
        h *= 0xc4ceb9fe1a85ec53;
        h ^= h >> 33;
        return h;
}

u64 intMapHashFunction(i64 key, u64 mask)
{/* Mixed first: `key & mask` alone sent keys sharing their low bits
        * (multiples of a power of two, aligned pointers, `id << 20`) to the
        * same slots. */
        return htFmix64(key) & mask;
}

pub IntMap *intNew(u64 capacity = 32)
{// null when out of memory
        IntMap *map = mem.alloc(sizeof(IntMap));
        if (!map) {
                return null;
        }
        capacity = htCapacity(capacity);
        map.capacity = capacity;
        map.mask = capacity-1;
        map.size = 0;
        map.indexes = vec.intNew();
        map.threshold = (capacity * HT_LOAD)/100;
        map.freeValue = null;
        map.entries = mem.allocZero(capacity * sizeof(IntMapNode *));
        if (!map.entries) {
                map.indexes.release();
                mem.free(map);
                return null;
        }
        return map;
}

pub void IntMap.setFreeValue(fn(void *value) void freeValue) {
        if (self) {
                self.freeValue = freeValue;
        }
}

IntMapNode *intMapNodeNew(i64 key, void *value) {
        IntMapNode *n = mem.alloc(sizeof(IntMapNode));
        n.key = key;
        n.value = value;
        n.order = HT_DELETED;
        return n;
}

static u64 intMapGetIdx(IntMap *map, i64 key)
{// Slot holding `key`, or HT_DELETED. Tombstones are skipped, not stopped at.
        u64 idx = intMapHashFunction(key, map.mask); 
        u64 mask = map.mask;
        IntMapNode **entries = map.entries;
        IntMapNode *cur;

        while ((cur = entries[idx])) {
                if (cur.order != HT_DELETED && cur.key == key) {
                        return idx;
                }
                idx = (idx + 1) & mask;
        }
        return HT_DELETED;
}

static u64 intMapGetNextIdx(IntMap *map, i64 key, bool *_is_free)
{// The slot holding `key` (*_is_free = false), else the slot to insert it
    // into (*_is_free = true): the first tombstone on the probe chain, or the
    // empty slot that ends it.
        u64 mask = map.mask;
        u64 idx = intMapHashFunction(key, mask);
        u64 tombstone = HT_DELETED;
        IntMapNode *cur;

        while ((cur = map.entries[idx]) != null) {
                if (cur.order == HT_DELETED) {
                        if (tombstone == HT_DELETED) {
                                tombstone = idx;
                        }
                } else if (cur.key == key) {
                        *_is_free = false;
                        return idx;
                }
                idx = (idx + 1) & mask;
        }
        *_is_free = true;
        if (tombstone != HT_DELETED) {
                return tombstone;
        }
        return idx;
}

pub void IntMap.release()
{// free the entire hashtable
        if (self) {
                auto indexes = self.indexes;
                fn(void *value) void freeValue = self.freeValue;
                for (auto i = 0; i < self.capacity; ++i) {
                        auto n = self.entries[i];
                        if (n) {
                                /* A tombstone's value was freed by IntMapDelete. */
                                if (freeValue && n.order != HT_DELETED) {
                                        freeValue(n.value);
                                }
                                mem.free(n);
                        }
                }
                indexes.release();
                mem.free(self.entries);
                mem.free(self);
        }
}

static bool intMapRehash(IntMap *map, u64 newCapacity)
{// Rebuild the table with `new_capacity` slots, keeping insertion order and
    // dropping tombstones. Returns false if OOM.
        u64 newMask;
        IntMapNode **newEntries, **oldEntries;
        bool isFree = 0;
        i64 *newIndexes, *oldIndexes, newSize;
        auto indexesCapacity = map.indexes.capacity();
        auto indexesSize = map.indexes.size;
        auto oldCapacity = map.capacity;

        oldEntries = map.entries;
        oldIndexes = map.indexes.entries;

        newMask = newCapacity - 1;

        /* OOM */
        if ((newIndexes = mem.allocZero(indexesCapacity * sizeof(i64))) == null) {
                return false;
        }

        /* OOM */
        if ((newEntries = mem.allocZero(newCapacity * sizeof(IntMapNode*))) == null) {
                mem.free(newIndexes);
                return false;
        }

        map.mask = newMask;
        map.entries = newEntries;
        map.capacity = newCapacity;
        newSize = 0;

        for (u64 i = 0; i < indexesSize; ++i) {
                i64 idx = oldIndexes[i];
                if (idx != HT_DELETED) {
                        auto old = oldEntries[idx];
                        auto newIdx = intMapGetNextIdx(map,old.key,&isFree);
                        old.order = newSize;
                        newIndexes[newSize] = newIdx;
                        newEntries[newIdx] = old;
                        newSize++;
                }
        }

        for (u64 i = 0; i < oldCapacity; ++i) {
                auto old = oldEntries[i];
                if (old && old.order == HT_DELETED) {
                        mem.free(old);
                }
        }

        mem.free(oldEntries);
        mem.free(oldIndexes);
        map.indexes.size = newSize;
        map.indexes.entries = newIndexes;

        map.threshold = (newCapacity * HT_LOAD)/100;
        map.entries = newEntries;
        map.size = newSize;
        return true;
}

pub bool IntMap.resize()
{// Double the hashtable, will return false if OOM
        if (!self) {
                return false;
        }
        return intMapRehash(self,self.capacity << 1);
}

pub bool IntMap.set(i64 key, void *value) {
        bool isFree;

        if (!self) {
                return false;
        }

        /* `indexes->size` is the live entries plus the holes deletes left, so this
            * also bounds the tombstones. A table that is mostly tombstones is rebuilt
            * at the same size rather than doubled. */
        if (self.indexes.size >= self.threshold) {
                u64 newCapacity = self.capacity;
                if (self.size >= self.threshold / 2) {
                        newCapacity <<= 1;
                }
                if (!intMapRehash(self,newCapacity)) {
                        /* This means we have run out of memory */
                        return false;
                }
        }

        u64 idx = intMapGetNextIdx(self,key,&isFree);
        if (isFree) {
                auto n = self.entries[idx];
                if (n) {
                        /* Reuse a tombstone */
                        n.key = key;
                        n.value = value;
                } else {
                        n = intMapNodeNew(key,value);
                        self.entries[idx] = n;
                }
                n.order = self.indexes.size;
                self.indexes.push(idx);
                self.size++;
                return true;
        } else {
                auto n = self.entries[idx];
                if (self.freeValue && n.value != value) {
                        /* Replacing a live value: the map owned the old one. */
                        self.freeValue(n.value);
                }
                n.value = value;
                return true;
        }
}

pub bool IntMap.delete(i64 key)
{// Remove `key`, freeing its value if a destructor is registered. Returns
    // false if the key was not in the map.
        if (!self) {
                return false;
        }
        auto idx = intMapGetIdx(self,key);
        if (idx != HT_DELETED) {
                auto n = self.entries[idx];
                if (self.freeValue) {
                        self.freeValue(n.value);
                }
                self.indexes.entries[n.order] = HT_DELETED;
                n.order = HT_DELETED;
                n.value = null;
                self.size--;
                return true;
        }
        return false;
}

pub bool IntMap.has(i64 key) {
        if (!self) {
                return false;
        }
        auto idx = intMapGetIdx(self,key);
        return idx != HT_DELETED;
}

pub void *IntMap.get(i64 key, bool *_ok=null)
{// The value stored for `key`, or null. `*_ok` (when given) tells a
    // missing key (false) from a stored null or 0 (true).
        if (self) {
                auto idx = intMapGetIdx(self,key);
                if (idx != HT_DELETED) {
                        if (_ok) { *_ok = true; }
                        return self.entries[idx].value;
                }
        }
        if (_ok) { *_ok = false; }
        return null;
}

pub void IntMap.clear()
{// Remove every entry, freeing the values if a destructor is registered.
    // The capacity is kept.
        if (!self) {
                return;
        }
        for (i64 i = 0; i < self.capacity; ++i) {
                auto n = self.entries[i];
                if (n) {
                        /* A tombstone's value was freed by IntMapDelete. */
                        if (self.freeValue && n.order != HT_DELETED) {
                                self.freeValue(n.value);
                        }
                        mem.free(n);
                        self.entries[i] = null;
                }
        }
        self.indexes.size = 0;
        self.size = 0;
}

pub bool IntMap.iter(i64 *_idx, IntMapNode **_node) {
        if (!self) {
                return false;
        }
        i64 idx = *_idx;
        auto indexes = self.indexes.entries;

        while (idx < self.indexes.size) {
                i64 index = indexes[idx];
                if (index != HT_DELETED) {
                        *_idx = idx + 1;
                        *_node = self.entries[index];
                        return true;
                }
                idx++;
        }
        return false;
}

pub bool IntMap.valueIter(i64 *_idx, void **_value) {
        IntMapNode *node;
        if (self.iter(_idx,&node)) {
                *_value = node.value;
                return true;
        }
        return false;
}

pub bool IntMap.keyIter(i64 *_idx, i64 *_key) {
        IntMapNode *node;
        if (self.iter(_idx,&node)) {
                *_key = node.key;
                return true;
        }
        return false;
}

pub String IntMap.toStr(fn(void *) String stringifyValue) {
        String out = str.from("{");
        if (self) {
                IntMapNode *entry = null;
                i64 printed = 0; /* `i` skips the holes deletes leave, so count */
                for (i64 i = 0; self.iter(&i,&entry);) {
                        String strVal = stringifyValue(entry.value);
                        if (printed++) { out.append(", "); }
                        out.appendFormat("[%d] => %s",entry.key,strVal);
                        strVal.free();
                }
        }
        out.append("}");
        return out;
}

pub String IntMap.keysToStr() {
        String out = str.from("{");
        if (self) {
                IntMapNode *entry = null;
                i64 printed = 0;
                for (i64 i = 0; self.iter(&i,&entry);) {
                        if (printed++) { out.append(", "); }
                        out.appendFormat("%d",entry.key);
                }
        }
        out.append("}");
        return out;
}

pub StrMap *strNew(u64 capacity = 32)
{// null when out of memory
        StrMap *map = mem.alloc(sizeof(StrMap));
        if (!map) {
                return null;
        }
        capacity = htCapacity(capacity);
        map.capacity = capacity;
        map.mask = capacity-1;
        map.size = 0;
        map.indexes = vec.intNew();
        map.threshold = (capacity * HT_LOAD)/100;
        map.freeValue = null;
        map.entries = mem.allocZero(capacity * sizeof(StrMapNode *));
        if (!map.entries) {
                map.indexes.release();
                mem.free(map);
                return null;
        }
        return map;
}

pub void StrMap.setFreeValue(fn(void *value) void freeValue) {
        if (self) {
                self.freeValue = freeValue;
        }
}

static void strMapFreeEntry(StrMap *map, StrMapNode *n)
{// Free a live entry's value with the registered destructor, and its key
        if (map.freeValue) {
                map.freeValue(n.value);
        }
        u8[] none;
        mem.free(n.key.ptr);
        n.key = none;
}

static u64 strMapHash(u8[] key)
{/* FNV-1a over the key's bytes, then mixed */
        u64 h = 0xcbf29ce484222325;
        u8 *p = key.ptr;
        for (i64 i = 0; i < key.len; i++) {
                h ^= p[i];
                h *= 0x100000001b3;
        }
        return htFmix64(h);
}

static bool strMapKeyIs(StrMapNode *n, u8[] key)
{
        return n.key.len == key.len &&
                (!key.len || !libc.memcmp(n.key.ptr, key.ptr, key.len));
}

static u8[] strMapKeyCopy(u8[] key)
{// The map's own copy of key, with a NUL after it; empty when out of memory
        u8 *bytes = mem.alloc(key.len + 1);
        if (!bytes) {
                return bytes[0:0];
        }
        if (key.len) {
                mem.copy(bytes, key.ptr, key.len);
        }
        bytes[key.len] = 0;
        return bytes[0:key.len];
}

static u64 strMapGetNextIdx(StrMap *map, u8[] key, bool *_is_free)
{// The slot holding `key` (*_is_free = false), else the slot to insert it
    // into (*_is_free = true): the first tombstone on the probe chain, or the
    // empty slot that ends it.
        u64 mask = map.mask;
        u64 idx = strMapHash(key) & mask;
        u64 tombstone = HT_DELETED;
        StrMapNode *cur;

        while ((cur = map.entries[idx]) != null) {
                if (cur.order == HT_DELETED) {
                        if (tombstone == HT_DELETED) {
                                tombstone = idx;
                        }
                } else if (strMapKeyIs(cur, key)) {
                        *_is_free = false;
                        return idx;
                }
                idx = (idx + 1) & mask;
        }
        *_is_free = true;
        if (tombstone != HT_DELETED) {
                return tombstone;
        }
        return idx;
}

static i64 strMapGetIdx(StrMap *map, u8[] key)
{// Slot holding `key`, or HT_DELETED. Tombstones are skipped, not stopped at.
        u64 mask = map.mask;
        u64 idx = strMapHash(key) & mask;
        StrMapNode **entries = map.entries;
        StrMapNode *cur;

        while ((cur = entries[idx]) != null) {
                if (cur.order != HT_DELETED && strMapKeyIs(cur, key)) {
                        return idx;
                }
                idx = (idx + 1) & mask;
        }
        return HT_DELETED;
}

pub void StrMap.release()
{// free the entire hashtable
        if (self) {
                for (auto i = 0; i < self.capacity; ++i) {
                        auto n = self.entries[i];
                        if (n) {
                                /* A tombstone's key and value were freed by delete() */
                                if (n.order != HT_DELETED) {
                                        strMapFreeEntry(self,n);
                                }
                                mem.free(n);
                        }
                }
                mem.free(self.entries);
                self.indexes.release();
                mem.free(self);
        }
}

static bool strMapRehash(StrMap *map, u64 newCapacity)
{// Rebuild the table with `new_capacity` slots, keeping insertion order and
    // dropping tombstones. Returns false if OOM.
        u64 newMask;
        StrMapNode **newEntries, **oldEntries;
        i64 *newIndexes, *oldIndexes;
        bool isFree = false;
        auto indexesCapacity = map.indexes.capacity();
        auto indexesSize = map.indexes.size;
        auto oldCapacity = map.capacity;

        oldEntries = map.entries;
        oldIndexes = map.indexes.entries;

        newMask = newCapacity - 1;

        /* OOM */
        if ((newIndexes = mem.allocZero(indexesCapacity * sizeof(i64))) == null) {
                return false;
        }

        /* OOM */
        if ((newEntries = mem.allocZero(newCapacity * sizeof(StrMapNode*))) == null) {
                mem.free(newIndexes);
                return false;
        }

        map.mask = newMask;
        map.entries = newEntries;
        map.capacity = newCapacity;

        i64 newSize = 0;

        for (i64 i = 0; i < indexesSize; ++i) {
                i64 idx = oldIndexes[i];
                if (idx != HT_DELETED) {
                        auto old = oldEntries[idx];
                        i64 newIdx = strMapGetNextIdx(map,old.key,&isFree);
                        old.order = newSize;
                        newIndexes[newSize] = newIdx;
                        newEntries[newIdx] = old;
                        newSize++;
                }
        }

        for (i64 i = 0; i < oldCapacity; ++i) {
                auto old = oldEntries[i];
                if (old && old.order == HT_DELETED) {
                        mem.free(old);
                }
        }

        mem.free(oldEntries);
        mem.free(oldIndexes);
        map.indexes.size = newSize;
        map.indexes.entries = newIndexes;

        map.size = newSize;
        map.entries = newEntries;
        map.threshold = (newCapacity * HT_LOAD)/100;
        return true;
}

pub bool StrMap.resize()
{// Double the hashtable, will return false if OOM
        if (!self) {
                return false;
        }
        return strMapRehash(self,self.capacity << 1);
}

pub bool StrMap.set(u8[] key, void *value)
{// Store value under a copy of key's bytes (the map owns its keys; key can
    // be a literal, a String or a view into a buffer). An existing key gets
    // the new value, and the old one is freed when a value destructor is
    // registered (unless it is the same pointer). False when out of memory.
        bool isFree;

        if (!self) {
                return false;
        }

        /* `indexes->size` is the live entries plus the holes deletes left, so this
            * also bounds the tombstones. A table that is mostly tombstones is rebuilt
            * at the same size rather than doubled. */
        if (self.indexes.size >= self.threshold) {
                u64 newCapacity = self.capacity;
                if (self.size >= self.threshold / 2) {
                        newCapacity <<= 1;
                }
                if (!strMapRehash(self,newCapacity)) {
                        /* This means we have run out of memory */
                        return false;
                }
        }

        u64 idx = strMapGetNextIdx(self,key,&isFree);

        if (isFree) {
                u8[] own = strMapKeyCopy(key);
                if (!own.ptr) {
                        return false;
                }
                auto n = self.entries[idx];
                if (n) {
                        /* Reuse a tombstone */
                        n.key = own;
                        n.value = value;
                } else {
                        n = mem.alloc(sizeof(StrMapNode));
                        if (!n) {
                                mem.free(own.ptr);
                                return false;
                        }
                        n.key = own;
                        n.value = value;
                        self.entries[idx] = n;
                }
                n.order = self.indexes.size;
                self.indexes.push(idx);
                self.size++;
                return true;
        }
        auto n = self.entries[idx];
        if (self.freeValue && n.value != value) {
                self.freeValue(n.value);
        }
        n.value = value;
        return true;
}

pub bool StrMap.delete(u8[] key)
{// Remove `key`, freeing its value if a destructor is registered. Returns
    // false if the key was not in the map.
        if (!self) {
                return false;
        }
        i64 idx = strMapGetIdx(self,key);
        if (idx == HT_DELETED) {
                return false;
        }
        auto n = self.entries[idx];
        strMapFreeEntry(self,n);
        self.indexes.entries[n.order] = HT_DELETED;
        n.order = HT_DELETED;
        n.value = null;
        self.size--;
        return true;
}

pub void *StrMap.get(u8[] key, bool *_ok=null)
{// The value stored for key's bytes, or null. `*_ok` (when given) tells a
    // missing key (false) from a stored null (true).
        if (self) {
                auto idx = strMapGetIdx(self,key);
                if (idx != HT_DELETED) {
                        if (_ok) { *_ok = true; }
                        return self.entries[idx].value;
                }
        }
        if (_ok) { *_ok = false; }
        return null;
}

pub void StrMap.clear()
{// Remove every entry, freeing the values if a destructor is registered.
    // The capacity is kept.
        if (!self) {
                return;
        }
        for (i64 i = 0; i < self.capacity; ++i) {
                auto n = self.entries[i];
                if (n) {
                        /* A tombstone's key and value were freed by delete() */
                        if (n.order != HT_DELETED) {
                                strMapFreeEntry(self,n);
                        }
                        mem.free(n);
                        self.entries[i] = null;
                }
        }
        self.indexes.size = 0;
        self.size = 0;
}

pub bool StrMap.has(u8[] key) {
        if (!self) {
                return false;
        }
        return strMapGetIdx(self,key) != HT_DELETED;
}

pub bool StrMap.iter(i64 *_idx, StrMapNode **_node) {
        if (!self) {
                return false;
        }
        i64 idx = *_idx;
        auto indexes = self.indexes.entries;
        auto size = self.indexes.size;

        while (idx < size) {
                i64 index = indexes[idx];
                if (index != HT_DELETED) {
                        *_idx = idx + 1;
                        *_node = self.entries[index];
                        return true;
                }
                idx++;
        }
        return false;
}

pub bool StrMap.valueIter(i64 *_idx, void **_value) {
        StrMapNode *node;
        if (self.iter(_idx,&node)) {
                *_value = node.value;
                return true;
        }
        return false;
}

pub bool StrMap.keyIter(i64 *_idx, u8[] *_key)
{// *_key is a view of the map's copy: valid until that key is deleted or
    // the map cleared or released
        StrMapNode *node;
        if (self.iter(_idx,&node)) {
                *_key = node.key;
                return true;
        }
        return false;
}

pub String StrMap.toStr(fn(void *) String stringifyValue) {
        String out = str.from("{");
        if (self) {
                StrMapNode *entry = null;
                i64 printed = 0; /* `i` skips the holes deletes leave, so count */
                for (i64 i = 0; self.iter(&i,&entry);) {
                        String strVal = stringifyValue(entry.value);
                        if (printed++) { out.append(", "); }
                        out.appendFormat("[%s] => %s",entry.key,strVal);
                        strVal.free();
                }
        }
        out.append("}");
        return out;
}

pub String StrMap.keysToStr() {
        String out = str.from("{");
        if (self) {
                StrMapNode *entry = null;
                i64 printed = 0;
                for (i64 i = 0; self.iter(&i,&entry);) {
                        if (printed++) { out.append(", "); }
                        out.append(entry.key);
                }
        }
        out.append("}");
        return out;
}
