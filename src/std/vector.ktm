/* There is a lot of duplication here but it does 'work', generics would clean
 * all of this up... */

public U0 QSortGeneric(U0 **arr, I64 high, I64 low,
    I64 (*_compare_fn)(U0 *_arg1, U0 *_arg2));

class IntVec
{
  I64 size;
  I64 *entries;
};

class PtrVec
{
  I64 size;
  U0 **entries;
};

class FloatVec 
{
  I64 size;
  F64 *entries;
};

/* Get/Set with an index outside [0, size): report it on stderr and exit,
 * rather than read or write past the entries. */
static U0 _VecIndexError(U8 *fn_name, I64 idx, I64 size)
{
  U8 *msg = StrPrint(NULL, "%s: index %d out of range (size %d)\n",
                     fn_name, idx, size);
  Write(STDERR, msg, StrLen(msg));
  Exit(1);
}

FloatVec *FloatVecNew(I64 size = 32)
{
  FloatVec *vec = MAlloc(sizeof(FloatVec));
  vec->size = 0;
  if (size < 1) size = 1; /* 0 or negative: start with room for one */
  vec->entries = MAlloc(sizeof(F64) * size);
  return vec;
}

U64 FloatVecCapacity(FloatVec *vec)
{
  return MSize(vec->entries)/sizeof(F64);
}

U0 FloatVecPush(FloatVec *vec, F64 value)
{
  auto capacity = MSize(vec->entries)/sizeof(F64);
  if (vec->size + 1 >= capacity) {
    auto new_capacity = capacity * 2;
    if (new_capacity < 8) new_capacity = 8;
    auto new_entries = ReAlloc(vec->entries, (new_capacity * sizeof(F64))(U64));
    vec->entries = new_entries;
  }
  vec->entries[vec->size++] = value;
}

F64 FloatVecPop(FloatVec *vec, Bool *_ok=NULL)
{
  if (vec->size > 0) {
    auto value = vec->entries[--vec->size];
    if (_ok) *_ok = TRUE;
    return value;
  }
  if (_ok) *_ok = FALSE;
  return -1.0;
}

F64 FloatVecGet(FloatVec *vec, I64 idx)
{
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("FloatVecGet", idx, vec->size);
  }
  return vec->entries[idx];
}

U0 FloatVecSet(FloatVec *vec, I64 idx, F64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("FloatVecSet", idx, vec->size);
  }
  vec->entries[idx] = value;
}

F64 FloatVecLast(FloatVec *vec, Bool *_ok=NULL)
{// The last entry without removing it; -1.0 and *_ok = FALSE when empty
  if (vec->size > 0) {
    if (_ok) *_ok = TRUE;
    return vec->entries[vec->size - 1];
  }
  if (_ok) *_ok = FALSE;
  return -1.0;
}

/* QSortGeneric hands the comparator two 64-bit slots as U0 *, in integer
 * registers: an F64 parameter would read the float registers instead, so
 * take the bits and reinterpret them. */
I64 _FloatCmp(U0 *a, U0 *b)
{
  F64 f1 = *(&a)(F64 *);
  F64 f2 = *(&b)(F64 *);
  return f1 < f2;
}

U0 FloatVecSort(FloatVec *vec)
{
  QSortGeneric(vec->entries(U0**),vec->size-1,0,&_FloatCmp);
}

U0 FloatVecClear(FloatVec *vec)
{
  vec->size = 0;
}

U0 FloatVecRelease(FloatVec *vec)
{
  if (vec) {
    Free(vec->entries);
    Free(vec);
  }
}


IntVec *IntVecNew(I64 size = 32)
{
  IntVec *vec = MAlloc(sizeof(IntVec));
  vec->size = 0;
  if (size < 1) size = 1; /* 0 or negative: start with room for one */
  vec->entries = MAlloc(sizeof(I64) * size);
  return vec;
}

U64 IntVecCapacity(IntVec *vec)
{
  return MSize(vec->entries)/sizeof(I64);
}

