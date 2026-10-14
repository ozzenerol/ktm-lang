module http;
import libc, mem, str;

/* Routes requests to handlers by method and path, as Go 1.22's
 * ServeMux does. Declare one (`http.Mux mux;`: no init call), add
 * routes with mux.handle, give it to http.listen. Patterns are in
 * route.ktm; the routes are kept so that a more specific pattern comes
 * before a more general one, and since two patterns that could match
 * the same request and are equally specific are refused, the first
 * route that matches is the most specific. */
pub struct Mux {
    private MuxRoute *routes;
    private i64 len;
    private i64 cap;
    private u64 subtrees;   /* bit n: a route ending in {...} has n segments (63: 63 or more) */
};

struct MuxRoute {
    Pattern p;
    Handler h;
};

/* Adds a route. pattern is "[METHOD ]/path": "GET /users/{id}",
 * "/static/" (a subtree), "POST /files/{path...}", "/{$}" (only "/").
 * Throws ERR_PATTERN for a pattern that isn't one, or one that
 * conflicts with a route already there. */
pub void Mux.handle(u8[] pattern, Handler h)
{
    if (!h) { throw(ERR_PATTERN, "http.Mux.handle: \"%s\": the handler is null", pattern); }
    Pattern p = parsePattern(pattern);
    i64 at = self.len;
    for (i64 i = 0; i < self.len; i++) {
        Pattern *q = &self.routes[i].p;
        Rel rel = comparePatterns(&p, q);
        if (rel == Rel.equivalent || rel == Rel.overlaps) {
            p.free();
            throw(ERR_PATTERN, "http.Mux.handle: pattern \"%s\" conflicts with pattern \"%s\": %s",
                  pattern, q.text, rel == Rel.equivalent ? "they match the same requests"
                  : "some requests match both and neither is more specific");
        }
        if (rel == Rel.moreSpecific && at == self.len) { at = i; }
    }
    if (self.len == self.cap) {
        i64 cap = self.cap ? self.cap * 2 : 8;
        self.routes = mem.realloc(self.routes, cap * sizeof(MuxRoute));
        self.cap = cap;
    }
    libc.memmove(&self.routes[at + 1], &self.routes[at], (self.len - at) * sizeof(MuxRoute));
    self.routes[at].p = p;
    self.routes[at].h = h;
    self.len++;
    if (p.multi) { self.subtrees |= (u64)1 << (p.nsegs < 63 ? p.nsegs : 63); }
}

static i64 slashCount(u8[] path)
{
    i64 n = 0;
    for (i64 i = 0; i < path.len; i++) { if (path.ptr[i] == '/') { n++; } }
    return n;
}

/* The first (most specific) route for the method (id mid) and path
 * (with `slashes` '/'s), null if none. Quick noes first: a path has one
 * segment per '/' and a pattern without a final {...} as many; the
 * first literal's first byte; the method. */
static MuxRoute *muxFind(Mux *m, u8[] method, i64 mid, u8[] path, i64 slashes, i64 *spans)
{
    i64 c1 = path.len > 1 ? path.ptr[1] : -1;
    if (c1 == '%') { c1 = -2; }        /* escaped: no quick no on it */
    for (i64 i = 0; i < m.len; i++) {
        MuxRoute *rt = &m.routes[i];
        Pattern *p = &rt.p;
        if (p.multi) {
            if (p.nsegs > slashes) { continue; }
        } else if (p.nsegs != slashes) {
            continue;
        }
        if (p.c0 >= 0 && p.c0 != c1 && c1 != -2) { continue; }
        if (!p.takesMethod(method, mid)) { continue; }
        if (p.matchPath(path, spans)) { return rt; }
    }
    return null;
}

/* A route p can match the path, whatever the method */
static bool pathFits(Pattern *p, u8[] path, i64 slashes)
{
    if (p.multi ? p.nsegs > slashes : p.nsegs != slashes) { return false; }
    return p.matchPath(path, null);
}

/* A 301 to path, the query kept */
static void muxRedirect(Writer *w, Request *r, u8[] path)
{
    u8[] to = path;
    if (r.rawQuery.len) {
        u8 *b = r.alloc(path.len + 1 + r.rawQuery.len);
        mem.copy(b, path.ptr, path.len);
        b[path.len] = '?';
        mem.copy(b + path.len + 1, r.rawQuery.ptr, r.rawQuery.len);
        to = b[0:path.len + 1 + r.rawQuery.len];
    }
    w.redirect(to, 301);
}

