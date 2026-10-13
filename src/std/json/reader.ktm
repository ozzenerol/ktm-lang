module json;
import cstr, libc, mem, str;

/* What a Reader remembers about each array or object it has open */
struct ReaderLevel
{
    bool object;   /* an object (else an array) */
    bool first;    /* nothing read from it yet */
    i64 at;        /* an object: where its current key starts; an array: the item's index */
};

/* Reads JSON text front to back without building a tree: open an object
 * with beginObject() and loop on nextKey(), open an array with
 * beginArray() and loop on nextItem(), and read each value with
 * readString/readInt/readFloat/readBool (isNull() first where null is
 * allowed, skipValue() for one you don't want). A wrong value throws
 * ERR_DECODE with where it is: "3:14: lines[2].price: expected a number,
 * got a string". Make one with json.readerNew(text) (the text is viewed,
 * not copied: keep it alive) and free it with r.free(). Its fields are
 * read-only outside module json. */
pub struct Reader
{
    u8[] text;
    i64 pos;                   /* the next byte to read */
    i64 depth;                 /* arrays and objects open */
    i64 cap;                   /* room in levels */
    ReaderLevel *levels;
    u8[] k;                    /* the current key */
    String buf;                /* a key or string with escapes, decoded */
    bool strict;               /* json.STRICT: generated decoders reject missing and unknown keys */
};

/* A Reader at the start of text. With json.STRICT in flags, generated
 * fromJson methods throw on a key with no field and on a missing key. */
pub Reader readerNew(u8[] text, i64 flags = 0)
{
    Reader r;
    libc.memset(&r, 0, sizeof(Reader));
    r.text = text;
    r.strict = (flags & STRICT) != 0;
    return r;
}

/* Frees what the Reader holds (not the text) */
pub void Reader.free()
{
    mem.free(self.levels);
    self.levels = null;
    self.cap = 0;
    self.depth = 0;
    self.buf.free();
}

static void readerWhitespace(Reader *r)
{
    u8 *b = r.text.ptr;
    i64 i = r.pos, n = r.text.len;
    while (i < n) {
        u8 ch = b[i];
        if (ch != ' ' && ch != '\n' && ch != '\r' && ch != '\t') { break; }
        i++;
    }
    r.pos = i;
}

/* The key an object level is at, as written (escapes and all) */
static u8[] readerRawKey(Reader *r, i64 at)
{
    u8 *b = r.text.ptr;
    i64 i = at + 1, n = r.text.len;
    while (i < n && b[i] != '"') {
        if (b[i] == '\\') { i++; }
        i++;
    }
    if (i > n) { i = n; }
    return b[at + 1:i];
}

/* Throws ERR_DECODE: "line:col: path: what" (no path at the top level);
 * with `field`, the path goes on to it ("ship.zip: missing") */
pub void Reader.fail(u8 *what, u8 *field = null)
{
    u8 *b = self.text.ptr;
    i64 at = self.pos;
    if (at > self.text.len) { at = self.text.len; }
    i64 line = 1, col = 1;
    for (i64 i = 0; i < at; i++) {
        if (b[i] == '\n') { line++; col = 1; } else { col++; }
    }
    String path;
    defer path.free();
    for (i64 i = 0; i < self.depth; i++) {
        ReaderLevel *l = &self.levels[i];
        if (l.first) { continue; }
        if (l.object) {
            if (path.len) { path.appendByte('.'); }
            path.append(readerRawKey(self, l.at));
        } else {
            path.appendFormat("[%d]", l.at);
        }
    }
    if (field) {
        if (path.len) { path.appendByte('.'); }
        path.append(cstr.view(field));
    }
    if (path.len) {
        throw(ERR_DECODE, "%d:%d: %s: %s", line, col, path, what);
    }
    throw(ERR_DECODE, "%d:%d: %s", line, col, what);
}

/* Is `lit` (n bytes) at r.pos? */
static bool readerIs(Reader *r, u8 *lit, i64 n)
{
    if (r.text.len - r.pos < n) { return false; }
    return !libc.memcmp(r.text.ptr + r.pos, lit, n);
}

