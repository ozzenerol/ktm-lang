module str;
import cstr, libc, mem;

/* The String type is the prelude's (every file sees it); its constructors
 * and methods are here. A String you get from one of these is yours:
 * `String s = str.new(); defer s.free();`. */

/* printf's format into a new String (yours: free it). `%s` takes a u8[]
 * or a String as well as a C string. */
pub String format(u8 *fmt, ...args)
{
    String s;
    u8 *text = cstr.formatArgs(null, fmt, args);
    if (text) {
        /* the formatter's buffer becomes the String's (it is mem's, with
         * the NUL after it), no copy */
        s.ptr = text;
        s.len = cstr.len(text);
        s.cap = s.len;
    }
    return s;
}

/* A String owning `bytes`, a mem.alloc'd block of len bytes and a NUL
 * after them (from C, or a reader): no copy, free it as any String */
pub String adopt(u8 *bytes, i64 len)
{
    String s;
    if (bytes) {
        s.ptr = bytes;
        s.len = len;
        s.cap = len;
    }
    return s;
}

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
    i64 n = t.len;
    if (!n) { return; }
    u8 *from = t.ptr;
    if (n > self.cap - self.len) {
        /* growing moves the buffer: a view of this String moves with it */
        u8 *old = self.ptr;
        bool own = old && from >= old && from <= old + self.len;
        self.reserve(n);
        if (own) { from = self.ptr + (from - old); }
    }
    libc.memmove(self.ptr + self.len, from, n);
    self.len += n;
    self.ptr[self.len] = 0;
}

pub void String.appendByte(u8 b)
{
    if (self.len >= self.cap) { self.reserve(1); }
    self.ptr[self.len++] = b;
    self.ptr[self.len] = 0;
}

