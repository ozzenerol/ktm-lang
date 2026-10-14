module http;
import libc, mem, str;

/* Middleware: runs before a handler (mux.use); false stops the request
 * there, with the reply the middleware wrote in w. */
pub type Middleware = fn(Writer *w, Request *r) bool;

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
    private Middleware *mws;  /* mux.use, in order */
    private i64 nmws;
    private i64 mwcap;
    private Mux *parent;    /* a group's parent; null for the Mux the server serves */
    private u8 *prefix;     /* a group's whole prefix ("/api/v1", no trailing '/'), NUL-ended */
    private i64 prefixLen;
    private Mux **groups;   /* the groups made from this Mux: freed with it */
    private i64 ngroups;
    private i64 gcap;
};

struct MuxRoute {
    Pattern p;
    Handler h;
    Mux *group;    /* the group it was added through, null if none */
    u8 *dir;       /* mux.files: the directory (own copy, NUL-ended); else null */
    i64 dirLen;
};

/* Adds a route. pattern is "[METHOD ]/path": "GET /users/{id}",
 * "/static/" (a subtree), "POST /files/{path...}", "/{$}" (only "/").
 * Throws ERR_PATTERN for a pattern that isn't one, or one that
 * conflicts with a route already there. */
pub void Mux.handle(u8[] pattern, Handler h)
{
    if (!h) { throw(ERR_PATTERN, "http.Mux.handle: \"%s\": the handler is null", pattern); }
    muxAdd(self, pattern, h, "", false);
}

/* The Mux at the top of a group's chain: the one that holds the routes */
static Mux *muxRoot(Mux *m)
{
    while (m.parent) { m = m.parent; }
    return m;
}

/* pattern added through m (a group's prefix put in front of its path),
 * to the routes of m's root; dir: a mux.files route */
static void muxAdd(Mux *m, u8[] pattern, Handler h, u8[] dir, bool isFiles)
{
    if (!m.parent) {
        muxInsert(m, pattern, h, null, dir, isFiles);
        return;
    }
    /* "[METHOD ]/path" -> "[METHOD ]<prefix>/path" */
    i64 at = 0;
    while (at < pattern.len && pattern[at] != '/') { at++; }
    i64 n = pattern.len + m.prefixLen;
    u8 *full = mem.alloc(n);
    defer mem.free(full);
    mem.copy(full, pattern.ptr, at);
    mem.copy(full + at, m.prefix, m.prefixLen);
    mem.copy(full + at + m.prefixLen, pattern.ptr + at, pattern.len - at);
    muxInsert(muxRoot(m), full[0:n], h, m, dir, isFiles);
}

static void muxInsert(Mux *m, u8[] pattern, Handler h, Mux *group, u8[] dir, bool isFiles)
{
    Pattern p = parsePattern(pattern);
    i64 at = m.len;
    for (i64 i = 0; i < m.len; i++) {
        Pattern *q = &m.routes[i].p;
        Rel rel = comparePatterns(&p, q);
        if (rel == Rel.equivalent || rel == Rel.overlaps) {
            p.free();
            throw(ERR_PATTERN, "http.Mux.handle: pattern \"%s\" conflicts with pattern \"%s\": %s",
                  pattern, q.text, rel == Rel.equivalent ? "they match the same requests"
                  : "some requests match both and neither is more specific");
        }
        if (rel == Rel.moreSpecific && at == m.len) { at = i; }
    }
    if (m.len == m.cap) {
        i64 cap = m.cap ? m.cap * 2 : 8;
        m.routes = mem.realloc(m.routes, cap * sizeof(MuxRoute));
        m.cap = cap;
    }
    libc.memmove(&m.routes[at + 1], &m.routes[at], (m.len - at) * sizeof(MuxRoute));
    MuxRoute *rt = &m.routes[at];
    mem.set(rt, 0, sizeof(MuxRoute));
    rt.p = p;
    rt.h = h;
    rt.group = group;
    if (isFiles) {
        rt.dir = mem.alloc(dir.len + 1);
        mem.copy(rt.dir, dir.ptr, dir.len);
        rt.dir[dir.len] = 0;
        rt.dirLen = dir.len;
    }
    m.len++;
    if (p.multi) { m.subtrees |= (u64)1 << (p.nsegs < 63 ? p.nsegs : 63); }
}

/* Adds a middleware: every request the Mux serves (404s and 405s too)
 * goes through its middlewares in the order they were added, then the
 * route's handler. On a group: only the group's routes, after the
 * middlewares of the Mux (and the groups) it was made from. */
pub void Mux.use(Middleware f)
{
    if (!f) { throw(ERR_PATTERN, "http.Mux.use: the middleware is null"); }
    if (self.nmws == self.mwcap) {
        self.mwcap = self.mwcap ? self.mwcap * 2 : 4;
        self.mws = mem.realloc(self.mws, self.mwcap * sizeof(Middleware));
    }
    self.mws[self.nmws] = f;
    self.nmws++;
}

/* A group of routes under prefix ("/api"): its handle("GET /users", h)
 * adds "GET /api/users" to this Mux, and its use() is for its routes
 * only. A group can have groups. It belongs to the Mux: freed by its
 * free(), never by you. */
