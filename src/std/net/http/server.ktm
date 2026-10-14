module http;
import coro, cstr, date, json, libc, log, mem, signal;

/* How http.listen runs. A declared Options is all zero: every 0 means the
 * default in the comment. */
pub struct Options {
    i64 threads;          /* coroutine loops (0: one per CPU) */
    u8[] bindaddr;        /* "" or "*": every IPv4 address; "::*" IPv6; or a numeric address */
    i64 maxHeaderBytes;   /* the request line and headers (0: 64 KiB): more gets a 431 */
    i64 maxBodyBytes;     /* a request body (0: 10 MiB): more gets a 413 */
    i64 readTimeoutMs;    /* a request that started must arrive within this (0: 30 s) */
    i64 idleTimeoutMs;    /* a kept-alive connection with no request is closed after (0: 120 s) */
    bool noSignals;       /* don't make SIGINT and SIGTERM stop the server */
    bool accessLog;       /* a log.info line per request: method target status bytes time */
    Transport *transport; /* what carries the bytes (null: the socket itself); net.https sets TLS */
};

/* A layer over each connection's socket, as TLS: start makes it for an
 * accepted fd (null: drop the connection), read and write move bytes
 * (as coro.read and coro.write: > 0, 0 at the end, -1 on an error or
 * the time limit), end finishes it (the fd is closed after). ctx is
 * start's first argument. net.https makes one over net.tls, which keeps
 * mbedTLS out of programs that don't use it. */
pub struct Transport {
    void *ctx;
    fn(void *ctx, i32 fd, i64 ms) void * start;
    fn(void *conn, u8[] buf, i64 ms) i64 read;
    fn(void *conn, u8[] data, i64 ms) i64 write;
    fn(void *conn) void end;
};

/* A running server: what every connection reads */
struct Server {
    Mux *mux;
    void *app;
    i64 maxHead;
    i64 maxBody;
    i64 readMs;
    i64 idleMs;
    bool accessLog;
    Transport *tr;
};

/* One connection: its buffers live as long as it does */
struct Conn {
    i32 fd;
    Server *srv;
    u8 *in;          /* received bytes: in[start:end] not used yet */
    i64 cap;
    i64 start;
    i64 end;
    String out;      /* replies not sent yet */
    i64 dateSec;
    u8 date[40];
    i64 dateLen;
    u8 pre200[96];   /* "HTTP/1.1 200 OK\r\nDate: <date>\r\nContent-Length: " */
    i64 pre200Len;
    bool sentContinue;
    void *tc;        /* the transport's connection, null for the plain socket */
};

const i64 CONN_IN_FIRST = 8192;

/* Flush replies once this much is waiting, even with more requests read */
const i64 CONN_OUT_FLUSH = 65536;

/* The parse results that aren't a request yet */
const i64 PARSE_MORE = 0;

static bool sameFold(u8 *a, i64 alen, u8 *lower)
{
    return libc.strncasecmp(a, lower, alen) == 0;
}

/* A token of a header value list ("keep-alive, Upgrade") equal to want */
static bool listHas(u8[] v, u8 *want, i64 wlen)
{
    if (v.len == wlen) { return sameFold(v.ptr, wlen, want); }   /* the usual: just the one */
    i64 i = 0;
    while (i < v.len) {
        while (i < v.len && (v.ptr[i] == ' ' || v.ptr[i] == '\t' || v.ptr[i] == ',')) { i++; }
        i64 s = i;
        while (i < v.len && v.ptr[i] != ',' && v.ptr[i] != ' ' && v.ptr[i] != '\t') { i++; }
        if (i - s == wlen && sameFold(&v.ptr[s], wlen, want)) { return true; }
    }
    return false;
}

/* Parses the request line and headers at p[0:n] into r. Returns the
 * bytes they took (through the blank line), PARSE_MORE when they aren't
 * all there yet, or a negative status for a bad request (-400, -431,
 * -501, -505). r's views point into p. */
