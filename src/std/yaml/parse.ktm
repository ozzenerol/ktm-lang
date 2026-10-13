module yaml;
import mem, str;

/* The parser behind Reader: turns the text into its list of events, or
 * throws ERR_DECODE at the first thing it can't read */

/* "line:col: path: what" for byte `at`, from the open levels' keys and
 * item indexes (given as a list of (object, key event or item index)) */
void throwAt(Reader *r, i64 at, String *path, u8 *what)
{
    u8 *b = r.text.ptr;
    if (at > r.text.len) { at = r.text.len; }
    i64 line = 1, col = 1;
    for (i64 i = 0; i < at; i++) {
        if (b[i] == '\n') { line++; col = 1; } else { col++; }
    }
    if (path.len) {
        throw(ERR_DECODE, "%d:%d: %s: %s", line, col, *path, what);
    }
    throw(ERR_DECODE, "%d:%d: %s", line, col, what);
}

/* Appends a mapping's key (event ev) or a sequence index to path */
void pathAppend(Reader *r, String *path, bool object, i64 at)
{
    if (object) {
        String tmp;
        defer tmp.free();
        if (path.len) { path.appendByte('.'); }
        path.append(decodeScalar(r, &r.evs[at], &tmp));
    } else {
        path.appendFormat("[%d]", at);
    }
}

static void parseFail(Reader *r, i64 at, u8 *what)
{
    String path;
    defer path.free();
    for (i64 i = 0; i < r.pdepth; i++) {
        ParseLevel *l = &r.plevels[i];
        if (l.object && l.key >= 0) { pathAppend(r, &path, true, l.key); }
        if (!l.object && l.items) { pathAppend(r, &path, false, l.items - 1); }
    }
    r.nev = 0;   /* nothing to read after this */
    throwAt(r, at, &path, what);
}

static i64 emit(Reader *r, u8 kind, u8 style, i64 start, i64 end)
{
    if (r.nev == r.evcap) {
        i64 cap = r.evcap ? r.evcap * 2 : 64;
        r.evs = mem.realloc(r.evs, cap * sizeof(Event));
        r.evcap = cap;
    }
    Event *e = &r.evs[r.nev];
    e.kind = kind;
    e.style = style;
    e.start = start;
    e.end = end;
    e.indent = 0;
    e.skip = 0;
    return r.nev++;
}

/* An empty value (null) at byte at */
static void emitEmpty(Reader *r, i64 at)
{
    emit(r, EV_SCALAR, ST_PLAIN, at, at);
}

static void openLevel(Reader *r, bool object, i64 at)
{
    if (r.pdepth >= MAX_DEPTH) { parseFail(r, at, "mappings and sequences nested deeper than yaml.MAX_DEPTH"); }
    if (r.pdepth == r.pcap) {
        i64 cap = r.pcap ? r.pcap * 2 : 16;
        r.plevels = mem.realloc(r.plevels, cap * sizeof(ParseLevel));
        r.pcap = cap;
    }
    ParseLevel *l = &r.plevels[r.pdepth++];
    l.object = object;
    l.ev = emit(r, object ? EV_MAP : EV_SEQ, 0, at, at);
    l.key = -1;
    l.items = 0;
}

static void closeLevel(Reader *r)
{
    ParseLevel *l = &r.plevels[--r.pdepth];
    i64 end = emit(r, EV_END, 0, r.pos, r.pos);
    r.evs[l.ev].skip = end;
    r.evs[end].skip = l.ev;
}

static u8 byteAt(Reader *r, i64 i)
{
    if (i >= r.text.len) { return 0; }
    return r.text.ptr[i];
}

/* The end of the input, a space or a line break */
static bool blankAt(Reader *r, i64 i)
{
    u8 ch = byteAt(r, i);
    return !ch || ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r';
}

static bool eolAt(Reader *r, i64 i)
{
    u8 ch = byteAt(r, i);
    return !ch || ch == '\n' || ch == '\r';
}

