module https;
import net.http, net.tls;

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
