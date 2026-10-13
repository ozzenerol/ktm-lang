module json;
import cstr, libc, str;

/* The code a Writer throws when it is used out of order (a value where a
 * key is expected, a key outside an object, end() with nothing open,
 * take() before the document is complete) or given something JSON can't
 * hold (a NaN or infinite float, a string that isn't UTF-8, raw() text
 * that isn't one JSON value). The message says which. */
pub const i64 ERR_WRITER = 0x6B746D10;

/* What a Writer remembers about each array or object it has open */
const u8 WRITER_OBJECT = 1;   /* an object (else an array) */

const u8 WRITER_ITEMS = 2;    /* something is in it already: the next needs a ',' */

const u8 WRITER_KEY = 4;      /* an object whose key() is waiting for its value */

/* Builds a JSON document into a String: begin/end the arrays and objects,
 * key() each member, and add the values; commas, quotes and escapes are
 * written for you. Make one with json.writerNew() (a declared Writer
 * isn't zeroed), free it with w.free(), and take the text with w.take().
 * Its fields are read-only outside module json. */
pub struct Writer
{
    String out;
    i64 indent;                /* spaces per level, 0 for compact */
    i64 depth;                 /* arrays and objects open */
    bool done;                 /* the top-level value is complete */
    u8 levels[MAX_DEPTH];      /* WRITER_* bits of each open level */
};

/* An empty Writer. With indent > 0 every member and item goes on its own
 * line, indented that many spaces per level, and keys are followed by
 * ": "; with 0 (the default) the text is compact: {"a":1,"b":[2,3]} */
pub Writer writerNew(i64 indent = 0)
{
    Writer w;
    libc.memset(&w, 0, sizeof(Writer));
    if (indent > 0) { w.indent = indent; }
    return w;
}

static void writerNewline(Writer *w)
{
    if (!w.indent) { return; }
    w.out.appendByte('\n');
    for (i64 i = w.indent * w.depth; i > 0; i--) { w.out.appendByte(' '); }
}

/* Before a value: checks one is allowed here and writes the ',' and the
 * line break in front of it. `what` names the call for the message. */
static void writerValue(Writer *w, u8 *what)
{
    if (!w.depth) {
        if (w.done) { throw(ERR_WRITER, "%s: the document already has its top-level value", what); }
        return;
    }
    u8 *lvl = &w.levels[w.depth - 1];
    if (*lvl & WRITER_OBJECT) {
        if (!(*lvl & WRITER_KEY)) { throw(ERR_WRITER, "%s: an object member needs key() first", what); }
        *lvl &= ~WRITER_KEY;
        return;   /* key() wrote the separator */
    }
    if (*lvl & WRITER_ITEMS) { w.out.appendByte(','); }
    writerNewline(w);
    *lvl |= WRITER_ITEMS;
}

/* After a value: the document is complete when it was the top-level one */
static void writerValueDone(Writer *w)
{
    if (!w.depth) { w.done = true; }
}

/* s as a JSON string literal: the escapes json.toStr writes (the caller
 * has checked s is UTF-8) */
