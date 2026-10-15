module tls;
#link <ktmtls>
import atomic, coro, cstr, date, fs, libc, mem, net, os, str;

/* TLS (v0.20.0): encrypted connections, client and server, over sockets
 * ktm already has. mbedTLS does the work (src/vendor/mbedtls, built into
 * libktmtls), so nothing has to be installed. In a loop coroutine
 * (coro.go, coro.serve, net.http handlers) a wait pauses only the
 * coroutine; elsewhere it blocks the thread, as net does.
 *
 *   tls.Conn *c = tls.dial("example.com", 443);   // the system's CAs
 *   c.write("GET / HTTP/1.0\r\nHost: example.com\r\n\r\n");
 *   i64 n = c.read(buf[0:]);
 *   c.close();
 *
 * A Context holds a configuration (CAs, a certificate, how to verify)
 * and is shared by every connection and thread: make one, use it for
 * many connections. This module stays source (it isn't in libktm), so
 * only programs that import it link libktmtls. */

pub const i64 ERR_TLS = 0x6B746D21;

/* What a client checks of the server's certificate */
pub enum Verify {
    full,       /* the chain up to a trusted CA, and the name (the default) */
    chain,      /* the chain only, any name (Postgres's verify-ca) */
    none        /* nothing: encryption without knowing who answers */
};

pub struct Config {
    Verify verify;
    u8[] caFile;        /* trusted CAs, a PEM file; neither this nor caPem: the system's */
    u8[] caPem;         /* trusted CAs as PEM text */
    u8[] certFile;      /* this side's certificate chain (a server's), PEM */
    u8[] keyFile;       /* and its private key, PEM */
    u8[] certPem;       /* the same as text */
    u8[] keyPem;
    u8[] keyPassword;   /* for an encrypted key */
    u8[] alpn;          /* ALPN protocols to offer or accept: "h2,http/1.1" */
    i64 handshakeMs;    /* the handshake's limit (0: 30 s) */
};

pub struct Context {
    private void *native;
    private bool server;
    private i64 handshakeMs;
};

pub struct Conn {
    i32 fd;
    private void *native;
    private String err;
    private Context *owned;     /* dial()'s own context: freed with the connection */
};

const i64 WANT_READ = -1;
const i64 WANT_WRITE = -2;
const i64 ERRLEN = 256;

extern "c" void *ktm_tls_ctx_new(i32 isServer, i32 verify, u8 *err, i32 errlen);
extern "c" u8 *ktm_tls_ctx_error(void *ctx);
extern "c" i32 ktm_tls_ctx_ca_file(void *ctx, u8 *path);
extern "c" i32 ktm_tls_ctx_ca_pem(void *ctx, u8 *pem, u64 len);
extern "c" i32 ktm_tls_ctx_system_ca(void *ctx);
extern "c" i32 ktm_tls_ctx_cert(void *ctx, u8 *cert, u64 clen, u8 *key, u64 klen, u8 *pass);
extern "c" i32 ktm_tls_ctx_alpn(void *ctx, u8 *list);
extern "c" void ktm_tls_ctx_free(void *ctx);
extern "c" void *ktm_tls_new(void *ctx, i32 fd, u8 *host, u8 *err, i32 errlen);
extern "c" i32 ktm_tls_handshake(void *c);
extern "c" i64 ktm_tls_read(void *c, u8 *buf, u64 len);
extern "c" i64 ktm_tls_write(void *c, u8 *buf, u64 len);
extern "c" i64 ktm_tls_pending(void *c);
extern "c" void ktm_tls_close_notify(void *c);
extern "c" u8 *ktm_tls_error(void *c);
extern "c" u8 *ktm_tls_version(void *c);
extern "c" u8 *ktm_tls_cipher(void *c);
extern "c" u8 *ktm_tls_alpn(void *c);
extern "c" u8 *ktm_tls_peer_cert(void *c, u64 *len, i32 *sigMd);
extern "c" void ktm_tls_free(void *c);

/* ---- contexts ---- */

static void fail(void *ctx, u8 *what) {
    scoped String m = str.from(cstr.view(ktm_tls_ctx_error(ctx)));
    ktm_tls_ctx_free(ctx);
    if (m.len) { throw(ERR_TLS, "%s", m); }
    throw(ERR_TLS, "%s", what);
}

