module vec;
import cstr, libc, mem, os;

pub struct IntVec {
    i64 size;
    i64 *entries;
};

pub struct PtrVec {
    i64 size;
    void **entries;
};

pub struct FloatVec {
    i64 size;
    f64 *entries;
};

/* Get/Set with an index outside [0, size): report it on stderr and exit,
 * rather than read or write past the entries. */
static void vecIndexError(u8 *fnName, i64 idx, i64 size) {
    u8 *msg = cstr.formatTo(null, "%s: index %d out of range (size %d)\n",
                                         fnName, idx, size);
    libc.write(os.STDERR, msg, cstr.len(msg));
    os.exit(1);
}

pub FloatVec *floatNew(i64 size = 32) {
    FloatVec *vec = mem.alloc(sizeof(FloatVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = mem.alloc(sizeof(f64) * size);
    return vec;
}

pub u64 FloatVec.capacity() {
    return mem.size(self.entries)/sizeof(f64);
}

pub void FloatVec.push(f64 value) {
    auto capacity = mem.size(self.entries)/sizeof(f64);
    if (self.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = mem.realloc(self.entries, (u64)(newCapacity * sizeof(f64)));
        self.entries = newEntries;
    }
    self.entries[self.size++] = value;
}

pub f64 FloatVec.pop(bool *_ok=null) {
    if (self.size > 0) {
        auto value = self.entries[--self.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return -1.0;
}

pub f64 FloatVec.get(i64 idx) {
    if (idx < 0 || idx >= self.size) {
        vecIndexError("FloatVecGet", idx, self.size);
    }
    return self.entries[idx];
}

pub void FloatVec.set(i64 idx, f64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= self.size) {
        vecIndexError("FloatVecSet", idx, self.size);
    }
    self.entries[idx] = value;
}

pub f64 FloatVec.last(bool *_ok=null)
{// The last entry without removing it; -1.0 and *_ok = false when empty
    if (self.size > 0) {
        if (_ok) { *_ok = true; }
        return self.entries[self.size - 1];
    }
    if (_ok) { *_ok = false; }
    return -1.0;
}

/* QSortGeneric hands the comparator two 64-bit slots as void *, in integer
 * registers: an f64 parameter would read the float registers instead, so
 * take the bits and reinterpret them. */
i64 floatCmp(void *a, void *b) {
    f64 f1 = *(f64 *)(&a);
    f64 f2 = *(f64 *)(&b);
    return f1 < f2;
}

pub void FloatVec.sort() {
    qsort((void**)self.entries,0,self.size-1,&floatCmp);
}

pub void FloatVec.clear() {
    self.size = 0;
}

pub void FloatVec.release() {
    if (self) {
        mem.free(self.entries);
        mem.free(self);
    }
}

pub IntVec *intNew(i64 size = 32) {
    IntVec *vec = mem.alloc(sizeof(IntVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = mem.alloc(sizeof(i64) * size);
    return vec;
}

pub u64 IntVec.capacity() {
    return mem.size(self.entries)/sizeof(i64);
}

pub void IntVec.push(i64 value) {
    auto capacity = self.capacity();
    if (self.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = mem.realloc(self.entries, (u64)(newCapacity * sizeof(i64)));
        self.entries = newEntries;
    }
    self.entries[self.size++] = value;
}

pub i64 IntVec.pop(bool *_ok=null) {
    if (self.size > 0) {
        auto value = self.entries[--self.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return -1;
}

pub i64 IntVec.get(i64 idx) {
    if (idx < 0 || idx >= self.size) {
        vecIndexError("IntVecGet", idx, self.size);
    }
    return self.entries[idx];
}

pub void IntVec.set(i64 idx, i64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= self.size) {
        vecIndexError("IntVecSet", idx, self.size);
    }
    self.entries[idx] = value;
}

pub i64 IntVec.last(bool *_ok=null)
{// The last entry without removing it; -1 and *_ok = false when empty
    if (self.size > 0) {
        if (_ok) { *_ok = true; }
        return self.entries[self.size - 1];
    }
    if (_ok) { *_ok = false; }
    return -1;
}

i64 intCmp(void *i1, void *i2) {
    return (i64)i1 < (i64)i2;
}

pub void IntVec.sort() {
    qsort((void**)self.entries,0,self.size-1,&intCmp);
}

pub void IntVec.clear() {
    self.size = 0;
}

pub void IntVec.release() {
    if (self) {
        mem.free(self.entries);
        mem.free(self);
    }
}

pub PtrVec *ptrNew(i64 size = 32) {
    PtrVec *vec = mem.alloc(sizeof(PtrVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = mem.alloc(sizeof(void*) * size);
    return vec;
}

pub u64 PtrVec.capacity() {
    return mem.size(self.entries)/sizeof(void*);
}

pub void PtrVec.push(void *value) {
    auto capacity = mem.size(self.entries)/sizeof(void*);
    if (self.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = mem.realloc(self.entries, (u64)(newCapacity * sizeof(void*)));
        self.entries = newEntries;
    }
    self.entries[self.size++] = value;
}

pub void *PtrVec.pop(bool *_ok=null) {
    if (self.size > 0) {
        auto value = self.entries[--self.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return null;
}

pub void *PtrVec.get(i64 idx) {
    if (idx < 0 || idx >= self.size) {
        vecIndexError("PtrVecGet", idx, self.size);
    }
    return self.entries[idx];
}

pub void PtrVec.set(i64 idx, void *value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= self.size) {
        vecIndexError("PtrVecSet", idx, self.size);
    }
    self.entries[idx] = value;
}

pub void *PtrVec.last(bool *_ok=null)
{// The last entry without removing it; null and *_ok = false when empty
    if (self.size > 0) {
        if (_ok) { *_ok = true; }
        return self.entries[self.size - 1];
    }
    if (_ok) { *_ok = false; }
    return null;
}

/* Sort arr[low..high] in place; `_compare_fn(a, b)` is true when `a` must
 * come before `b` (a "less than", not a C-style -1/0/1 comparator). Not
 * stable. Quicksort with a median-of-three pivot and a three-way
 * partition, so sorted, reversed and all-equal input stay O(n log n); it
 * recurses into the smaller part and loops on the larger one, so the
 * recursion depth is O(log n). Short ranges use an insertion sort. */
pub void qsort(void **arr, i64 low, i64 high,
        fn(void *_arg1, void *_arg2) i64 _compare_fn) {
    void *tmp;
    while (high - low >= 16) {
        /* Pivot: the median of three entries, one from each third of the
         * range, at offsets scrambled by a hash of the bounds. Fixed spots
         * (first/middle/last) are defeated by the order the partition leaves
         * sorted input in and degrade towards O(n^2). */
        u64 third = (u64)((high - low + 1) / 3);
        u64 h = (u64)low * 0x9E3779B97F4A7C15 + (u64)high * 0xC2B2AE3D27D4EB4F;
        h ^= h >> 31;
        i64 a = low + (i64)(h % third);
        i64 c = low + (i64)third + (i64)((h >> 20) % third);
        i64 b = high - (i64)((h >> 40) % third);
        i64 m;
        if (_compare_fn(arr[c], arr[a])) {
            if (_compare_fn(arr[b], arr[c]))      { m = c; }
            else if (_compare_fn(arr[b], arr[a])) { m = b; }
            else                                  { m = a; }
        } else {
            if (_compare_fn(arr[b], arr[a]))      { m = a; }
            else if (_compare_fn(arr[b], arr[c])) { m = b; }
            else                                  { m = c; }
        }
        tmp = arr[low]; arr[low] = arr[m]; arr[m] = tmp;

        /* Three-way partition: [low, lt) < pivot, [lt, gt] == pivot,
         * (gt, high] > pivot. The pivot element itself is never compared,
         * so the middle part is never empty and both sides shrink. */
        void *pivot = arr[low];
        i64 lt = low, i = low + 1, gt = high;
        while (i <= gt) {
            if (_compare_fn(arr[i], pivot)) {
                tmp = arr[lt]; arr[lt] = arr[i]; arr[i] = tmp;
                lt++;
                i++;
            } else if (_compare_fn(pivot, arr[i])) {
                tmp = arr[gt]; arr[gt] = arr[i]; arr[i] = tmp;
                gt--;
            } else {
                i++;
            }
        }

        if (lt - low < high - gt) {
            qsort(arr, low, lt - 1, _compare_fn);
            low = gt + 1;
        } else {
            qsort(arr, gt + 1, high, _compare_fn);
            high = lt - 1;
        }
    }

    for (i64 j = low + 1; j <= high; ++j) {
        void *x = arr[j];
        i64 k = j - 1;
        while (k >= low && _compare_fn(x, arr[k])) {
            arr[k + 1] = arr[k];
            k--;
        }
        arr[k + 1] = x;
    }
}

pub void PtrVec.sort(fn(void *_arg1, void *_arg2) i64 _compare_fn) {
    qsort(self.entries,0,self.size-1,_compare_fn);
}

pub void PtrVec.clear() {
    self.size = 0;
}

pub void PtrVec.release(fn(void *_val) void freeValue = null)
{ // Optionally pass a callback to free each item in the vector 
    if (self) {
        if (freeValue) {
            void **entries = self.entries;
            i64 size = self.size;
            for (i64 i = 0; i < size; ++i) {
                freeValue(entries[i]);
            }
        }
        mem.free(self.entries);
        mem.free(self);
    }
}

#ifdef VEC_TEST
void main() {
    auto vec = floatNew();
    floatPush(vec,32.32);
    floatPush(vec,30.33);
    floatPush(vec,42.65);
    floatPush(vec,52.99);

    for (i64 i = 0; i < vec->size; ++i) {
        auto val = vec->entries[i];// FloatVecGet(vec,i);
        printf("[%d] => %f\n",i,(f64)val);
    }
    floatRelease(vec);
}
#endif
