module yaml;
import cstr, libc, mem, str;

/* A Reader parses the document's structure on its first call into a flat
 * list of events (a mapping or sequence opens, one closes, a scalar),
 * each pointing at its text; the read calls walk that list. Scalars are
 * decoded only when they are read. */
const u8 EV_MAP = 1;
const u8 EV_SEQ = 2;
const u8 EV_END = 3;
const u8 EV_SCALAR = 4;

/* How a scalar is written */
const u8 ST_PLAIN = 1;       /* plain, on one line */
const u8 ST_FOLD = 2;        /* plain, over several lines */
const u8 ST_SQUOTE = 3;      /* 'single-quoted' */
const u8 ST_DQUOTE = 4;      /* "double-quoted" */
const u8 ST_LITERAL = 5;     /* | block */
const u8 ST_FOLDED = 6;      /* > block */

struct Event
{
    u8 kind;
    u8 style;
    i64 start;   /* where it starts in the text */
    i64 end;     /* a scalar: where it ends */
    i64 indent;  /* a block scalar: its content's indentation */
    i64 skip;    /* a mapping or sequence: its EV_END; an EV_END: its opener */
};

/* What a Reader remembers about each mapping or sequence it has open */
struct ReaderLevel
{
    bool object;   /* a mapping (else a sequence) */
    bool first;    /* nothing read from it yet */
    i64 at;        /* a mapping: its current key's event; a sequence: the item's index */
    i64 value;     /* the event of the value nextKey/nextItem moved to */
};

/* The same while the structure is parsed (for the path in its errors) */
struct ParseLevel
{
    bool object;
    i64 ev;        /* its opening event */
    i64 key;       /* a mapping: its current key's event, -1 before the first */
    i64 items;     /* a sequence: items so far */
};

/* Reads a YAML document front to back the way json.Reader reads JSON,
 * with the same calls, so one decoder can serve both: open a mapping
 * with beginObject() and loop on nextKey(), open a sequence with
 * beginArray() and loop on nextItem(), and read each value with
 * readString/readInt/readFloat/readBool (isNull() first where null is
 * allowed, skipValue() for one you don't want). Text that isn't YAML,
 * a part of YAML that isn't supported (anchors and aliases, tags, <<
 * merge keys, several documents) and a value of the wrong kind throw
 * ERR_DECODE with where it is: "3:14: lines[2].price: expected a number,
 * got a string". Make one with yaml.readerNew(text) (the text is viewed,
 * not copied: keep it alive) and free it with r.free(). Its fields are
 * read-only outside module yaml. */
pub struct Reader
{
    u8[] text;
    bool parsed;
    i64 pos;                   /* parsing: the next byte */
    Event *evs;
    i64 nev;
    i64 evcap;
    i64 ev;                    /* reading: the next event */
    i64 depth;                 /* mappings and sequences open */
    i64 cap;                   /* room in levels */
    ReaderLevel *levels;
    i64 pdepth;
    i64 pcap;
    ParseLevel *plevels;
    u8[] k;                    /* the current key */
    String buf;                /* a scalar that needed decoding */
    bool strict;               /* yaml.STRICT: generated decoders reject missing and unknown keys */
};

/* A Reader at the start of text. With yaml.STRICT in flags, generated
 * fromYaml methods throw on a key with no field and on a missing key. */
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
    mem.free(self.evs);
    mem.free(self.levels);
    mem.free(self.plevels);
    self.evs = null;
    self.levels = null;
    self.plevels = null;
    self.nev = self.evcap = self.cap = self.pcap = 0;
    self.depth = self.pdepth = self.ev = 0;
    self.buf.free();
}

/* ---- Decoding scalars ---- */

static bool isSpace(u8 ch)
{
    return ch == ' ' || ch == '\t';
}

/* Strips a '\r' before the line end at `end` */
static i64 lineEnd(u8 *b, i64 i, i64 end)
{
    while (i < end && b[i] != '\n') { i++; }
    return i;
}

/* Several lines of plain text joined: a line break between two lines is a
 * space, each empty line between them a '\n' */
