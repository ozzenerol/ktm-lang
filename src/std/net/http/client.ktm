module http;
import atomic, coro, date, json, libc, mem, net, net.url, os, reflect, str, vec;

/* An HTTP client (v0.20.0), as Go's http.Get and http.Client:
 *
 *   http.Response r = http.get("https://api.example.com/users/7");
 *   defer r.free();
 *   if (r.status == 200) { User u = http.decodeResponse<User>(&r); ... }
 *
 * HTTP/1.1, connections kept alive and reused (per host and thread),
 * redirects followed, a time limit per request. https:// needs
 * `import net.https;` (TLS stays out of programs that don't). In a loop
 * coroutine (a net.http handler, coro.go) waits pause only the coroutine;
 * elsewhere they block the thread. Network failures throw ERR_HTTP; an
 * answer with any status, 404 or 500 included, is a Response. */

static bool eqi(u8[] a, u8[] b) {
    return a.len == b.len && (a.len == 0 || libc.strncasecmp(a.ptr, b.ptr, a.len) == 0);
}

/* does s hold w, any case? */
static bool hasi(u8[] s, u8[] w) {
    for (i64 i = 0; i + w.len <= s.len; i++) {
        if (libc.strncasecmp(s.ptr + i, w.ptr, w.len) == 0) { return true; }
    }
    return false;
}

/* A request's extra headers, in order */
pub struct Headers {
    vec.Vec<Header> items;
};

pub void Headers.add(u8[] name, u8[] value) {
    for (i64 i = 0; i < name.len; i++) {
        if (name[i] == '\r' || name[i] == '\n' || name[i] == ':') { throw(ERR_HTTP, "http: a header name with %c", name[i]); }
    }
    for (i64 i = 0; i < value.len; i++) {
        if (value[i] == '\r' || value[i] == '\n') { throw(ERR_HTTP, "http: a header value with a line break"); }
    }
    Header h;
    h.name = str.from(name)[0:];
    h.value = str.from(value)[0:];
    self.items.push(h);
}

pub void Headers.free() {
    for (i64 i = 0; i < self.items.len; i++) {
        mem.free(self.items.data[i].name.ptr);
        mem.free(self.items.data[i].value.ptr);
    }
    self.items.free();
}

/* An answer */
pub struct Response {
    i32 status;             /* 200, 404... */
    String body;            /* the whole body (chunked bodies joined) */
    String url;             /* where it came from, after redirects */
    private String heads;   /* "name: value\n" lines */
};

/* A header of the answer by name, any case ("" if none) */
pub u8[] Response.header(u8[] name) {
    u8[] h = self.heads[0:];
    i64 at = 0;
    while (at < h.len) {
        i64 nl = at;
        while (nl < h.len && h[nl] != '\n') { nl++; }
        u8[] line = h[at:nl];
        i64 colon = 0;
        while (colon < line.len && line[colon] != ':') { colon++; }
        if (colon == name.len && eqi(line[0:colon], name)) {
            u8[] v = line[colon + 1:];
            while (v.len && v[0] == ' ') { v = v[1:]; }
            return v;
        }
        at = nl + 1;
    }
    return "";
}

pub void Response.free() {
    self.body.free();
    self.url.free();
    self.heads.free();
}

/* The body as JSON in a T (json.decode's rules); yours to free as
 * json.decode's are. Throws json.ERR_DECODE on bad JSON. */
pub T decodeResponse<T>(Response *r, i64 flags = 0) {
    return json.decode<T>(r.body[0:r.body.len], flags);
}

/* ---- the TLS hook ---- */

/* What carries an https:// request's bytes: net.https sets it */
pub struct ClientTransport {
    fn(u8[] host, i32 fd, i64 ms) void * start;     /* a TLS client over fd; throws */
    fn(void *conn, u8[] buf, i64 ms) i64 read;
    fn(void *conn, u8[] data, i64 ms) i64 write;
    fn(void *conn) void end;                         /* the fd is closed after */
};

ClientTransport *gTlsClient;

/* net.https calls it at start-up */
pub void setTlsClient(ClientTransport *t) {
    gTlsClient = t;
}

/* ---- connections, kept alive ---- */

struct ClientConn {
    i32 fd;
    void *tls;          /* the TLS session, null for plain HTTP */
    u64 owner;          /* the thread (a socket in a loop stays with its loop) */
    String key;         /* "https://host:port" */
    i64 idleSince;
    ClientConn *next;
};