U0 IntVecPush(IntVec *vec, I64 value)
{
  auto capacity = IntVecCapacity(vec);
  if (vec->size + 1 >= capacity) {
    auto new_capacity = capacity * 2;
    if (new_capacity < 8) new_capacity = 8;
    auto new_entries = ReAlloc(vec->entries, (new_capacity * sizeof(I64))(U64));
    vec->entries = new_entries;
  }
  vec->entries[vec->size++] = value;
}

I64 IntVecPop(IntVec *vec, Bool *_ok=NULL)
{
  if (vec->size > 0) {
    auto value = vec->entries[--vec->size];
    if (_ok) *_ok = TRUE;
    return value;
  }
  if (_ok) *_ok = FALSE;
  return -1;
}

I64 IntVecGet(IntVec *vec, I64 idx)
{
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("IntVecGet", idx, vec->size);
  }
  return vec->entries[idx];
}

U0 IntVecSet(IntVec *vec, I64 idx, I64 value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("IntVecSet", idx, vec->size);
  }
  vec->entries[idx] = value;
}

I64 IntVecLast(IntVec *vec, Bool *_ok=NULL)
{// The last entry without removing it; -1 and *_ok = FALSE when empty
  if (vec->size > 0) {
    if (_ok) *_ok = TRUE;
    return vec->entries[vec->size - 1];
  }
  if (_ok) *_ok = FALSE;
  return -1;
}

I64 _IntCmp(I64 i1, I64 i2)
{
  return i1 < i2;
}

U0 IntVecSort(IntVec *vec)
{
  QSortGeneric(vec->entries(U0**),vec->size-1,0,&_IntCmp);
}

U0 IntVecClear(IntVec *vec)
{
  vec->size = 0;
}

U0 IntVecRelease(IntVec *vec)
{
  if (vec) {
    Free(vec->entries);
    Free(vec);
  }
}

PtrVec *PtrVecNew(I64 size = 32)
{
  PtrVec *vec = MAlloc(sizeof(PtrVec));
  vec->size = 0;
  if (size < 1) size = 1; /* 0 or negative: start with room for one */
  vec->entries = MAlloc(sizeof(U0*) * size);
  return vec;
}

U64 PtrVecCapacity(PtrVec *vec)
{
  return MSize(vec->entries)/sizeof(U0*);
}


U0 PtrVecPush(PtrVec *vec, U0 *value)
{
  auto capacity = MSize(vec->entries)/sizeof(U0*);
  if (vec->size + 1 >= capacity) {
    auto new_capacity = capacity * 2;
    if (new_capacity < 8) new_capacity = 8;
    auto new_entries = ReAlloc(vec->entries, (new_capacity * sizeof(U0*))(U64));
    vec->entries = new_entries;
  }
  vec->entries[vec->size++] = value;
}

U0 *PtrVecPop(PtrVec *vec, Bool *_ok=NULL)
{
  if (vec->size > 0) {
    auto value = vec->entries[--vec->size];
    if (_ok) *_ok = TRUE;
    return value;
  }
  if (_ok) *_ok = FALSE;
  return NULL;
}

U0 *PtrVecGet(PtrVec *vec, I64 idx)
{
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("PtrVecGet", idx, vec->size);
  }
  return vec->entries[idx];
}

U0 PtrVecSet(PtrVec *vec, I64 idx, U0 *value)
{// Replace the entry at `idx`; the index must be in [0, size), as for Get
  if (idx < 0 || idx >= vec->size) {
    _VecIndexError("PtrVecSet", idx, vec->size);
  }
  vec->entries[idx] = value;
}

U0 *PtrVecLast(PtrVec *vec, Bool *_ok=NULL)
{// The last entry without removing it; NULL and *_ok = FALSE when empty
  if (vec->size > 0) {
    if (_ok) *_ok = TRUE;
    return vec->entries[vec->size - 1];
  }
  if (_ok) *_ok = FALSE;
  return NULL;
}