/* What the next value is, for a message */
static u8 *readerGot(Reader *r)
{
    if (r.pos >= r.text.len) { return "the end of the input"; }
    u8 ch = r.text.ptr[r.pos];
    switch (ch) {
        case '"':      return "a string";
        case '{':      return "an object";
        case '[':      return "an array";
        case 't':      if (readerIs(r, "true", 4)) { return "a bool"; }
        case 'f':      if (readerIs(r, "false", 5)) { return "a bool"; }
        case 'n':      if (readerIs(r, "null", 4)) { return "null"; }
        case '}':      return "'}'";
        case ']':      return "']'";
        case ',':      return "','";
        case ':':      return "':'";
    }
    if (ch == '-' || ('0' <= ch <= '9')) { return "a number"; }
    return "something that isn't JSON";
}

static void readerExpected(Reader *r, u8 *want)
{
    String what = str.format("expected %s, got %s", want, readerGot(r));
    defer what.free();
    r.fail(what.cstr());
}

static void readerPush(Reader *r, bool object)
{
    if (r.depth >= MAX_DEPTH) {
        r.fail("arrays and objects nested deeper than json.MAX_DEPTH");
    }
    if (r.depth == r.cap) {
        i64 cap = r.cap ? r.cap * 2 : 16;
        r.levels = mem.realloc(r.levels, cap * sizeof(ReaderLevel));
        r.cap = cap;
    }
    ReaderLevel *l = &r.levels[r.depth++];
    l.object = object;
    l.first = true;
    l.at = 0;
}

/* Opens the object that is the next value: its members with nextKey() */
pub void Reader.beginObject()
{
    readerWhitespace(self);
    if (self.pos >= self.text.len || self.text.ptr[self.pos] != '{') {
        readerExpected(self, "an object");
    }
    self.pos++;
    readerPush(self, true);
}

/* Opens the array that is the next value: its items with nextItem() */
pub void Reader.beginArray()
{
    readerWhitespace(self);
    if (self.pos >= self.text.len || self.text.ptr[self.pos] != '[') {
        readerExpected(self, "an array");
    }
    self.pos++;
    readerPush(self, false);
}

static u8 readerHex(u8 ch)
{
    if ('0' <= ch <= '9') { return ch - '0'; }
    if ('a' <= ch <= 'f') { return ch - 'a' + 10; }
    if ('A' <= ch <= 'F') { return ch - 'A' + 10; }
    return 0xFF;
}

/* The 4 hex digits at b[i], or -1 */
static i64 readerHex4(Reader *r, i64 i)
{
    if (i + 4 > r.text.len) { return -1; }
    i64 v = 0;
    for (i64 j = 0; j < 4; j++) {
        u8 h = readerHex(r.text.ptr[i + j]);
        if (h == 0xFF) { return -1; }
        v = (v << 4) | h;
    }
    return v;
}

/* The rest of a string with escapes, from byte i (the first '\\'), decoded
 * into r.buf after the plain bytes from `from` */
static u8[] readerEscaped(Reader *r, i64 from, i64 i, bool ascii)
{
    u8 *b = r.text.ptr;
    i64 n = r.text.len;
    r.buf.clear();
    r.buf.append(b[from:i]);
    i64 run = i;   /* bytes from run to i need no decoding */
    while (true) {
        if (i >= n) {
            r.pos = n;
            r.fail("a string with no closing quote");
        }
        u8 ch = b[i];
        if (ch == '"') { break; }
        if (ch < 0x20) {
            r.pos = i;
            r.fail("a control character in a string (write \\n, \\t, \\u0001 ...)");
        }
        if (ch >= 0x80) { ascii = false; }
        if (ch != '\\') {
            i++;
            continue;
        }
        r.buf.append(b[run:i]);
        if (i + 1 >= n) {
            r.pos = n;
            r.fail("a string with no closing quote");
        }
        u8 e = b[i + 1];
        i += 2;
        switch (e) {
            case '"':  r.buf.appendByte('"');
            case '\\': r.buf.appendByte('\\');
            case '/':  r.buf.appendByte('/');
            case 'b':  r.buf.appendByte('\b');
            case 'f':  r.buf.appendByte('\f');
            case 'n':  r.buf.appendByte('\n');
            case 'r':  r.buf.appendByte('\r');
            case 't':  r.buf.appendByte('\t');
            case 'u': {
                i64 cp = readerHex4(r, i);
                if (cp < 0) {
                    r.pos = i - 2;
                    r.fail("\\u not followed by 4 hex digits");
                }
                i += 4;
                if (0xDC00 <= cp <= 0xDFFF) {
                    r.pos = i - 6;
                    r.fail("a \\u escape with an unpaired surrogate");
                }
                if (0xD800 <= cp <= 0xDBFF) {
                    i64 lo = -1;
                    if (i + 1 < n && b[i] == '\\' && b[i + 1] == 'u') { lo = readerHex4(r, i + 2); }
                    if (!(0xDC00 <= lo <= 0xDFFF)) {
                        r.pos = i - 6;
                        r.fail("a \\u escape with an unpaired surrogate");
                    }
                    i += 6;
                    cp = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
                }
                u8 utf8[4];
                r.buf.append(utf8[0:jsonUtf8Encode(utf8, cp)]);
            }
            default:
                r.pos = i - 2;
                r.fail("an unknown escape in a string");
        }
        run = i;
    }
    r.buf.append(b[run:i]);
    r.pos = i + 1;
    if (!ascii && !str.validUtf8(r.buf.ptr[0:r.buf.len])) {
        r.pos = from - 1;
        r.fail("a string that isn't UTF-8");
    }
    return r.buf.ptr[0:r.buf.len];
}