/* printf's format at the end */
pub void String.appendFormat(u8 *fmt, ...args)
{
    u8 *text = cstr.formatArgs(null, fmt, args);
    self.append(text[0:cstr.len(text)]);
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

/* Its bytes as a C string that is now yours (mem.free it), null when it
 * has none; it is empty after. For C: `u8 *text = fs.readAll(p).take();` */
pub u8 *String.take()
{
    u8 *bytes = self.ptr;
    self.ptr = null;
    self.len = 0;
    self.cap = 0;
    return bytes;
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

/* ---- Reading bytes: these take a u8[], so a literal, a slice or a
 * String; called as methods too (`s.find("x")` is str.find(s, "x")) ---- */

/* Where needle first is in s, -1 when it isn't (an empty needle is at 0) */
pub i64 find(u8[] s, u8[] needle)
{
    if (needle.len > s.len) { return -1; }
    if (!needle.len) { return 0; }
    u8 first = needle.ptr[0];
    for (i64 i = 0; i <= s.len - needle.len; ++i) {
        if (s.ptr[i] == first && !libc.memcmp(s.ptr + i, needle.ptr, needle.len)) { return i; }
    }
    return -1;
}

/* Where needle last is in s, -1 when it isn't */
pub i64 findLast(u8[] s, u8[] needle)
{
    if (needle.len > s.len) { return -1; }
    for (i64 i = s.len - needle.len; i >= 0; --i) {
        if (!libc.memcmp(s.ptr + i, needle.ptr, needle.len)) { return i; }
    }
    return -1;
}

pub bool contains(u8[] s, u8[] needle)
{
    return find(s, needle) >= 0;
}

pub bool startsWith(u8[] s, u8[] prefix)
{
    return prefix.len <= s.len && !libc.memcmp(s.ptr, prefix.ptr, prefix.len);
}

pub bool endsWith(u8[] s, u8[] suffix)
{
    return suffix.len <= s.len &&
           !libc.memcmp(s.ptr + s.len - suffix.len, suffix.ptr, suffix.len);
}

/* <0, 0 or >0 as a sorts before, with or after b (bytes, then length) */
pub i64 compare(u8[] a, u8[] b)
{
    i64 n = a.len < b.len ? a.len : b.len;
    i32 c = n ? libc.memcmp(a.ptr, b.ptr, n) : 0;
    if (c) { return c; }
    return a.len - b.len;
}

/* ---- Numbers from text: the whole of s must be the number (no spaces,
 * nothing after it); false, with *out untouched, when it isn't one or
 * doesn't fit. `s.toInt(&n)` works as a method too ---- */

/* An unsigned number in base 10, or 16 after 0x / 0X, or 2 after 0b */
pub bool toUint(u8[] s, u64 *out)
{
    i64 i = 0;
    u64 base = 10;
    if (s.len > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) { base = 16; i = 2; }
    else if (s.len > 2 && s[0] == '0' && (s[1] == 'b' || s[1] == 'B')) { base = 2; i = 2; }
    if (i >= s.len) { return false; }
    u64 v = 0;
    for (; i < s.len; i++) {
        u8 c = s[i];
        u64 d;
        if (c >= '0' && c <= '9') { d = c - '0'; }
        else if (c >= 'a' && c <= 'f') { d = c - 'a' + 10; }
        else if (c >= 'A' && c <= 'F') { d = c - 'A' + 10; }
        else { return false; }
        if (d >= base) { return false; }
        if (v > (0xFFFFFFFFFFFFFFFF - d) / base) { return false; }
        v = v * base + d;
    }
    *out = v;
    return true;
}

/* A signed number: an optional + or -, then as toUint */
pub bool toInt(u8[] s, i64 *out)
{
    bool neg = false;
    i64 i = 0;
    if (s.len > 0 && (s[0] == '-' || s[0] == '+')) { neg = s[0] == '-'; i = 1; }
    u64 v;
    if (!toUint(s[i:s.len], &v)) { return false; }
    if (neg) {
        if (v > 0x8000000000000000) { return false; }
        *out = -(i64)v;
    } else {
        if (v > 0x7FFFFFFFFFFFFFFF) { return false; }
        *out = (i64)v;
    }
    return true;
}

/* A floating point number as C writes one: 1.5, -2e10, .5, inf, nan */
pub bool toFloat(u8[] s, f64 *out)
{
    if (s.len == 0 || s.len > 511) { return false; }
    u8 c0 = s[0];
    if (c0 == ' ' || c0 == '\t' || c0 == '\n' || c0 == '\r' || c0 == '\v' || c0 == '\f') { return false; }
    u8 buf[512];
    libc.memcpy(buf, s.ptr, s.len);
    buf[s.len] = 0;
    u8 *end = null;
    f64 v = libc.strtod(buf, &end);
    if (end != buf + s.len) { return false; }
    *out = v;
    return true;
}

/* The same bytes */
pub bool equals(u8[] a, u8[] b)
{
    return a.len == b.len && (!a.len || !libc.memcmp(a.ptr, b.ptr, a.len));
}

static bool viewIsSpace(u8 c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\v' || c == '\f';
}

/* s without the whitespace at its ends: a view of it, nothing copied */
pub u8[] trim(u8[] s)
{
    i64 a = 0, b = s.len;
    while (a < b && viewIsSpace(s.ptr[a])) { a++; }
    while (b > a && viewIsSpace(s.ptr[b - 1])) { b--; }
    return s[a:b];
}

/* The parts of s between the seps, views of it (an empty sep: s whole).
 * The list is allocated: `u8[][] parts = s.split(","); defer parts.free();` */
pub u8[][] split(u8[] s, u8[] sep)
{
    i64 n = 1;
    if (sep.len) {
        for (i64 at = find(s, sep); at >= 0; ) {
            n++;
            i64 next = find(s[at + sep.len:], sep);
            at = next >= 0 ? at + sep.len + next : -1;
        }
    }
    u8[] *parts = mem.alloc(n * sizeof(u8[]));
    u8[][] out = parts[0:n];
    u8[] rest = s;
    for (i64 i = 0; i < n - 1; ++i) {
        i64 at = find(rest, sep);
        out[i] = rest[:at];
        rest = rest[at + sep.len:];
    }
    out[n - 1] = rest;
    return out;
}

/* The parts with sep between them, as a new String (yours):
 * `String line = str.join(words, " "); defer line.free();` */
pub String join(u8[][] parts, u8[] sep)
{
    i64 n = 0;
    for (i64 i = 0; i < parts.len; ++i) { n += parts[i].len + (i ? sep.len : 0); }
    String out = withCap(n);
    for (i64 i = 0; i < parts.len; ++i) {
        if (i) { out.append(sep); }
        out.append(parts[i]);
    }
    return out;
}

/* The same for a Vec of Strings or of views (anything with .len and
 * .get(i)): str.join(&names, ", "). Generic, so str needn't import vec. */
pub String join<V>(V *parts, u8[] sep)
{
    i64 n = 0;
    for (i64 i = 0; i < parts.len; ++i) { n += parts.get(i).len + (i ? sep.len : 0); }
    String out = withCap(n);
    for (i64 i = 0; i < parts.len; ++i) {
        if (i) { out.append(sep); }
        out.append(parts.get(i));
    }
    return out;
}

/* Every old in it replaced by new (an empty old changes nothing) */
pub void String.replace(u8[] old, u8[] new)
{
    if (!old.len || !self.len) { return; }
    String out = withCap(self.len);
    u8[] rest = *self;
    for (i64 at = find(rest, old); at >= 0; at = find(rest, old)) {
        out.append(rest[:at]);
        out.append(new);
        rest = rest[at + old.len:];
    }
    out.append(rest);
    mem.free(self.ptr);
    self.ptr = out.ptr;
    self.len = out.len;
    self.cap = out.cap;
}

/* ---- UTF-8: a String's or a u8[]'s bytes as characters (s.len and s[i]
 * stay bytes) ---- */

/* The code point at byte *at of s, *at moved past it; U+FFFD for bytes
 * that aren't UTF-8 (then *at moves one byte), -1 at the end */
pub i32 decodeRune(u8[] s, i64 *at)
{
    i64 i = *at;
    if (i < 0 || i >= s.len) { return -1; }
    u8 b0 = s.ptr[i];
    if (b0 < 0x80) {
        *at = i + 1;
        return b0;
    }
    i64 n = 0;
    i32 cp = 0;
    u8 lo = 0x80, hi = 0xBF; /* the second byte's range (RFC 3629) */
    if (0xC2 <= b0 <= 0xDF) { n = 2; cp = b0 & 0x1F; }
    else if (b0 == 0xE0) { n = 3; cp = b0 & 0x0F; lo = 0xA0; }
    else if (b0 == 0xED) { n = 3; cp = b0 & 0x0F; hi = 0x9F; }
    else if (0xE1 <= b0 <= 0xEF) { n = 3; cp = b0 & 0x0F; }
    else if (b0 == 0xF0) { n = 4; cp = b0 & 0x07; lo = 0x90; }
    else if (0xF1 <= b0 <= 0xF3) { n = 4; cp = b0 & 0x07; }
    else if (b0 == 0xF4) { n = 4; cp = b0 & 0x07; hi = 0x8F; }
    if (!n || i + n > s.len) {
        *at = i + 1;
        return 0xFFFD;
    }
    for (i64 k = 1; k < n; ++k) {
        u8 b = s.ptr[i + k];
        u8 min = k == 1 ? lo : 0x80, max = k == 1 ? hi : 0xBF;
        if (b < min || b > max) {
            *at = i + 1;
            return 0xFFFD;
        }
        cp = (cp << 6) | (b & 0x3F);
    }
    *at = i + n;
    return cp;
}

/* How many characters s holds (each byte that isn't UTF-8 counts as one) */
pub i64 runeCount(u8[] s)
{
    i64 n = 0, at = 0;
    while (decodeRune(s, &at) >= 0) { n++; }
    return n;
}

/* Whether all of s is UTF-8 */
pub bool validUtf8(u8[] s)
{
    u8 *p = s.ptr;
    i64 n = s.len;
    i64 at = 0;
    while (true) {
        /* ASCII runs: 8 bytes at a time */
        while (at + 8 <= n && !(*(u64 *)(p + at) & 0x8080808080808080)) { at += 8; }
        while (at < n && p[at] < 0x80) { at++; }
        if (at >= n) { return true; }
        i64 before = at;
        i32 cp = decodeRune(s, &at);
        if (cp == 0xFFFD && !(at - before == 3 && p[before] == 0xEF)) { return false; }
    }
    return true;
}

/* ---- Bytes ---- */

/* An ASCII letter in the other case, any other byte as is */
pub u8 toLower(u8 ch)
{
    if (ch >= 'A' && ch <= 'Z') { return ch + 32; }
    return ch;
}

pub u8 toUpper(u8 ch)
{
    if (ch >= 'a' && ch <= 'z') { return ch - 32; }
    return ch;
}

/* ' ', '\t', '\n', '\v', '\f' or '\r' */
pub bool isSpace(u8 ch)
{
    return viewIsSpace(ch);
}

/* s without the whitespace at its start, a view of it */
pub u8[] trimStart(u8[] s)
{
    i64 a = 0;
    while (a < s.len && viewIsSpace(s.ptr[a])) { a++; }
    return s[a:];
}

/* s without what is at its end of the bytes in `chars` (whitespace when
 * left out), a view of it */
pub u8[] trimEnd(u8[] s, u8[] chars = " \t\n\v\f\r")
{
    i64 b = s.len;
    while (b > 0 && find(chars, s[b - 1:b]) >= 0) { b--; }
    return s[:b];
}

/* s around the first sep: *before and *after, views of it, and true; or
 * false, with *before all of s and *after empty, when sep isn't in it */
pub bool splitFirst(u8[] s, u8[] sep, u8[] *before, u8[] *after)
{
    i64 at = find(s, sep);
    if (at < 0) {
        *before = s;
        *after = s[s.len:];
        return false;
    }
    *before = s[:at];
    *after = s[at + sep.len:];
    return true;
}

/* As splitFirst, around the last sep */
pub bool splitLast(u8[] s, u8[] sep, u8[] *before, u8[] *after)
{
    i64 at = findLast(s, sep);
    if (at < 0) {
        *before = s;
        *after = s[s.len:];
        return false;
    }
    *before = s[:at];
    *after = s[at + sep.len:];
    return true;
}

/* ---- Ignoring case: ASCII letters only (A-Z equals a-z); every other
 * byte, UTF-8 included, must match exactly. Right for protocol text:
 * HTTP header names, MIME types, file extensions, hex. ---- */

static u8 foldByte(u8 ch)
{
    if (ch >= 'A' && ch <= 'Z') { return ch + 32; }
    return ch;
}

/* <0, 0 or >0 as a sorts before, with or after b, ignoring ASCII case
 * (as if both were lowercase) */
pub i64 compareFold(u8[] a, u8[] b)
{
    i64 n = a.len < b.len ? a.len : b.len;
    for (i64 i = 0; i < n; i++) {
        i64 c = foldByte(a.ptr[i]) - foldByte(b.ptr[i]);
        if (c) { return c; }
    }
    return a.len - b.len;
}

/* The same bytes, ignoring ASCII case: equalsFold("Content-Length",
 * "content-length") */
pub bool equalsFold(u8[] a, u8[] b)
{
    return a.len == b.len && !compareFold(a, b);
}

/* Where needle first is in s ignoring ASCII case, -1 when it isn't (an
 * empty needle is at 0) */
pub i64 findFold(u8[] s, u8[] needle)
{
    if (needle.len > s.len) { return -1; }
    for (i64 i = 0; i <= s.len - needle.len; ++i) {
        if (!compareFold(s[i:i + needle.len], needle)) { return i; }
    }
    return -1;
}

pub bool containsFold(u8[] s, u8[] needle)
{
    return findFold(s, needle) >= 0;
}

pub bool startsWithFold(u8[] s, u8[] prefix)
{
    return prefix.len <= s.len && !compareFold(s[:prefix.len], prefix);
}

pub bool endsWithFold(u8[] s, u8[] suffix)
{
    return suffix.len <= s.len && !compareFold(s[s.len - suffix.len:], suffix);
}

/* ---- URLs and HTML ---- */

static bool urlUnreserved(u8 ch)
{
    return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') ||
           ch == '-' || ch == '_' || ch == '.' || ch == '~';
}

static String urlEncodeAs(u8[] s, u8 keep, bool plus)
{
    u8 *digits = "0123456789ABCDEF";
    String out = withCap(s.len);
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        if (urlUnreserved(ch) || (keep && ch == keep)) {
            out.appendByte(ch);
        } else if (plus && ch == ' ') {
            out.appendByte('+');
        } else {
            out.appendByte('%');
            out.appendByte(digits[ch >> 4]);
            out.appendByte(digits[ch & 15]);
        }
    }
    return out;
}

/* s percent-encoded as one URL component (a query value, a path
 * segment): every byte but A-Z a-z 0-9 - _ . ~ becomes %XX, space
 * included (%20). A new String, yours. */
pub String urlEncode(u8[] s)
{
    return urlEncodeAs(s, 0, false);
}

/* As urlEncode, keeping '/': for a whole path ("/a b/c" -> "/a%20b/c") */
pub String urlEncodePath(u8[] s)
{
    return urlEncodeAs(s, '/', false);
}

/* As urlEncode, with space as '+': application/x-www-form-urlencoded,
 * what HTML forms send */
pub String formEncode(u8[] s)
{
    return urlEncodeAs(s, 0, true);
}

static i64 hexDigit(u8 ch)
{
    if (ch >= '0' && ch <= '9') { return ch - '0'; }
    if (ch >= 'a' && ch <= 'f') { return ch - 'a' + 10; }
    if (ch >= 'A' && ch <= 'F') { return ch - 'A' + 10; }
    return -1;
}

static bool urlDecodeAs(u8[] s, String *out, bool plus)
{
    out.clear();
    out.reserve(s.len);
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        if (ch == '%') {
            i64 h = i + 2 < s.len ? hexDigit(s.ptr[i + 1]) : -1;
            i64 l = h >= 0 ? hexDigit(s.ptr[i + 2]) : -1;
            if (l < 0) {
                out.clear();
                return false;
            }
            out.appendByte(h * 16 + l);
            i += 2;
        } else if (plus && ch == '+') {
            out.appendByte(' ');
        } else {
            out.appendByte(ch);
        }
    }
    return true;
}

/* s with each %XX turned back into its byte, into *out (a String,
 * replaced: what was in it is cleared, its memory reused). '+' stays '+'.
 * False, with *out empty, when a '%' isn't followed by two hex digits.
 * The result can hold any byte, %00 and %2F included: check it before
 * using it as a path. */
pub bool urlDecode(u8[] s, String *out)
{
    return urlDecodeAs(s, out, false);
}

/* As urlDecode, with '+' as a space: for form data and query strings */
pub bool formDecode(u8[] s, String *out)
{
    return urlDecodeAs(s, out, true);
}

/* s with & < > " ' as &amp; &lt; &gt; &quot; &#39;, safe in HTML text
 * and in a quoted attribute value. A new String, yours. */
pub String htmlEscape(u8[] s)
{
    String out = withCap(s.len);
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        switch (ch) {
            case '&': out.append("&amp;");
            case '<': out.append("&lt;");
            case '>': out.append("&gt;");
            case '"': out.append("&quot;");
            case '\'': out.append("&#39;");
            default: out.appendByte(ch);
        }
    }
    return out;
}
