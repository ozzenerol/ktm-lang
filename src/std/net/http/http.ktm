module http;
import cstr, json, libc, log, mem, net, reflect, str, validate;

/* HTTP/1.1 servers for REST APIs, the Go way:
 *
 *   void hello(http.Writer *w, http.Request *r) { w.text(200, "hello %s\n", r.param("name")); }
 *
 *   http.Mux mux;
 *   mux.handle("GET /hello/{name}", &hello);
 *   http.listen(8080, &mux);
 *
 * A handler gets the request and a Writer for the reply. What the module
 * hands it (the method, path, headers, query values, path parameters, the
 * body, values from http.decode) is freed by the module when the handler
 * returns: nothing in a handler needs freeing unless the handler itself
 * allocated it. A handler that throws gets a 500 reply (and a log line), a
 * json.ERR_DECODE gets a 400 with the decode message, and http.decodeValid's
 * validate.ERR_INVALID a 422 with the field errors as JSON. */

/* A function that answers one request */
pub type Handler = fn(Writer *w, Request *r) void;

/* Headers a request may carry; more gets a 431 reply */
pub const i64 MAX_HEADERS = 64;

/* Path parameters ({name}) a pattern may have */
pub const i64 MAX_PARAMS = 16;

/* Values a request carries from middleware to its handler (r.set) */
pub const i64 MAX_VALUES = 8;

struct RequestValue {
    u8[] key;
    void *ptr;
    i64 n;
};

/* A request header or a path parameter: views into the request */
pub struct Header {
    u8[] name;
    u8[] value;
};

/* Memory that lives until the request is answered: big blocks carved up,
 * freed together. The first block is kept for the next request. */
struct ArenaBlock;
struct ArenaBlock {
    ArenaBlock *next;
    i64 cap;
};

struct Arena {
    u8 *ptr;          /* the current block's free room */
    i64 left;
    ArenaBlock *head; /* every block, newest first */
};

const i64 ARENA_FIRST = 4096;

static void *arenaAlloc(Arena *a, i64 n)
{
    n = (n + 15) & ~15;
    if (n > a.left) {
        i64 cap = a.head ? a.head.cap * 2 : ARENA_FIRST;
        if (cap < n + 64) { cap = n + 64; }
        ArenaBlock *b = mem.alloc(cap);
        if (!b) { throw(ERR_HTTP, "http: out of memory"); }
        b.cap = cap;
        b.next = a.head;
        a.head = b;
        a.ptr = (u8 *)b + 32;
        a.left = cap - 32;
    }
    u8 *p = a.ptr;
    a.ptr += n;
    a.left -= n;
    return p;
}

/* Back to empty: keeps the newest (biggest) block */
static void arenaReset(Arena *a)
{
    ArenaBlock *b = a.head;
    if (!b) { return; }
    ArenaBlock *old = b.next;
    while (old) {
        ArenaBlock *n = old.next;
        mem.free(old);
        old = n;
    }
    b.next = null;
    a.ptr = (u8 *)b + 32;
    a.left = b.cap - 32;
}

static void arenaFree(Arena *a)
{
    ArenaBlock *b = a.head;
    while (b) {
        ArenaBlock *n = b.next;
        mem.free(b);
        b = n;
    }
    a.head = null;
    a.ptr = null;
    a.left = 0;
}

/* The code the module throws for its own failures (out of memory, a bad
 * listen call); handlers can throw any code */
pub const i64 ERR_HTTP = 0x6B746D20;

/* Values http.decode filled: freed when the request is answered */
const i64 MAX_CLEANUPS = 8;

/* One request. Its fields are read-only; the u8[] views (and everything
 * the methods return) are valid until the handler returns: copy what
 * must live longer (str.from). */