static Context *make(Config *cfg, bool server) {
    Config none;
    if (!cfg) { cfg = &none; }
    u8 err[256];
    err[0] = 0;
    /* a server checks client certificates only when it was given CAs */
    i32 verify = cfg.verify == Verify.full ? 2 : cfg.verify == Verify.chain ? 1 : 0;
    if (server && !cfg.caFile.len && !cfg.caPem.len) { verify = 0; }
    void *ctx = ktm_tls_ctx_new(server ? 1 : 0, verify, err, ERRLEN);
    if (!ctx) { throw(ERR_TLS, "%s", err); }
    if (cfg.caFile.len) {
        scoped String path = str.from(cfg.caFile);
        if (ktm_tls_ctx_ca_file(ctx, path.cstr()) != 0) { fail(ctx, "tls: reading the CAs"); }
    } else if (cfg.caPem.len) {
        if (ktm_tls_ctx_ca_pem(ctx, cfg.caPem.ptr, cfg.caPem.len) != 0) { fail(ctx, "tls: reading the CAs"); }
    } else if (!server && verify) {
        if (ktm_tls_ctx_system_ca(ctx) != 0) { fail(ctx, "tls: no system CAs"); }
    }
    u8[] cert = cfg.certPem, key = cfg.keyPem;
    String certText, keyText;
    defer certText.free();
    defer keyText.free();
    if (cfg.certFile.len) {
        certText = fs.readAll(cfg.certFile);
        if (!certText.ptr) {
            ktm_tls_ctx_free(ctx);
            throw(ERR_TLS, "tls: can't read %.*s", cfg.certFile.len, cfg.certFile.ptr);
        }
        cert = certText[0:];
    }
    if (cfg.keyFile.len) {
        keyText = fs.readAll(cfg.keyFile);
        if (!keyText.ptr) {
            ktm_tls_ctx_free(ctx);
            throw(ERR_TLS, "tls: can't read %.*s", cfg.keyFile.len, cfg.keyFile.ptr);
        }
        key = keyText[0:];
    }
    if (cert.len || key.len) {
        if (!cert.len || !key.len) {
            ktm_tls_ctx_free(ctx);
            throw(ERR_TLS, "tls: a certificate needs its key, and a key its certificate");
        }
        scoped String pass = str.from(cfg.keyPassword);
        if (ktm_tls_ctx_cert(ctx, cert.ptr, cert.len, key.ptr, key.len,
                             cfg.keyPassword.len ? pass.cstr() : null) != 0) {
            fail(ctx, "tls: reading the certificate");
        }
    } else if (server) {
        ktm_tls_ctx_free(ctx);
        throw(ERR_TLS, "tls: a server needs a certificate and its key (certFile and keyFile)");
    }
    if (cfg.alpn.len) {
        scoped String list = str.from(cfg.alpn);
        if (ktm_tls_ctx_alpn(ctx, list.cstr()) != 0) { fail(ctx, "tls: ALPN"); }
    }
    Context *c = mem.allocZero(sizeof(Context));
    c.native = ctx;
    c.server = server;
    c.handshakeMs = cfg.handshakeMs > 0 ? cfg.handshakeMs : 30000;
    return c;
}

/* A context for client connections; cfg null: verify servers against the
 * system's CAs. Throws ERR_TLS (a CA file that can't be read...). */
pub Context *client(Config *cfg = null) {
    return make(cfg, false);
}

/* A context for server connections: cfg gives the certificate and key */
pub Context *server(Config *cfg) {
    return make(cfg, true);
}

/* Frees the context; its connections must be closed first (a session
 * uses its context to the end) */
pub void Context.free() {
    ktm_tls_ctx_free(self.native);
    mem.free(self);
}

/* ---- waiting ---- */

/* Waits until fd is ready for events or deadline (date.monoMs, -1: none)
 * passes: true when ready */
