module yaml;
import cstr, libc, mem, str;

/* What a Writer remembers about each mapping or sequence it has open */
const u8 WRITER_OBJECT = 1;    /* a mapping (else a sequence) */

const u8 WRITER_ITEMS = 2;     /* something is in it already */

const u8 WRITER_KEY = 4;       /* a mapping whose key() is waiting for its value */

const u8 WRITER_NEWLINE = 8;   /* the value of a key: its first entry starts a new line */

const u8 WRITER_INLINE = 16;   /* an item of a sequence: its first entry follows the "- " */

/* Builds a YAML document in block style into a String: begin/end the
 * mappings and sequences, key() each entry, and add the values. Each
 * level is indented two spaces, a sequence item starts with "- ", and a
 * string is quoted only when it would otherwise read back as something
 * else. An empty mapping or sequence is written {} or []. Make one with
 * yaml.writerNew() (a declared Writer is all zero, not set up), free it with
 * w.free(), and take the text with w.take(). Its fields are read-only
 * outside module yaml. */
pub struct Writer
{
    String out;
    i64 depth;                 /* mappings and sequences open */
    bool done;                 /* the top-level value is complete */
    u8 levels[MAX_DEPTH];      /* WRITER_* bits of each open level */
};

/* An empty Writer */
pub Writer writerNew()
{
    Writer w;
    libc.memset(&w, 0, sizeof(Writer));
    return w;
}

/* Starts the next entry of the innermost level: the line break or the
 * indentation in front of its key or "- " */
static void writerEntry(Writer *w)
{
    u8 *lvl = &w.levels[w.depth - 1];
    if (*lvl & WRITER_ITEMS || *lvl & WRITER_NEWLINE) {
        if (!(*lvl & WRITER_ITEMS)) { w.out.appendByte('\n'); }
        for (i64 i = 2 * (w.depth - 1); i > 0; i--) { w.out.appendByte(' '); }
    }
    *lvl |= WRITER_ITEMS;
}

/* Before a value: checks one is allowed here and writes the "- " of a
 * sequence item (and, for a scalar after a key, the space) */
static void writerValue(Writer *w, u8 *what, bool scalar)
{
    if (!w.depth) {
        if (w.done) { throw(ERR_WRITER, "%s: the document already has its top-level value", what); }
        return;
    }
    u8 *lvl = &w.levels[w.depth - 1];
    if (*lvl & WRITER_OBJECT) {
        if (!(*lvl & WRITER_KEY)) { throw(ERR_WRITER, "%s: a mapping entry needs key() first", what); }
        *lvl &= ~WRITER_KEY;
        if (scalar) { w.out.appendByte(' '); }
        return;
    }
    writerEntry(w);
    w.out.append("- ");
}

/* After a value: the end of its line; the document is complete when it
 * was the top-level one */
static void writerValueDone(Writer *w)
{
    w.out.appendByte('\n');
    if (!w.depth) { w.done = true; }
}

/* Must s be quoted to read back as the same string? */
static bool writerNeedsQuotes(u8[] s)
{
    if (!s.len || scalarKind(s) != STRING) { return true; }
    u8 first = s.ptr[0], last = s.ptr[s.len - 1];
    /* indicators, and a leading space */
    if (libc.memchr("-?:,[]{}#&*!|>'\"%@` ", first, 20)) { return true; }
    if (last == ' ' || last == ':') { return true; }
    if (s.len >= 3 && first == '.' && s.ptr[1] == '.' && s.ptr[2] == '.') { return true; }
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        if (ch < 0x20 || ch == 0x7F) { return true; }
        if (ch == ':' && i + 1 < s.len && s.ptr[i + 1] == ' ') { return true; }
        if (ch == ' ' && i + 1 < s.len && s.ptr[i + 1] == '#') { return true; }
    }
    return false;
}

/* s as a plain scalar when that reads back as s, else double-quoted */
static void writerScalar(Writer *w, u8[] s)
{
    if (!writerNeedsQuotes(s)) {
        w.out.append(s);
        return;
    }
    u8 *hex = "0123456789ABCDEF";
    w.out.reserve(s.len + 2);
    w.out.appendByte('"');
    i64 from = 0;   /* bytes from here to i need no escape: copied at once */
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        if (ch >= 0x20 && ch != 0x7F && ch != '"' && ch != '\\') { continue; }
        w.out.append(s[from:i]);
        from = i + 1;
        w.out.appendByte('\\');
        switch (ch) {
            case '"':  w.out.appendByte('"');
            case '\\': w.out.appendByte('\\');
            case '\n': w.out.appendByte('n');
            case '\t': w.out.appendByte('t');
            case '\r': w.out.appendByte('r');
            case 0:    w.out.appendByte('0');
            default:
                w.out.appendByte('x');
                w.out.appendByte(hex[ch >> 4]);
                w.out.appendByte(hex[ch & 0xF]);
        }
    }
    w.out.append(s[from:]);
    w.out.appendByte('"');
}

static void writerBegin(Writer *w, u8 kind, u8 *what)
{
    if (w.depth >= MAX_DEPTH) {
        throw(ERR_WRITER, "%s: nested more than yaml.MAX_DEPTH (%d) deep", what, MAX_DEPTH);
    }
    writerValue(w, what, false);
    if (w.depth) {
        kind |= w.levels[w.depth - 1] & WRITER_OBJECT ? WRITER_NEWLINE : WRITER_INLINE;
    }
    w.levels[w.depth++] = kind;
}

