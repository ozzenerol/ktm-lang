module http;
import libc, mem, str;

/* Mux patterns, as Go 1.22's ServeMux has them: "[METHOD ]/path", each
 * path segment a literal, {name} (one segment), {name...} (the rest of
 * the path, last only) or {$} (the end, after a trailing slash); a
 * trailing '/' is the whole subtree. A pattern is parsed once, at
 * handle(), into segments that point into its own copy of the text.
 * Matching walks the request path once and allocates nothing. */

pub const i64 ERR_PATTERN = 0x6B746D18;

/* how many named wildcards one pattern may have */
const i64 MAX_WILD = 16;

enum SegKind { lit, wild, multi, end };

/* lit: text is the unescaped literal; wild, multi: the name ("" for the
 * nameless multi of a trailing slash); end: {$} */
struct Seg {
    SegKind kind;
    u8[] text;
};

struct Pattern {
    u8[] text;       /* the pattern as given (our copy) */
    u8[] method;     /* "" = every method */
    Seg *segs;
    i64 nsegs;
    i64 nwild;       /* named wildcards */
    u8 *store;       /* text, then the unescaped literals */
    /* for a quick no while matching */
    bool multi;      /* ends in a {...} (or a trailing '/') */
    i64 mid;         /* method id (M_ANY, M_GET...) */
    i64 c0;          /* the first byte of a first literal segment, -1 if none */
};

/* method ids: the common ones are compared as numbers */
const i64 M_ANY = 0;
const i64 M_GET = 1;
const i64 M_HEAD = 2;
const i64 M_POST = 3;
const i64 M_PUT = 4;
const i64 M_DELETE = 5;
const i64 M_PATCH = 6;
const i64 M_OPTIONS = 7;
const i64 M_OTHER = 8;

static i64 methodId(u8[] m) {
    if (m.len == 0) { return M_ANY; }
    u8 *p = m.ptr;
    switch (p[0]) {
        case 'G': if (m.len == 3 && p[1] == 'E' && p[2] == 'T') { return M_GET; }
        case 'H': if (str.equals(m, "HEAD")) { return M_HEAD; }
        case 'P':
            if (str.equals(m, "POST")) { return M_POST; }
            if (str.equals(m, "PUT")) { return M_PUT; }
            if (str.equals(m, "PATCH")) { return M_PATCH; }
        case 'D': if (str.equals(m, "DELETE")) { return M_DELETE; }
        case 'O': if (str.equals(m, "OPTIONS")) { return M_OPTIONS; }
    }
    return M_OTHER;
}

/* how two patterns relate: the requests p1 matches against p2's */
enum Rel { equivalent, moreGeneral, moreSpecific, overlaps, disjoint };

/* ---- escapes ---- */

static i64 patHex(u8 c) {
    if (c >= '0' && c <= '9') { return c - '0'; }
    if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
}

/* true when s has a %XX and every % starts a valid one (Go's
 * url.PathUnescape fails otherwise, and the mux then uses s as it is) */
static bool hasEscapes(u8[] s) {
    bool any = false;
    for (i64 i = 0; i < s.len; i++) {
        if (s.ptr[i] == '%') {
            if (i + 2 >= s.len) { return false; }
            if (patHex(s.ptr[i + 1]) < 0 || patHex(s.ptr[i + 2]) < 0) { return false; }
            any = true;
            i += 2;
        }
    }
    return any;
}

/* s with its %XX decoded into out (room for s.len); the decoded length.
 * s must pass hasEscapes. */
static i64 unescapeTo(u8[] s, u8 *out) {
    i64 n = 0;
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s.ptr[i];
        if (c == '%') {
            c = (u8)(patHex(s.ptr[i + 1]) * 16 + patHex(s.ptr[i + 2]));
            i += 2;
        }
        out[n] = c;
        n++;
    }
    return n;
}

/* the path segment raw, unescaped, equals lit */
static bool segEq(u8[] raw, u8[] lit) {
    if (raw.len == lit.len && libc.memcmp(raw.ptr, lit.ptr, raw.len) == 0) { return true; }
    if (raw.len < lit.len || !hasEscapes(raw)) { return false; }
    i64 j = 0;
    for (i64 i = 0; i < raw.len; i++) {
        u8 c = raw.ptr[i];
        if (c == '%') {
            c = (u8)(patHex(raw.ptr[i + 1]) * 16 + patHex(raw.ptr[i + 2]));
            i += 2;
        }
        if (j >= lit.len || lit.ptr[j] != c) { return false; }
        j++;
    }
    return j == lit.len;
}