static i64 colOf(Reader *r, i64 p)
{
    i64 i = p;
    while (i > 0 && r.text.ptr[i - 1] != '\n') { i--; }
    return p - i;
}

static void skipSpaces(Reader *r)
{
    while (r.pos < r.text.len && isSpace(r.text.ptr[r.pos])) { r.pos++; }
}

/* Past spaces, comments and line breaks to the next thing to read; a
 * tab in front of it on its line is an error */
static void skipBlank(Reader *r)
{
    bool newline = false;
    while (r.pos < r.text.len) {
        u8 ch = r.text.ptr[r.pos];
        if (isSpace(ch)) { r.pos++; continue; }
        if (ch == '#') {
            while (!eolAt(r, r.pos)) { r.pos++; }
            continue;
        }
        if (ch == '\n' || ch == '\r') {
            r.pos++;
            newline = true;
            continue;
        }
        break;
    }
    if (newline && r.pos < r.text.len) {
        for (i64 i = r.pos - colOf(r, r.pos); i < r.pos; i++) {
            if (r.text.ptr[i] == '\t') { parseFail(r, i, "a tab in the indentation (YAML indents with spaces)"); }
        }
    }
}

/* After a value on its line: only spaces and a comment may follow */
static void lineDone(Reader *r)
{
    skipSpaces(r);
    if (byteAt(r, r.pos) == '#') {
        while (!eolAt(r, r.pos)) { r.pos++; }
    }
    if (!eolAt(r, r.pos)) { parseFail(r, r.pos, "unexpected text after the value"); }
}

/* --- or ... at the start of a line */
static bool docMarker(Reader *r, i64 p, u8 ch)
{
    return colOf(r, p) == 0 && byteAt(r, p) == ch && byteAt(r, p + 1) == ch && byteAt(r, p + 2) == ch && blankAt(r, p + 3);
}

static bool docEnd(Reader *r)
{
    return r.pos >= r.text.len || docMarker(r, r.pos, '-') || docMarker(r, r.pos, '.');
}

/* Where the quoted scalar at p ends (after its closing quote), on its
 * line only when line is set; -1 when it doesn't */
static i64 quotedEnd(Reader *r, i64 p, bool line)
{
    u8 *b = r.text.ptr;
    u8 q = b[p];
    i64 i = p + 1, n = r.text.len;
    while (i < n) {
        u8 ch = b[i];
        if (line && (ch == '\n' || ch == '\r')) { return -1; }
        if (q == '\'' && ch == '\'') {
            if (byteAt(r, i + 1) != '\'') { return i + 1; }
            i += 2;
            continue;
        }
        if (q == '"' && ch == '\\') {
            i += 2;
            continue;
        }
        if (q == '"' && ch == '"') { return i + 1; }
        i++;
    }
    return -1;
}

/* Is the text at r.pos a key ("key: ...", on this line)? */
static bool isKey(Reader *r)
{
    u8 *b = r.text.ptr;
    i64 i = r.pos;
    if (b[i] == '"' || b[i] == '\'') {
        i = quotedEnd(r, i, true);
        if (i < 0) { return false; }
        while (isSpace(byteAt(r, i))) { i++; }
        return byteAt(r, i) == ':' && blankAt(r, i + 1);
    }
    for (; !eolAt(r, i); i++) {
        if (b[i] == ':' && blankAt(r, i + 1)) { return true; }
        if (b[i] == '#' && i > r.pos && isSpace(b[i - 1])) { return false; }
    }
    return false;
}

/* What can't start a value, or isn't supported */
static void checkStart(Reader *r)
{
    u8 ch = byteAt(r, r.pos);
    switch (ch) {
        case '&': parseFail(r, r.pos, "anchors (&) are not supported");
        case '*': parseFail(r, r.pos, "aliases (*) are not supported");
        case '!': parseFail(r, r.pos, "tags (!) are not supported");
        case '@': parseFail(r, r.pos, "'@' is reserved: quote a value that starts with it");
        case '`': parseFail(r, r.pos, "'`' is reserved: quote a value that starts with it");
        case '?':
            if (blankAt(r, r.pos + 1)) { parseFail(r, r.pos, "complex keys (?) are not supported"); }
    }
}