/* A route for some other method matches path: 405 with an Allow header
 * of those methods (sorted, HEAD where GET is); otherwise 404 */
static void muxNoRoute(Mux *m, Writer *w, Request *r, u8[] path)
{
    i64 slashes = slashCount(path);
    i64 room = 0;
    for (i64 i = 0; i < m.len; i++) {
        Pattern *p = &m.routes[i].p;
        if (p.method.len && pathFits(p, path, slashes)) {
            room += p.method.len + 2 + (str.equals(p.method, "GET") ? 6 : 0);
        }
    }
    if (room == 0) {
        w.error(404, "not found");
        return;
    }
    u8 *b = r.alloc(room);
    i64 n = 0;
    u8[] last = "";
    while (true) {
        /* the smallest method after last */
        u8[] next = "";
        for (i64 i = 0; i < m.len; i++) {
            Pattern *p = &m.routes[i].p;
            if (!p.method.len || !pathFits(p, path, slashes)) { continue; }
            u8[] c = p.method;
            for (i64 k = 0; k < 2; k++) {
                if (k == 1) {
                    if (!str.equals(p.method, "GET")) { break; }
                    c = "HEAD";
                }
                if ((last.len == 0 || str.compare(c, last) > 0) &&
                    (next.len == 0 || str.compare(c, next) < 0)) {
                    next = c;
                }
            }
        }
        if (next.len == 0) { break; }
        if (n) {
            b[n] = ',';
            b[n + 1] = ' ';
            n += 2;
        }
        mem.copy(b + n, next.ptr, next.len);
        n += next.len;
        last = next;
    }
    w.header("Allow", b[0:n]);
    w.error(405, "method not allowed");
}

/* Answers r: the most specific route's handler, with the path's
 * wildcards set as r.param(name) (unescaped). Like Go: a path that isn't
 * clean ("//", "/./", "/../") gets a 301 to the clean one, "/dir" a 301
 * to "/dir/" when only the subtree "/dir/" (or "/dir/{x...}") would
 * match it; no route 404, a route for other methods only 405 + Allow. */
pub void Mux.serve(Writer *w, Request *r)
{
    u8[] path = r.path;
    i64 slashes = 0;
    bool unclean = needsClean(path, &slashes);
    if (unclean) {
        u8 *b = r.alloc(path.len + 2);
        path = b[0:cleanTo(path, b)];
        slashes = slashCount(path);
    }
    i64 mid = methodId(r.method);
    i64 spans[2 * MAX_WILD];
    MuxRoute *rt = muxFind(self, r.method, mid, path, slashes, spans);
    i64 want = slashes + 1;
    if ((!rt || !rt.p.exactFor(path, slashes)) && path.len && path.ptr[path.len - 1] != '/' &&
        (self.subtrees & ((u64)1 << (want < 63 ? want : 63)))) {
        /* a subtree route that path + "/" matches exactly: redirect there */
        u8 *b = r.alloc(path.len + 1);
        mem.copy(b, path.ptr, path.len);
        b[path.len] = '/';
        u8[] slashed = b[0:path.len + 1];
        MuxRoute *sub = muxFind(self, r.method, mid, slashed, want, null);
        if (sub && sub.p.exactFor(slashed, want)) {
            muxRedirect(w, r, slashed);
            return;
        }
    }
    if (unclean) {
        muxRedirect(w, r, path);
        return;
    }
    if (!rt) {
        muxNoRoute(self, w, r, path);
        return;
    }
    i64 k = 0;
    for (i64 i = 0; i < rt.p.nsegs; i++) {
        Seg *sg = &rt.p.segs[i];
        if (sg.kind == SegKind.lit || sg.kind == SegKind.end || sg.text.len == 0) { continue; }
        u8[] v = path[spans[k]:spans[k + 1]];
        if (hasEscapes(v)) {
            u8 *b = r.alloc(v.len);
            v = b[0:unescapeTo(v, b)];
        }
        r.setParam(sg.text, v);
        k += 2;
    }
    rt.h(w, r);
}

/* Frees the routes; the Mux is empty again */
pub void Mux.free()
{
    for (i64 i = 0; i < self.len; i++) { self.routes[i].p.free(); }
    mem.free(self.routes);
    self.routes = null;
    self.len = 0;
    self.cap = 0;
    self.subtrees = 0;
}
