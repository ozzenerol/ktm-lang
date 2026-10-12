module coll;
import mem;

pub struct Vec<T> { T *data; i64 len; i64 cap; };

pub Vec<T> vecNew<T>(i64 cap = 4) {
    Vec<T> v;
    v.data = mem.alloc(sizeof(T) * cap);
    v.len = 0;
    v.cap = cap;
    return v;
}
pub void Vec<T>.push(T x) {
    if (self.len == self.cap) { self.cap *= 2; self.data = mem.realloc(self.data, sizeof(T) * self.cap); }
    self.data[self.len++] = x;
}
pub T Vec<T>.get(i64 i) { return self.data[i]; }
pub void Vec<T>.free() { mem.free(self.data); }