ClientConn *gIdle;
i64 gIdleLock;
const i64 IDLE_MAX_MS = 60000;
const i64 IDLE_MAX = 64;

extern "c" u64 pthread_self();

static void connClose(ClientConn *c) {
    if (c.tls) { gTlsClient.end(c.tls); }
    if (coro.inLoop()) { coro.close(c.fd); } else { libc.close(c.fd); }
    c.key.free();
    mem.free(c);
}

/* An idle connection to key of this thread, or null */
static ClientConn *connTake(u8[] key) {
    u64 me = pthread_self();
    i64 now = date.monoMs();
    ClientConn *found = null, *stale = null;
    while (!atomic.cas(&gIdleLock, 0, 1)) { }
    ClientConn **at = &gIdle;
    while (*at) {
        ClientConn *c = *at;
        if (c.owner == me && now - c.idleSince > IDLE_MAX_MS) {
            *at = c.next;
            c.next = stale;
            stale = c;
            continue;
        }
        if (!found && c.owner == me && str.equals(c.key[0:], key)) {
            *at = c.next;
            found = c;
            continue;
        }
        at = &c.next;
    }
    atomic.store(&gIdleLock, 0);
    while (stale) {
        ClientConn *n = stale.next;
        connClose(stale);
        stale = n;
    }
    return found;
}

static void connPut(ClientConn *c) {
    c.idleSince = date.monoMs();
    i64 n = 0;
    while (!atomic.cas(&gIdleLock, 0, 1)) { }
    for (ClientConn *p = gIdle; p; p = p.next) { n++; }
    bool keep = n < IDLE_MAX;
    if (keep) {
        c.next = gIdle;
        gIdle = c;
    }
    atomic.store(&gIdleLock, 0);
    if (!keep) { connClose(c); }
}

/* Every idle connection of this thread closed (a test's tidy end, or
 * before a fork) */
pub void closeIdle() {
    u64 me = pthread_self();
    ClientConn *mine = null;
    while (!atomic.cas(&gIdleLock, 0, 1)) { }
    ClientConn **at = &gIdle;
    while (*at) {
        ClientConn *c = *at;
        if (c.owner == me) {
            *at = c.next;
            c.next = mine;
            mine = c;
        } else {
            at = &c.next;
        }
    }
    atomic.store(&gIdleLock, 0);
    while (mine) {
        ClientConn *n = mine.next;
        connClose(mine);
        mine = n;
    }
}

static i64 leftMs(i64 deadline) {
    i64 ms = deadline - date.monoMs();
    return ms < 0 ? 0 : ms;
}

static bool waitFd(i32 fd, i64 events, i64 deadline) {
    i64 ms = leftMs(deadline);
    if (coro.inLoop()) { return coro.wait(fd, (i16)events, ms) > 0; }
    libc.pollfd p;
    p.fd = fd;
    p.events = (i16)events;
    p.revents = 0;
    return libc.poll(&p, 1, (i32)ms) > 0;
}

static i64 connRead(ClientConn *c, u8[] buf, i64 deadline) {
    if (c.tls) { return gTlsClient.read(c.tls, buf, leftMs(deadline)); }
    if (coro.inLoop()) { return coro.read(c.fd, buf, leftMs(deadline)); }
    if (!waitFd(c.fd, libc.POLLIN, deadline)) { return -1; }
    return libc.recv(c.fd, buf.ptr, buf.len, 0);
}

static bool connWrite(ClientConn *c, u8[] data, i64 deadline) {
    if (c.tls) { return gTlsClient.write(c.tls, data, leftMs(deadline)) == data.len; }
    if (coro.inLoop()) { return coro.write(c.fd, data, leftMs(deadline)) == data.len; }
    i64 off = 0;
    while (off < data.len) {
        if (!waitFd(c.fd, libc.POLLOUT, deadline)) { return false; }
        i64 n = libc.send(c.fd, data.ptr + off, data.len - off, libc.MSG_NOSIGNAL);
        if (n <= 0) { return false; }
        off += n;
    }
    return true;
}