/* A quoted scalar at r.pos; its escapes are checked */
static void parseQuoted(Reader *r)
{
    i64 start = r.pos;
    i64 end = quotedEnd(r, start, false);
    if (end < 0) { parseFail(r, start, "a quoted string with no closing quote"); }
    u8 *b = r.text.ptr;
    if (b[start] == '"') {
        for (i64 i = start + 1; i < end - 1; i++) {
            if (b[i] != '\\') { continue; }
            u8 nx = b[i + 1];
            if (nx == '\n' || nx == '\r') { continue; }
            i64 len;
            if (!escapeValue(b, i + 1, end - 1, &len)) { parseFail(r, i, "an unknown or malformed escape in a string"); }
            i += len;
        }
    }
    emit(r, EV_SCALAR, b[start] == '"' ? ST_DQUOTE : ST_SQUOTE, start, end);
    r.pos = end;
}

/* The end of a plain scalar's text on the line from i (before a comment,
 * ": ", a line break and trailing spaces); *comment: a comment ends it.
 * In [] and {} it also ends at , [ ] { } and ':' before one of them. */
static i64 plainLine(Reader *r, i64 i, bool flow, bool *comment)
{
    u8 *b = r.text.ptr;
    i64 from = i;
    *comment = false;
    for (; !eolAt(r, i); i++) {
        u8 ch = b[i];
        if (ch == ':' && blankAt(r, i + 1)) { break; }
        if (ch == '#' && i > from && isSpace(b[i - 1])) {
            *comment = true;
            break;
        }
        if (flow) {
            if (ch == ',' || ch == '[' || ch == ']' || ch == '{' || ch == '}') { break; }
            u8 nx = byteAt(r, i + 1);
            if (ch == ':' && (nx == ',' || nx == '[' || nx == ']' || nx == '{' || nx == '}')) { break; }
        }
    }
    while (i > from && (isSpace(b[i - 1]) || b[i - 1] == '\r')) { i--; }
    return i;
}

/* A plain scalar in block context at r.pos; it goes on over the next
 * lines that are indented more than its parent */
static void parsePlain(Reader *r, i64 parent)
{
    u8 *b = r.text.ptr;
    i64 n = r.text.len;
    i64 start = r.pos;
    u8 ch = b[start];
    if (ch == ',' || ch == ']' || ch == '}') { parseFail(r, start, "unexpected character"); }
    bool comment;
    i64 end = plainLine(r, start, false, &comment);
    u8 style = ST_PLAIN;
    i64 i = end;
    while (!comment) {
        while (!eolAt(r, i)) { i++; }   /* to the line break */
        /* the next line that isn't empty */
        i64 line = i;
        while (line < n) {
            line++;   /* past '\n' or '\r' */
            if (line < n && b[line - 1] == '\r' && b[line] == '\n') { line++; }
            i64 j = line;
            while (j < n && b[j] == ' ') { j++; }
            if (j < n && (b[j] == '\n' || b[j] == '\r')) {
                line = j;
                continue;
            }
            if (j >= n || j - line <= parent || b[j] == '#' || b[j] == '\t' || docMarker(r, j, '-') || docMarker(r, j, '.')) {
                line = -1;
            } else {
                line = j;
            }
            break;
        }
        if (line < 0 || line >= n) { break; }
        i64 save = r.pos;
        r.pos = line;
        if (isKey(r)) { parseFail(r, line, "a key inside a plain scalar that goes on over several lines (quote the value or indent the key less)"); }
        r.pos = save;
        end = plainLine(r, line, false, &comment);
        style = ST_FOLD;
        i = end;
    }
    emit(r, EV_SCALAR, style, start, end);
    r.pos = end;
    lineDone(r);
}