static void decodeFold(u8 *b, i64 i, i64 end, String *buf)
{
    bool any = false;
    i64 breaks = 0;
    while (i < end) {
        i64 e = lineEnd(b, i, end);
        i64 s = i, t = e;
        while (s < t && isSpace(b[s])) { s++; }
        while (t > s && (isSpace(b[t - 1]) || b[t - 1] == '\r')) { t--; }
        if (s == t) {
            breaks++;
        } else {
            if (any) {
                if (!breaks) { buf.appendByte(' '); }
                for (; breaks > 0; breaks--) { buf.appendByte('\n'); }
            }
            buf.append(b[s:t]);
            any = true;
            breaks = 0;
        }
        i = e + 1;
    }
}

static i64 hexDigit(u8 ch)
{
    if ('0' <= ch <= '9') { return ch - '0'; }
    if ('a' <= ch <= 'f') { return ch - 'a' + 10; }
    if ('A' <= ch <= 'F') { return ch - 'A' + 10; }
    return -1;
}

/* n hex digits at b[i], -1 if they aren't */
static i64 hexValue(u8 *b, i64 i, i64 end, i64 n)
{
    if (i + n > end) { return -1; }
    i64 v = 0;
    for (i64 j = 0; j < n; j++) {
        i64 d = hexDigit(b[i + j]);
        if (d < 0) { return -1; }
        v = (v << 4) | d;
    }
    return v;
}

/* The bytes of a \ escape (the letter at b[i]), 0 when it isn't one;
 * *len: how many bytes the escape takes after the '\\' */
static i64 escapeValue(u8 *b, i64 i, i64 end, i64 *len)
{
    *len = 1;
    switch (b[i]) {
        case '0':  return 0x100;   /* NUL: 0 is "not an escape" */
        case 'a':  return 7;
        case 'b':  return 8;
        case 't':  return 9;
        case '\t': return 9;
        case 'n':  return 10;
        case 'v':  return 11;
        case 'f':  return 12;
        case 'r':  return 13;
        case 'e':  return 27;
        case ' ':  return ' ';
        case '"':  return '"';
        case '/':  return '/';
        case '\\': return '\\';
        case 'N':  return 0x85;
        case '_':  return 0xA0;
        case 'L':  return 0x2028;
        case 'P':  return 0x2029;
    }
    i64 n = 0;
    switch (b[i]) {
        case 'x': n = 2;
        case 'u': n = 4;
        case 'U': n = 8;
        default: return 0;
    }
    i64 cp = hexValue(b, i + 1, end, n);
    if (cp < 0 || cp > 0x10FFFF || 0xD800 <= cp <= 0xDFFF) { return 0; }
    *len = n + 1;
    return cp ? cp : 0x100;
}

/* A quoted scalar's text (between its quotes), unescaped and folded */
static void decodeQuoted(u8 *b, i64 i, i64 end, bool dq, String *buf)
{
    i64 keep = 0;   /* buf's bytes before this may not be trimmed */
    while (i < end) {
        u8 ch = b[i];
        if (!dq && ch == '\'') {   /* '' */
            buf.appendByte('\'');
            i += 2;
            continue;
        }
        if (dq && ch == '\\') {
            if (b[i + 1] == '\n' || b[i + 1] == '\r') {   /* an escaped line break */
                i = lineEnd(b, i, end) + 1;
                while (i < end && isSpace(b[i])) { i++; }
                keep = buf.len;
                continue;
            }
            i64 len;
            i64 cp = escapeValue(b, i + 1, end, &len);
            if (cp == 0x100) { cp = 0; }
            u8 utf8[4];
            buf.append(utf8[0:utf8Encode(utf8, cp)]);
            i += 1 + len;
            keep = buf.len;
            continue;
        }
        if (ch == '\n' || ch == '\r') {
            i64 t = buf.len;
            while (t > keep && isSpace(buf.ptr[t - 1])) { t--; }
            if (t < buf.len) { buf.remove(t, buf.len); }
            i = lineEnd(b, i, end) + 1;
            i64 breaks = 0;
            while (true) {
                while (i < end && isSpace(b[i])) { i++; }
                if (i < end && (b[i] == '\n' || b[i] == '\r')) {
                    breaks++;
                    i = lineEnd(b, i, end) + 1;
                    continue;
                }
                break;
            }
            if (!breaks) { buf.appendByte(' '); }
            for (; breaks > 0; breaks--) { buf.appendByte('\n'); }
            keep = buf.len;
            continue;
        }
        buf.appendByte(ch);
        i++;
    }
}