/* The string at r.pos, decoded: a view of the text when it has no escapes,
 * else of r.buf */
static u8[] readerString(Reader *r)
{
    readerWhitespace(r);
    u8 *b = r.text.ptr;
    i64 n = r.text.len;
    if (r.pos >= n || b[r.pos] != '"') { readerExpected(r, "a string"); }
    i64 from = r.pos + 1, i = from;
    bool ascii = true;
    while (i < n) {
        u8 ch = b[i];
        if (ch == '"') { break; }
        if (ch == '\\') { return readerEscaped(r, from, i, ascii); }
        if (ch < 0x20) {
            r.pos = i;
            r.fail("a control character in a string (write \\n, \\t, \\u0001 ...)");
        }
        if (ch >= 0x80) { ascii = false; }
        i++;
    }
    if (i >= n) {
        r.pos = n;
        r.fail("a string with no closing quote");
    }
    u8[] s = b[from:i];
    if (!ascii && !str.validUtf8(s)) {
        r.pos = from - 1;
        r.fail("a string that isn't UTF-8");
    }
    r.pos = i + 1;
    return s;
}

/* Moves to the next member of the open object, reading its key and the
 * ':' after it; false when the object ends, which closes it */
pub bool Reader.nextKey()
{
    if (!self.depth || !self.levels[self.depth - 1].object) {
        self.fail("nextKey(): no object is open");
    }
    ReaderLevel *l = &self.levels[self.depth - 1];
    readerWhitespace(self);
    u8 *b = self.text.ptr;
    i64 n = self.text.len;
    if (self.pos < n && b[self.pos] == '}' ) {
        self.pos++;
        self.depth--;
        return false;
    }
    if (!l.first) {
        if (self.pos >= n || b[self.pos] != ',') { readerExpected(self, "',' or '}'"); }
        self.pos++;
        readerWhitespace(self);
    }
    if (self.pos >= n || b[self.pos] != '"') {
        readerExpected(self, l.first ? "a key or '}'" : "a key");
    }
    l.at = self.pos;
    l.first = false;
    self.k = readerString(self);
    readerWhitespace(self);
    if (self.pos >= n || b[self.pos] != ':') { readerExpected(self, "':' after the key"); }
    self.pos++;
    return true;
}

/* The key nextKey() read, decoded: a view, valid until the next read */
pub u8[] Reader.key()
{
    return self.k;
}

/* Moves to the next item of the open array; false when the array ends,
 * which closes it */
pub bool Reader.nextItem()
{
    if (!self.depth || self.levels[self.depth - 1].object) {
        self.fail("nextItem(): no array is open");
    }
    ReaderLevel *l = &self.levels[self.depth - 1];
    readerWhitespace(self);
    u8 *b = self.text.ptr;
    i64 n = self.text.len;
    if (self.pos < n && b[self.pos] == ']') {
        self.pos++;
        self.depth--;
        return false;
    }
    if (l.first) {
        l.first = false;
    } else {
        if (self.pos >= n || b[self.pos] != ',') { readerExpected(self, "',' or ']'"); }
        self.pos++;
        l.at++;
        readerWhitespace(self);
        if (self.pos < n && b[self.pos] == ']') { self.fail("a ',' before ']'"); }
    }
    return true;
}