/* A | or > block scalar at r.pos, in a parent indented `parent` */
static void parseBlockScalar(Reader *r, i64 parent)
{
    u8 *b = r.text.ptr;
    i64 n = r.text.len;
    i64 start = r.pos;
    i64 explicit = 0;
    r.pos++;
    for (i64 k = 0; k < 2; k++) {
        u8 ch = byteAt(r, r.pos);
        if (ch == '-' || ch == '+') { r.pos++; }
        else if ('1' <= ch <= '9') {
            explicit = ch - '0';
            r.pos++;
        }
    }
    if (!blankAt(r, r.pos)) { parseFail(r, r.pos, "expected a line break after the block scalar's | or >"); }
    lineDone(r);
    if (parent < 0) { parent = -1; }
    i64 indent = -1;
    if (explicit) { indent = (parent < 0 ? 0 : parent) + explicit; }
    /* the lines: empty ones, and those indented at least `indent` (the
     * first line with text sets it when it isn't explicit) */
    i64 line = r.pos;
    if (line < n) {
        line++;
        if (b[line - 1] == '\r' && line < n && b[line] == '\n') { line++; }
    }
    i64 end = line;
    while (line < n) {
        i64 j = line;
        while (j < n && b[j] == ' ') { j++; }
        i64 le = j;
        while (le < n && b[le] != '\n') { le++; }
        bool empty = j >= n || b[j] == '\n' || b[j] == '\r';
        if (!empty) {
            if (indent < 0) {
                if (j - line <= parent) { break; }
                indent = j - line;
            }
            if (j - line < indent) { break; }
            if (j - line == 0 && (docMarker(r, j, '-') || docMarker(r, j, '.'))) { break; }
        }
        line = le < n ? le + 1 : n;
        end = line;
    }
    if (indent < 0) { indent = parent + 1; }
    i64 ev = emit(r, EV_SCALAR, b[start] == '>' ? ST_FOLDED : ST_LITERAL, start, end);
    r.evs[ev].indent = indent;
    r.pos = end;
}

static void parseFlow(Reader *r);

/* Past spaces, line breaks and comments inside [] or {} */
static void flowSkip(Reader *r)
{
    u8 *b = r.text.ptr;
    while (r.pos < r.text.len) {
        u8 ch = b[r.pos];
        if (ch == ' ' || ch == '\t' || ch == '\n' || ch == '\r') { r.pos++; continue; }
        if (ch == '#' && (!r.pos || isSpace(b[r.pos - 1]) || b[r.pos - 1] == '\n')) {
            while (!eolAt(r, r.pos)) { r.pos++; }
            continue;
        }
        break;
    }
}

/* A scalar inside [] or {}; false when there is none here */
static bool flowScalar(Reader *r)
{
    checkStart(r);
    u8 ch = byteAt(r, r.pos);
    if (ch == '"' || ch == '\'') {
        parseQuoted(r);
        return true;
    }
    if (ch == '|' || ch == '>') { parseFail(r, r.pos, "a block scalar (| or >) inside [] or {}"); }
    bool comment;
    i64 end = plainLine(r, r.pos, true, &comment);
    if (end == r.pos) { return false; }
    emit(r, EV_SCALAR, ST_PLAIN, r.pos, end);
    r.pos = end;
    return true;
}

static void flowValue(Reader *r)
{
    u8 ch = byteAt(r, r.pos);
    if (ch == '[' || ch == '{') {
        parseFlow(r);
        return;
    }
    if (!flowScalar(r)) {
        if (!ch) { parseFail(r, r.pos, "the text ends inside [] or {}"); }
        parseFail(r, r.pos, "expected a value");
    }
}