/* ---- parsing ---- */

/* an HTTP token byte (RFC 9110), what a method is made of */
static bool isTokenByte(u8 c) {
    if (c <= ' ' || c >= 127) { return false; }
    u8[] seps = "()<>@,;:\\\"/[]?={}";
    for (i64 i = 0; i < seps.len; i++) { if (seps[i] == c) { return false; } }
    return true;
}

static bool isIdent(u8[] s) {
    if (s.len == 0) { return false; }
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s.ptr[i];
        bool letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
        if (!letter && !(i > 0 && c >= '0' && c <= '9')) { return false; }
    }
    return true;
}

static void badPattern(u8[] text, u8 *why) {
    throw(ERR_PATTERN, "http.Mux.handle: bad pattern \"%s\": %s", text, why);
}

/* text parsed into a Pattern; throws ERR_PATTERN if it isn't one */
static Pattern parsePattern(u8[] text) {
    Pattern p;
    if (text.len == 0) { badPattern(text, "empty pattern"); }
    try {
        parseInto(&p, text);
    } catch (Error e) {
        p.free();
        throw(e);
    }
    p.multi = p.nsegs > 0 && p.segs[p.nsegs - 1].kind == SegKind.multi;
    p.mid = methodId(p.method);
    p.c0 = -1;
    if (p.nsegs > 0 && p.segs[0].kind == SegKind.lit && p.segs[0].text.len > 0) { p.c0 = p.segs[0].text[0]; }
    return p;
}

static void parseInto(Pattern *p, u8[] text) {
    /* the copy of the text, and as much again for unescaped literals */
    p.store = mem.alloc(text.len * 2);
    mem.copy(p.store, text.ptr, text.len);
    p.text = p.store[0:text.len];
    u8 *lits = p.store + text.len;
    i64 nlits = 0;
    u8[] s = p.text;

    i64 sp = 0;
    while (sp < s.len && s[sp] != ' ' && s[sp] != '\t') { sp++; }
    u8[] rest = s;
    if (sp < s.len) {
        p.method = s[0:sp];
        for (i64 i = 0; i < sp; i++) {
            if (!isTokenByte(s[i])) { badPattern(text, "invalid method"); }
        }
        i64 at = sp;
        while (at < s.len && (s[at] == ' ' || s[at] == '\t')) { at++; }
        rest = s[at:];
    }
    if (rest.len == 0 || rest[0] != '/') {
        if (str.contains(rest, "/")) { badPattern(text, "host patterns are not supported: the path must start with '/'"); }
        badPattern(text, "the path must start with '/'");
    }

    /* at most one segment per '/' */
    i64 max = 0;
    for (i64 i = 0; i < rest.len; i++) { if (rest[i] == '/') { max++; } }
    p.segs = mem.allocZero(sizeof(Seg) * max);
    p.nsegs = 0;
    p.nwild = 0;

    while (rest.len > 0) {
        rest = rest[1:];                  /* the '/' */
        Seg *sg = &p.segs[p.nsegs];
        if (rest.len == 0) {              /* trailing slash: the subtree */
            sg.kind = SegKind.multi;
            p.nsegs++;
            break;
        }
        i64 e = 0;
        while (e < rest.len && rest[e] != '/') { e++; }
        u8[] seg = rest[0:e];
        rest = rest[e:];
        i64 brace = str.find(seg, "{");
        if (brace < 0) {
            sg.kind = SegKind.lit;
            if (hasEscapes(seg)) {
                i64 n = unescapeTo(seg, lits + nlits);
                sg.text = (lits + nlits)[0:n];
                nlits += n;
            } else {
                sg.text = seg;
            }
            p.nsegs++;
            continue;
        }
        if (brace != 0) { badPattern(text, "bad wildcard segment (must start with '{')"); }
        if (seg[seg.len - 1] != '}') { badPattern(text, "bad wildcard segment (must end with '}')"); }
        u8[] name = seg[1:seg.len - 1];
        if (str.equals(name, "$")) {
            if (rest.len != 0) { badPattern(text, "{$} not at the end"); }
            sg.kind = SegKind.end;
            p.nsegs++;
            break;
        }
        bool multi = str.endsWith(name, "...");
        if (multi) {
            name = name[0:name.len - 3];
            if (rest.len != 0) { badPattern(text, "{...} wildcard not at the end"); }
        }
        if (name.len == 0) { badPattern(text, "empty wildcard name"); }
        if (!isIdent(name)) { badPattern(text, "bad wildcard name"); }
        for (i64 k = 0; k < p.nsegs; k++) {
            if (p.segs[k].kind != SegKind.lit && str.equals(p.segs[k].text, name)) {
                badPattern(text, "duplicate wildcard name");
            }
        }
        if (p.nwild == MAX_WILD) { badPattern(text, "more than 16 wildcards"); }
        sg.kind = multi ? SegKind.multi : SegKind.wild;
        sg.text = name;
        p.nwild++;
        p.nsegs++;
    }
}

