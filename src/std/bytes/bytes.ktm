module bytes;
import libc, mem, rt, str;

/* A growable queue of bytes: append at the end, consume from the front.
 * For what a socket or a file gives in pieces (read into room(), parse
 * data(), consume() what was used) and for building binary messages
 * (appendBe32(...), then data() or take()). Make one with
 * bytes.bufferNew() or declare one (it starts empty), free it with
 * b.free(). Its fields are read-only outside module bytes. */
pub struct Buffer
{
    u8 *ptr;
    i64 start;    /* the first unread byte is ptr[start] */
    i64 len;      /* unread bytes */
    i64 cap;      /* bytes allocated at ptr */
};

/* An empty Buffer, with room for cap bytes before it allocates again */
pub Buffer bufferNew(i64 cap = 0)
{
    Buffer b;
    libc.memset(&b, 0, sizeof(Buffer));
    if (cap > 0) { b.reserve(cap); }
    return b;
}

/* Room for n more bytes at the end: the unread bytes move to the front
 * first when that is enough, else the Buffer grows */
pub void Buffer.reserve(i64 n)
{
    if (n < 0) { throw(ERR_BOUNDS, "bytes.Buffer.reserve: n is %d", n); }
    if (self.start + self.len + n <= self.cap) { return; }
    if (self.start) {
        libc.memmove(self.ptr, self.ptr + self.start, self.len);
        self.start = 0;
        if (self.len + n <= self.cap) { return; }
    }
    i64 cap = self.cap ? self.cap * 2 : 64;
    if (cap < self.len + n) { cap = self.len + n; }
    u8 *grown = mem.realloc(self.ptr, cap);
    if (!grown) { throw(1); }   /* out of memory */
    self.ptr = grown;
    self.cap = cap;
}

/* The unread bytes, a view: it is valid until the Buffer changes */
pub u8[] Buffer.data()
{
    return self.ptr[self.start:self.start + self.len];
}

/* t's bytes at the end (t may be a view of this Buffer's data()) */
pub void Buffer.append(u8[] t)
{
    if (!t.len) { return; }
    i64 own = -1;
    u8 *data = self.ptr + self.start;
    if (self.ptr && t.ptr >= data && t.ptr < data + self.len) { own = t.ptr - data; }
    self.reserve(t.len);
    u8 *from = own >= 0 ? self.ptr + self.start + own : t.ptr;
    libc.memmove(self.ptr + self.start + self.len, from, t.len);
    self.len += t.len;
}

/* One byte at the end */
pub void Buffer.appendByte(u8 b)
{
    self.reserve(1);
    self.ptr[self.start + self.len++] = b;
}

/* Drop the first n unread bytes (n > the unread count throws ERR_BOUNDS) */
pub void Buffer.consume(i64 n)
{
    if (n < 0 || n > self.len) {
        throw(ERR_BOUNDS, "bytes.Buffer.consume: %d bytes of %d", n, self.len);
    }
    self.len -= n;
    self.start = self.len ? self.start + n : 0;
}

/* At least n writable bytes after the unread ones, a view to fill (by
 * libc.read, recv, a copy); commit() then counts the bytes written */
pub u8[] Buffer.room(i64 n)
{
    self.reserve(n);
    i64 end = self.start + self.len;
    return self.ptr[end:self.cap];
}

/* n bytes written into room() are now part of the data */
pub void Buffer.commit(i64 n)
{
    if (n < 0 || self.start + self.len + n > self.cap) {
        throw(ERR_BOUNDS, "bytes.Buffer.commit: %d bytes, room for %d", n,
              self.cap - self.start - self.len);
    }
    self.len += n;
}

/* One read(2) of at most max bytes from fd onto the end: what read
 * returned (0 at end of file, -1 with errno set: EAGAIN on a
 * non-blocking fd with nothing to read) */
pub i64 Buffer.readFrom(i32 fd, i64 max = 65536)
{
    u8[] r = self.room(max);
    i64 n = libc.read(fd, r.ptr, max);
    if (n > 0) { self.len += n; }
    return n;
}

/* One write(2) of the unread bytes to fd; what was written is consumed.
 * Returns what write returned (-1 with errno set: EAGAIN when a
 * non-blocking fd is full) */
pub i64 Buffer.writeTo(i32 fd)
{
    if (!self.len) { return 0; }
    i64 n = libc.write(fd, self.ptr + self.start, self.len);
    if (n > 0) { self.consume(n); }
    return n;
}

/* Integers at the end, big-endian (network order) or little-endian */
pub void Buffer.appendBe16(u16 v) { putBe16(self.room(2), 0, v); self.len += 2; }
pub void Buffer.appendBe32(u32 v) { putBe32(self.room(4), 0, v); self.len += 4; }
pub void Buffer.appendBe64(u64 v) { putBe64(self.room(8), 0, v); self.len += 8; }
pub void Buffer.appendLe16(u16 v) { putLe16(self.room(2), 0, v); self.len += 2; }
pub void Buffer.appendLe32(u32 v) { putLe32(self.room(4), 0, v); self.len += 4; }
pub void Buffer.appendLe64(u64 v) { putLe64(self.room(8), 0, v); self.len += 8; }