pub Mux *Mux.group(u8[] prefix)
{
    if (prefix.len == 0 || prefix[0] != '/') {
        throw(ERR_PATTERN, "http.Mux.group: prefix \"%s\" must start with '/'", prefix);
    }
    if (str.contains(prefix, " ") || str.contains(prefix, "\t")) {
        throw(ERR_PATTERN, "http.Mux.group: prefix \"%s\" has a space", prefix);
    }
    i64 n = prefix.len;
    while (n > 0 && prefix[n - 1] == '/') { n--; }
    Mux *g = mem.allocZero(sizeof(Mux));
    g.parent = self;
    g.prefixLen = self.prefixLen + n;
    g.prefix = mem.alloc(g.prefixLen + 1);
    if (self.prefixLen) { mem.copy(g.prefix, self.prefix, self.prefixLen); }
    mem.copy(g.prefix + self.prefixLen, prefix.ptr, n);
    g.prefix[g.prefixLen] = 0;
    if (self.ngroups == self.gcap) {
        self.gcap = self.gcap ? self.gcap * 2 : 4;
        self.groups = mem.realloc(self.groups, self.gcap * sizeof(Mux *));
    }
    self.groups[self.ngroups] = g;
    self.ngroups++;
    return g;
}

/* Never called: mux.files routes are served by fileServe */
static void filesRoute(Writer *w, Request *r) { }

/* Serves the files under dir at prefix ("/static/", which must end in
 * '/'): GET prefix{path...}. See fileServe (files.ktm) for what it does. */
pub void Mux.files(u8[] prefix, u8[] dir)
{
    if (prefix.len == 0 || prefix[0] != '/' || prefix[prefix.len - 1] != '/') {
        throw(ERR_PATTERN, "http.Mux.files: prefix \"%s\" must start and end with '/'", prefix);
    }
    if (dir.len == 0) { throw(ERR_PATTERN, "http.Mux.files: the directory is empty"); }
    i64 dl = dir.len;
    while (dl > 1 && dir[dl - 1] == '/') { dl--; }
    u8[] tail = "{path...}";
    i64 n = 4 + prefix.len + tail.len;
    u8 *pat = mem.alloc(n);
    defer mem.free(pat);
    mem.copy(pat, "GET ", 4);
    mem.copy(pat + 4, prefix.ptr, prefix.len);
    mem.copy(pat + 4 + prefix.len, tail.ptr, tail.len);
    muxAdd(self, pat[0:n], &filesRoute, dir[0:dl], true);
}

/* The middlewares of g's chain, outermost group first (the top Mux's
 * already ran); false if one stopped the request */
static bool groupMws(Mux *g, Writer *w, Request *r)
{
    if (!g.parent) { return true; }
    if (!groupMws(g.parent, w, r)) { return false; }
    for (i64 i = 0; i < g.nmws; i++) {
        if (!g.mws[i](w, r)) { return false; }
    }
    return true;
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
    if (self.parent) {
        muxRoot(self).serve(w, r);
        return;
    }
    /* a throw is answered here as the server would (400, 422, 500), so a
     * test through a Mux sees the same reply */
    try {
        muxServe(self, w, r);
    } catch (Error e) {
        answerThrow(w, r, e);
    }
}

static void muxServe(Mux *m, Writer *w, Request *r)
{
    for (i64 i = 0; i < m.nmws; i++) {
        if (!m.mws[i](w, r)) { return; }
    }
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
    MuxRoute *rt = muxFind(m, r.method, mid, path, slashes, spans);
    i64 want = slashes + 1;
    if ((!rt || !rt.p.exactFor(path, slashes)) && path.len && path.ptr[path.len - 1] != '/' &&
        (m.subtrees & ((u64)1 << (want < 63 ? want : 63)))) {
        /* a subtree route that path + "/" matches exactly: redirect there */
        u8 *b = r.alloc(path.len + 1);
        mem.copy(b, path.ptr, path.len);
        b[path.len] = '/';
        u8[] slashed = b[0:path.len + 1];
        MuxRoute *sub = muxFind(m, r.method, mid, slashed, want, null);
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
        muxNoRoute(m, w, r, path);
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
    if (rt.group && !groupMws(rt.group, w, r)) { return; }
    if (rt.dir) {
        fileServe(w, r, rt.dir[0:rt.dirLen]);
        return;
    }
    rt.h(w, r);
}

/* Everything m allocated: its routes, middlewares, groups and prefix */
static void muxFree(Mux *m)
{
    for (i64 i = 0; i < m.len; i++) {
        m.routes[i].p.free();
        mem.free(m.routes[i].dir);
    }
    mem.free(m.routes);
    for (i64 i = 0; i < m.ngroups; i++) {
        muxFree(m.groups[i]);
        mem.free(m.groups[i]);
    }
    mem.free(m.groups);
    mem.free(m.mws);
    mem.free(m.prefix);
    mem.set(m, 0, sizeof(Mux));
}

/* Frees the routes, middlewares and groups; the Mux is empty again.
 * Nothing on a group: it is freed with the Mux it was made from. */
pub void Mux.free()
{
    if (self.parent) { return; }
    muxFree(self);
}
