module vec;
import libc, mem, rt, sort;

/* A growable array of T, held by value (like String): make one with
 * vec.new<T>(), free it with free() (or declare it with `ad`). The values
 * are yours: free() frees the array, not what its pointers point to.
 * An index outside 0..len-1 throws ERR_BOUNDS, as a slice index does. */
pub struct Vec<T> {
    T *data;
    i64 len;
    i64 cap;
};

/* An empty Vec<T> with room for `cap` items (at least 1) */
pub Vec<T> new<T>(i64 cap = 8) {
    Vec<T> v;
    if (cap < 1) { cap = 1; }
    v.data = mem.alloc(sizeof(T) * cap);
    v.len = 0;
    v.cap = cap;
    return v;
}

/* Room for at least `n` items in all, without growing again */
pub void Vec<T>.reserve(i64 n) {
    if (n <= self.cap) { return; }
    i64 cap = self.cap > 0 ? self.cap : 1;
    while (cap < n) { cap *= 2; }
    self.data = mem.realloc(self.data, sizeof(T) * cap);
    self.cap = cap;
}

pub void Vec<T>.push(T x) {
    if (self.len == self.cap) { self.reserve(self.len + 1); }
    self.data[self.len] = x;
    self.len++;
}

/* The last item, removed; with `ok`, false (and a zero T) when empty, else
 * an empty Vec throws ERR_BOUNDS */
pub T Vec<T>.pop(bool *ok = null) {
    if (self.len == 0) {
        if (ok) {
            *ok = false;
            T zero;
            libc.memset(&zero, 0, sizeof(T));
            return zero;
        }
        throw(ERR_BOUNDS, "vec.Vec.pop: the Vec is empty");
    }
    if (ok) { *ok = true; }
    self.len--;
    return self.data[self.len];
}

pub T Vec<T>.get(i64 i) {
    if (i < 0 || i >= self.len) { throw(ERR_BOUNDS, "vec.Vec.get: index %d of %d", i, self.len); }
    return self.data[i];
}

/* A pointer to item i, to change it in place (until the Vec grows) */
pub T *Vec<T>.at(i64 i) {
    if (i < 0 || i >= self.len) { throw(ERR_BOUNDS, "vec.Vec.at: index %d of %d", i, self.len); }
    return &self.data[i];
}

pub void Vec<T>.set(i64 i, T x) {
    if (i < 0 || i >= self.len) { throw(ERR_BOUNDS, "vec.Vec.set: index %d of %d", i, self.len); }
    self.data[i] = x;
}

/* The last item; with `ok`, false (and a zero T) when empty, else an
 * empty Vec throws ERR_BOUNDS */
pub T Vec<T>.last(bool *ok = null) {
    if (self.len == 0) {
        if (ok) {
            *ok = false;
            T zero;
            libc.memset(&zero, 0, sizeof(T));
            return zero;
        }
        throw(ERR_BOUNDS, "vec.Vec.last: the Vec is empty");
    }
    if (ok) { *ok = true; }
    return self.data[self.len - 1];
}

/* x put at index i (0..len), the items from i on moving up one */
pub void Vec<T>.insert(i64 i, T x) {
    if (i < 0 || i > self.len) { throw(ERR_BOUNDS, "vec.Vec.insert: index %d of %d", i, self.len); }
    if (self.len == self.cap) { self.reserve(self.len + 1); }
    libc.memmove(&self.data[i + 1], &self.data[i], (self.len - i) * sizeof(T));
    self.data[i] = x;
    self.len++;
}

/* Item i, removed, the ones after it moving down one (their order kept) */
pub T Vec<T>.removeAt(i64 i) {
    if (i < 0 || i >= self.len) { throw(ERR_BOUNDS, "vec.Vec.removeAt: index %d of %d", i, self.len); }
    T x = self.data[i];
    libc.memmove(&self.data[i], &self.data[i + 1], (self.len - i - 1) * sizeof(T));
    self.len--;
    return x;
}

/* The items as a slice (a view: valid until the Vec grows or is freed),
 * for `for (auto x : v.items())` and anything that takes a T[] */
pub T[] Vec<T>.items() {
    return self.data[0:self.len];
}

/* Sorted with cmp(a, b): negative when a goes first, 0 when equal,
 * positive when b goes first (sort.slice) */
pub void Vec<T>.sort(fn(T *, T *) i64 cmp) {
    sort.slice(self.data[0:self.len], cmp);
}

/* Sorted smallest first, for a T that `<` compares (numbers, pointers) */
pub void Vec<T>.sortAsc() {
    sort.asc(self.data[0:self.len]);
}

/* Empty again, keeping its room */
pub void Vec<T>.clear() {
    self.len = 0;
}

pub void Vec<T>.free() {
    mem.free(self.data);
    self.data = null;
    self.len = 0;
    self.cap = 0;
}