static ClientConn *connOpen(url.URL *u, u8[] key, i64 deadline) {
    bool tls = str.equals(u.scheme[0:], "https");
    if (tls && !gTlsClient) {
        throw(ERR_HTTP, "http: https:// needs `import net.https;` (it brings TLS)");
    }
    i32 port = u.portOrDefault();
    i64 ms = leftMs(deadline);
    i32 fd = coro.inLoop() ? coro.dial(u.host[0:], port, ms) : net.dial(u.host[0:], port, false, ms > 0 ? ms : 1);
    if (fd < 0) {
        throw(ERR_HTTP, "http: can't connect to %s:%d (errno %d)", u.host, port, os.errno());
    }
    ClientConn *c = mem.allocZero(sizeof(ClientConn));
    c.fd = fd;
    c.owner = pthread_self();
    c.key = str.from(key);
    if (tls) {
        try {
            c.tls = gTlsClient.start(u.host[0:], fd, leftMs(deadline));
        } catch (Error e) {
            if (coro.inLoop()) { coro.close(fd); } else { libc.close(fd); }
            c.key.free();
            mem.free(c);
            throw(ERR_HTTP, "%s", e.message);
        }
    }
    return c;
}

/* ---- one exchange ---- */

pub struct ClientOptions {
    i64 timeoutMs;          /* the whole request, redirects included (0: 30 s) */
    i64 maxRedirects;       /* followed (0: 10; -1: none, the 3xx is the answer) */
    i64 maxBodyBytes;       /* an answer's body (0: 64 MiB): more throws */
};

/* The answer's head in in[0:headLen]: status and headers, how the body
 * comes. 0 when it isn't all there yet, -1 when it isn't HTTP. */
static i64 headEnd(u8[] in) {
    for (i64 i = 3; i < in.len; i++) {
        if (in[i] == '\n' && in[i - 1] == '\r' && in[i - 2] == '\n' && in[i - 3] == '\r') { return i + 1; }
    }
    return 0;
}

/* Reads the answer to a request already sent on c into *r: true when
 * the connection can be used again */