/* A string value into `into`, replacing its bytes (its buffer is kept) */
pub void Reader.readString(String *into)
{
    u8[] s = readerString(self);
    into.clear();
    into.append(s);
}

/* A string value, decoded: a view, valid until the next read */
pub u8[] Reader.readStr()
{
    return readerString(self);
}

/* A string value in a new mem.alloc block with a NUL after it; its length
 * in *len when len isn't null */
pub u8 *Reader.readBytes(i64 *len = null)
{
    u8[] s = readerString(self);
    u8 *p = mem.alloc(s.len + 1);
    if (s.len) { mem.copy(p, s.ptr, s.len); }
    p[s.len] = 0;
    if (len) { *len = s.len; }
    return p;
}

/* The length of the number at r.pos (RFC 8259's grammar, ending at the
 * input's end, whitespace, ',', ']' or '}'), 0 if there is none;
 * *isInt: no fraction and no exponent */
static i64 readerNumberLen(Reader *r, bool *isInt)
{
    u8 *b = r.text.ptr;
    i64 i = r.pos, n = r.text.len;
    *isInt = true;
    if (i < n && b[i] == '-') { i++; }
    if (i >= n || !('0' <= b[i] <= '9')) {
        if (i > r.pos) { goto bad; }   /* a '-' alone */
        return 0;
    }
    if (b[i] == '0') {
        i++;
    } else {
        while (i < n && '0' <= b[i] <= '9') { i++; }
    }
    if (i < n && b[i] == '.') {
        *isInt = false;
        i++;
        if (i >= n || !('0' <= b[i] <= '9')) { goto bad; }
        while (i < n && '0' <= b[i] <= '9') { i++; }
    }
    if (i < n && (b[i] == 'e' || b[i] == 'E')) {
        *isInt = false;
        i++;
        if (i < n && (b[i] == '+' || b[i] == '-')) { i++; }
        if (i >= n || !('0' <= b[i] <= '9')) { goto bad; }
        while (i < n && '0' <= b[i] <= '9') { i++; }
    }
    if (i < n && b[i] != ' ' && b[i] != '\t' && b[i] != '\n' &&
        b[i] != '\r' && b[i] != ',' && b[i] != ']' && b[i] != '}') {
        goto bad;
    }
    return i - r.pos;

bad:
    r.pos = i;
    r.fail("a malformed number");
    return 0;
}

/* The digits of the integer at r.pos (len bytes, an optional '-') as a
 * magnitude; false when it is more than max */
static bool readerMagnitude(Reader *r, i64 len, u64 max, u64 *out)
{
    u8 *b = r.text.ptr + r.pos;
    i64 i = b[0] == '-' ? 1 : 0;
    u64 v = 0;
    for (; i < len; i++) {
        u64 d = b[i] - '0';
        if (v > (max - d) / 10) { return false; }
        v = v * 10 + d;
    }
    *out = v;
    return true;
}

/* An integer value that fits an i64 */
pub i64 Reader.readInt()
{
    readerWhitespace(self);
    bool isInt;
    i64 len = readerNumberLen(self, &isInt);
    if (!len) { readerExpected(self, "an integer"); }
    bool neg = self.text.ptr[self.pos] == '-';
    u64 v;
    if (!isInt || !readerMagnitude(self, len, neg ? 0x8000000000000000 : 0x7FFFFFFFFFFFFFFF, &v)) {
        self.fail("expected an integer that fits an i64, got a number");
    }
    self.pos += len;
    return neg ? -(i64)v : (i64)v;
}

/* An integer value from 0 to 2^64 - 1 (a u64 past the i64 range) */
pub u64 Reader.readUint()
{
    readerWhitespace(self);
    bool isInt;
    i64 len = readerNumberLen(self, &isInt);
    if (!len) { readerExpected(self, "an integer"); }
    u64 v;
    if (!isInt || self.text.ptr[self.pos] == '-' ||
        !readerMagnitude(self, len, 0xFFFFFFFFFFFFFFFF, &v)) {
        self.fail("expected an integer from 0 to 2^64 - 1, got a number");
    }
    self.pos += len;
    return v;
}