pub struct Request {
    u8[] method;       /* "GET", "POST", ... as sent */
    u8[] path;         /* "/users/7": the target before any '?', not unescaped */
    u8[] rawQuery;     /* "page=2&q=a%20b": after the '?', not unescaped */
    u8[] body;         /* the whole body (a chunked one already joined) */
    void *app;         /* what http.listen was given: the program's own state */
    private i32 peerFd;        /* the connection's socket + 1 (0: none) */
    private i64 peerLen;       /* the client's address in peerIp: -1 not looked up, 0 none */
    private u8 peerIp[48];
    private i32 peerPort;
    i64 minor;         /* HTTP/1.minor */
    bool keepAlive;    /* the connection stays open after the reply */
    i64 nheaders;
    Header headers[MAX_HEADERS];
    i64 nparams;
    Header params[MAX_PARAMS];
    i64 nvalues;
    RequestValue values[MAX_VALUES];
    i64 contentLength; /* -1 when not given */
    bool chunked;
    bool expectContinue;
    Arena arena;
    i64 ncleanups;
    reflect.Value cleanups[MAX_CLEANUPS];
    u8[] invalid;      /* decodeValid's 422 body, in the arena */
};

/* n bytes that live until the request is answered (zeroed) */
pub void *Request.alloc(i64 n)
{
    void *p = arenaAlloc(&self.arena, n);
    mem.set(p, 0, n);
    return p;
}

/* The value of the first header with this name (any case), "" if none */
pub u8[] Request.header(u8[] name)
{
    for (i64 i = 0; i < self.nheaders; i++) {
        Header *h = &self.headers[i];
        if (h.name.len == name.len && libc.strncasecmp(h.name.ptr, name.ptr, name.len) == 0) {
            return h.value;
        }
    }
    return "";
}

/* true when the request has a header with this name (any case) */
pub bool Request.hasHeader(u8[] name)
{
    for (i64 i = 0; i < self.nheaders; i++) {
        Header *h = &self.headers[i];
        if (h.name.len == name.len && libc.strncasecmp(h.name.ptr, name.ptr, name.len) == 0) {
            return true;
        }
    }
    return false;
}

/* The path parameter a Mux pattern named ({id} in "GET /users/{id}"),
 * unescaped; "" if the pattern has none of that name */
pub u8[] Request.param(u8[] name)
{
    for (i64 i = 0; i < self.nparams; i++) {
        Header *h = &self.params[i];
        if (h.name.len == name.len && libc.memcmp(h.name.ptr, name.ptr, name.len) == 0) {
            return h.value;
        }
    }
    return "";
}

/* Sets a path parameter (a Mux does this; a later one of the same name
 * wins). name and value must live as long as the request: views into the
 * request or memory from r.alloc. */
pub void Request.setParam(u8[] name, u8[] value)
{
    for (i64 i = 0; i < self.nparams; i++) {
        if (self.params[i].name.len == name.len &&
            libc.memcmp(self.params[i].name.ptr, name.ptr, name.len) == 0) {
            self.params[i].value = value;
            return;
        }
    }
    if (self.nparams == MAX_PARAMS) { throw(ERR_HTTP, "http: more than %d path parameters", MAX_PARAMS); }
    self.params[self.nparams].name = name;
    self.params[self.nparams].value = value;
    self.nparams++;
}

static i64 hexVal(u8 c)
{
    if (c >= '0' && c <= '9') { return c - '0'; }
    if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
}

/* s with %XX escapes (and, for a query, '+' as a space) decoded: s itself
 * when there is nothing to decode, else a copy in the request's memory.
 * A bad escape is kept as it is. */
pub u8[] Request.unescape(u8[] s, bool query = false)
{
    i64 i = 0;
    for (; i < s.len; i++) {
        u8 c = s.ptr[i];
        if (c == '%' || (query && c == '+')) { break; }
    }
    if (i == s.len) { return s; }
    u8 *out = arenaAlloc(&self.arena, s.len + 1);
    i64 n = 0;
    for (i = 0; i < s.len; i++) {
        u8 c = s.ptr[i];
        if (c == '%' && i + 2 < s.len + 0 && hexVal(s.ptr[i + 1]) >= 0 && hexVal(s.ptr[i + 2]) >= 0) {
            out[n++] = (u8)(hexVal(s.ptr[i + 1]) * 16 + hexVal(s.ptr[i + 2]));
            i += 2;
        } else if (query && c == '+') {
            out[n++] = ' ';
        } else {
            out[n++] = c;
        }
    }
    out[n] = 0;
    return out[0:n];
}