static i64 parseHead(u8 *p, i64 n, Request *r, i64 maxHead)
{
    u8 *end = p + n;
    /* tolerate empty lines before the request line (RFC 9112 2.2) */
    u8 *line = p;
    while (line < end && (*line == '\r' || *line == '\n')) { line++; }
    if (line == end) { return PARSE_MORE; }
    u8 *nl = libc.memchr(line, '\n', end - line);
    if (!nl) { return n > maxHead ? -431 : PARSE_MORE; }
    u8 *le = nl;
    if (le > line && le[-1] == '\r') { le--; }
    u8 *sp1 = libc.memchr(line, ' ', le - line);
    if (!sp1 || sp1 == line) { return -400; }
    u8 *t = sp1 + 1;
    u8 *sp2 = libc.memchr(t, ' ', le - t);
    if (!sp2 || sp2 == t) { return -400; }
    u8 *v = sp2 + 1;
    if (le - v != 8 || libc.memcmp(v, "HTTP/1.", 7) != 0) {
        if (le - v >= 5 && libc.memcmp(v, "HTTP/", 5) == 0) { return -505; }
        return -400;
    }
    if (v[7] != '0' && v[7] != '1') { return -505; }
    r.method = line[0:sp1 - line];
    for (i64 i = 0; i < r.method.len; i++) {
        u8 c = line[i];
        if (c <= ' ' || c >= 127) { return -400; }
    }
    if (*t != '/' && !(sp2 - t == 1 && *t == '*')) { return -400; }
    u8 *qm = libc.memchr(t, '?', sp2 - t);
    if (qm) {
        r.path = t[0:qm - t];
        r.rawQuery = qm[1:sp2 - qm];
    } else {
        r.path = t[0:sp2 - t];
        r.rawQuery = "";
    }
    r.minor = v[7] - '0';
    r.keepAlive = r.minor == 1;
    r.nheaders = 0;
    r.nparams = 0;
    r.contentLength = -1;
    r.chunked = false;
    r.expectContinue = false;
    bool sawConnection = false;

    u8 *q = nl + 1;
    while (true) {
        if (q >= end) { return q - p > maxHead ? -431 : PARSE_MORE; }
        nl = libc.memchr(q, '\n', end - q);
        if (!nl) { return end - p > maxHead ? -431 : PARSE_MORE; }
        le = nl;
        if (le > q && le[-1] == '\r') { le--; }
        if (le == q) { break; }
        if (*q == ' ' || *q == '\t') { return -400; }   /* obsolete line folding */
        u8 *colon = libc.memchr(q, ':', le - q);
        if (!colon || colon == q) { return -400; }
        u8 *nameEnd = colon;
        if (nameEnd[-1] == ' ' || nameEnd[-1] == '\t') { return -400; }
        u8 *vs = colon + 1;
        while (vs < le && (*vs == ' ' || *vs == '\t')) { vs++; }
        u8 *ve = le;
        while (ve > vs && (ve[-1] == ' ' || ve[-1] == '\t')) { ve--; }
        if (r.nheaders == MAX_HEADERS) { return -431; }
        Header *h = &r.headers[r.nheaders++];
        i64 nlen = nameEnd - q;
        h.name = q[0:nlen];
        h.value = vs[0:ve - vs];
        switch (nlen) {
            case 14:
                if (sameFold(q, 14, "content-length")) {
                    if (h.value.len == 0 || h.value.len > 18) { return -400; }
                    i64 cl = 0;
                    for (i64 i = 0; i < h.value.len; i++) {
                        u8 c = h.value.ptr[i];
                        if (c < '0' || c > '9') { return -400; }
                        cl = cl * 10 + (c - '0');
                    }
                    if (r.contentLength >= 0 && r.contentLength != cl) { return -400; }
                    r.contentLength = cl;
                }
            case 17:
                if (sameFold(q, 17, "transfer-encoding")) {
                    if (!(h.value.len == 7 && sameFold(h.value.ptr, 7, "chunked"))) { return -501; }
                    r.chunked = true;
                }
            case 10:
                if (sameFold(q, 10, "connection")) {
                    sawConnection = true;
                    if (listHas(h.value, "close", 5)) {
                        r.keepAlive = false;
                    } else if (listHas(h.value, "keep-alive", 10)) {
                        r.keepAlive = true;
                    }
                }
            case 6:
                if (sameFold(q, 6, "expect") && h.value.len == 12 && sameFold(h.value.ptr, 12, "100-continue")) {
                    r.expectContinue = true;
                }
        }
        q = nl + 1;
        if (q - p > maxHead) { return -431; }
    }
    if (r.chunked && r.contentLength >= 0) { return -400; }  /* RFC 9112 6.3: smuggling */
    return nl + 1 - p;
}