static bool readAnswer(ClientConn *c, bool head, Response *r, i64 maxBody, i64 deadline, bool *gotAny) {
    String in;
    defer in.free();
    u8 buf[16384];
    i64 hend = 0;
    while (!(hend = headEnd(in[0:]))) {
        if (in.len > 65536) { throw(ERR_HTTP, "http: the answer's headers are over 64 KiB"); }
        i64 n = connRead(c, buf[0:], deadline);
        if (n <= 0) {
            if (date.monoMs() >= deadline) { throw(ERR_HTTP, "http: no answer in time"); }
            throw(ERR_HTTP, "http: the connection closed before the answer");
        }
        *gotAny = true;
        in.append(buf[0:n]);
    }
    u8[] h = in[0:hend];
    /* "HTTP/1.1 200 OK" */
    if (h.len < 12 || !str.startsWith(h, "HTTP/1.")) { throw(ERR_HTTP, "http: not an HTTP answer"); }
    i64 code = 0;
    for (i64 i = 9; i < 12; i++) {
        if (h[i] < '0' || h[i] > '9') { throw(ERR_HTTP, "http: a bad status line"); }
        code = code * 10 + (h[i] - '0');
    }
    r.status = (i32)code;
    bool http10 = h[7] == '0';
    i64 lineEnd = 0;
    while (h[lineEnd] != '\n') { lineEnd++; }
    r.heads.clear();
    i64 at = lineEnd + 1;
    while (at < h.len - 2) {
        i64 nl = at;
        while (nl < h.len && h[nl] != '\n') { nl++; }
        u8[] line = h[at:nl > at && h[nl - 1] == '\r' ? nl - 1 : nl];
        r.heads.append(line);
        r.heads.appendByte('\n');
        at = nl + 1;
    }
    /* informational answers (100 Continue) before the real one */
    if (code >= 100 && code < 200) {
        String rest = str.from(in[hend:]);
        in.free();
        in = rest;
        return readAnswer(c, head, r, maxBody, deadline, gotAny);
    }
    u8[] te = r.header("transfer-encoding");
    u8[] cl = r.header("content-length");
    u8[] conn = r.header("connection");
    bool close = http10 ? !eqi(conn, "keep-alive") : eqi(conn, "close");
    r.body.clear();
    u8[] body = in[hend:];
    if (head || code == 204 || code == 304) {
        return !close;
    }
    if (te.len && hasi(te, "chunked")) {
        String raw = str.from(body);
        defer raw.free();
        i64 pos = 0;
        while (true) {
            /* a chunk's size line */
            i64 nl = -1;
            while (nl < 0) {
                for (i64 i = pos; i + 1 < raw.len; i++) {
                    if (raw[i] == '\r' && raw[i + 1] == '\n') { nl = i; break; }
                }
                if (nl >= 0) { break; }
                i64 n = connRead(c, buf[0:], deadline);
                if (n <= 0) { throw(ERR_HTTP, "http: the answer ended inside a chunk"); }
                raw.append(buf[0:n]);
            }
            i64 size = 0, k = pos;
            while (k < nl && raw[k] != ';') {
                i64 d = raw[k] >= '0' && raw[k] <= '9' ? raw[k] - '0'
                      : raw[k] >= 'a' && raw[k] <= 'f' ? raw[k] - 'a' + 10
                      : raw[k] >= 'A' && raw[k] <= 'F' ? raw[k] - 'A' + 10 : -1;
                if (d < 0 || size > maxBody) { throw(ERR_HTTP, "http: a bad chunk size"); }
                size = size * 16 + d;
                k++;
            }
            pos = nl + 2;
            if (size == 0) {
                /* trailers, up to an empty line */
                while (true) {
                    i64 e = -1;
                    for (i64 i = pos; i + 1 < raw.len; i++) {
                        if (raw[i] == '\r' && raw[i + 1] == '\n') { e = i; break; }
                    }
                    if (e < 0) {
                        i64 n = connRead(c, buf[0:], deadline);
                        if (n <= 0) { throw(ERR_HTTP, "http: the answer ended in its trailers"); }
                        raw.append(buf[0:n]);
                        continue;
                    }
                    bool empty = e == pos;
                    pos = e + 2;
                    if (empty) { break; }
                }
                break;
            }
            if (r.body.len + size > maxBody) { throw(ERR_HTTP, "http: the answer's body is over the limit"); }
            while (raw.len < pos + size + 2) {
                i64 n = connRead(c, buf[0:], deadline);
                if (n <= 0) { throw(ERR_HTTP, "http: the answer ended inside a chunk"); }
                raw.append(buf[0:n]);
            }
            r.body.append(raw[pos:pos + size]);
            pos += size + 2;
        }
        return !close;
    }
    if (cl.len) {
        i64 want;
        if (!str.toInt(cl, &want) || want < 0) { throw(ERR_HTTP, "http: a bad Content-Length"); }
        if (want > maxBody) { throw(ERR_HTTP, "http: the answer's body is over the limit"); }
        r.body.reserve(want);
        r.body.append(body.len > want ? body[0:want] : body);
        while (r.body.len < want) {
            i64 n = connRead(c, buf[0:], deadline);
            if (n <= 0) { throw(ERR_HTTP, "http: the answer ended before its Content-Length"); }
            i64 take = n < want - r.body.len ? n : want - r.body.len;
            r.body.append(buf[0:take]);
        }
        return !close;
    }
    /* neither: the body goes to the end of the connection */
    r.body.append(body);
    while (true) {
        i64 n = connRead(c, buf[0:], deadline);
        if (n <= 0) { break; }
        if (r.body.len + n > maxBody) { throw(ERR_HTTP, "http: the answer's body is over the limit"); }
        r.body.append(buf[0:n]);
    }
    return false;
}

static void requestText(String *o, u8[] method, url.URL *u, u8[] body, Headers *h, bool hasBody) {
    o.append(method);
    o.appendByte(' ');
    o.append(u.path.len ? u.path[0:] : "/");
    if (u.hasQuery) {
        o.appendByte('?');
        o.append(u.rawQuery);
    }
    o.append(" HTTP/1.1\r\nHost: ");
    bool v6 = false;
    for (i64 i = 0; i < u.host.len; i++) { if (u.host[i] == ':') { v6 = true; } }
    if (v6) { o.appendByte('['); }
    o.append(u.host);
    if (v6) { o.appendByte(']'); }
    if (u.port) { o.appendFormat(":%d", u.port); }
    o.append("\r\n");
    bool ua = false, accept = false;
    for (i64 i = 0; h && i < h.items.len; i++) {
        Header *x = &h.items.data[i];
        if (eqi(x.name, "user-agent")) { ua = true; }
        if (eqi(x.name, "accept")) { accept = true; }
        o.append(x.name);
        o.append(": ");
        o.append(x.value);
        o.append("\r\n");
    }
    if (!ua) { o.append("User-Agent: ktm\r\n"); }
    if (!accept) { o.append("Accept: */*\r\n"); }
    if (hasBody) { o.appendFormat("Content-Length: %d\r\n", body.len); }
    o.append("\r\n");
    o.append(body);
}

