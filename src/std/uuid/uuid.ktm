module uuid;
import atomic, libc, str;

/* UUIDs (RFC 9562, v0.20.0): v4, 122 random bits, and v7, a millisecond
 * Unix timestamp then random bits: v7s sort by creation time, which keeps
 * a database index on them compact, so they make good primary keys.
 *
 *   uuid.UUID id = uuid.v7();
 *   String s = id.string();              // "01929b0e-7c4f-7d32-9a51-3f0c2b8d4e6a"
 *   uuid.UUID back;
 *   if (uuid.parse(s, &back)) { ... }
 *
 * The random bits come from the operating system (getrandom on Linux,
 * arc4random on macOS). */

pub struct UUID {
    u8 bytes[16];
};

#ifdef IS_LINUX
extern "c" i64 getrandom(void *buf, u64 len, u32 flags);
#else
extern "c" void arc4random_buf(void *buf, u64 len);
#endif
extern "c" i32 clock_gettime(i32 clock, libc.timespec *ts);

static void fill(u8 *buf, i64 len) {
#ifdef IS_LINUX
    i64 got = 0;
    while (got < len) {
        i64 n = getrandom(buf + got, len - got, 0);
        if (n > 0) { got += n; }
    }
#else
    arc4random_buf(buf, len);
#endif
}

/* The nil UUID, all zeros */
pub UUID nil() {
    UUID u;
    return u;
}

/* A random UUID (version 4) */
pub UUID v4() {
    UUID u;
    fill(u.bytes, 16);
    u.bytes[6] = (u.bytes[6] & 0x0F) | 0x40;
    u.bytes[8] = (u.bytes[8] & 0x3F) | 0x80;
    return u;
}

i64 gLast;     /* the last v7's timestamp (ms) << 12 | its counter */

/* A time-ordered UUID (version 7): the Unix time in milliseconds, a
 * counter within the millisecond (so each is greater than the last,
 * across threads too) and 62 random bits */
pub UUID v7() {
    libc.timespec ts;
    clock_gettime(0, &ts);
    i64 now = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    i64 next;
    while (true) {
        i64 prev = atomic.load(&gLast);
        next = now << 12;
        if (next <= prev) { next = prev + 1; }   /* the same ms (or the clock went back) */
        if (atomic.cas(&gLast, prev, next)) { break; }
    }
    i64 ms = next >> 12, seq = next & 0xFFF;
    UUID u;
    fill(u.bytes, 16);
    for (i64 i = 0; i < 6; i++) { u.bytes[i] = (u8)(ms >> (40 - 8 * i)); }
    u.bytes[6] = (u8)(0x70 | (seq >> 8));
    u.bytes[7] = (u8)seq;
    u.bytes[8] = (u.bytes[8] & 0x3F) | 0x80;
    return u;
}

const u8 *DIGITS = "0123456789abcdef";

/* The canonical form, 36 characters: a new String, yours */
pub String UUID.string() {
    u8 buf[37];
    self.format(buf);
    return str.from(buf[0:36]);
}

/* The canonical form into buf (at least 37 bytes, NUL-terminated):
 * no allocation */
pub void UUID.format(u8 *buf) {
    i64 o = 0;
    for (i64 i = 0; i < 16; i++) {
        if (i == 4 || i == 6 || i == 8 || i == 10) { buf[o++] = '-'; }
        buf[o++] = DIGITS[self.bytes[i] >> 4];
        buf[o++] = DIGITS[self.bytes[i] & 15];
    }
    buf[o] = 0;
}

static i64 digit(u8 c) {
    if (c >= '0' && c <= '9') { return c - '0'; }
    if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
}

/* text as a UUID into *out: the canonical form (either case), the 32
 * digits without hyphens, or either in braces or after "urn:uuid:";
 * false (and *out nil) otherwise */
pub bool parse(u8[] text, UUID *out) {
    *out = nil();
    u8[] t = text;
    if (t.len == 45 && str.startsWith(t, "urn:uuid:")) { t = t[9:45]; }
    else if (t.len == 38 && t[0] == '{' && t[37] == '}') { t = t[1:37]; }
    bool hyphens = t.len == 36;
    if (!hyphens && t.len != 32) { return false; }
    i64 at = 0;
    for (i64 i = 0; i < 16; i++) {
        if (hyphens && (i == 4 || i == 6 || i == 8 || i == 10)) {
            if (t[at] != '-') { *out = nil(); return false; }
            at++;
        }
        i64 h = digit(t[at]), l = digit(t[at + 1]);
        if (h < 0 || l < 0) { *out = nil(); return false; }
        out.bytes[i] = (u8)(h * 16 + l);
        at += 2;
    }
    return true;
}

/* The version: 4, 7 (or another, for a UUID from elsewhere) */
pub i64 UUID.version() {
    return self.bytes[6] >> 4;
}

/* A v7's timestamp, Unix milliseconds (0 for other versions) */
pub i64 UUID.unixMs() {
    if (self.version() != 7) { return 0; }
    i64 ms = 0;
    for (i64 i = 0; i < 6; i++) { ms = ms << 8 | self.bytes[i]; }
    return ms;
}

pub bool UUID.equals(UUID other) {
    for (i64 i = 0; i < 16; i++) {
        if (self.bytes[i] != other.bytes[i]) { return false; }
    }
    return true;
}

pub bool UUID.isNil() {
    for (i64 i = 0; i < 16; i++) {
        if (self.bytes[i]) { return false; }
    }
    return true;
}

/* <0, 0, >0 in byte order: v7s by time */
pub i64 UUID.compare(UUID other) {
    for (i64 i = 0; i < 16; i++) {
        if (self.bytes[i] != other.bytes[i]) { return (i64)self.bytes[i] - (i64)other.bytes[i]; }
    }
    return 0;
}