/* Opens a mapping: key() and a value for each entry, then end() */
pub void Writer.beginObject()
{
    writerBegin(self, WRITER_OBJECT, "beginObject()");
}

/* Opens a sequence: its items, then end() */
pub void Writer.beginArray()
{
    writerBegin(self, 0, "beginArray()");
}

/* Closes the innermost open mapping or sequence */
pub void Writer.end()
{
    if (!self.depth) { throw(ERR_WRITER, "end(): no mapping or sequence is open"); }
    u8 lvl = self.levels[self.depth - 1];
    if (lvl & WRITER_KEY) { throw(ERR_WRITER, "end(): the last key() has no value"); }
    self.depth--;
    if (!(lvl & WRITER_ITEMS)) {
        if (lvl & WRITER_NEWLINE) { self.out.appendByte(' '); }
        if (lvl & WRITER_OBJECT) { self.out.append("{}"); } else { self.out.append("[]"); }
        writerValueDone(self);
    } else if (!self.depth) {
        self.done = true;
    }
}

static void writerKey(Writer *w, u8 *what)
{
    if (!w.depth || !(w.levels[w.depth - 1] & WRITER_OBJECT)) {
        throw(ERR_WRITER, "%s: no mapping is open", what);
    }
    if (w.levels[w.depth - 1] & WRITER_KEY) { throw(ERR_WRITER, "%s: the last key has no value yet", what); }
    writerEntry(w);
}

/* The key of the next entry of the open mapping (any UTF-8, quoted when
 * it needs to be) */
pub void Writer.key(u8[] k)
{
    if (!str.validUtf8(k)) { throw(ERR_WRITER, "key(): the string isn't UTF-8"); }
    writerKey(self, "key()");
    writerScalar(self, k);
    self.out.appendByte(':');
    self.levels[self.depth - 1] |= WRITER_KEY;
}

/* A key that is safe to write as it is (a generator's field names) */
pub void Writer.rawKey(u8[] k)
{
    writerKey(self, "rawKey()");
    self.out.append(k);
    self.out.appendByte(':');
    self.levels[self.depth - 1] |= WRITER_KEY;
}

pub void Writer.int(i64 v)
{
    writerValue(self, "int()", true);
    self.out.appendFormat("%d", v);
    writerValueDone(self);
}

pub void Writer.uint(u64 v)
{
    writerValue(self, "uint()", true);
    u8 digits[24];
    i64 at = 24;
    do {
        digits[--at] = '0' + v % 10;
        v /= 10;
    } while (v);
    self.out.append(digits[at:24]);
    writerValueDone(self);
}

/* The shortest digits that read back as v, always with a '.' or an
 * exponent so they read back as a float; .inf, -.inf and .nan */
pub void Writer.float(f64 v)
{
    writerValue(self, "float()", true);
    u64 bits;
    mem.copy(&bits, &v, sizeof(f64));
    if (((bits >> 52) & 0x7FF) == 0x7FF) {
        if (bits & 0xFFFFFFFFFFFFF) { self.out.append(".nan"); }
        else if (bits >> 63) { self.out.append("-.inf"); }
        else { self.out.append(".inf"); }
        writerValueDone(self);
        return;
    }
    u8 buf[64];
    i64 prec = 15;
    while (true) {
        libc.snprintf(buf, 64, "%.*g", (i32)prec, v);
        if (prec >= 17 || libc.strtod(buf, null) == v) { break; }
        prec++;
    }
    self.out.append(cstr.view(buf));
    if (!cstr.count(buf, '.') && !cstr.count(buf, 'e')) { self.out.append(".0"); }
    writerValueDone(self);
}

pub void Writer.bool(bool v)
{
    writerValue(self, "bool()", true);
    if (v) { self.out.append("true"); } else { self.out.append("false"); }
    writerValueDone(self);
}

/* YAML's null (`null` itself is a keyword, so it can't be a method name) */
pub void Writer.nil()
{
    writerValue(self, "nil()", true);
    self.out.append("null");
    writerValueDone(self);
}

/* A string value: plain when it reads back as the same string, else
 * double-quoted with escapes; it must be UTF-8, or it throws */
pub void Writer.str(u8[] s)
{
    if (!str.validUtf8(s)) { throw(ERR_WRITER, "str(): the string isn't UTF-8"); }
    writerValue(self, "str()", true);
    writerScalar(self, s);
    writerValueDone(self);
}

/* The finished document, yours to free; the Writer is empty after it and
 * can write another. Throws while a mapping or sequence is still open,
 * or before any value was written. */
pub String Writer.take()
{
    if (self.depth) { throw(ERR_WRITER, "take(): %d mapping(s) or sequence(s) still open", self.depth); }
    if (!self.done) { throw(ERR_WRITER, "take(): nothing was written"); }
    String s = self.out;
    libc.memset(&self.out, 0, sizeof(String));
    self.done = false;
    return s;
}

/* Frees what the Writer holds; it is empty after and can write again */
pub void Writer.free()
{
    self.out.free();
    self.depth = 0;
    self.done = false;
}