/* Sort arr[low..high] in place; `_compare_fn(a, b)` is TRUE when `a` must
 * come before `b` (a "less than", not a C-style -1/0/1 comparator). Not
 * stable. Quicksort with a median-of-three pivot and a three-way
 * partition, so sorted, reversed and all-equal input stay O(n log n); it
 * recurses into the smaller part and loops on the larger one, so the
 * recursion depth is O(log n). Short ranges use an insertion sort. */
U0 QSortGeneric(U0 **arr, I64 high, I64 low,
    I64 (*_compare_fn)(U0 *_arg1, U0 *_arg2))
{
  U0 *tmp;
  while (high - low >= 16) {
    /* Pivot: the median of three entries, one from each third of the
     * range, at offsets scrambled by a hash of the bounds. Fixed spots
     * (first/middle/last) are defeated by the order the partition leaves
     * sorted input in and degrade towards O(n^2). */
    U64 third = ((high - low + 1) / 3)(U64);
    U64 h = low(U64) * 0x9E3779B97F4A7C15 + high(U64) * 0xC2B2AE3D27D4EB4F;
    h ^= h >> 31;
    I64 a = low + (h % third)(I64);
    I64 c = low + third(I64) + ((h >> 20) % third)(I64);
    I64 b = high - ((h >> 40) % third)(I64);
    I64 m;
    if (_compare_fn(arr[c], arr[a])) {
      if (_compare_fn(arr[b], arr[c]))      m = c;
      else if (_compare_fn(arr[b], arr[a])) m = b;
      else                                  m = a;
    } else {
      if (_compare_fn(arr[b], arr[a]))      m = a;
      else if (_compare_fn(arr[b], arr[c])) m = b;
      else                                  m = c;
    }
    tmp = arr[low]; arr[low] = arr[m]; arr[m] = tmp;

    /* Three-way partition: [low, lt) < pivot, [lt, gt] == pivot,
     * (gt, high] > pivot. The pivot element itself is never compared,
     * so the middle part is never empty and both sides shrink. */
    U0 *pivot = arr[low];
    I64 lt = low, i = low + 1, gt = high;
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
      QSortGeneric(arr, lt - 1, low, _compare_fn);
      low = gt + 1;
    } else {
      QSortGeneric(arr, high, gt + 1, _compare_fn);
      high = lt - 1;
    }
  }

  for (I64 j = low + 1; j <= high; ++j) {
    U0 *x = arr[j];
    I64 k = j - 1;
    while (k >= low && _compare_fn(x, arr[k])) {
      arr[k + 1] = arr[k];
      k--;
    }
    arr[k + 1] = x;
  }
}

U0 PtrVecSort(PtrVec *vec, I64 (*_compare_fn)(U0 *_arg1, U0 *_arg2))
{
  QSortGeneric(vec->entries,vec->size-1,0,_compare_fn);
}

U0 PtrVecClear(PtrVec *vec)
{
  vec->size = 0;
}

U0 PtrVecRelease(PtrVec *vec, U0 (*_free_value)(U0 *_val) = NULL)
{ // Optionally pass a callback to free each item in the vector 
  if (vec) {
    if (_free_value) {
      U0 **entries = vec->entries;
      I64 size = vec->size;
      for (I64 i = 0; i < size; ++i) {
        _free_value(entries[i]);
      }
    }
    Free(vec->entries);
    Free(vec);
  }
}

#ifdef VEC_TEST
U0 Main()
{
  auto vec = FloatVecNew();
  FloatVecPush(vec,32.32);
  FloatVecPush(vec,30.33);
  FloatVecPush(vec,42.65);
  FloatVecPush(vec,52.99);

  for (I64 i = 0; i < vec->size; ++i) {
    auto val = vec->entries[i];// FloatVecGet(vec,i);
    "[%d] => %f\n",i,val(F64);
  }
  FloatVecRelease(vec);
}
#endif