static void writerQuote(Writer *w, u8[] s)
{
    u8 *hex = "0123456789abcdef";
    w.out.reserve(s.len + 2);
    w.out.appendByte('"');
    i64 from = 0;   /* bytes from here to i need no escape: copied at once */
    for (i64 i = 0; i < s.len; i++) {
        u8 ch = s.ptr[i];
        if (ch >= 0x20 && ch != '"' && ch != '\\') { continue; }
        w.out.append(s[from:i]);
        from = i + 1;
        w.out.appendByte('\\');
        switch (ch) {
            case '"':  w.out.appendByte('"');
            case '\\': w.out.appendByte('\\');
            case '\b': w.out.appendByte('b');
            case '\f': w.out.appendByte('f');
            case '\n': w.out.appendByte('n');
            case '\r': w.out.appendByte('r');
            case '\t': w.out.appendByte('t');
            default:
                w.out.append("u00");
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
        throw(ERR_WRITER, "%s: nested more than json.MAX_DEPTH (%d) deep", what, MAX_DEPTH);
    }
    writerValue(w, what);
    w.out.appendByte(kind & WRITER_OBJECT ? '{' : '[');
    w.levels[w.depth++] = kind;
}

/* Opens an object: key() and a value for each member, then end() */
pub void Writer.beginObject()
{
    writerBegin(self, WRITER_OBJECT, "beginObject()");
}

/* Opens an array: its items, then end() */
pub void Writer.beginArray()
{
    writerBegin(self, 0, "beginArray()");
}

/* Closes the innermost open array or object */
pub void Writer.end()
{
    if (!self.depth) { throw(ERR_WRITER, "end(): no array or object is open"); }
    u8 lvl = self.levels[self.depth - 1];
    if (lvl & WRITER_KEY) { throw(ERR_WRITER, "end(): the last key() has no value"); }
    self.depth--;
    if (lvl & WRITER_ITEMS) { writerNewline(self); }
    self.out.appendByte(lvl & WRITER_OBJECT ? '}' : ']');
    writerValueDone(self);
}

/* The key of the next member of the open object (any bytes, escaped) */
pub void Writer.key(u8[] k)
{
    if (!self.depth || !(self.levels[self.depth - 1] & WRITER_OBJECT)) {
        throw(ERR_WRITER, "key(): no object is open");
    }
    u8 *lvl = &self.levels[self.depth - 1];
    if (*lvl & WRITER_KEY) { throw(ERR_WRITER, "key(): the last key() has no value yet"); }
    if (!str.validUtf8(k)) { throw(ERR_WRITER, "key(): the string isn't UTF-8"); }
    if (*lvl & WRITER_ITEMS) { self.out.appendByte(','); }
    writerNewline(self);
    writerQuote(self, k);
    self.out.appendByte(':');
    if (self.indent) { self.out.appendByte(' '); }
    *lvl |= WRITER_KEY | WRITER_ITEMS;
}

/* key() for a key that is JSON already: k is written between the quotes
 * as it is, with no escaping and no checks (generated encoders pass their
 * keys escaped in advance) */
pub void Writer.rawKey(u8[] k)
{
    if (!self.depth || !(self.levels[self.depth - 1] & WRITER_OBJECT)) {
        throw(ERR_WRITER, "rawKey(): no object is open");
    }
    u8 *lvl = &self.levels[self.depth - 1];
    if (*lvl & WRITER_KEY) { throw(ERR_WRITER, "rawKey(): the last key has no value yet"); }
    if (*lvl & WRITER_ITEMS) { self.out.appendByte(','); }
    writerNewline(self);
    self.out.reserve(k.len + 4);
    self.out.appendByte('"');
    self.out.append(k);
    self.out.appendByte('"');
    self.out.appendByte(':');
    if (self.indent) { self.out.appendByte(' '); }
    *lvl |= WRITER_KEY | WRITER_ITEMS;
}

pub void Writer.int(i64 v)
{
    writerValue(self, "int()");
    self.out.appendFormat("%d", v);
    writerValueDone(self);
}

/* An unsigned integer: all of a u64, also past the i64 range */
pub void Writer.uint(u64 v)
{
    writerValue(self, "uint()");
    u8 digits[24];
    i64 at = 24;
    do {
        digits[--at] = '0' + v % 10;
        v /= 10;
    } while (v);
    self.out.append(digits[at:24]);
    writerValueDone(self);
}

/* The same digits json.toStr writes (they read back as v); JSON has no
 * NaN or infinity, so those throw */
pub void Writer.float(f64 v)
{
    u64 bits;
    libc.memcpy(&bits, &v, sizeof(f64));
    if (((bits >> 52) & 0x7FF) == 0x7FF) { throw(ERR_WRITER, "float(): JSON has no NaN or infinity"); }
    writerValue(self, "float()");
    u8 buf[64];
    self.out.append(cstr.view(jsonFloatToStr(buf, v)));
    writerValueDone(self);
}

pub void Writer.bool(bool v)
{
    writerValue(self, "bool()");
    if (v) { self.out.append("true"); } else { self.out.append("false"); }
    writerValueDone(self);
}

/* JSON's null (`null` itself is a keyword, so it can't be a method name) */
pub void Writer.nil()
{
    writerValue(self, "nil()");
    self.out.append("null");
    writerValueDone(self);
}

/* A string value: all of s's bytes, escaped; they must be UTF-8 (what
 * json.parse accepts), or it throws */
pub void Writer.str(u8[] s)
{
    if (!str.validUtf8(s)) { throw(ERR_WRITER, "str(): the string isn't UTF-8"); }
    writerValue(self, "str()");
    writerQuote(self, s);
    writerValueDone(self);
}

/* A C string value (NUL-terminated), as str() writes it; a null pointer
 * is JSON's null, as json.encode writes a null u8 * */
pub void Writer.cstr(u8 *s)
{
    if (!s) {
        self.nil();
        return;
    }
    self.str(cstr.view(s));
}

/* A value that is JSON text already (one value, checked with json.parse),
 * inserted as it is */
pub void Writer.raw(u8[] text)
{
    Value *doc = parse(text);
    bool good = doc && doc.state.error == OK;
    release(doc);
    if (!good) { throw(ERR_WRITER, "raw(): the text isn't one JSON value"); }
    writerValue(self, "raw()");
    self.out.append(text);
    writerValueDone(self);
}

/* The finished document, yours to free; the Writer is empty after it and
 * can write another. Throws while an array or object is still open, or
 * before any value was written. */
pub String Writer.take()
{
    if (self.depth) { throw(ERR_WRITER, "take(): %d array(s) or object(s) still open", self.depth); }
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