static bool waitFd(i32 fd, i64 events, i64 deadline) {
    i64 ms = -1;
    if (deadline >= 0) {
        ms = deadline - date.monoMs();
        if (ms < 0) { ms = 0; }
    }
    if (coro.inLoop()) {
        return coro.wait(fd, (i16)events, ms) > 0;
    }
    libc.pollfd p;
    p.fd = fd;
    p.events = (i16)events;
    p.revents = 0;
    while (true) {
        i32 r = libc.poll(&p, 1, (i32)ms);
        if (r > 0) { return true; }
        if (r == 0) { return false; }
        if (os.errno() != libc.EINTR) { return true; }   /* the call that follows reports it */
    }
    return false;
}

static i64 deadlineOf(i64 ms) {
    return ms < 0 ? -1 : date.monoMs() + ms;
}

/* ---- connections ---- */

static Conn *start(Context *ctx, i32 fd, u8[] host) {
    u8 err[256];
    err[0] = 0;
    scoped String h = str.from(host);
    void *n = ktm_tls_new(ctx.native, fd, host.len ? h.cstr() : null, err, ERRLEN);
    if (!n) { throw(ERR_TLS, "%s", err); }
    Conn *c = mem.allocZero(sizeof(Conn));
    c.fd = fd;
    c.native = n;
    i64 deadline = deadlineOf(ctx.handshakeMs);
    while (true) {
        i32 r = ktm_tls_handshake(n);
        if (r == 0) { return c; }
        bool ok = false;
        if (r == WANT_READ) { ok = waitFd(fd, libc.POLLIN, deadline); }
        else if (r == WANT_WRITE) { ok = waitFd(fd, libc.POLLOUT, deadline); }
        if (!ok) {
            scoped String msg = r == WANT_READ || r == WANT_WRITE
                          ? str.format("tls: the handshake took longer than %d ms", ctx.handshakeMs)
                          : str.from(cstr.view(ktm_tls_error(n)));
            ktm_tls_free(n);
            mem.free(c);
            throw(ERR_TLS, "%s", msg);
        }
    }
    return null;
}

/* TLS as a client over fd, a connected socket: the handshake is done
 * when it returns. host is the name to check the certificate against
 * (and to send, SNI); "" for none. Throws ERR_TLS; fd stays open then. */
pub Conn *Context.connect(i32 fd, u8[] host) {
    if (self.server) { throw(ERR_TLS, "tls: connect on a server context: use tls.client()"); }
    return start(self, fd, host);
}

/* TLS as a server over fd, an accepted socket */
pub Conn *Context.accept(i32 fd) {
    if (!self.server) { throw(ERR_TLS, "tls: accept on a client context: use tls.server()"); }
    return start(self, fd, "");
}

i64 gDefault;   /* the shared client context of dial(), a Context * */

static Context *defaultClient() {
    Context *c = (Context *)atomic.load(&gDefault);
    if (c) { return c; }
    Context *mine = client();
    if (!atomic.cas(&gDefault, 0, (i64)mine)) {
        mine.free();
        return (Context *)atomic.load(&gDefault);
    }
    return mine;
}

/* Connects to host:port and starts TLS, checking the server's
 * certificate against host. cfg null: the system's CAs, a context shared
 * by every dial. ms: the time to connect (-1: no limit). Throws ERR_TLS. */
pub Conn *dial(u8[] host, i32 port, Config *cfg = null, i64 ms = -1) {
    bool loop = coro.inLoop();
    i32 fd = loop ? coro.dial(host, port, ms) : net.dial(host, port, false, ms > 0 ? ms : 0);
    if (fd < 0) {
        throw(ERR_TLS, "tls: can't connect to %.*s:%d (errno %d)", host.len, host.ptr, port, os.errno());
    }
    Context *ctx;
    try {
        ctx = cfg ? client(cfg) : defaultClient();
    } catch (Error e) {
        if (loop) { coro.close(fd); } else { libc.close(fd); }
        throw(e.code, "%s", e.message);
    }
    Conn *c;
    try {
        c = ctx.connect(fd, host);
    } catch (Error e) {
        if (cfg) { ctx.free(); }
        if (loop) { coro.close(fd); } else { libc.close(fd); }
        throw(e.code, "%s", e.message);
    }
    /* the session uses its context to the end */
    if (cfg) { c.owned = ctx; }
    return c;
}

