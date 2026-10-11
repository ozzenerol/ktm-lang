module map;

#define HT_LOAD 60

#define HT_DELETED 0x7fffffffffffffff

#define HT_MIN_CAPACITY 8

#define HT_MAX_CAPACITY (1 << 28)

/* Deletion leaves a tombstone: the node stays in its slot with `order` set
 * to HT_DELETED so probe chains running through it are not cut short. For a
 * live node `order` is its position in the map's `indexes` vector, which is
 * how a delete removes it from iteration. Tombstones are dropped whenever the
 * table is rebuilt; the rebuild is triggered by `indexes->size`, which counts
 * live entries plus at least one hole per tombstone, so the table always
 * keeps an empty slot and every probe terminates. */
public class IntMapNode
{
  I64 key;
  U0 *value;
  I64 order;
};

public class StrMapNode
{
  U8 *key;
  I64 key_len;
  U0 *value;
  I64 order;
};

public class IntMap
{
  U64 size;     /* How many entries are in the hashtable */
  U64 capacity; /* How much capacity we have in the entries array */
  U64 mask;     /* Used for hashing, as the capacity is always a power of 2
                 * we can use fast modulo of `<int> & capacity-1`. */
  IntVec *indexes; /* Where all of the values are in the entries array, in 
                    * insertion order. Means we can iterate over the HashTable 
                    * quickly at the cost of memory */
  U64 threashold; /* rebuild threashold */
  U0 (*_free_value)(U0 *value); /* User defined callback for freeing values */
  IntMapNode **entries; /* The slots */
};

public class StrMap
{
  U64 size;     /* How many entries are in the hashtable */
  U64 capacity; /* How much capacity we have in the entries array */
  U64 mask;     /* Used for hashing, as the capacity is always a power of 2
                 * we can use fast modulo of `<int> & capacity-1`. */
  IntVec *indexes; /* Where all of the values are in the entries array, in 
                 * insertion order. Means we can iterate over the HashTable 
                 * quickly at the cost of memory */
  U64 threashold; /* rebuild threashold */
  U0 (*_free_value)(U0 *_value); /* User defined callback for freeing values */
  U0 (*_free_key)(U0 *_key); /* User defined callback for freeing keys */
  StrMapNode **entries; /* The slots */
};