/* The first value of the query parameter name (?name=value), unescaped;
 * "" when it is missing (hasQuery tells "" and missing apart) */
pub u8[] Request.query(u8[] name)
{
    u8[] q = self.rawQuery;
    i64 i = 0;
    while (i < q.len) {
        i64 end = i;
        while (end < q.len && q.ptr[end] != '&') { end++; }
        i64 eq = i;
        while (eq < end && q.ptr[eq] != '=') { eq++; }
        u8[] key = self.unescape(q[i:eq], true);
        if (key.len == name.len && libc.memcmp(key.ptr, name.ptr, name.len) == 0) {
            if (eq == end) { return ""; }
            return self.unescape(q[eq + 1:end], true);
        }
        i = end + 1;
    }
    return "";
}

/* true when the query has the parameter name, even with no value */
pub bool Request.hasQuery(u8[] name)
{
    u8[] q = self.rawQuery;
    i64 i = 0;
    while (i < q.len) {
        i64 end = i;
        while (end < q.len && q.ptr[end] != '&') { end++; }
        i64 eq = i;
        while (eq < end && q.ptr[eq] != '=') { eq++; }
        u8[] key = self.unescape(q[i:eq], true);
        if (key.len == name.len && libc.memcmp(key.ptr, name.ptr, name.len) == 0) { return true; }
        i = end + 1;
    }
    return false;
}

/* Keeps v (a value json.decode filled) until the request is answered,
 * then json.free(v). http.decode does this itself. */
pub void Request.freeLater(reflect.Value v)
{
    if (self.ncleanups == MAX_CLEANUPS) {
        throw(ERR_HTTP, "http: more than %d values decoded in one request", MAX_CLEANUPS);
    }
    self.cleanups[self.ncleanups++] = v;
}

/* Runs the json.free of every freeLater value and empties the arena */
static void requestDone(Request *r)
{
    for (i64 i = 0; i < r.ncleanups; i++) {
        json.free(r.cleanups[i]);
    }
    r.ncleanups = 0;
    r.invalid = "";
    arenaReset(&r.arena);
}

/* The body as JSON in a T: `User u = http.decode<User>(r);`. What it
 * allocated is freed when the handler returns: don't free it, and copy
 * what must live longer. Bad JSON throws json.ERR_DECODE, which the
 * server answers with a 400 and the decode message, so a handler needs
 * no check of its own. */
pub T decode<T>(Request *r, i64 flags = 0)
{
    T out = json.decode<T>(r.body, flags);
    T *keep = r.alloc(sizeof(T));
    *keep = out;
    r.freeLater(reflect.__value(keep, reflect.typeOf(out)));
    return out;
}

static void lookupPeer(Request *r)
{
    if (r.peerLen) { return; }
    r.peerLen = -1;
    net.Addr a;
    if (r.peerFd <= 0 || !net.remoteAddr(r.peerFd - 1, &a)) { return; }
    String ip = a.ip();
    if (ip.len && ip.len < 48) {
        mem.copy(r.peerIp, ip.ptr, ip.len);
        r.peerLen = ip.len;
        r.peerPort = a.port();
    }
    ip.free();
}

/* The client's IP address as text ("203.0.113.9", "::1"); "" when it
 * isn't known (a Unix socket). The TCP peer: behind a proxy that is the
 * proxy (the middleware's realIp reads X-Forwarded-For). Looked up once
 * per connection. */
pub u8[] Request.remoteAddr()
{
    lookupPeer(self);
    return self.peerLen > 0 ? self.peerIp[0:self.peerLen] : "";
}