/* A [ sequence ] or { mapping } at r.pos */
static void parseFlow(Reader *r)
{
    i64 start = r.pos;
    bool object = byteAt(r, start) == '{';
    u8 closer = object ? '}' : ']';
    openLevel(r, object, start);
    ParseLevel *l = &r.plevels[r.pdepth - 1];
    r.pos++;
    bool first = true;
    while (true) {
        flowSkip(r);
        u8 ch = byteAt(r, r.pos);
        if (r.pos >= r.text.len) {
            parseFail(r, start, object ? "a { with no closing }" : "a [ with no closing ]");
        }
        if (ch == closer) { break; }
        if (!first) {
            if (ch != ',') { parseFail(r, r.pos, object ? "expected ',' or '}'" : "expected ',' or ']'"); }
            r.pos++;
            flowSkip(r);
            if (byteAt(r, r.pos) == closer) { break; }
        }
        first = false;
        l = &r.plevels[r.pdepth - 1];
        if (!object) {
            l.items++;
            flowValue(r);
            flowSkip(r);
            if (byteAt(r, r.pos) == ':') { parseFail(r, r.pos, "a key: value pair inside [] is not supported"); }
            continue;
        }
        ch = byteAt(r, r.pos);
        if (ch == '[' || ch == '{') { parseFail(r, r.pos, "a [] or {} as a key is not supported"); }
        i64 keyAt = r.pos;
        if (!flowScalar(r)) { parseFail(r, r.pos, "expected a key"); }
        l = &r.plevels[r.pdepth - 1];
        l.key = r.nev - 1;
        u8[] keyText = r.text.ptr[keyAt:r.pos];
        if (str.equals(keyText, "<<")) { parseFail(r, keyAt, "merge keys (<<) are not supported"); }
        flowSkip(r);
        if (byteAt(r, r.pos) == ':') {
            r.pos++;
            flowSkip(r);
            ch = byteAt(r, r.pos);
            if (ch == ',' || ch == '}') { emitEmpty(r, r.pos); }
            else { flowValue(r); }
        } else {
            emitEmpty(r, r.pos);
        }
    }
    r.pos++;
    closeLevel(r);
}

static void parseNode(Reader *r, i64 parent, bool afterKey);

/* The value of a key or an item whose text starts on the next line (or
 * there is none: null) */
static void parseNextLine(Reader *r, i64 parent, bool seqAtParent)
{
    skipBlank(r);
    if (docEnd(r)) {
        emitEmpty(r, r.pos);
        return;
    }
    i64 c = colOf(r, r.pos);
    if (c > parent) {
        parseNode(r, parent, false);
    } else if (seqAtParent && c == parent && byteAt(r, r.pos) == '-' && blankAt(r, r.pos + 1)) {
        parseNode(r, parent - 1, false);   /* a sequence as a key's value, not indented */
    } else {
        emitEmpty(r, r.pos);
    }
}

/* A block mapping whose keys are at column m, the first at r.pos */
static void parseMap(Reader *r, i64 m)
{
    openLevel(r, true, r.pos);
    while (true) {
        checkStart(r);
        u8 ch = byteAt(r, r.pos);
        if (ch == '[' || ch == '{') { parseFail(r, r.pos, "a [] or {} as a key is not supported"); }
        if (ch == '-' && blankAt(r, r.pos + 1)) { parseFail(r, r.pos, "expected a key, got a sequence item"); }
        if (!isKey(r)) { parseFail(r, r.pos, "expected a key (key: value)"); }
        i64 keyAt = r.pos;
        if (ch == '"' || ch == '\'') {
            parseQuoted(r);
            skipSpaces(r);
        } else {
            i64 e = r.pos;
            while (!(byteAt(r, e) == ':' && blankAt(r, e + 1))) { e++; }
            i64 end = e;
            while (end > keyAt && isSpace(r.text.ptr[end - 1])) { end--; }
            if (str.equals(r.text.ptr[keyAt:end], "<<")) { parseFail(r, keyAt, "merge keys (<<) are not supported"); }
            emit(r, EV_SCALAR, ST_PLAIN, keyAt, end);
            r.pos = e;
        }
        r.plevels[r.pdepth - 1].key = r.nev - 1;
        r.pos++;   /* the ':' */
        skipSpaces(r);
        u8 nx = byteAt(r, r.pos);
        if (eolAt(r, r.pos) || nx == '#') {
            parseNextLine(r, m, true);
        } else {
            parseNode(r, m, true);
        }
        skipBlank(r);
        if (docEnd(r)) { break; }
        i64 c = colOf(r, r.pos);
        if (c < m) { break; }
        if (c > m) { parseFail(r, r.pos, "bad indentation: expected a key in line with the ones above"); }
    }
    closeLevel(r);
}

