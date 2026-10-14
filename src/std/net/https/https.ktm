module https;
import atomic, net.http, net.tls;

/* HTTPS servers (v0.20.0): net.http over TLS, the same Mux and handlers.
 *
 *   import net.http, net.https;
 *   ...
 *   return https.listen(8443, &mux, "server.pem", "server.key");
 *
 * TLS 1.2 and 1.3 by net.tls (mbedTLS inside ktm). This module is source,
 * as net.tls is: programs that don't import it don't carry TLS. */

/* A TLS connection for a server connection's socket */
static void *start(void *ctx, i32 fd, i64 ms) {
    tls.Context *c = ctx;
    try {
        return c.accept(fd);
    } catch (Error e) {
        return null;        /* not TLS, an old client, a dropped handshake */
    }
    return null;
}

static i64 read(void *conn, u8[] buf, i64 ms) {
    tls.Conn *c = conn;
    return c.read(buf, ms);
}

static i64 write(void *conn, u8[] data, i64 ms) {
    tls.Conn *c = conn;
    return c.write(data, ms);
}

static void end(void *conn) {
    tls.Conn *c = conn;
    c.finish();
}

/* As http.listen, over TLS with the certificate chain and private key
 * in PEM files certFile and keyFile. Throws tls.ERR_TLS when they can't
 * be read or don't belong together; otherwise as http.listen: serves
 * until SIGINT, SIGTERM or http.shutdown(), then returns 0 (-1 with
 * os.errno() when the port can't be used). */
pub i32 listen(i32 port, http.Mux *mux, u8[] certFile, u8[] keyFile, void *app = null,
               http.Options *opts = null) {
    tls.Config cfg;
    cfg.certFile = certFile;
    cfg.keyFile = keyFile;
    cfg.alpn = "http/1.1";
    tls.Context *ctx = tls.server(&cfg);
    http.Transport t;
    t.ctx = ctx;
    t.start = &start;
    t.read = &read;
    t.write = &write;
    t.end = &end;
    http.Options o;
    if (opts) { o = *opts; }
    o.transport = &t;
    i32 rc = http.listen(port, mux, app, &o);
    ctx.free();
    return rc;
}

/* The same with a tls.Config of your own (client certificates through
 * caFile, a key password, ALPN...) */
pub i32 listenConfig(i32 port, http.Mux *mux, tls.Config *cfg, void *app = null, http.Options *opts = null) {
    tls.Context *ctx = tls.server(cfg);
    http.Transport t;
    t.ctx = ctx;
    t.start = &start;
    t.read = &read;
    t.write = &write;
    t.end = &end;
    http.Options o;
    if (opts) { o = *opts; }
    o.transport = &t;
    i32 rc = http.listen(port, mux, app, &o);
    ctx.free();
    return rc;
}

/* ---- the client's https:// ---- */

i64 gClientCtx;        /* the tls.Context of https:// requests, made on first use */

static tls.Context *clientCtx() {
    tls.Context *c = (tls.Context *)atomic.load(&gClientCtx);
    if (c) { return c; }
    tls.Config cfg;
    cfg.alpn = "http/1.1";
    tls.Context *mine = tls.client(&cfg);
    if (!atomic.cas(&gClientCtx, 0, (i64)mine)) {
        mine.free();
        return (tls.Context *)atomic.load(&gClientCtx);
    }
    return mine;
}

static void *clientStart(u8[] host, i32 fd, i64 ms) {
    return clientCtx().connect(fd, host);
}

static void clientEnd(void *conn) {
    tls.Conn *c = conn;
    c.finish();
}

http.ClientTransport gClientTransport;

static i64 registerClient() {
    gClientTransport.start = &clientStart;
    gClientTransport.read = &read;
    gClientTransport.write = &write;
    gClientTransport.end = &clientEnd;
    http.setTlsClient(&gClientTransport);
    return 1;
}

/* importing net.https gives net.http's client https:// */
i64 gRegistered = registerClient();

/* The TLS settings of https:// requests from now on (a CA of your own,
 * a client certificate); by default the system's CAs. Call it before
 * the first request: connections already open keep theirs. */
pub void setClientConfig(tls.Config *cfg) {
    tls.Config c = *cfg;
    if (!c.alpn.len) { c.alpn = "http/1.1"; }
    tls.Context *mine = tls.client(&c);
    atomic.store(&gClientCtx, (i64)mine);   /* the old one stays: its sessions may be in use */
}
