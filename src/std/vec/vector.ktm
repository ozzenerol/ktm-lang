module vec;
import mem, os, str;

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
    u8 *msg = strPrint(null, "%s: index %d out of range (size %d)\n",
                                         fnName, idx, size);
    fdWrite(os.STDERR, msg, strLen(msg));
    exitProgram(1);
}

pub FloatVec *floatVecNew(i64 size = 32) {
    FloatVec *vec = memAlloc(sizeof(FloatVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = memAlloc(sizeof(f64) * size);
    return vec;
}

pub u64 floatVecCapacity(FloatVec *vec) {
    return memSize(vec.entries)/sizeof(f64);
}

pub void floatVecPush(FloatVec *vec, f64 value) {
    auto capacity = memSize(vec.entries)/sizeof(f64);
    if (vec.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = memRealloc(vec.entries, (u64)(newCapacity * sizeof(f64)));
        vec.entries = newEntries;
    }
    vec.entries[vec.size++] = value;
}

pub f64 floatVecPop(FloatVec *vec, bool *_ok=null) {
    if (vec.size > 0) {
        auto value = vec.entries[--vec.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return -1.0;
}

pub f64 floatVecGet(FloatVec *vec, i64 idx) {
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("FloatVecGet", idx, vec.size);
    }
    return vec.entries[idx];
}

pub void floatVecSet(FloatVec *vec, i64 idx, f64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("FloatVecSet", idx, vec.size);
    }
    vec.entries[idx] = value;
}

pub f64 floatVecLast(FloatVec *vec, bool *_ok=null)
{// The last entry without removing it; -1.0 and *_ok = false when empty
    if (vec.size > 0) {
        if (_ok) { *_ok = true; }
        return vec.entries[vec.size - 1];
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

pub void floatVecSort(FloatVec *vec) {
    qsortGeneric((void**)vec.entries,vec.size-1,0,&floatCmp);
}

pub void floatVecClear(FloatVec *vec) {
    vec.size = 0;
}

pub void floatVecRelease(FloatVec *vec) {
    if (vec) {
        memFree(vec.entries);
        memFree(vec);
    }
}

pub IntVec *intVecNew(i64 size = 32) {
    IntVec *vec = memAlloc(sizeof(IntVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = memAlloc(sizeof(i64) * size);
    return vec;
}

pub u64 intVecCapacity(IntVec *vec) {
    return memSize(vec.entries)/sizeof(i64);
}

pub void intVecPush(IntVec *vec, i64 value) {
    auto capacity = intVecCapacity(vec);
    if (vec.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = memRealloc(vec.entries, (u64)(newCapacity * sizeof(i64)));
        vec.entries = newEntries;
    }
    vec.entries[vec.size++] = value;
}

pub i64 intVecPop(IntVec *vec, bool *_ok=null) {
    if (vec.size > 0) {
        auto value = vec.entries[--vec.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return -1;
}

pub i64 intVecGet(IntVec *vec, i64 idx) {
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("IntVecGet", idx, vec.size);
    }
    return vec.entries[idx];
}

pub void intVecSet(IntVec *vec, i64 idx, i64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("IntVecSet", idx, vec.size);
    }
    vec.entries[idx] = value;
}

pub i64 intVecLast(IntVec *vec, bool *_ok=null)
{// The last entry without removing it; -1 and *_ok = false when empty
    if (vec.size > 0) {
        if (_ok) { *_ok = true; }
        return vec.entries[vec.size - 1];
    }
    if (_ok) { *_ok = false; }
    return -1;
}

i64 intCmp(void *i1, void *i2) {
    return (i64)i1 < (i64)i2;
}

pub void intVecSort(IntVec *vec) {
    qsortGeneric((void**)vec.entries,vec.size-1,0,&intCmp);
}

pub void intVecClear(IntVec *vec) {
    vec.size = 0;
}

pub void intVecRelease(IntVec *vec) {
    if (vec) {
        memFree(vec.entries);
        memFree(vec);
    }
}

pub PtrVec *ptrVecNew(i64 size = 32) {
    PtrVec *vec = memAlloc(sizeof(PtrVec));
    vec.size = 0;
    if (size < 1) { size = 1; } /* 0 or negative: start with room for one */
    vec.entries = memAlloc(sizeof(void*) * size);
    return vec;
}

pub u64 ptrVecCapacity(PtrVec *vec) {
    return memSize(vec.entries)/sizeof(void*);
}

pub void ptrVecPush(PtrVec *vec, void *value) {
    auto capacity = memSize(vec.entries)/sizeof(void*);
    if (vec.size + 1 >= capacity) {
        auto newCapacity = capacity * 2;
        if (newCapacity < 8) { newCapacity = 8; }
        auto newEntries = memRealloc(vec.entries, (u64)(newCapacity * sizeof(void*)));
        vec.entries = newEntries;
    }
    vec.entries[vec.size++] = value;
}

pub void *ptrVecPop(PtrVec *vec, bool *_ok=null) {
    if (vec.size > 0) {
        auto value = vec.entries[--vec.size];
        if (_ok) { *_ok = true; }
        return value;
    }
    if (_ok) { *_ok = false; }
    return null;
}

pub void *ptrVecGet(PtrVec *vec, i64 idx) {
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("PtrVecGet", idx, vec.size);
    }
    return vec.entries[idx];
}

pub void ptrVecSet(PtrVec *vec, i64 idx, void *value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
    if (idx < 0 || idx >= vec.size) {
        vecIndexError("PtrVecSet", idx, vec.size);
    }
    vec.entries[idx] = value;
}

pub void *ptrVecLast(PtrVec *vec, bool *_ok=null)
{// The last entry without removing it; null and *_ok = false when empty
    if (vec.size > 0) {
        if (_ok) { *_ok = true; }
        return vec.entries[vec.size - 1];
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
pub void qsortGeneric(void **arr, i64 high, i64 low,
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
            qsortGeneric(arr, lt - 1, low, _compare_fn);
            low = gt + 1;
        } else {
            qsortGeneric(arr, high, gt + 1, _compare_fn);
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

pub void ptrVecSort(PtrVec *vec, fn(void *_arg1, void *_arg2) i64 _compare_fn) {
    qsortGeneric(vec.entries,vec.size-1,0,_compare_fn);
}

pub void ptrVecClear(PtrVec *vec) {
    vec.size = 0;
}

pub void ptrVecRelease(PtrVec *vec, fn(void *_val) void _free_value = null)
{ // Optionally pass a callback to free each item in the vector 
    if (vec) {
        if (_free_value) {
            void **entries = vec.entries;
            i64 size = vec.size;
            for (i64 i = 0; i < size; ++i) {
                _free_value(entries[i]);
            }
        }
        memFree(vec.entries);
        memFree(vec);
    }
}

#ifdef VEC_TEST
void main() {
    auto vec = FloatVecNew();
    FloatVecPush(vec,32.32);
    FloatVecPush(vec,30.33);
    FloatVecPush(vec,42.65);
    FloatVecPush(vec,52.99);

    for (i64 i = 0; i < vec->size; ++i) {
        auto val = vec->entries[i];// FloatVecGet(vec,i);
        printf("[%d] => %f\n",i,(f64)val);
    }
    FloatVecRelease(vec);
}
#endif