void Pattern.free() {
    mem.free(self.segs);
    mem.free(self.store);
    self.segs = null;
    self.store = null;
}

bool Pattern.endsInMulti() {
    return self.multi;
}

/* ---- matching ---- */

/* request method m (id mid) is one the pattern takes (GET takes HEAD too) */
bool Pattern.takesMethod(u8[] m, i64 mid) {
    if (self.mid == mid) { return mid != M_OTHER || str.equals(self.method, m); }
    return self.mid == M_ANY || (self.mid == M_GET && mid == M_HEAD);
}

/* path (raw, starting with '/') matches the pattern's path. spans, if
 * not null, gets the start and end of each named wildcard's value in
 * path, in order (2 * nwild i64s). Segments are split at the raw '/'s
 * and compared unescaped, as Go does. */
bool Pattern.matchPath(u8[] path, i64 *spans) {
    u8 *base = path.ptr;
    u8 *end = base + path.len;
    if (base == end || *base != '/') { return false; }
    u8 *at = base;           /* *at is a '/' */
    i64 w = 0;
    Seg *sg = self.segs;
    for (i64 k = 0; k < self.nsegs; k++) {
        if (at >= end) { return false; }
        if (sg.kind == SegKind.multi) {
            if (sg.text.len > 0 && spans) {
                spans[w] = at + 1 - base;
                spans[w + 1] = path.len;
            }
            return true;
        }
        u8 *b = at + 1;
        if (b == end) {          /* only the trailing "/" left */
            if (sg.kind != SegKind.end) { return false; }
            at = end;
            sg++;
            continue;
        }
        u8 *e = libc.memchr(b, '/', end - b);
        if (!e) { e = end; }
        if (sg.kind == SegKind.lit) {
            i64 n = e - b;
            if (n != sg.text.len || libc.memcmp(b, sg.text.ptr, n) != 0) {
                if (!segEq(b[0:n], sg.text)) { return false; }
            }
        } else if (sg.kind == SegKind.wild) {
            if (spans) {
                spans[w] = b - base;
                spans[w + 1] = e - base;
            }
            w += 2;
        } else {
            return false;        /* {$} before the end */
        }
        at = e;
        sg++;
    }
    return at == end;
}

/* the match of path is exact: not through a trailing multi, unless that
 * multi matched nothing (Go's exactMatch, which decides the redirect of
 * /dir to /dir/) */
bool Pattern.exactFor(u8[] path, i64 slashes) {
    if (!self.multi) { return true; }
    if (path.len > 0 && path.ptr[path.len - 1] != '/') { return false; }
    return self.nsegs == slashes;
}

/* ---- precedence (Go's routing rules) ---- */

static Rel inverse(Rel r) {
    if (r == Rel.moreGeneral) { return Rel.moreSpecific; }
    if (r == Rel.moreSpecific) { return Rel.moreGeneral; }
    return r;
}

static Rel combine(Rel r1, Rel r2) {
    if (r1 == Rel.equivalent) { return r2; }
    if (r1 == Rel.disjoint) { return Rel.disjoint; }
    if (r1 == Rel.overlaps) { return r2 == Rel.disjoint ? Rel.disjoint : Rel.overlaps; }
    /* moreGeneral, moreSpecific */
    if (r2 == Rel.equivalent) { return r1; }
    if (r2 == inverse(r1)) { return Rel.overlaps; }
    return r2;
}

static Rel compareMethods(u8[] m1, u8[] m2) {
    if (str.equals(m1, m2)) { return Rel.equivalent; }
    if (m1.len == 0) { return Rel.moreGeneral; }
    if (m2.len == 0) { return Rel.moreSpecific; }
    if (str.equals(m1, "GET") && str.equals(m2, "HEAD")) { return Rel.moreGeneral; }
    if (str.equals(m2, "GET") && str.equals(m1, "HEAD")) { return Rel.moreSpecific; }
    return Rel.disjoint;
}