/* A | or > block scalar: its header at b[e.start], its lines up to e.end */
static void decodeBlock(Reader *r, Event *e, String *buf)
{
    u8 *b = r.text.ptr;
    bool folded = b[e.start] == '>';
    u8 chomp = 0;
    for (i64 i = e.start + 1; i < e.start + 3 && i < e.end; i++) {
        if (b[i] == '-' || b[i] == '+') { chomp = b[i]; }
    }
    i64 i = lineEnd(b, e.start, e.end) + 1;
    bool any = false, prevMore = false;
    i64 breaks = 0;   /* line breaks not written yet */
    while (i < e.end) {
        i64 le = lineEnd(b, i, e.end);
        i64 t = le;
        if (t > i && b[t - 1] == '\r') { t--; }
        i64 s = i + e.indent;
        if (s >= t) {   /* an empty line */
            if (le < e.end) { breaks++; }
            i = le + 1;
            continue;
        }
        bool more = isSpace(b[s]);   /* a more-indented line keeps its breaks */
        if (any) {
            if (folded && !more && !prevMore) {
                if (breaks == 1) { buf.appendByte(' '); }
                else { for (i64 j = 1; j < breaks; j++) { buf.appendByte('\n'); } }
            } else {
                for (i64 j = 0; j < breaks; j++) { buf.appendByte('\n'); }
            }
        } else {
            for (i64 j = 0; j < breaks; j++) { buf.appendByte('\n'); }
        }
        buf.append(b[s:t]);
        any = true;
        prevMore = more;
        breaks = le < e.end ? 1 : 0;   /* the one after this line, if it has one */
        i = le + 1;
    }
    /* breaks: the last line's break and the empty lines after it */
    if (chomp == '-') { return; }
    if (chomp == '+') {
        for (; breaks > 0; breaks--) { buf.appendByte('\n'); }
        return;
    }
    if (any && breaks) { buf.appendByte('\n'); }
}

/* Does b[i:end] hold one of `a` or `c`, or a line break? */
static bool hasAny(u8 *b, i64 i, i64 end, u8 a, u8 c)
{
    for (; i < end; i++) {
        u8 ch = b[i];
        if (ch == a || ch == c || ch == '\n' || ch == '\r') { return true; }
    }
    return false;
}

/* The scalar of event e, decoded: a view of the text when it needs no
 * decoding, else of *buf (valid until buf changes) */
u8[] decodeScalar(Reader *r, Event *e, String *buf)
{
    u8 *b = r.text.ptr;
    switch (e.style) {
        case ST_PLAIN:
            return b[e.start:e.end];
        case ST_FOLD:
            buf.clear();
            decodeFold(b, e.start, e.end, buf);
        case ST_SQUOTE:
            if (!hasAny(b, e.start + 1, e.end - 1, '\'', '\'')) { return b[e.start + 1:e.end - 1]; }
            buf.clear();
            decodeQuoted(b, e.start + 1, e.end - 1, false, buf);
        case ST_DQUOTE:
            if (!hasAny(b, e.start + 1, e.end - 1, '\\', '\\')) { return b[e.start + 1:e.end - 1]; }
            buf.clear();
            decodeQuoted(b, e.start + 1, e.end - 1, true, buf);
        default:
            buf.clear();
            decodeBlock(r, e, buf);
    }
    return buf.ptr[0:buf.len];
}

/* ---- Reading ---- */

static void readerParse(Reader *r)
{
    if (r.parsed) { return; }
    r.parsed = true;
    parseDocument(r);
}

/* The next event, null at the end of the events */
static Event *readerCur(Reader *r)
{
    readerParse(r);
    if (r.ev >= r.nev) { return null; }
    return &r.evs[r.ev];
}

/* Throws ERR_DECODE: "line:col: path: what" (no path at the top level);
 * with `field`, the path goes on to it ("ship.zip: missing") */
pub void Reader.fail(u8 *what, u8 *field = null)
{
    i64 at = self.text.len;
    if (self.ev < self.nev) { at = self.evs[self.ev].start; }
    String path;
    defer path.free();
    for (i64 i = 0; i < self.depth; i++) {
        ReaderLevel *l = &self.levels[i];
        if (!l.first) { pathAppend(self, &path, l.object, l.at); }
    }
    if (field) {
        if (path.len) { path.appendByte('.'); }
        path.append(cstr.view(field));
    }
    throwAt(self, at, &path, what);
}

