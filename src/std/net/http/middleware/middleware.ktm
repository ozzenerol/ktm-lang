module middleware;
import atomic, date, libc, mem, net.http, str, uuid;

/* Middleware for net.http (v0.21.0): request IDs, the client's real
 * address behind a proxy, CORS, rate limiting and bearer-token auth.
 * Each one is added to a Mux (or a group) by a call here:
 *
 *   http.Mux mux;
 *   middleware.realIp(&mux, "10.0.0.0/8");         // first: the others see the client
 *   middleware.requestId(&mux);
 *   middleware.CorsOptions cors;
 *   cors.origins = "https://app.example.com";
 *   middleware.cors(&mux, &cors);
 *   middleware.rateLimit(&mux, 100, 60);           // 100 requests a minute per client
 *
 *   http.Mux *api = mux.group("/api");
 *   middleware.bearer(api, &checkToken);           // 401 without a good token
 *
 * A throw in a handler already becomes a 500 (and is logged) without any
 * middleware. Time limits are per operation: http.Options' read and idle
 * limits, the client's timeoutMs, a database's statement timeout. */

/* ---- request IDs ---- */

static bool idSafe(u8[] s)
{
    if (!s.len || s.len > 128) { return false; }
    for (i64 i = 0; i < s.len; i++) {
        u8 c = s[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
                  c == '-' || c == '_' || c == '.' || c == ':';
        if (!ok) { return false; }
    }
    return true;
}

static bool requestIdRun(http.Writer *w, http.Request *r)
{
    u8[] id = r.header("X-Request-Id");
    if (!idSafe(id)) {
        u8 *buf = r.alloc(40);
        uuid.v7().format(buf);
        id = buf[0:36];
    }
    u8[] *keep = r.alloc(sizeof(u8[]));
    *keep = id;
    r.set("requestId", keep);
    w.header("X-Request-Id", id);
    return true;
}

/* Every request gets an ID: the client's X-Request-Id when it sent a
 * sane one (up to 128 letters, digits, - _ . :), else a new UUIDv7; it
 * is in the reply's X-Request-Id and in requestIdOf(r), for logs. */
pub void requestId(http.Mux *m)
{
    m.use(&requestIdRun);
}

/* The request's ID ("" without the requestId middleware) */
pub u8[] requestIdOf(http.Request *r)
{
    u8[] *id = r.get("requestId");
    return id ? *id : "";
}

/* ---- the real client address ---- */

struct Net {
    i32 family;
    u8 addr[16];
    i64 bits;
};

const i64 MAX_TRUSTED = 32;

struct RealIp {
    i64 n;
    Net nets[MAX_TRUSTED];
};

/* text as an address (no prefix) into a: false when it isn't one */
static bool parseIp(u8[] text, i32 *family, u8 *addr)
{
    u8 buf[64];
    if (!text.len || text.len >= 64) { return false; }
    mem.copy(buf, text.ptr, text.len);
    buf[text.len] = 0;
    if (libc.inet_pton(libc.AF_INET, buf, addr) == 1) {
        *family = libc.AF_INET;
        return true;
    }
    if (libc.inet_pton(libc.AF_INET6, buf, addr) == 1) {
        /* an IPv4-mapped IPv6 address (::ffff:10.0.0.1) is the IPv4 one */
        bool mapped = true;
        for (i64 i = 0; i < 10; i++) { if (addr[i]) { mapped = false; } }
        if (mapped && addr[10] == 0xFF && addr[11] == 0xFF) {
            for (i64 i = 0; i < 4; i++) { addr[i] = addr[12 + i]; }
            *family = libc.AF_INET;
            return true;
        }
        *family = libc.AF_INET6;
        return true;
    }
    return false;
}

static bool inNet(Net *n, i32 family, u8 *addr)
{
    if (n.family != family) { return false; }
    i64 bits = n.bits;
    for (i64 i = 0; bits > 0; i++) {
        u8 mask = bits >= 8 ? 0xFF : (u8)(0xFF << (8 - bits));
        if ((n.addr[i] & mask) != (addr[i] & mask)) { return false; }
        bits -= 8;
    }
    return true;
}

static bool trusted(RealIp *t, u8[] text)
{
    i32 family;
    u8 addr[16];
    if (!parseIp(str.trim(text), &family, addr)) { return false; }
    for (i64 i = 0; i < t.n; i++) {
        if (inNet(&t.nets[i], family, addr)) { return true; }
    }
    return false;
}

static bool realIpRun(http.Writer *w, http.Request *r, void *ctx)
{
    RealIp *t = ctx;
    if (!trusted(t, r.remoteAddr())) { return true; }
    u8[] xff = r.header("X-Forwarded-For");
    if (!xff.len) { return true; }
    /* from the right: the first address that isn't a trusted proxy */
    u8[] pick = "";
    u8[] rest = xff;
    while (rest.len) {
        i64 comma = -1;
        for (i64 i = rest.len - 1; i >= 0; i--) {
            if (rest[i] == ',') {
                comma = i;
                break;
            }
        }
        u8[] one = str.trim(comma < 0 ? rest : rest[comma + 1:]);
        rest = comma < 0 ? rest[0:0] : rest[0:comma];
        i32 family;
        u8 addr[16];
        if (!parseIp(one, &family, addr)) { break; }
        pick = one;
        if (!trusted(t, one)) { break; }
    }
    if (pick.len) { r.setRemoteAddr(pick); }
    return true;
}

/* Behind a proxy or load balancer, r.remoteAddr() is the proxy's. When
 * the request comes from one of trustedProxies ("10.0.0.0/8, 127.0.0.1,
 * ::1": addresses and CIDR ranges), the client is the right-most address
 * of X-Forwarded-For that isn't a trusted proxy, and remoteAddr says so
 * from then on (rate limits and logs see the client). Requests from
 * anywhere else keep their address: X-Forwarded-For can't be faked
 * past it. Add it first. Throws http.ERR_HTTP for a list it can't read. */
pub void realIp(http.Mux *m, u8[] trustedProxies)
{
    RealIp *t = mem.allocZero(sizeof(RealIp));
    u8[] rest = trustedProxies;
    while (rest.len) {
        i64 comma = str.find(rest, ",");
        u8[] one = str.trim(comma < 0 ? rest : rest[0:comma]);
        rest = comma < 0 ? rest[rest.len:] : rest[comma + 1:];
        if (!one.len) { continue; }
        if (t.n == MAX_TRUSTED) {
            mem.free(t);
            throw(http.ERR_HTTP, "middleware.realIp: more than %d trusted proxies", MAX_TRUSTED);
        }
        Net *n = &t.nets[t.n];
        i64 slash = str.find(one, "/");
        u8[] ip = slash < 0 ? one : one[0:slash];
        if (!parseIp(ip, &n.family, n.addr)) {
            mem.free(t);
            throw(http.ERR_HTTP, "middleware.realIp: `%s` isn't an address or a range", one);
        }
        i64 max = n.family == libc.AF_INET ? 32 : 128;
        n.bits = max;
        if (slash >= 0 && (!str.toInt(one[slash + 1:], &n.bits) || n.bits < 0 || n.bits > max)) {
            mem.free(t);
            throw(http.ERR_HTTP, "middleware.realIp: `%s` has a bad prefix length", one);
        }
        t.n++;
    }
    m.useWith(&realIpRun, t, true);
}

/* ---- CORS ---- */

/* Which other sites' pages may call the API from a browser */
pub struct CorsOptions {
    u8[] origins;       /* "https://a.example, https://b.example", or "*" for any */
    u8[] methods;       /* "": GET, POST, PUT, PATCH, DELETE, HEAD */
    u8[] headers;       /* request headers allowed; "": whatever the browser asks for */
    u8[] expose;        /* response headers the page may read ("X-Request-Id") */
    bool credentials;   /* cookies and Authorization (not with "*": each origin is named back) */
    i64 maxAge;         /* seconds a browser may keep a preflight's answer (0: 600) */
};

struct Cors {
    CorsOptions o;      /* its texts are copies, after the struct */
};

static u8[] copyText(u8 **at, u8[] s)
{
    u8 *p = *at;
    if (s.len) { mem.copy(p, s.ptr, s.len); }
    *at = p + s.len;
    return p[0:s.len];
}

static bool originAllowed(Cors *c, u8[] origin)
{
    if (str.equals(str.trim(c.o.origins), "*")) { return true; }
    u8[] rest = c.o.origins;
    while (rest.len) {
        i64 comma = str.find(rest, ",");
        u8[] one = str.trim(comma < 0 ? rest : rest[0:comma]);
        rest = comma < 0 ? rest[rest.len:] : rest[comma + 1:];
        if (one.len == origin.len && libc.strncasecmp(one.ptr, origin.ptr, one.len) == 0) { return true; }
    }
    return false;
}

static bool corsRun(http.Writer *w, http.Request *r, void *ctx)
{
    Cors *c = ctx;
    u8[] origin = r.header("Origin");
    if (!origin.len) { return true; }
    bool preflight = str.equals(r.method, "OPTIONS") && r.hasHeader("Access-Control-Request-Method");
    w.header("Vary", "Origin");
    if (!originAllowed(c, origin)) {
        if (preflight) {
            w.error(403, "origin not allowed");
            return false;
        }
        return true;        /* no CORS headers: the browser keeps the answer from the page */
    }
    bool any = str.equals(str.trim(c.o.origins), "*") && !c.o.credentials;
    u8[] allowed = origin;
    if (any) { allowed = "*"; }
    w.header("Access-Control-Allow-Origin", allowed);
    if (c.o.credentials) { w.header("Access-Control-Allow-Credentials", "true"); }
    if (!preflight) {
        if (c.o.expose.len) { w.header("Access-Control-Expose-Headers", c.o.expose); }
        return true;
    }
    u8[] methods = "GET, POST, PUT, PATCH, DELETE, HEAD";
    if (c.o.methods.len) { methods = c.o.methods; }
    w.header("Access-Control-Allow-Methods", methods);
    u8[] asked = r.header("Access-Control-Request-Headers");
    u8[] allow = c.o.headers.len ? c.o.headers : asked;
    if (allow.len) { w.header("Access-Control-Allow-Headers", allow); }
    u8 age[24];
    i64 n = libc.snprintf(age, sizeof(age), "%lld", c.o.maxAge > 0 ? c.o.maxAge : 600);
    w.header("Access-Control-Max-Age", age[0:n]);
    w.status(204);
    return false;           /* answered: the preflight needs no route */
}

/* Answers browsers' CORS checks (preflight OPTIONS requests included) for
 * the origins in o, and adds the headers to the replies of allowed
 * origins. Add it on the Mux the server serves, not a group: a preflight
 * is answered before routing. o is copied. */
pub void cors(http.Mux *m, CorsOptions *o)
{
    if (!o.origins.len) { throw(http.ERR_HTTP, "middleware.cors: no origins (\"*\" allows any)"); }
    i64 n = o.origins.len + o.methods.len + o.headers.len + o.expose.len;
    Cors *c = mem.allocZero(sizeof(Cors) + n);
    u8 *at = (u8 *)c + sizeof(Cors);
    c.o.origins = copyText(&at, o.origins);
    c.o.methods = copyText(&at, o.methods);
    c.o.headers = copyText(&at, o.headers);
    c.o.expose = copyText(&at, o.expose);
    c.o.credentials = o.credentials;
    c.o.maxAge = o.maxAge;
    m.useWith(&corsRun, c, true);
}

/* ---- rate limiting ---- */

const i64 RL_SHARDS = 64;
const i64 RL_SLOTS = 128;      /* per shard: 8192 clients tracked at once */
const i64 RL_PROBE = 8;

struct Bucket {
    u8 key[48];
    i64 keyLen;
    f64 tokens;
    i64 lastNs;
};

struct Limiter {
    f64 burst;          /* requests */
    f64 perNs;          /* tokens a nanosecond */
    i64 locks[RL_SHARDS];
    Bucket slots[RL_SHARDS * RL_SLOTS];
};

static u64 keyHash(u8[] s)
{
    u64 h = 1469598103934665603;
    for (i64 i = 0; i < s.len; i++) {
        h ^= s[i];
        h *= 1099511628211;
    }
    return h;
}

/* One token for key: true, or false with the seconds until the next one */
static bool take(Limiter *l, u8[] key, i64 *retry, i64 *left)
{
    u64 h = keyHash(key);
    i64 shard = (i64)(h % RL_SHARDS);
    Bucket *base = &l.slots[shard * RL_SLOTS];
    i64 start = (i64)((h / RL_SHARDS) % RL_SLOTS);
    i64 now = date.monoNs();
    while (!atomic.cas(&l.locks[shard], 0, 1)) { }
    Bucket *b = null, *oldest = null;
    for (i64 i = 0; i < RL_PROBE; i++) {
        Bucket *s = &base[(start + i) % RL_SLOTS];
        if (s.keyLen == key.len && libc.memcmp(s.key, key.ptr, key.len) == 0) {
            b = s;
            break;
        }
        if (!oldest || s.lastNs < oldest.lastNs) { oldest = s; }
    }
    if (!b) {
        /* a new client takes the least recently seen slot */
        b = oldest;
        mem.copy(b.key, key.ptr, key.len);
        b.keyLen = key.len;
        b.tokens = l.burst;
        b.lastNs = now;
    }
    f64 tokens = b.tokens + (f64)(now - b.lastNs) * l.perNs;
    if (tokens > l.burst) { tokens = l.burst; }
    b.lastNs = now;
    bool ok = tokens >= 1.0;
    if (ok) { tokens -= 1.0; }
    b.tokens = tokens;
    atomic.store(&l.locks[shard], 0);
    *left = (i64)tokens;
    if (!ok) {
        f64 ns = (1.0 - tokens) / l.perNs;
        *retry = (i64)(ns / 1e9) + 1;
    }
    return ok;
}

static bool rateLimitRun(http.Writer *w, http.Request *r, void *ctx)
{
    Limiter *l = ctx;
    u8[] key = r.remoteAddr();
    if (!key.len || key.len > 48) { return true; }
    i64 retry = 0, left = 0;
    bool ok = take(l, key, &retry, &left);
    u8 num[24];
    i64 n = libc.snprintf(num, sizeof(num), "%lld", (i64)l.burst);
    w.header("RateLimit-Limit", num[0:n]);
    n = libc.snprintf(num, sizeof(num), "%lld", left);
    w.header("RateLimit-Remaining", num[0:n]);
    if (ok) { return true; }
    n = libc.snprintf(num, sizeof(num), "%lld", retry);
    w.header("Retry-After", num[0:n]);
    w.error(429, "too many requests");
    return false;
}

/* At most `requests` requests every `seconds` seconds per client address
 * (r.remoteAddr(): add realIp before it behind a proxy), as a token
 * bucket: a quiet client may send `requests` at once. Past it: 429 with
 * Retry-After. Shared by every thread of the server; one per Mux or group
 * (a strict one on a login group, a looser one on the whole API). */
pub void rateLimit(http.Mux *m, i64 requests, i64 seconds)
{
    if (requests < 1 || seconds < 1) { throw(http.ERR_HTTP, "middleware.rateLimit: requests and seconds are 1 or more"); }
    Limiter *l = mem.allocZero(sizeof(Limiter));
    if (!l) { throw(http.ERR_HTTP, "middleware.rateLimit: out of memory"); }
    l.burst = (f64)requests;
    l.perNs = (f64)requests / ((f64)seconds * 1e9);
    m.useWith(&rateLimitRun, l, true);
}

/* ---- bearer tokens ---- */

/* Is token good? It may r.set what it found (the user) for the handler */
pub type TokenCheck = fn(u8[] token, http.Request *r) bool;

struct Bearer {
    TokenCheck check;
};

static bool bearerRun(http.Writer *w, http.Request *r, void *ctx)
{
    Bearer *b = ctx;
    u8[] auth = str.trim(r.header("Authorization"));
    u8[] token = "";
    if (auth.len > 7 && libc.strncasecmp(auth.ptr, "bearer ", 7) == 0) { token = str.trim(auth[7:]); }
    if (token.len && b.check(token, r)) { return true; }
    if (token.len) {
        w.header("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    } else {
        w.header("WWW-Authenticate", "Bearer");
    }
    w.error(401, "unauthorized");
    return false;
}

/* Requests need `Authorization: Bearer <token>` that check accepts; else
 * 401 with WWW-Authenticate. check can r.set the user it found, for the
 * handlers (r.get). On a group, it guards the group's routes only. */
pub void bearer(http.Mux *m, TokenCheck check)
{
    if (!check) { throw(http.ERR_HTTP, "middleware.bearer: the check is null"); }
    Bearer *b = mem.allocZero(sizeof(Bearer));
    b.check = check;
    m.useWith(&bearerRun, b, true);
}