static i64 hexDigit(u8 c)
{
    if (c >= '0' && c <= '9') { return c - '0'; }
    if (c >= 'a' && c <= 'f') { return c - 'a' + 10; }
    if (c >= 'A' && c <= 'F') { return c - 'A' + 10; }
    return -1;
}

/* A chunked body at p[0:n]: joins the chunks' data in place at the start
 * of p and sets *bodyLen. Returns the bytes the body took on the wire
 * (through the last CRLF of the trailers), PARSE_MORE, or -400 / -413. */
static i64 parseChunked(u8 *p, i64 n, i64 maxBody, i64 *bodyLen)
{
    /* first pass: is it all there, and is it valid? */
    i64 at = 0, total = 0;
    while (true) {
        i64 size = 0, digits = 0;
        while (at < n && hexDigit(p[at]) >= 0) {
            size = size * 16 + hexDigit(p[at]);
            at++;
            if (++digits > 15) { return -400; }
        }
        if (at == n) { return PARSE_MORE; }
        if (!digits) { return -400; }
        u8 *nl = libc.memchr(&p[at], '\n', n - at);   /* skips chunk extensions */
        if (!nl) { return PARSE_MORE; }
        at = nl + 1 - p;
        if (size == 0) { break; }
        total += size;
        if (total > maxBody) { return -413; }
        if (at + size + 1 > n) { return PARSE_MORE; }
        at += size;
        if (p[at] == '\r') {
            if (at + 1 >= n) { return PARSE_MORE; }
            at++;
        }
        if (p[at] != '\n') { return -400; }
        at++;
    }
    /* trailers, up to an empty line */
    while (true) {
        if (at >= n) { return PARSE_MORE; }
        u8 *nl = libc.memchr(&p[at], '\n', n - at);
        if (!nl) { return PARSE_MORE; }
        i64 len = nl - &p[at];
        at = nl + 1 - p;
        if (len == 0 || (len == 1 && nl[-1] == '\r')) { break; }
    }
    /* second pass: move the data together (never forward of where it is) */
    i64 rd = 0, wr = 0;
    while (true) {
        i64 size = 0;
        while (hexDigit(p[rd]) >= 0) {
            size = size * 16 + hexDigit(p[rd]);
            rd++;
        }
        u8 *nl = libc.memchr(&p[rd], '\n', n - rd);
        rd = nl + 1 - p;
        if (size == 0) { break; }
        libc.memmove(&p[wr], &p[rd], size);
        wr += size;
        rd += size;
        if (p[rd] == '\r') { rd++; }
        rd++;
    }
    *bodyLen = wr;
    return at;
}

/* The Date header's value, formatted once a second per connection */
static void connDate(Conn *c)
{
    i64 now = date.unixNow();
    if (now == c.dateSec && c.dateLen) { return; }
    c.dateLen = date.httpDateTo(now, c.date);
    c.dateSec = now;
    /* the start of most replies, with this Date */
    u8[] a = "HTTP/1.1 200 OK\r\nDate: ";
    u8[] b = "\r\nContent-Length: ";
    mem.copy(c.pre200, a.ptr, a.len);
    mem.copy(c.pre200 + a.len, c.date, c.dateLen);
    mem.copy(c.pre200 + a.len + c.dateLen, b.ptr, b.len);
    c.pre200Len = a.len + c.dateLen + b.len;
}

static void appendInt(String *s, i64 v)
{
    u8 tmp[24];
    i64 i = 24;
    if (v == 0) { tmp[--i] = '0'; }
    while (v > 0) {
        tmp[--i] = (u8)('0' + v % 10);
        v /= 10;
    }
    s.append(tmp[i:24]);
}

/* The status line, the server's headers, the handler's headers and the
 * body, added to c.out */
