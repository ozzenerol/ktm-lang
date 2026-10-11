module str;
import libc, mem;

/* The String type is the prelude's (every file sees it); its constructors
 * and methods are here. A String you get from one of these is yours:
 * `String s = str.new(); defer s.free();`. */

/* An empty String: nothing allocated until something is added */
pub String new()
{
    String s;
    return s;
}

/* An empty String with room for n bytes */
pub String withCap(i64 n)
{
    String s;
    s.reserve(n);
    return s;
}

/* A String of t's bytes, a copy */
pub String from(u8[] t)
{
    String s;
    s.append(t);
    return s;
}

/* Room for n more bytes (and the NUL after them) */
pub void String.reserve(i64 n)
{
    if (n <= self.cap - self.len) { return; }
    i64 cap = self.cap ? self.cap * 2 : 16;
    if (cap < self.len + n) { cap = self.len + n; }
    u8 *grown = mem.realloc(self.ptr, cap + 1);
    if (!grown) { throw(1); }   /* out of memory */
    self.ptr = grown;
    self.cap = cap;
    self.ptr[self.len] = 0;
}

/* Where t's bytes are in this String's buffer, -1 when they aren't (a
 * view of the String itself moves when it grows) */
static i64 stringOwnOffset(String *s, u8[] t)
{
    if (s.ptr && t.ptr >= s.ptr && t.ptr <= s.ptr + s.len) { return t.ptr - s.ptr; }
    return -1;
}

/* t's bytes at the end (t may be a view of this String itself) */
pub void String.append(u8[] t)
{
    if (!t.len) { return; }
    i64 own = stringOwnOffset(self, t);
    self.reserve(t.len);
    u8 *from = own >= 0 ? self.ptr + own : t.ptr;
    libc.memmove(self.ptr + self.len, from, t.len);
    self.len += t.len;
    self.ptr[self.len] = 0;
}

pub void String.appendByte(u8 b)
{
    self.reserve(1);
    self.ptr[self.len++] = b;
    self.ptr[self.len] = 0;
}

/* printf's format at the end */
pub void String.appendFormat(u8 *fmt, ...args)
{
    u8 *text = formatArgs(null, fmt, args);
    self.append(text[0:len(text)]);
    mem.free(text);
}

/* t's bytes before byte `at` (0 to len) */
pub void String.insert(i64 at, u8[] t)
{
    /* 0 <= at <= len, or ERR_BOUNDS as a sub-slice's check throws it */
    u8[] whole = self.ptr[0:self.len];
    whole[at:];
    if (!t.len) { return; }
    i64 own = stringOwnOffset(self, t);
    self.reserve(t.len);
    libc.memmove(self.ptr + at + t.len, self.ptr + at, self.len - at);
    if (own < 0) {
        libc.memmove(self.ptr + at, t.ptr, t.len);
    } else {
        /* a view of this String: the part of it after `at` moved along */
        for (i64 i = 0; i < t.len; ++i) {
            i64 src = own + i;
            if (src >= at) { src += t.len; }
            self.ptr[at + i] = self.ptr[src];
        }
    }
    self.len += t.len;
    self.ptr[self.len] = 0;
}

/* Bytes a (included) to b (excluded) out, 0 <= a <= b <= len */
pub void String.remove(i64 a, i64 b)
{
    /* 0 <= a <= b <= len, or ERR_BOUNDS as a sub-slice's check throws it */
    u8[] whole = self.ptr[0:self.len];
    whole[a:b];
    if (a == b) { return; }
    libc.memmove(self.ptr + a, self.ptr + b, self.len - b);
    self.len -= b - a;
    self.ptr[self.len] = 0;
}

/* Empty, keeping its room */
pub void String.clear()
{
    self.len = 0;
    if (self.ptr) { self.ptr[0] = 0; }
}

/* A copy of it, its own */
pub String String.clone()
{
    String copy;
    copy.append(*self);
    return copy;
}

/* Its bytes as a C string, NUL-terminated ("" when it has none) */
pub u8 *String.cstr()
{
    return self.ptr ? self.ptr : "";
}

/* ASCII letters in place */
pub void String.toLower()
{
    for (i64 i = 0; i < self.len; ++i) {
        u8 c = self.ptr[i];
        if (c >= 'A' && c <= 'Z') { self.ptr[i] = c + 32; }
    }
}

pub void String.toUpper()
{
    for (i64 i = 0; i < self.len; ++i) {
        u8 c = self.ptr[i];
        if (c >= 'a' && c <= 'z') { self.ptr[i] = c - 32; }
    }
}

/* Its bytes in reverse order (bytes: a multi-byte UTF-8 character is
 * reversed too) */
pub void String.reverse()
{
    for (i64 i = 0, j = self.len - 1; i < j; i++, j--) {
        u8 c = self.ptr[i];
        self.ptr[i] = self.ptr[j];
        self.ptr[j] = c;
    }
}

/* Its bytes given back; it is empty after, so a second free() of the same
 * String does nothing */
pub void String.free()
{
    mem.free(self.ptr);
    self.ptr = null;
    self.len = 0;
    self.cap = 0;
}