/* The client's port, 0 when it isn't known */
pub i32 Request.remotePort()
{
    lookupPeer(self);
    return self.peerLen > 0 ? self.peerPort : 0;
}

/* Sets what remoteAddr and remotePort answer (for tests: testRequest's
 * are 192.0.2.1 and 1234, as Go's httptest) */
pub void Request.setRemoteAddr(u8[] ip, i32 port = 0)
{
    i64 n = ip.len < 47 ? ip.len : 47;
    if (n) { mem.copy(self.peerIp, ip.ptr, n); }
    self.peerLen = n ? n : -1;
    self.peerPort = port;
}

static RequestValue *findValue(Request *r, u8[] key)
{
    for (i64 i = 0; i < r.nvalues; i++) {
        if (r.values[i].key.len == key.len && libc.memcmp(r.values[i].key.ptr, key.ptr, key.len) == 0) {
            return &r.values[i];
        }
    }
    return null;
}

static RequestValue *slotFor(Request *r, u8[] key)
{
    RequestValue *v = findValue(r, key);
    if (v) { return v; }
    if (r.nvalues == MAX_VALUES) { throw(ERR_HTTP, "http: more than %d request values", MAX_VALUES); }
    v = &r.values[r.nvalues++];
    v.key = key;
    v.ptr = null;
    v.n = 0;
    return v;
}

/* Keeps v under key for the rest of this request, so middleware can hand
 * a handler what it found (the signed-in user, a request ID): a later set
 * of the same key replaces it. The key must outlive the request (a
 * literal); v too: r.alloc(n) gives memory that lives exactly as long. */
pub void Request.set(u8[] key, void *v)
{
    slotFor(self, key).ptr = v;
}

/* What set kept under key, or null */
pub void *Request.get(u8[] key)
{
    RequestValue *v = findValue(self, key);
    return v ? v.ptr : null;
}

/* A number under key (an id, a count): no memory to manage */
pub void Request.setInt(u8[] key, i64 n)
{
    slotFor(self, key).n = n;
}

/* The number setInt kept under key, or def */
pub i64 Request.getInt(u8[] key, i64 def = 0)
{
    RequestValue *v = findValue(self, key);
    return v ? v.n : def;
}

/* Was key set (by set or setInt) on this request? */
pub bool Request.has(u8[] key)
{
    return findValue(self, key) != null;
}

/* The body as JSON in a T, checked with validate.check: `Bookmark b =
 * http.decodeValid<Bookmark>(r);`. Bad JSON is a 400 as with decode; a
 * value that breaks its fields' rules throws validate.ERR_INVALID, which
 * the server (and a Mux in a test) answers with a 422 and
 * {"error":"invalid request","fields":[{"field":"url","message":"not a URL"}]}. */
pub T decodeValid<T>(Request *r, i64 flags = 0)
{
    T out = decode<T>(r, flags);
    validate.Errors errs;
    defer errs.free();
    if (!validate.check(reflect.of(out), &errs)) {
        String body = errs.toJson();
        u8 *keep = r.alloc(body.len);
        mem.copy(keep, body.ptr, body.len);
        r.invalid = keep[0:body.len];
        body.free();
        String msg = errs.message();
        defer msg.free();
        throw(validate.ERR_INVALID, "%s", msg.cstr());
    }
    return out;
}

/* The reply to a handler's throw e, replacing what it had started:
 * ERR_DECODE 400, ERR_INVALID 422, anything else a log line and a 500 */
static void answerThrow(Writer *w, Request *r, Error e)
{
    bool close = w.close, head = w.head;
    writerReset(w);
    w.close = close;
    w.head = head;
    if (e.code == json.ERR_DECODE) {
        w.error(400, cstr.view(e.message));
    } else if (e.code == validate.ERR_INVALID && r.invalid.len) {
        w.jsonText(422, r.invalid);
    } else {
        log.error("http: %s %s: the handler threw 0x%x at %s:%d: %s",
                  r.method, r.path, e.code, e.file, e.line, e.message);
        w.error(500, "internal server error");
    }
}