static void writeReply(Conn *c, Writer *w)
{
    String *o = &c.out;
    i32 code = w.code ? w.code : 200;
    bool noBody = code == 204 || code == 304 || (code >= 100 && code < 200);
    o.reserve(160 + w.headers.len + w.body.len);
    connDate(c);
    if (code == 200) {
        o.append(c.pre200[0:c.pre200Len]);
    } else {
        o.append("HTTP/1.1 ");
        appendInt(o, code);
        o.appendByte(' ');
        o.append(statusText(code));
        o.append("\r\nDate: ");
        o.append(c.date[0:c.dateLen]);
        if (!noBody) { o.append("\r\nContent-Length: "); }
    }
    if (!noBody) {
        appendInt(o, w.body.len);
        if (!w.hasType && w.body.len) { o.append("\r\nContent-Type: text/plain; charset=utf-8"); }
    }
    if (w.close) { o.append("\r\nConnection: close"); }
    o.append("\r\n");
    o.append(w.headers[0:w.headers.len]);
    o.append("\r\n");
    if (!w.head && !noBody) { o.append(w.body[0:w.body.len]); }
}

/* A reply to a request that couldn't be read: the status and close */
static void writeBadRequest(Conn *c, Writer *w, i64 code)
{
    writerReset(w);
    w.close = true;
    u8[] msg = statusText((i32)code);
    w.error((i32)code, msg);
    writeReply(c, w);
}

/* Sends what c.out holds; false when the peer is gone */
static bool connFlush(Conn *c)
{
    if (!c.out.len) { return true; }
    i64 n = c.tc ? c.srv.tr.write(c.tc, c.out[0:c.out.len], c.srv.readMs)
                 : coro.write(c.fd, c.out[0:c.out.len], c.srv.readMs);
    c.out.clear();
    return n >= 0;
}

/* Reads more into c.in, making room first; false at end of input, an
 * error or the timeout */
static bool connRead(Conn *c, i64 ms)
{
    if (c.start == c.end) {
        c.start = c.end = 0;
    } else if (c.end == c.cap && c.start > 0) {
        libc.memmove(c.in, &c.in[c.start], c.end - c.start);
        c.end -= c.start;
        c.start = 0;
    }
    if (c.end == c.cap) {
        i64 limit = c.srv.maxHead + c.srv.maxBody + 4096;
        if (c.cap >= limit) { return false; }
        i64 cap = c.cap * 2;
        if (cap > limit) { cap = limit; }
        u8 *in = mem.realloc(c.in, cap);
        if (!in) { return false; }
        c.in = in;
        c.cap = cap;
    }
    i64 n = c.tc ? c.srv.tr.read(c.tc, c.in[c.end:c.cap], ms) : coro.read(c.fd, c.in[c.end:c.cap], ms);
    if (n <= 0) { return false; }
    c.end += n;
    return true;
}

/* Runs the handler for r and adds its reply to c.out */
static void dispatch(Conn *c, Request *r, Writer *w)
{
    writerReset(w);
    w.head = r.method.len == 4 && libc.memcmp(r.method.ptr, "HEAD", 4) == 0;
    w.close = !r.keepAlive;
    r.app = c.srv.app;
    r.nvalues = 0;              /* each request starts with none */
    i64 t0 = c.srv.accessLog ? date.monoNs() : 0;
    c.srv.mux.serve(w, r);   /* answers a handler's throw itself */
    if (c.srv.accessLog) { accessLine(r, w, t0); }
    if (r.ncleanups) {
        try {
            requestDone(r);
        } catch (Error e) {
            log.error("http: freeing the request's values threw at %s:%d: %s", e.file, e.line, e.message);
        }
    } else {
        requestDone(r);   /* only the arena: nothing that throws */
    }
    writeReply(c, w);
}

/* The access log's line for r: "GET /users/7?full=1 200 41 0.120ms" */
static void accessLine(Request *r, Writer *w, i64 t0)
{
    f64 ms = (f64)(date.monoNs() - t0) / 1000000.0;
    i64 bytes = w.head ? 0 : w.body.len;
    log.info("%s %s%s%s %d %d %.3fms", r.method, r.path, r.rawQuery.len ? "?" : "", r.rawQuery,
             w.code ? w.code : 200, bytes, ms);
}