/* method to rawUrl with body (sent when not empty, or always for POST,
 * PUT and PATCH) and the extra headers: the answer. Redirects are
 * followed as browsers do (303, and 301/302 of a POST, become a GET;
 * 307 and 308 keep the method and body). Throws ERR_HTTP when there's
 * no answer: a bad URL, no connection, the time limit. */
pub Response request(u8[] method, u8[] rawUrl, u8[] body = "", Headers *headers = null,
                     ClientOptions *opts = null) {
    ClientOptions o;
    if (opts) { o = *opts; }
    i64 deadline = date.monoMs() + (o.timeoutMs > 0 ? o.timeoutMs : 30000);
    i64 maxRedirects = o.maxRedirects == 0 ? 10 : o.maxRedirects < 0 ? 0 : o.maxRedirects;
    i64 maxBody = o.maxBodyBytes > 0 ? o.maxBodyBytes : 64 * 1024 * 1024;
    Response r;
    url.URL u;
    defer u.free();
    if (!url.parse(rawUrl, &u) || !u.host.len ||
        !(str.equals(u.scheme[0:], "http") || str.equals(u.scheme[0:], "https"))) {
        throw(ERR_HTTP, "http: `%.*s` is not an http:// or https:// URL", rawUrl.len, rawUrl.ptr);
    }
    String m = str.from(method);
    String b = str.from(body);
    defer m.free();
    defer b.free();
    for (i64 hop = 0; ; hop++) {
        String key = str.format("%s://%s:%d", u.scheme, u.host, u.portOrDefault());
        defer key.free();
        bool hasBody = b.len || str.equals(m[0:], "POST") || str.equals(m[0:], "PUT") || str.equals(m[0:], "PATCH");
        String text;
        defer text.free();
        requestText(&text, m[0:], &u, b[0:], headers, hasBody);
        bool head = str.equals(m[0:], "HEAD");
        bool done = false;
        for (i64 attempt = 0; !done; attempt++) {
            ClientConn *c = attempt == 0 ? connTake(key[0:]) : null;
            bool reused = c != null;
            if (!c) { c = connOpen(&u, key[0:], deadline); }
            bool gotAny = false;
            try {
                if (!connWrite(c, text[0:], deadline)) { throw(ERR_HTTP, "http: the connection closed while sending"); }
                bool again = readAnswer(c, head, &r, maxBody, deadline, &gotAny);
                if (again) { connPut(c); } else { connClose(c); }
                done = true;
            } catch (Error e) {
                connClose(c);
                /* a kept-alive connection the server had closed: once more on a new one */
                if (reused && !gotAny && date.monoMs() < deadline) { continue; }
                r.free();
                throw(e.code, "%s", e.message);
            }
        }
        r.url.free();
        r.url = u.string();
        bool redirect = r.status == 301 || r.status == 302 || r.status == 303 || r.status == 307 || r.status == 308;
        u8[] loc = r.header("location");
        if (!redirect || !loc.len || hop >= maxRedirects) { return r; }
        url.URL next;
        if (!u.resolve(loc, &next) || !next.host.len) {
            next.free();
            return r;
        }
        if (r.status == 303 || ((r.status == 301 || r.status == 302) && !str.equals(m[0:], "GET") &&
                                !str.equals(m[0:], "HEAD"))) {
            m.free();
            m = str.from("GET");
            b.clear();
        }
        u.free();
        u = next;
        r.free();
        mem.set(&r, 0, sizeof(Response));
    }
    return r;
}

/* GET url */
pub Response get(u8[] rawUrl, ClientOptions *opts = null) {
    return request("GET", rawUrl, "", null, opts);
}

/* POST body (with its Content-Type) to url */
pub Response post(u8[] rawUrl, u8[] contentType, u8[] body, ClientOptions *opts = null) {
    Headers h;
    defer h.free();
    h.add("Content-Type", contentType);
    return request("POST", rawUrl, body, &h, opts);
}

/* POST v as JSON (reflect.of(x)) to url */
pub Response postJson(u8[] rawUrl, reflect.Value v, ClientOptions *opts = null) {
    String body = json.encode(v);
    defer body.free();
    Headers h;
    defer h.free();
    h.add("Content-Type", "application/json");
    h.add("Accept", "application/json");
    return request("POST", rawUrl, body[0:], &h, opts);
}