/* A request for tests: method and target ("/users/7?full=1") with a body,
 * to hand to a Mux or a handler with an http.recorder() Writer. Free it
 * with r.free(). */
pub Request testRequest(u8[] method, u8[] target, u8[] body = "")
{
    Request r;
    r.method = method;
    i64 q = 0;
    while (q < target.len && target.ptr[q] != '?') { q++; }
    r.path = target[0:q];
    r.rawQuery = q < target.len ? target[q + 1:target.len] : "";
    r.body = body;
    r.minor = 1;
    r.keepAlive = true;
    r.contentLength = body.len;
    r.setRemoteAddr("192.0.2.1", 1234);
    return r;
}

/* Adds a header to a test request (name and value must outlive it) */
pub void Request.addHeader(u8[] name, u8[] value)
{
    if (self.nheaders == MAX_HEADERS) { throw(ERR_HTTP, "http: more than %d headers", MAX_HEADERS); }
    self.headers[self.nheaders].name = name;
    self.headers[self.nheaders].value = value;
    self.nheaders++;
}

/* Frees what a test request (or one the server made) allocated */
pub void Request.free()
{
    requestDone(self);
    arenaFree(&self.arena);
}

/* The reply a handler builds: a status, headers and a body, sent in one
 * piece when the handler returns. Fields are read-only (a test reads
 * code, body and the headers through w.header... of an http.recorder()). */
pub struct Writer {
    i32 code;          /* 0 until set: 200 when the handler sets none */
    String headers;    /* "Name: value\r\n" lines the handler added */
    String body;
    bool hasType;      /* a Content-Type is in headers */
    bool head;         /* a HEAD request: the body isn't sent */
    bool close;        /* close the connection after this reply */
};

/* Sets the status code (200 when never set) */
pub void Writer.status(i32 code)
{
    self.code = code;
}

/* no CR, LF or NUL in s (libc's memchr: a byte loop costs more here) */
static bool headerSafe(u8[] s)
{
    if (!s.len) { return true; }
    return !libc.memchr(s.ptr, '\r', s.len) && !libc.memchr(s.ptr, '\n', s.len) &&
           !libc.memchr(s.ptr, 0, s.len);
}

/* "name: value\r\n" into w's headers, unchecked: the library's own */
static void writerHeader(Writer *w, u8[] name, u8[] value)
{
    w.headers.reserve(name.len + value.len + 4);
    w.headers.append(name);
    w.headers.append(": ");
    w.headers.append(value);
    w.headers.append("\r\n");
}

/* the library's Content-Type, unless the handler set one */
static void writerType(Writer *w, u8[] value)
{
    if (w.hasType) { return; }
    w.hasType = true;
    writerHeader(w, "Content-Type", value);
}

/* Adds a header to the reply. A Content-Type replaces the one text, json
 * and error would set; Content-Length, Date and Connection are written
 * by the server. Throws ERR_HTTP for a name or value with a line break. */
pub void Writer.header(u8[] name, u8[] value)
{
    if (!headerSafe(name) || !headerSafe(value) || name.len == 0) {
        throw(ERR_HTTP, "http: header %s: a name or value with a line break", name);
    }
    if (name.len == 12 && libc.strncasecmp(name.ptr, "content-type", 12) == 0) { self.hasType = true; }
    writerHeader(self, name, value);
}

/* The value of a reply header the handler set, "" if none (for tests and
 * middleware) */
pub u8[] Writer.getHeader(u8[] name)
{
    u8[] h = self.headers[0:self.headers.len];
    i64 i = 0;
    while (i < h.len) {
        i64 end = i;
        while (end < h.len && h.ptr[end] != '\r') { end++; }
        i64 colon = i;
        while (colon < end && h.ptr[colon] != ':') { colon++; }
        if (colon - i == name.len && libc.strncasecmp(&h.ptr[i], name.ptr, name.len) == 0) {
            i64 v = colon + 1;
            while (v < end && h.ptr[v] == ' ') { v++; }
            return h[v:end];
        }
        i = end + 2;
    }
    return "";
}