/* Any number value */
pub f64 Reader.readFloat()
{
    readerWhitespace(self);
    bool isInt;
    i64 len = readerNumberLen(self, &isInt);
    if (!len) { readerExpected(self, "a number"); }
    u64 v;
    /* up to 15 digits an f64 holds exactly */
    if (isInt && len <= 16 && readerMagnitude(self, len, 999999999999999, &v)) {
        f64 f = (f64)v;
        if (self.text.ptr[self.pos] == '-') { f = -f; }
        self.pos += len;
        return f;
    }
    /* strtod needs the text on its own: more digits may follow in the buffer */
    u8 small[64], *text = small;
    if (len >= 64) { text = mem.alloc(len + 1); }
    mem.copy(text, self.text.ptr + self.pos, len);
    text[len] = 0;
    f64 f = libc.strtod(text, null);
    if (text != small) { mem.free(text); }
    self.pos += len;
    return f;
}

/* true or false */
pub bool Reader.readBool()
{
    readerWhitespace(self);
    u8 *b = self.text.ptr + self.pos;
    i64 left = self.text.len - self.pos;
    if (left >= 4 && b[0] == 't' && b[1] == 'r' && b[2] == 'u' && b[3] == 'e') {
        self.pos += 4;
        return true;
    }
    if (left >= 5 && b[0] == 'f' && b[1] == 'a' && b[2] == 'l' && b[3] == 's' && b[4] == 'e') {
        self.pos += 5;
        return false;
    }
    readerExpected(self, "a bool");
    return false;
}

/* Is the next value null? Then it is read; otherwise nothing is */
pub bool Reader.isNull()
{
    readerWhitespace(self);
    u8 *b = self.text.ptr + self.pos;
    if (self.text.len - self.pos >= 4 && b[0] == 'n' && b[1] == 'u' && b[2] == 'l' && b[3] == 'l') {
        self.pos += 4;
        return true;
    }
    return false;
}

/* The kind of the next value, read nothing: json.STRING, INT (an integer
 * that fits an i64), FLOAT (any other number), BOOL, NULL, ARRAY, OBJECT */
pub i64 Reader.peek()
{
    readerWhitespace(self);
    if (self.pos >= self.text.len) { readerExpected(self, "a value"); }
    u8 ch = self.text.ptr[self.pos];
    switch (ch) {
        case '"': return STRING;
        case '{': return OBJECT;
        case '[': return ARRAY;
        case 't': if (readerIs(self, "true", 4)) { return BOOL; }
        case 'f': if (readerIs(self, "false", 5)) { return BOOL; }
        case 'n': if (readerIs(self, "null", 4)) { return NULL; }
    }
    if (ch != '-' && !('0' <= ch <= '9')) { readerExpected(self, "a value"); }
    bool isInt;
    i64 len = readerNumberLen(self, &isInt);
    if (!len) { readerExpected(self, "a value"); }
    u64 v;
    bool neg = ch == '-';
    if (isInt && readerMagnitude(self, len, neg ? 0x8000000000000000 : 0x7FFFFFFFFFFFFFFF, &v)) {
        return INT;
    }
    return FLOAT;
}

/* Reads the next value, whatever it is, and drops it */
pub void Reader.skipValue()
{
    switch (self.peek()) {
        case OBJECT:
            self.beginObject();
            while (self.nextKey()) { self.skipValue(); }
        case ARRAY:
            self.beginArray();
            while (self.nextItem()) { self.skipValue(); }
        case STRING:
            readerString(self);
        case BOOL:
            self.readBool();
        case NULL:
            if (!self.isNull()) { readerExpected(self, "null"); }
        default:
            bool isInt;
            self.pos += readerNumberLen(self, &isInt);
    }
}

/* After the top-level value: throws unless only whitespace is left */
pub void Reader.finish()
{
    if (self.depth) { self.fail("finish(): an array or object is still open"); }
    readerWhitespace(self);
    if (self.pos < self.text.len) { readerExpected(self, "the end of the input"); }
}