U64 RoundUpToNextPowerOf2(U64 v)
{
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

static U64 HtCapacity(U64 capacity)
{/* The probe loops use `idx & mask`, so the capacity, the mask and the size
  * of the entries array must all be the same power of two. Round any
  * requested size up to one (and to at least HT_MIN_CAPACITY, which also
  * keeps 0 and 1 from producing a zero mask or a zero threshold).
  * The request is only a starting size: one that is negative (`-1` passed
  * for the U64) gets HT_MIN_CAPACITY, and one above HT_MAX_CAPACITY gets
  * HT_MAX_CAPACITY; past 2^63 the rounding would wrap to 0. The map still
  * grows past HT_MAX_CAPACITY as entries are added. */
  if (capacity(I64) < HT_MIN_CAPACITY) {
    capacity = HT_MIN_CAPACITY;
  } else if (capacity > HT_MAX_CAPACITY) {
    capacity = HT_MAX_CAPACITY;
  }
  return RoundUpToNextPowerOf2(capacity);
}

static U64 HtFmix64(U64 h)
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

U64 IntMapHashFunction(I64 key, U64 mask)
{/* Mixed first: `key & mask` alone sent keys sharing their low bits
  * (multiples of a power of two, aligned pointers, `id << 20`) to the
  * same slots. */
  return HtFmix64(key) & mask;
}

public IntMap *IntMapNew(U64 capacity)
{// NULL when out of memory
  IntMap *map = MAlloc(sizeof(IntMap));
  if (!map) {
    return NULL;
  }
  capacity = HtCapacity(capacity);
  map->capacity = capacity;
  map->mask = capacity-1;
  map->size = 0;
  map->indexes = IntVecNew();
  map->threashold = (capacity * HT_LOAD)/100;
  map->_free_value = NULL;
  map->entries = CAlloc(capacity * sizeof(IntMapNode *));
  if (!map->entries) {
    IntVecRelease(map->indexes);
    Free(map);
    return NULL;
  }
  return map;
}

public U0 IntMapSetFreeValue(IntMap *map, U0 (*_free_value)(U0 *value))
{
  if (map) {
    map->_free_value = _free_value;
  }
}

IntMapNode *IntMapNodeNew(I64 key, U0 *value)
{
  IntMapNode *n = MAlloc(sizeof(IntMapNode));
  n->key = key;
  n->value = value;
  n->order = HT_DELETED;
  return n;
}

static U64 IntMapGetIdx(IntMap *map, I64 key)
{// Slot holding `key`, or HT_DELETED. Tombstones are skipped, not stopped at.
  U64 idx = IntMapHashFunction(key, map->mask); 
  U64 mask = map->mask;
  IntMapNode **entries = map->entries;
  IntMapNode *cur;

  while ((cur = entries[idx])) {
    if (cur->order != HT_DELETED && cur->key == key) {
      return idx;
    }
    idx = (idx + 1) & mask;
  }
  return HT_DELETED;
}

static U64 IntMapGetNextIdx(IntMap *map, I64 key, Bool *_is_free)
{// The slot holding `key` (*_is_free = FALSE), else the slot to insert it
 // into (*_is_free = TRUE): the first tombstone on the probe chain, or the
 // empty slot that ends it.
  U64 mask = map->mask;
  U64 idx = IntMapHashFunction(key, mask);
  U64 tombstone = HT_DELETED;
  IntMapNode *cur;

  while ((cur = map->entries[idx]) != NULL) {
    if (cur->order == HT_DELETED) {
      if (tombstone == HT_DELETED) {
        tombstone = idx;
      }
    } else if (cur->key == key) {
      *_is_free = FALSE;
      return idx;
    }
    idx = (idx + 1) & mask;
  }
  *_is_free = TRUE;
  if (tombstone != HT_DELETED) {
    return tombstone;
  }
  return idx;
}

public U0 IntMapRelease(IntMap *map)
{// free the entire hashtable
  if (map) {
    auto indexes = map->indexes;
    U0 (*free_value)(U0 *value) = map->_free_value;
    for (auto i = 0; i < map->capacity; ++i) {
      auto n = map->entries[i];
      if (n) {
        /* A tombstone's value was freed by IntMapDelete. */
        if (free_value && n->order != HT_DELETED) {
          free_value(n->value);
        }
        Free(n);
      }
    }
    IntVecRelease(indexes);
    Free(map->entries);
    Free(map);
  }
}

static Bool IntMapRehash(IntMap *map, U64 new_capacity)
{// Rebuild the table with `new_capacity` slots, keeping insertion order and
 // dropping tombstones. Returns FALSE if OOM.
  U64 new_mask;
  IntMapNode **new_entries, **old_entries;
  Bool is_free = 0;
  I64 *new_indexes, *old_indexes, new_size;
  auto indexes_capacity = IntVecCapacity(map->indexes);
  auto indexes_size = map->indexes->size;
  auto old_capacity = map->capacity;

  old_entries = map->entries;
  old_indexes = map->indexes->entries;

  new_mask = new_capacity - 1;

  /* OOM */
  if ((new_indexes = CAlloc(indexes_capacity * sizeof(I64))) == NULL) {
    return FALSE;
  }

  /* OOM */
  if ((new_entries = CAlloc(new_capacity * sizeof(IntMapNode*))) == NULL) {
    Free(new_indexes);
    return FALSE;
  }

  map->mask = new_mask;
  map->entries = new_entries;
  map->capacity = new_capacity;
  new_size = 0;

  for (U64 i = 0; i < indexes_size; ++i) {
    I64 idx = old_indexes[i];
    if (idx != HT_DELETED) {
      auto old = old_entries[idx];
      auto new_idx = IntMapGetNextIdx(map,old->key,&is_free);
      old->order = new_size;
      new_indexes[new_size] = new_idx;
      new_entries[new_idx] = old;
      new_size++;
    }
  }

  for (U64 i = 0; i < old_capacity; ++i) {
    auto old = old_entries[i];
    if (old && old->order == HT_DELETED) {
      Free(old);
    }
  }

  Free(old_entries);
  Free(old_indexes);
  map->indexes->size = new_size;
  map->indexes->entries = new_indexes;

  map->threashold = (new_capacity * HT_LOAD)/100;
  map->entries = new_entries;
  map->size = new_size;
  return TRUE;
}

public Bool IntMapResize(IntMap *map)
{// Double the hashtable, will return false if OOM
  if (!map) {
    return FALSE;
  }
  return IntMapRehash(map,map->capacity << 1);
}

public auto IntMapSet(IntMap *map, I64 key, U0 *value)
{
  Bool is_free;

  if (!map) {
    return FALSE;
  }

  /* `indexes->size` is the live entries plus the holes deletes left, so this
   * also bounds the tombstones. A table that is mostly tombstones is rebuilt
   * at the same size rather than doubled. */
  if (map->indexes->size >= map->threashold) {
    U64 new_capacity = map->capacity;
    if (map->size >= map->threashold / 2) {
      new_capacity <<= 1;
    }
    if (!IntMapRehash(map,new_capacity)) {
      /* This means we have run out of memory */
      return FALSE;
    }
  }

  U64 idx = IntMapGetNextIdx(map,key,&is_free);
  if (is_free) {
    auto n = map->entries[idx];
    if (n) {
      /* Reuse a tombstone */
      n->key = key;
      n->value = value;
    } else {
      n = IntMapNodeNew(key,value);
      map->entries[idx] = n;
    }
    n->order = map->indexes->size;
    IntVecPush(map->indexes,idx);
    map->size++;
    return TRUE;
  } else {
    auto n = map->entries[idx];
    if (map->_free_value && n->value != value) {
      /* Replacing a live value: the map owned the old one. */
      map->_free_value(n->value);
    }
    n->value = value;
    return TRUE;
  }
}

public Bool IntMapDelete(IntMap *map, I64 key)
{// Remove `key`, freeing its value if a destructor is registered. Returns
 // FALSE if the key was not in the map.
  if (!map) {
    return FALSE;
  }
  auto idx = IntMapGetIdx(map,key);
  if (idx != HT_DELETED) {
    auto n = map->entries[idx];
    if (map->_free_value) {
      map->_free_value(n->value);
    }
    map->indexes->entries[n->order] = HT_DELETED;
    n->order = HT_DELETED;
    n->value = NULL;
    map->size--;
    return TRUE;
  }
  return FALSE;
}

public Bool IntMapHas(IntMap *map, I64 key)
{
  if (!map) {
    return FALSE;
  }
  auto idx = IntMapGetIdx(map,key);
  return idx != HT_DELETED;
}

public U0 *IntMapGet(IntMap *map, I64 key, Bool *_ok=NULL)
{// The value stored for `key`, or NULL. `*_ok` (when given) tells a
 // missing key (FALSE) from a stored NULL or 0 (TRUE).
  if (map) {
    auto idx = IntMapGetIdx(map,key);
    if (idx != HT_DELETED) {
      if (_ok) *_ok = TRUE;
      return map->entries[idx]->value;
    }
  }
  if (_ok) *_ok = FALSE;
  return NULL;
}

public U0 IntMapClear(IntMap *map)
{// Remove every entry, freeing the values if a destructor is registered.
 // The capacity is kept.
  if (!map) {
    return;
  }
  for (I64 i = 0; i < map->capacity; ++i) {
    auto n = map->entries[i];
    if (n) {
      /* A tombstone's value was freed by IntMapDelete. */
      if (map->_free_value && n->order != HT_DELETED) {
        map->_free_value(n->value);
      }
      Free(n);
      map->entries[i] = NULL;
    }
  }
  map->indexes->size = 0;
  map->size = 0;
}

public Bool IntMapIter(IntMap *map, I64 *_idx, IntMapNode **_node)
{
  if (!map) {
    return FALSE;
  }
  I64 idx = *_idx;
  auto indexes = map->indexes->entries;

  while (idx < map->indexes->size) {
    I64 index = indexes[idx];
    if (index != HT_DELETED) {
      *_idx = idx + 1;
      *_node = map->entries[index];
      return TRUE;
    }
    idx++;
  }
  return FALSE;
}

public Bool IntMapValueIter(IntMap *map, I64 *_idx, U0 **_value)
{
  IntMapNode *node;
  if (IntMapIter(map,_idx,&node)) {
    *_value = node->value;
    return TRUE;
  }
  return FALSE;
}

public Bool IntMapKeyIter(IntMap *map, I64 *_idx, I64 *_key)
{
  IntMapNode *node;
  if (IntMapIter(map,_idx,&node)) {
    *_key = node->key;
    return TRUE;
  }
  return FALSE;
}

public U8 *IntMapToString(IntMap *map, U8 *(*stringify_value)(U0 *))
{
  if (!map) {
    return StrPrint(NULL,"{}");
  }
  auto map_size = map->size;
  if (map_size == 0) {
    return StrPrint(NULL,"{}");
  }

  IntMapNode *entry = NULL;
  auto buffer = StrPrint(NULL,"{");
  I64 printed = 0; /* `i` skips the holes deletes leave, so count */
  for (I64 i = 0; IntMapIter(map,&i,&entry);) {
    auto str_val = stringify_value(entry->value);
    if (++printed == map_size) {
      buffer = CatPrint(buffer, "[%d] => %s}",entry->key,str_val);
    } else {
      buffer = CatPrint(buffer, "[%d] => %s, ",entry->key,str_val);
    }
    Free(str_val);
  }
  return buffer;
}

public U8 *IntMapKeysToString(IntMap *map)
{
  if (!map) {
    return StrPrint(NULL,"{}");
  }
  auto map_size = map->size;
  if (map_size == 0) {
    return StrPrint(NULL,"{}");
  }

  IntMapNode *entry = NULL;
  auto buffer = StrPrint(NULL,"{");
  I64 printed = 0; /* `i` skips the holes deletes leave, so count */
  for (I64 i = 0; IntMapIter(map,&i,&entry);) {
    if (++printed == map_size) {
      buffer = CatPrint(buffer, "%d}",entry->key);
    } else {
      buffer = CatPrint(buffer, "%d, ",entry->key);
    }
  }
  return buffer;
}

public StrMap *StrMapNew(U64 capacity)
{// NULL when out of memory
  StrMap *map = MAlloc(sizeof(StrMap));
  if (!map) {
    return NULL;
  }
  capacity = HtCapacity(capacity);
  map->capacity = capacity;
  map->mask = capacity-1;
  map->size = 0;
  map->indexes = IntVecNew();
  map->threashold = (capacity * HT_LOAD)/100;
  map->_free_value = NULL;
  map->_free_key = NULL;
  map->entries = CAlloc(capacity * sizeof(StrMapNode *));
  if (!map->entries) {
    IntVecRelease(map->indexes);
    Free(map);
    return NULL;
  }
  return map;
}

public U0 StrMapSetFreeValue(StrMap *map, U0 (*_free_value)(U0 *value))
{
  if (map) {
    map->_free_value = _free_value;
  }
}

public U0 StrMapSetFreeKey(StrMap *map, U0 (*_free_key)(U0 *key))
{
  if (map) {
    map->_free_key = _free_key;
  }
}

static U0 StrMapFreeEntry(StrMap *map, StrMapNode *n)
{/* Free a live entry's value and key with the registered destructors. A
  * key that is also the value (`StrMapSet(m,s,s)`) is freed once, as the
  * value. */
  if (map->_free_value) {
    map->_free_value(n->value);
  }
  if (map->_free_key && !(n->key == n->value && map->_free_value)) {
    map->_free_key(n->key);
  }
}

static U64 StrMapHash(U8 *key, I64 key_len)
{/* FNV-1a over exactly the `key_len` bytes the lookup compares, then
  * mixed. Set, Get, Has and Delete hash a NUL-terminated key's StrLen bytes
  * and GetLen/HasLen their `key_len`, so `("hello world", 5)` finds
  * "hello". */
  U64 h = 0xcbf29ce484222325;
  for (I64 i = 0; i < key_len; i++) {
    h ^= key[i];
    h *= 0x100000001b3;
  }
  return HtFmix64(h);
}

StrMapNode *StrMapNodeNew(U8 *key, I64 key_len, U0 *value)
{
  StrMapNode *n = MAlloc(sizeof(StrMapNode));
  n->key = key;
  n->key_len = key_len;
  n->value = value;
  n->order = HT_DELETED;
  return n;
}

static U64 StrMapGetNextIdx(StrMap *map, U8 *key, I64 key_len, Bool *_is_free)
{// The slot holding `key` (*_is_free = FALSE), else the slot to insert it
 // into (*_is_free = TRUE): the first tombstone on the probe chain, or the
 // empty slot that ends it.
  U64 mask = map->mask;
  U64 idx = StrMapHash(key, key_len) & mask;
  U64 tombstone = HT_DELETED;
  StrMapNode *cur;

  while ((cur = map->entries[idx]) != NULL) {
    if (cur->order == HT_DELETED) {
      if (tombstone == HT_DELETED) {
        tombstone = idx;
      }
    } else if (key_len == cur->key_len && !StrNCmp(cur->key,key,key_len)) {
      *_is_free = FALSE;
      return idx;
    }
    idx = (idx + 1) & mask;
  }
  *_is_free = TRUE;
  if (tombstone != HT_DELETED) {
    return tombstone;
  }
  return idx;
}

static I64 StrMapGetIdx(StrMap *map, U8 *key, I64 key_len)
{// Slot holding `key`, or HT_DELETED. Tombstones are skipped, not stopped at.
  U64 mask = map->mask;
  U64 idx = StrMapHash(key, key_len) & mask;
  StrMapNode **entries = map->entries;
  StrMapNode *cur;

  while ((cur = entries[idx]) != NULL) {
    if (cur->order != HT_DELETED && cur->key_len == key_len &&
        !StrNCmp(cur->key, key, key_len)) {
      return idx;
    }
    idx = (idx + 1) & mask;
  }
  return HT_DELETED;
}

public U0 StrMapRelease(StrMap *map)
{// free the entire hashtable
  if (map) {
    for (auto i = 0; i < map->capacity; ++i) {
      auto n = map->entries[i];
      if (n) {
        /* A tombstone's key and value were freed by StrMapDelete. */
        if (n->order != HT_DELETED) {
          StrMapFreeEntry(map,n);
        }
        Free(n);
      }
    }
    Free(map->entries);
    IntVecRelease(map->indexes);
    Free(map);
  }
}

static Bool StrMapRehash(StrMap *map, U64 new_capacity)
{// Rebuild the table with `new_capacity` slots, keeping insertion order and
 // dropping tombstones. Returns FALSE if OOM.
  U64 new_mask;
  StrMapNode **new_entries, **old_entries;
  I64 *new_indexes, *old_indexes;
  Bool is_free = FALSE;
  auto indexes_capacity = IntVecCapacity(map->indexes);
  auto indexes_size = map->indexes->size;
  auto old_capacity = map->capacity;

  old_entries = map->entries;
  old_indexes = map->indexes->entries;

  new_mask = new_capacity - 1;

  /* OOM */
  if ((new_indexes = CAlloc(indexes_capacity * sizeof(I64))) == NULL) {
    return FALSE;
  }

  /* OOM */
  if ((new_entries = CAlloc(new_capacity * sizeof(StrMapNode*))) == NULL) {
    Free(new_indexes);
    return FALSE;
  }

  map->mask = new_mask;
  map->entries = new_entries;
  map->capacity = new_capacity;

  I64 new_size = 0;

  for (I64 i = 0; i < indexes_size; ++i) {
    I64 idx = old_indexes[i];
    if (idx != HT_DELETED) {
      auto old = old_entries[idx];
      I64 new_idx = StrMapGetNextIdx(map,old->key,old->key_len,&is_free);
      old->order = new_size;
      new_indexes[new_size] = new_idx;
      new_entries[new_idx] = old;
      new_size++;
    }
  }

  for (I64 i = 0; i < old_capacity; ++i) {
    auto old = old_entries[i];
    if (old && old->order == HT_DELETED) {
      Free(old);
    }
  }

  Free(old_entries);
  Free(old_indexes);
  map->indexes->size = new_size;
  map->indexes->entries = new_indexes;

  map->size = new_size;
  map->entries = new_entries;
  map->threashold = (new_capacity * HT_LOAD)/100;
  return TRUE;
}

public Bool StrMapResize(StrMap *map)
{// Double the hashtable, will return false if OOM
  if (!map) {
    return FALSE;
  }
  return StrMapRehash(map,map->capacity << 1);
}

public Bool StrMapSet(StrMap *map, U8 *key, U0 *value)
{
  Bool is_free;

  if (!map || !key) {
    return FALSE;
  }

  /* `indexes->size` is the live entries plus the holes deletes left, so this
   * also bounds the tombstones. A table that is mostly tombstones is rebuilt
   * at the same size rather than doubled. */
  if (map->indexes->size >= map->threashold) {
    U64 new_capacity = map->capacity;
    if (map->size >= map->threashold / 2) {
      new_capacity <<= 1;
    }
    if (!StrMapRehash(map,new_capacity)) {
      /* This means we have run out of memory */
      return FALSE;
    }
  }

  I64 key_len = StrLen(key);
  U64 idx = StrMapGetNextIdx(map,key,key_len,&is_free);

  if (is_free) {
    auto n = map->entries[idx];
    if (n) {
      /* Reuse a tombstone */
      n->key = key;
      n->key_len = key_len;
      n->value = value;
    } else {
      n = StrMapNodeNew(key,key_len,value);
      map->entries[idx] = n;
    }
    n->order = map->indexes->size;
    IntVecPush(map->indexes, idx);
    map->size++;
    return TRUE;
  } else {
    auto n = map->entries[idx];
    /* Updating an existing key keeps the key the map already stores; the
     * pointer passed now may be a reused buffer. With a key destructor
     * registered the map owns every key it is given, so the duplicate is
     * freed here instead of leaking (unless it is the stored key itself).
     * Likewise the replaced value is freed when a value destructor is
     * registered, unless the same pointer is being set again.
     * A pointer that is both a key and a value is freed once:
     *   - the stored key was also the old value, which is freed now: the
     *     passed key becomes the stored key instead;
     *   - the passed key is also the new value: it is kept, not freed. */
    U8 *old_key = n->key;
    U0 *old_value = n->value;
    Bool free_old_value = map->_free_value && old_value != value;
    U8 *dropped = key;
    if (free_old_value && old_key == old_value) {
      n->key = key;
      dropped = old_key;
    }
    if (free_old_value) {
      map->_free_value(old_value);
    }
    if (map->_free_key && dropped != n->key && dropped != value &&
        dropped != old_value) {
      map->_free_key(dropped);
    }
    n->value = value;
    return TRUE;
  }
}

public Bool StrMapDelete(StrMap *map, U8 *key)
{// Remove `key`, freeing the stored key and value if destructors are
 // registered. Returns FALSE if the key was not in the map.
  if (!map || !key) {
    return FALSE;
  }
  I64 idx = StrMapGetIdx(map,key,StrLen(key));
  if (idx == HT_DELETED) {
    return FALSE;
  }
  auto n = map->entries[idx];
  StrMapFreeEntry(map,n);
  map->indexes->entries[n->order] = HT_DELETED;
  n->order = HT_DELETED;
  n->key = NULL;
  n->key_len = 0;
  n->value = NULL;
  map->size--;
  return TRUE;
}

public U0 *StrMapGetLen(StrMap *map, U8 *key, I64 key_len, Bool *_ok=NULL)
{// The value stored for the `key_len` bytes at `key`, or NULL. `*_ok`
 // (when given) tells a missing key (FALSE) from a stored NULL (TRUE).
  if (map && key) {
    auto idx = StrMapGetIdx(map,key,key_len);
    if (idx != HT_DELETED) {
      if (_ok) *_ok = TRUE;
      return map->entries[idx]->value;
    }
  }
  if (_ok) *_ok = FALSE;
  return NULL;
}

public U0 *StrMapGet(StrMap *map, U8 *key, Bool *_ok=NULL)
{// See StrMapGetLen
  if (!map || !key) {
    if (_ok) *_ok = FALSE;
    return NULL;
  }
  I64 key_len = StrLen(key);
  return StrMapGetLen(map,key,key_len,_ok);
}

public U0 StrMapClear(StrMap *map)
{// Remove every entry, freeing keys and values if destructors are
 // registered. The capacity is kept.
  if (!map) {
    return;
  }
  for (I64 i = 0; i < map->capacity; ++i) {
    auto n = map->entries[i];
    if (n) {
      /* A tombstone's key and value were freed by StrMapDelete. */
      if (n->order != HT_DELETED) {
        StrMapFreeEntry(map,n);
      }
      Free(n);
      map->entries[i] = NULL;
    }
  }
  map->indexes->size = 0;
  map->size = 0;
}

public Bool StrMapHasLen(StrMap *map, U8 *key, I64 key_len)
{
  if (!map || !key) {
    return FALSE;
  }
  I64 idx = StrMapGetIdx(map,key,key_len);
  if (idx != HT_DELETED) {
    return TRUE;
  }
  return FALSE;
}

public Bool StrMapHas(StrMap *map, U8 *key)
{
  if (!map || !key) {
    return FALSE;
  }
  I64 key_len = StrLen(key);
  return StrMapHasLen(map,key,key_len);
}

public Bool StrMapIter(StrMap *map, I64 *_idx, StrMapNode **_node)
{
  if (!map) {
    return FALSE;
  }
  I64 idx = *_idx;
  auto indexes = map->indexes->entries;
  auto size = map->indexes->size;

  while (idx < size) {
    I64 index = indexes[idx];
    if (index != HT_DELETED) {
      *_idx = idx + 1;
      *_node = map->entries[index];
      return TRUE;
    }
    idx++;
  }
  return FALSE;
}

public Bool StrMapValueIter(StrMap *map, I64 *_idx, U0 **_value)
{
  StrMapNode *node;
  if (StrMapIter(map,_idx,&node)) {
    *_value = node->value;
    return TRUE;
  }
  return FALSE;
}

public Bool StrMapKeyIter(StrMap *map, I64 *_idx, U8 **_key)
{
  StrMapNode *node;
  if (StrMapIter(map,_idx,&node)) {
    *_key = node->key;
    return TRUE;
  }
  return FALSE;
}

public U8 *StrMapToString(StrMap *map, U8 *(*stringify_value)(U0 *))
{
  if (!map) {
    return StrPrint(NULL,"{}");
  }
  auto map_size = map->size;

  if (map_size == 0) {
    return StrPrint(NULL,"{}");
  }

  StrMapNode *entry = NULL;
  auto buffer = StrPrint(NULL,"{");
  I64 printed = 0; /* `i` skips the holes deletes leave, so count */
  for (I64 i = 0; StrMapIter(map,&i,&entry);) {
    auto str_val = stringify_value(entry->value);
    if (++printed == map_size) {
      buffer = CatPrint(buffer, "[%s] => %s}",entry->key,str_val);
    } else {
      buffer = CatPrint(buffer, "[%s] => %s, ",entry->key,str_val);
    }
    Free(str_val);
  }
  return buffer;

}

public U8 *StrMapKeysToString(StrMap *map)
{
  if (!map) {
    return StrPrint(NULL,"{}");
  }
  auto map_size = map->size;
  if (map_size == 0) {
    return StrPrint(NULL,"{}");
  }

  StrMapNode *entry = NULL;
  auto buffer = StrPrint(NULL,"{");
  I64 printed = 0; /* `i` skips the holes deletes leave, so count */
  for (I64 i = 0; StrMapIter(map,&i,&entry);) {
    if (++printed == map_size) {
      buffer = CatPrint(buffer, "%s}",entry->key);
    } else {
      buffer = CatPrint(buffer, "%s, ",entry->key);
    }
  }
  return buffer;
}

#ifdef KTM_HT_TEST
U0 IntMapTests(U0)
{
  IntMap *map = IntMapNew(8);
  IntMapNode *n;
  U8 *strs[] = {
    "hello",
    "world",
    "foo",
    "bar",
    "baz",
    "cat",
    "dog",
    "owl"
  };
  I64 len = sizeof(strs)/sizeof(strs[0]);

  for (I64 i = 0; i < len; i++) {
    IntMapSet(map,i,strs[i]);
  }


  for (I64 i = 0; i < len; i++) {
    U8 *value = IntMapGet(map,i);
    if (value != strs[i]) {
      "Failed IntMapTests -> IntMapGet: %d=>%s\n",i,value;
      Exit;
    }
  }

  for (I64 i = 0; i < len; i++) {
    if (!IntMapHas(map,i)) {
      "Failed IntMapTests -> IntMapHas\n";
      Exit;
    }
  }

  /* Inorder iteration */
  for (I64 i = 0; IntMapIter(map,&i,&n);) {
    if (strs[i-1] != n->value) {
      "Failed IntMapTests -> IntMapIter i=%d key=%d => value=%s str = %s\n",
        i,n->key,n->value,strs[i];
      Exit;
    }
  }

  for (I64 i = 0; i < len; i++) {
    if (!IntMapDelete(map,i)) {
      "Failed IntMapTests -> IntMapDelete\n";
      Exit;
    }
  }

  IntMapRelease(map);
  "PASS IntMap\n";
}

U0 StrMapTests(U0)
{
  StrMap *map = StrMapNew(8);
  StrMapNode *n;
  U8 *keys[] = {
    "card",
    "can",
    "will",
    "shill",
    "cup",
    "mug",
    "glass",
    "tin"
  };
  U8 *strs[] = {
    "hello",
    "world",
    "foo",
    "bar",
    "baz",
    "cat",
    "dog",
    "owl"
  };

  I64 len = sizeof(strs)/sizeof(strs[0]);


  for (I64 i = 0; i < len; i++) {
    StrMapSet(map,keys[i],strs[i]);
  }

  for (I64 i = 0; i < len; i++) {
    if (StrMapGet(map,keys[i]) != strs[i]) {
      "Failed StrMapTests -> StrMapGet\n";
      Exit;
    }
  }

  for (I64 i = 0; i < len; i++) {
    if (!StrMapHas(map,keys[i])) {
      "Failed StrMapTests -> StrMapHas\n";
      Exit;
    }
  }

  /* Inorder iteration */
  for (I64 i = 0; StrMapIter(map,&i,&n);) {
    if (keys[i-1] != n->key || strs[i-1] != n->value) {
      "Failed StrMapTests -> StrMapIter\n";
      Exit;
    }
  }

  for (I64 i = 0; i < len; i++) {
    if (!StrMapDelete(map,keys[i])) {
      "Failed StrMapTests -> StrMapDelete\n";
      Exit;
    }
  }

  StrMapRelease(map);
  "PASS StrMap\n";
}

U0 Main(U0)
{
  IntMapTests;
  StrMapTests;
}
#endif