/* A block sequence whose "- " are at column s, the first at r.pos */
static void parseSeq(Reader *r, i64 s)
{
    openLevel(r, false, r.pos);
    while (true) {
        r.plevels[r.pdepth - 1].items++;
        r.pos++;   /* the '-' */
        skipSpaces(r);
        if (eolAt(r, r.pos) || byteAt(r, r.pos) == '#') {
            parseNextLine(r, s, false);
        } else {
            parseNode(r, s, false);
        }
        skipBlank(r);
        if (docEnd(r)) { break; }
        i64 c = colOf(r, r.pos);
        if (c == s && byteAt(r, r.pos) == '-' && blankAt(r, r.pos + 1)) { continue; }
        if (c > s) { parseFail(r, r.pos, "bad indentation: expected a '- ' in line with the ones above"); }
        break;
    }
    closeLevel(r);
}

/* The value at r.pos, in a parent indented `parent`; afterKey: on the
 * same line as its key */
static void parseNode(Reader *r, i64 parent, bool afterKey)
{
    checkStart(r);
    i64 c = colOf(r, r.pos);
    u8 ch = byteAt(r, r.pos);
    if (ch == '-' && blankAt(r, r.pos + 1)) {
        if (afterKey) { parseFail(r, r.pos, "a sequence can't start on the same line as its key"); }
        parseSeq(r, c);
        return;
    }
    if (ch == '[' || ch == '{') {
        parseFlow(r);
        skipSpaces(r);
        if (byteAt(r, r.pos) == ':') { parseFail(r, r.pos, "a [] or {} as a key is not supported"); }
        lineDone(r);
        return;
    }
    if (ch == '|' || ch == '>') {
        parseBlockScalar(r, parent);
        return;
    }
    if (isKey(r)) {
        if (afterKey) { parseFail(r, r.pos, "a mapping can't start on the same line as its key"); }
        parseMap(r, c);
        return;
    }
    if (ch == '"' || ch == '\'') {
        parseQuoted(r);
        lineDone(r);
        return;
    }
    parsePlain(r, parent);
}

/* The whole document into r's events */
void parseDocument(Reader *r)
{
    u8 *b = r.text.ptr;
    if (!str.validUtf8(r.text)) { parseFail(r, 0, "the text isn't UTF-8"); }
    if (r.text.len >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF) { r.pos = 3; }
    skipBlank(r);
    if (byteAt(r, r.pos) == '%' && !colOf(r, r.pos)) { parseFail(r, r.pos, "directives (%YAML, %TAG) are not supported"); }
    if (docMarker(r, r.pos, '-')) {
        r.pos += 3;
        skipSpaces(r);
        if (eolAt(r, r.pos) || byteAt(r, r.pos) == '#') { skipBlank(r); }
    }
    if (docEnd(r)) {
        emitEmpty(r, r.pos);
    } else {
        parseNode(r, -1, false);
    }
    skipBlank(r);
    if (docMarker(r, r.pos, '.')) {
        r.pos += 3;
        skipBlank(r);
    }
    if (r.pos < r.text.len) {
        if (docMarker(r, r.pos, '-')) { parseFail(r, r.pos, "several documents (a second ---) are not supported"); }
        parseFail(r, r.pos, "expected the end of the document");
    }
}