/* Adds bytes to the body */
pub void Writer.write(u8[] data)
{
    self.body.append(data);
}

/* A text/plain reply: the status and the formatted text */
pub void Writer.text(i32 code, u8 *fmt, ...args)
{
    self.code = code;
    writerType(self, "text/plain; charset=utf-8");
    u8 *text = cstr.formatArgs(null, fmt, args);
    self.body.append(text[0:cstr.len(text)]);
    mem.free(text);
}

/* A JSON reply: v (reflect.of(x)) encoded as the body */
pub void Writer.json(i32 code, reflect.Value v)
{
    self.code = code;
    writerType(self, "application/json");
    json.encodeTo(&self.body, v);
}

/* A JSON body that is already text */
pub void Writer.jsonText(i32 code, u8[] text)
{
    self.code = code;
    writerType(self, "application/json");
    self.body.append(text);
}

/* An error reply: the status and {"error": msg} as JSON, replacing a body
 * the handler had started */
pub void Writer.error(i32 code, u8[] msg)
{
    self.code = code;
    self.body.clear();
    writerType(self, "application/json");
    self.body.append("{\"error\":");
    if (str.validUtf8(msg)) {
        json.quoteTo(&self.body, msg);
    } else {
        self.body.append("\"(a message that isn't UTF-8)\"");
    }
    self.body.append("}");
}

/* A redirect to url with code (301, 302, 303, 307 or 308) */
pub void Writer.redirect(u8[] url, i32 code = 302)
{
    self.code = code;
    self.header("Location", url);
}

/* Forget everything for the next request, keeping the room */
static void writerReset(Writer *w)
{
    w.code = 0;
    w.headers.clear();
    w.body.clear();
    w.hasType = false;
    w.head = false;
    w.close = false;
}

/* A Writer for tests: hand it to a Mux or a handler, then read w.code,
 * w.body and w.getHeader(...). Free it with w.free(). */
pub Writer recorder()
{
    Writer w;
    return w;
}

pub void Writer.free()
{
    self.headers.free();
    self.body.free();
}

/* The reason phrase of a status code ("Not Found"), "" when unknown */
pub u8[] statusText(i32 code)
{
    switch (code) {
        case 100: return "Continue";
        case 101: return "Switching Protocols";
        case 200: return "OK";
        case 201: return "Created";
        case 202: return "Accepted";
        case 204: return "No Content";
        case 206: return "Partial Content";
        case 301: return "Moved Permanently";
        case 302: return "Found";
        case 303: return "See Other";
        case 304: return "Not Modified";
        case 307: return "Temporary Redirect";
        case 308: return "Permanent Redirect";
        case 400: return "Bad Request";
        case 401: return "Unauthorized";
        case 403: return "Forbidden";
        case 404: return "Not Found";
        case 405: return "Method Not Allowed";
        case 406: return "Not Acceptable";
        case 408: return "Request Timeout";
        case 409: return "Conflict";
        case 410: return "Gone";
        case 411: return "Length Required";
        case 412: return "Precondition Failed";
        case 413: return "Content Too Large";
        case 414: return "URI Too Long";
        case 415: return "Unsupported Media Type";
        case 416: return "Range Not Satisfiable";
        case 417: return "Expectation Failed";
        case 418: return "I'm a teapot";
        case 422: return "Unprocessable Content";
        case 425: return "Too Early";
        case 426: return "Upgrade Required";
        case 428: return "Precondition Required";
        case 429: return "Too Many Requests";
        case 431: return "Request Header Fields Too Large";
        case 451: return "Unavailable For Legal Reasons";
        case 500: return "Internal Server Error";
        case 501: return "Not Implemented";
        case 502: return "Bad Gateway";
        case 503: return "Service Unavailable";
        case 504: return "Gateway Timeout";
        case 505: return "HTTP Version Not Supported";
    }
    return "";
}