/* The kind of event e, as peek() says it */
static i64 readerKind(Reader *r, Event *e)
{
    switch (e.kind) {
        case EV_MAP: return OBJECT;
        case EV_SEQ: return ARRAY;
        case EV_END: return -1;
    }
    if (e.style != ST_PLAIN) { return STRING; }
    return scalarKind(r.text.ptr[e.start:e.end]);
}

/* What the next value is, for a message */
static u8 *readerGot(Reader *r)
{
    Event *e = readerCur(r);
    if (!e) { return "the end of the input"; }
    if (e.kind == EV_END) {
        return r.evs[e.skip].kind == EV_MAP ? "the end of the mapping" : "the end of the sequence";
    }
    switch (readerKind(r, e)) {
        case OBJECT: return "a mapping";
        case ARRAY:  return "a sequence";
        case STRING: return "a string";
        case INT:    return "a number";
        case FLOAT:  return "a number";
        case BOOL:   return "a bool";
    }
    return "null";
}

static void readerExpected(Reader *r, u8 *want)
{
    String what = str.format("expected %s, got %s", want, readerGot(r));
    defer what.free();
    r.fail(what.cstr());
}

static void readerPush(Reader *r, bool object)
{
    if (r.depth == r.cap) {
        i64 cap = r.cap ? r.cap * 2 : 16;
        r.levels = mem.realloc(r.levels, cap * sizeof(ReaderLevel));
        r.cap = cap;
    }
    ReaderLevel *l = &r.levels[r.depth++];
    l.object = object;
    l.first = true;
    l.at = 0;
    l.value = -1;
}

/* Opens the mapping that is the next value: its entries with nextKey() */
pub void Reader.beginObject()
{
    Event *e = readerCur(self);
    if (!e || e.kind != EV_MAP) { readerExpected(self, "a mapping"); }
    self.ev++;
    readerPush(self, true);
}

/* Opens the sequence that is the next value: its items with nextItem() */
pub void Reader.beginArray()
{
    Event *e = readerCur(self);
    if (!e || e.kind != EV_SEQ) { readerExpected(self, "a sequence"); }
    self.ev++;
    readerPush(self, false);
}

/* Moves to the next entry of the open mapping, reading its key; false
 * when the mapping ends, which closes it */
pub bool Reader.nextKey()
{
    readerParse(self);
    if (!self.depth || !self.levels[self.depth - 1].object) {
        self.fail("nextKey(): no mapping is open");
    }
    ReaderLevel *l = &self.levels[self.depth - 1];
    if (l.value == self.ev) { self.fail("nextKey(): the last key's value wasn't read"); }
    Event *e = &self.evs[self.ev];
    if (e.kind == EV_END) {
        self.ev++;
        self.depth--;
        return false;
    }
    l.at = self.ev;
    l.first = false;
    self.k = decodeScalar(self, e, &self.buf);
    self.ev++;
    l.value = self.ev;
    return true;
}

/* The key nextKey() read, decoded: a view, valid until the next read */
pub u8[] Reader.key()
{
    return self.k;
}

/* Moves to the next item of the open sequence; false when the sequence
 * ends, which closes it */
pub bool Reader.nextItem()
{
    readerParse(self);
    if (!self.depth || self.levels[self.depth - 1].object) {
        self.fail("nextItem(): no sequence is open");
    }
    ReaderLevel *l = &self.levels[self.depth - 1];
    if (l.value == self.ev) { self.fail("nextItem(): the last item wasn't read"); }
    if (self.evs[self.ev].kind == EV_END) {
        self.ev++;
        self.depth--;
        return false;
    }
    if (l.first) { l.first = false; } else { l.at++; }
    l.value = self.ev;
    return true;
}

/* A scalar value as a string: a view, valid until the next read. Any
 * scalar reads as a string, a plain one as it is written (true, 12, null,
 * and nothing at all as "") */
pub u8[] Reader.readStr()
{
    Event *e = readerCur(self);
    if (!e || e.kind != EV_SCALAR) { readerExpected(self, "a string"); }
    u8[] s = decodeScalar(self, e, &self.buf);
    self.ev++;
    return s;
}