/* One connection: read requests, answer each, until it closes */
static void serveConn(i32 fd, void *arg)
{
    Conn c;
    c.fd = fd;
    c.srv = arg;
    if (c.srv.tr) {
        c.tc = c.srv.tr.start(c.srv.tr.ctx, fd, c.srv.readMs);
        if (!c.tc) { return; }
    }
    c.in = mem.alloc(CONN_IN_FIRST);
    if (!c.in) {
        if (c.tc) { c.srv.tr.end(c.tc); }
        return;
    }
    c.cap = CONN_IN_FIRST;
    Request r;
    r.peerFd = fd + 1;          /* remoteAddr looks it up when asked */
    Writer w;
    defer {
        mem.free(c.in);
        c.out.free();
        r.free();
        w.free();
        if (c.tc) { c.srv.tr.end(c.tc); }
    }
    Server *s = c.srv;
    while (true) {
        i64 avail = c.end - c.start;
        u8 *p = &c.in[c.start];
        i64 head = avail ? parseHead(p, avail, &r, s.maxHead) : PARSE_MORE;
        i64 used = 0;
        if (head > 0) {
            if (r.chunked) {
                i64 bodyLen = 0;
                i64 b = parseChunked(&p[head], avail - head, s.maxBody, &bodyLen);
                if (b > 0) {
                    r.body = p[head:head + bodyLen];
                    used = head + b;
                } else if (b < 0) {
                    head = b;
                }
            } else {
                i64 cl = r.contentLength > 0 ? r.contentLength : 0;
                if (cl > s.maxBody) {
                    head = -413;
                } else if (avail - head >= cl) {
                    r.body = p[head:head + cl];
                    used = head + cl;
                }
            }
        }
        if (head < 0) {
            writeBadRequest(&c, &w, -head);
            connFlush(&c);
            return;
        }
        if (!used) {
            /* not all there: send what is waiting, then read */
            if (!connFlush(&c)) { return; }
            if (head > 0 && r.expectContinue && !c.sentContinue) {
                c.sentContinue = true;
                u8[] cont = "HTTP/1.1 100 Continue\r\n\r\n";
                if ((c.tc ? s.tr.write(c.tc, cont, s.readMs) : coro.write(fd, cont, s.readMs)) < 0) { return; }
            }
            if (!connRead(&c, avail ? s.readMs : s.idleMs)) { return; }
            continue;
        }
        c.sentContinue = false;
        dispatch(&c, &r, &w);
        c.start += used;
        if (w.close) {
            connFlush(&c);
            return;
        }
        if (c.out.len >= CONN_OUT_FLUSH && !connFlush(&c)) { return; }
    }
}

/* Stops every http.listen of the program, as SIGINT does */
pub void shutdown()
{
    coro.serveStop();
}

static void *watchSignals(void *unused)
{
    if (coro.waitIdle(signal.stopInit(), libc.POLLIN) > 0) { coro.serveStop(); }
    return null;
}

/* Serves mux on port until SIGINT or SIGTERM (or http.shutdown()), then
 * lets the requests in progress finish and returns 0; -1 with
 * os.errno() set when the port can't be listened on. app reaches every
 * handler as r.app. Runs a coroutine loop per CPU: handlers run on
 * several threads at once, so what they share through app needs the
 * atomic module or a lock. The mux is freed when listen returns. */
pub i32 listen(i32 port, Mux *mux, void *app = null, Options *opts = null)
{
    Options o;
    if (opts) { o = *opts; }
    Server s;
    s.mux = mux;
    s.app = app;
    s.maxHead = o.maxHeaderBytes > 0 ? o.maxHeaderBytes : 65536;
    s.maxBody = o.maxBodyBytes > 0 ? o.maxBodyBytes : 10 * 1024 * 1024;
    s.readMs = o.readTimeoutMs > 0 ? o.readTimeoutMs : 30000;
    s.idleMs = o.idleTimeoutMs > 0 ? o.idleTimeoutMs : 120000;
    s.accessLog = o.accessLog;
    s.tr = o.transport;
    u8[] bind = o.bindaddr.len ? o.bindaddr : "*";
    signal.handle(libc.SIGPIPE, libc.SIG_IGN);
    if (!o.noSignals) {
        signal.stopInit();
        coro.go(&watchSignals);
    }
    i32 rc = coro.serve(port, &serveConn, &s, o.threads, bind);
    mux.free();
    return rc;
}