/* Empty, keeping what it allocated */
pub void Buffer.clear()
{
    self.start = 0;
    self.len = 0;
}

/* The unread bytes as a String that is now yours; the Buffer is empty
 * (and allocates again when used) */
pub String Buffer.take()
{
    String s = str.from(self.data());
    self.free();
    return s;
}

/* Its bytes given back; a second free() does nothing */
pub void Buffer.free()
{
    mem.free(self.ptr);
    libc.memset(self, 0, sizeof(Buffer));
}

/* ---- Integers in byte slices: s[at] onwards, big-endian (network order)
 * or little-endian. Past the end of s throws ERR_BOUNDS, like s[i]. ---- */

static void bytesCheck(u8[] s, i64 at, i64 n, u8 *what)
{
    if (at < 0 || at + n > s.len) {
        throw(ERR_BOUNDS, "bytes.%s: %d bytes at %d of a %d-byte slice", what, n, at, s.len);
    }
}

/* The big-endian (network order) 16-bit integer at s[at] (2 bytes;
 * past the end throws ERR_BOUNDS) */
pub u16 be16(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 2, "be16");
    return (u16)((u64)s.ptr[at] << 8 | s.ptr[at + 1]);
}

/* The big-endian (network order) 32-bit integer at s[at] (4 bytes;
 * past the end throws ERR_BOUNDS) */
pub u32 be32(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 4, "be32");
    u8 *p = s.ptr + at;
    return (u32)((u64)p[0] << 24 | (u64)p[1] << 16 | (u64)p[2] << 8 | p[3]);
}

/* The big-endian (network order) 64-bit integer at s[at] (8 bytes;
 * past the end throws ERR_BOUNDS) */
pub u64 be64(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 8, "be64");
    u8 *p = s.ptr + at;
    u64 v = 0;
    for (i64 i = 0; i < 8; i++) { v = v << 8 | p[i]; }
    return v;
}

/* The little-endian 16-bit integer at s[at] (2 bytes; past the end
 * throws ERR_BOUNDS) */
pub u16 le16(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 2, "le16");
    return (u16)((u64)s.ptr[at + 1] << 8 | s.ptr[at]);
}

/* The little-endian 32-bit integer at s[at] (4 bytes; past the end
 * throws ERR_BOUNDS) */
pub u32 le32(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 4, "le32");
    u8 *p = s.ptr + at;
    return (u32)((u64)p[3] << 24 | (u64)p[2] << 16 | (u64)p[1] << 8 | p[0]);
}

/* The little-endian 64-bit integer at s[at] (8 bytes; past the end
 * throws ERR_BOUNDS) */
pub u64 le64(u8[] s, i64 at = 0)
{
    bytesCheck(s, at, 8, "le64");
    u8 *p = s.ptr + at;
    u64 v = 0;
    for (i64 i = 7; i >= 0; i--) { v = v << 8 | p[i]; }
    return v;
}

/* v written big-endian (network order) at s[at] (2 bytes; past the end
 * throws ERR_BOUNDS) */
pub void putBe16(u8[] s, i64 at, u16 v)
{
    bytesCheck(s, at, 2, "putBe16");
    s.ptr[at] = v >> 8;
    s.ptr[at + 1] = v;
}

/* v written big-endian (network order) at s[at] (4 bytes; past the end
 * throws ERR_BOUNDS) */
pub void putBe32(u8[] s, i64 at, u32 v)
{
    bytesCheck(s, at, 4, "putBe32");
    for (i64 i = 0; i < 4; i++) { s.ptr[at + i] = v >> (24 - 8 * i); }
}

/* v written big-endian (network order) at s[at] (8 bytes; past the end
 * throws ERR_BOUNDS) */
pub void putBe64(u8[] s, i64 at, u64 v)
{
    bytesCheck(s, at, 8, "putBe64");
    for (i64 i = 0; i < 8; i++) { s.ptr[at + i] = v >> (56 - 8 * i); }
}

/* v written little-endian at s[at] (2 bytes; past the end throws
 * ERR_BOUNDS) */
pub void putLe16(u8[] s, i64 at, u16 v)
{
    bytesCheck(s, at, 2, "putLe16");
    s.ptr[at] = v;
    s.ptr[at + 1] = v >> 8;
}

/* v written little-endian at s[at] (4 bytes; past the end throws
 * ERR_BOUNDS) */
pub void putLe32(u8[] s, i64 at, u32 v)
{
    bytesCheck(s, at, 4, "putLe32");
    for (i64 i = 0; i < 4; i++) { s.ptr[at + i] = v >> (8 * i); }
}

/* v written little-endian at s[at] (8 bytes; past the end throws
 * ERR_BOUNDS) */
pub void putLe64(u8[] s, i64 at, u64 v)
{
    bytesCheck(s, at, 8, "putLe64");
    for (i64 i = 0; i < 8; i++) { s.ptr[at + i] = v >> (8 * i); }
}