/* A string value into `into`, replacing its bytes (its buffer is kept) */
pub void Reader.readString(String *into)
{
    u8[] s = self.readStr();
    into.clear();
    into.append(s);
}

/* A string value in a new mem.alloc block with a NUL after it; its length
 * in *len when len isn't null */
pub u8 *Reader.readBytes(i64 *len = null)
{
    u8[] s = self.readStr();
    u8 *p = mem.alloc(s.len + 1);
    if (s.len) { mem.copy(p, s.ptr, s.len); }
    p[s.len] = 0;
    if (len) { *len = s.len; }
    return p;
}

/* The plain scalar of the next value when it is an integer (its
 * magnitude and sign), else throws: expected `range` for a number out of
 * a u64's range, else `want` */
static void readerInteger(Reader *r, u8 *want, u8 *range, u64 *mag, bool *neg)
{
    Event *e = readerCur(r);
    if (!e || e.kind != EV_SCALAR || e.style != ST_PLAIN) { readerExpected(r, want); }
    u8[] s = r.text.ptr[e.start:e.end];
    switch (intForm(s, mag, neg)) {
        case 0:
            if (scalarKind(s) == FLOAT) {
                String what = str.format("expected %s, got a number", want);
                defer what.free();
                r.fail(what.cstr());
            }
            readerExpected(r, want);
        case 2:
            String what = str.format("expected %s, got a number", range);
            defer what.free();
            r.fail(what.cstr());
    }
}

/* An integer value that fits an i64 (decimal, 0x hex or 0o octal) */
pub i64 Reader.readInt()
{
    u64 v;
    bool neg;
    readerInteger(self, "an integer", "an integer that fits an i64", &v, &neg);
    if (v > (neg ? 0x8000000000000000 : 0x7FFFFFFFFFFFFFFF)) {
        self.fail("expected an integer that fits an i64, got a number");
    }
    self.ev++;
    return neg ? -(i64)v : (i64)v;
}

/* An integer value from 0 to 2^64 - 1 (a u64 past the i64 range) */
pub u64 Reader.readUint()
{
    u64 v;
    bool neg;
    readerInteger(self, "an integer", "an integer from 0 to 2^64 - 1", &v, &neg);
    if (neg && v) { self.fail("expected an integer from 0 to 2^64 - 1, got a number"); }
    self.ev++;
    return v;
}

/* Any number value (also .inf, -.inf and .nan) */
pub f64 Reader.readFloat()
{
    Event *e = readerCur(self);
    if (!e || (readerKind(self, e) != INT && readerKind(self, e) != FLOAT)) { readerExpected(self, "a number"); }
    self.ev++;
    return scalarFloat(self.text.ptr[e.start:e.end]);
}

/* true or false (also True, TRUE, False, FALSE) */
pub bool Reader.readBool()
{
    Event *e = readerCur(self);
    if (!e || readerKind(self, e) != BOOL) { readerExpected(self, "a bool"); }
    self.ev++;
    u8 ch = self.text.ptr[e.start];
    return ch == 't' || ch == 'T';
}

/* Is the next value null (null, ~ or nothing)? Then it is read;
 * otherwise nothing is */
pub bool Reader.isNull()
{
    Event *e = readerCur(self);
    if (e && e.kind == EV_SCALAR && readerKind(self, e) == NULL) {
        self.ev++;
        return true;
    }
    return false;
}

/* The kind of the next value, read nothing: yaml.STRING, INT (an integer
 * that fits an i64), FLOAT (any other number), BOOL, NULL, ARRAY, OBJECT */
pub i64 Reader.peek()
{
    Event *e = readerCur(self);
    if (!e || e.kind == EV_END) { readerExpected(self, "a value"); }
    return readerKind(self, e);
}

/* Reads the next value, whatever it is, and drops it */
pub void Reader.skipValue()
{
    Event *e = readerCur(self);
    if (!e || e.kind == EV_END) { readerExpected(self, "a value"); }
    if (e.kind == EV_MAP || e.kind == EV_SEQ) {
        self.ev = e.skip + 1;
    } else {
        self.ev++;
    }
}

/* After the top-level value: throws unless the document is all read */
pub void Reader.finish()
{
    readerParse(self);
    if (self.depth) { self.fail("finish(): a mapping or sequence is still open"); }
    if (self.ev < self.nev) { readerExpected(self, "the end of the document"); }
}