static Rel compareSegs(Seg *s1, Seg *s2) {
    bool m1 = s1.kind == SegKind.multi, m2 = s2.kind == SegKind.multi;
    if (m1 && m2) { return Rel.equivalent; }
    if (m1) { return Rel.moreGeneral; }
    if (m2) { return Rel.moreSpecific; }
    bool w1 = s1.kind == SegKind.wild, w2 = s2.kind == SegKind.wild;
    if (w1 && w2) { return Rel.equivalent; }
    /* a {name} never matches the trailing slash {$} stands for */
    if (w1) { return s2.kind == SegKind.end ? Rel.disjoint : Rel.moreGeneral; }
    if (w2) { return s1.kind == SegKind.end ? Rel.disjoint : Rel.moreSpecific; }
    if (s1.kind != s2.kind) { return Rel.disjoint; }
    if (s1.kind == SegKind.end || str.equals(s1.text, s2.text)) { return Rel.equivalent; }
    return Rel.disjoint;
}

static Rel comparePaths(Pattern *p1, Pattern *p2) {
    bool multi1 = p1.endsInMulti(), multi2 = p2.endsInMulti();
    if (p1.nsegs != p2.nsegs && !multi1 && !multi2) { return Rel.disjoint; }
    Rel rel = Rel.equivalent;
    i64 k = 0;
    for (; k < p1.nsegs && k < p2.nsegs; k++) {
        rel = combine(rel, compareSegs(&p1.segs[k], &p2.segs[k]));
        if (rel == Rel.disjoint) { return rel; }
    }
    if (k == p1.nsegs && k == p2.nsegs) { return rel; }
    if (p1.nsegs < p2.nsegs && multi1) { return combine(rel, Rel.moreGeneral); }
    if (p2.nsegs < p1.nsegs && multi2) { return combine(rel, Rel.moreSpecific); }
    return Rel.disjoint;
}

static Rel comparePatterns(Pattern *p1, Pattern *p2) {
    Rel r1 = compareMethods(p1.method, p2.method);
    if (r1 == Rel.disjoint) { return Rel.disjoint; }
    return combine(r1, comparePaths(p1, p2));
}

/* ---- path cleaning (Go's cleanPath: path.Clean, trailing slash kept) ---- */

/* path needs cleaning: it has "//", "/./", "/../" or ends in "/." or
 * "/.."; *slashes gets its '/'s (counted while it looks) */
static bool needsClean(u8[] path, i64 *slashes) {
    i64 n = 0;
    u8 *p = path.ptr;
    u8 *end = p + path.len;
    while (p < end) {
        u8 *q = libc.memchr(p, '/', end - p);
        if (!q) { break; }
        n++;
        p = q + 1;
        if (p == end) { break; }
        u8 c = *p;
        if (c == '/') { return true; }
        if (c == '.') {
            if (p + 1 == end || p[1] == '/') { return true; }
            if (p[1] == '.' && (p + 2 == end || p[2] == '/')) { return true; }
        }
    }
    *slashes = n;
    return false;
}

/* the cleaned path written to out (room for path.len + 1); its length */
static i64 cleanTo(u8[] p, u8 *out) {
    i64 n = 0;
    out[n] = '/';
    n++;
    i64 r = 0;
    i64 n0 = n;              /* where ".." may back up to */
    while (r < p.len) {
        if (p.ptr[r] == '/') {
            r++;
        } else if (p.ptr[r] == '.' && (r + 1 == p.len || p.ptr[r + 1] == '/')) {
            r++;
        } else if (p.ptr[r] == '.' && p.ptr[r + 1] == '.' && (r + 2 == p.len || p.ptr[r + 2] == '/')) {
            r += 2;
            if (n > n0) {
                n--;
                while (n > n0 && out[n] != '/') { n--; }
            }
        } else {
            if (n != 1) {
                out[n] = '/';
                n++;
            }
            while (r < p.len && p.ptr[r] != '/') {
                out[n] = p.ptr[r];
                n++;
                r++;
            }
        }
    }
    if (p.len > 0 && p.ptr[p.len - 1] == '/' && n != 1) {
        out[n] = '/';
        n++;
    }
    return n;
}