static void keepError(Conn *c, u8 *msg) {
    c.err.free();
    c.err = str.from(cstr.view(msg));
}

/* Reads into buf: the bytes read (> 0), 0 once the peer closed, -1 on an
 * error or after ms milliseconds (-1: no limit); c.error() says which */
pub i64 Conn.read(u8[] buf, i64 ms = -1) {
    i64 deadline = deadlineOf(ms);
    bool loop = coro.inLoop();
    while (true) {
        if (!loop && ms >= 0 && ktm_tls_pending(self.native) == 0 &&
            !waitFd(self.fd, libc.POLLIN, deadline)) {
            keepError(self, "tls: read timed out");
            return -1;
        }
        i64 n = ktm_tls_read(self.native, buf.ptr, buf.len);
        if (n >= 0) { return n; }
        bool ok = false;
        if (n == WANT_READ) { ok = waitFd(self.fd, libc.POLLIN, deadline); }
        else if (n == WANT_WRITE) { ok = waitFd(self.fd, libc.POLLOUT, deadline); }
        else {
            keepError(self, ktm_tls_error(self.native));
            return -1;
        }
        if (!ok) {
            keepError(self, "tls: read timed out");
            return -1;
        }
    }
    return -1;
}

/* Writes all of data: data.len, or -1 on an error or after ms */
pub i64 Conn.write(u8[] data, i64 ms = -1) {
    i64 deadline = deadlineOf(ms);
    i64 off = 0;
    while (off < data.len) {
        i64 n = ktm_tls_write(self.native, data.ptr + off, data.len - off);
        if (n > 0) {
            off += n;
            continue;
        }
        bool ok = false;
        if (n == WANT_WRITE) { ok = waitFd(self.fd, libc.POLLOUT, deadline); }
        else if (n == WANT_READ) { ok = waitFd(self.fd, libc.POLLIN, deadline); }
        else {
            keepError(self, ktm_tls_error(self.native));
            return -1;
        }
        if (!ok) {
            keepError(self, "tls: write timed out");
            return -1;
        }
    }
    return data.len;
}

/* Why the last read or write failed */
pub u8[] Conn.error() {
    return self.err[0:];
}

/* Decrypted bytes waiting: a read returns them without the socket */
pub i64 Conn.pending() {
    return ktm_tls_pending(self.native);
}

/* "TLSv1.3", "TLSv1.2" */
pub u8[] Conn.version() {
    return cstr.view(ktm_tls_version(self.native));
}

/* The cipher suite, as "TLS1-3-AES-256-GCM-SHA384" */
pub u8[] Conn.cipher() {
    return cstr.view(ktm_tls_cipher(self.native));
}

/* The protocol ALPN chose ("h2"), "" if none */
pub u8[] Conn.alpn() {
    return cstr.view(ktm_tls_alpn(self.native));
}

/* The peer's certificate (DER), valid while the connection is; "" if it
 * sent none. For channel binding (tls-server-end-point, as SCRAM-SHA-256-
 * PLUS uses): hash it with SHA-256, or with sigHash's algorithm when that
 * is stronger (mbedTLS md ids: 9 SHA-256, 10 SHA-384, 11 SHA-512). */
pub u8[] Conn.peerCert(i32 *sigHash = null) {
    u64 len = 0;
    i32 md = 0;
    u8 *p = ktm_tls_peer_cert(self.native, &len, &md);
    if (sigHash) { *sigHash = md; }
    if (!p) { return ""; }
    return p[0:(i64)len];
}

/* Tells the peer the session ends (close_notify) and frees it; the
 * socket stays open, for its owner to close */
pub void Conn.finish() {
    ktm_tls_close_notify(self.native);
    self.free();
}

/* Ends the session (tells the peer), frees it, closes the socket */
pub void Conn.close() {
    ktm_tls_close_notify(self.native);
    i32 fd = self.fd;
    self.free();
    if (coro.inLoop()) { coro.close(fd); } else { libc.close(fd); }
}

/* Frees the session and leaves the socket open (it holds TLS bytes no
 * one else can read: close it) */
pub void Conn.free() {
    ktm_tls_free(self.native);
    if (self.owned) { self.owned.free(); }
    self.err.free();
    mem.free(self);
}
