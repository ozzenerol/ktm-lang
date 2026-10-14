/* libktmtls: the small C layer net.tls (src/std/net/tls) calls, over
 * mbedTLS (src/vendor/mbedtls). It keeps mbedTLS's structs on the C
 * side: ktm sees opaque pointers and plain integers.
 *
 * A context (ktm_tls_ctx) is a configuration: client or server, the
 * trusted CAs, a certificate and key, how to verify. It is made once and
 * shared by every connection and thread. A connection (ktm_tls_conn) is
 * one TLS session over a socket the caller owns. Its calls never block on
 * their own account: on a non-blocking socket they answer
 * KTM_TLS_WANT_READ / KTM_TLS_WANT_WRITE and the caller waits (coro.wait
 * in a loop coroutine, poll(2) elsewhere) and calls again. */
#define _DEFAULT_SOURCE
#define _DARWIN_C_SOURCE
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/net_sockets.h"
#include "mbedtls/pk.h"
#include "mbedtls/ssl.h"
#include "mbedtls/x509_crt.h"
#include "psa/crypto.h"

#define KTM_TLS_WANT_READ  -1
#define KTM_TLS_WANT_WRITE -2
#define KTM_TLS_ERROR      -3

typedef struct ktm_tls_ctx {
    mbedtls_ssl_config conf;
    mbedtls_entropy_context entropy;
    mbedtls_ctr_drbg_context drbg;
    mbedtls_x509_crt ca;
    mbedtls_x509_crt cert;
    mbedtls_pk_context key;
    int is_server;
    int check_host;        /* verify the name as well as the chain */
    int has_ca, has_cert;
    char *alpn_buf;        /* ALPN names, and the list mbedTLS keeps */
    const char **alpn;
    char err[256];
} ktm_tls_ctx;

typedef struct ktm_tls_conn {
    mbedtls_ssl_context ssl;
    ktm_tls_ctx *ctx;
    int fd;
    int done;              /* the handshake finished */
    char err[256];
} ktm_tls_conn;

/* ---- start-up ---- */

static pthread_once_t ktm_tls_once = PTHREAD_ONCE_INIT;
static int ktm_tls_psa_status = 0;

static void ktmTlsInitOnce(void) {
    ktm_tls_psa_status = (int)psa_crypto_init();
}

static int ktmTlsInit(void) {
    pthread_once(&ktm_tls_once, ktmTlsInitOnce);
    return ktm_tls_psa_status == PSA_SUCCESS;
}

static void ktmTlsSetErr(char *buf, const char *what, int code) {
    char m[160];
    mbedtls_strerror(code, m, sizeof(m));
    snprintf(buf, 256, "%s: %s (-0x%04x)", what, m, (unsigned)-code);
}

/* ---- the socket ---- */

static int ktmTlsSend(void *p, const unsigned char *buf, size_t len) {
    ktm_tls_conn *c = p;
#ifdef MSG_NOSIGNAL
    ssize_t n = send(c->fd, buf, len, MSG_NOSIGNAL);
#else
    ssize_t n = send(c->fd, buf, len, 0);     /* macOS: SO_NOSIGPIPE, set below */
#endif
    if (n >= 0) return (int)n;
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_WRITE;
    if (errno == EPIPE || errno == ECONNRESET) return MBEDTLS_ERR_NET_CONN_RESET;
    return MBEDTLS_ERR_NET_SEND_FAILED;
}

static int ktmTlsRecv(void *p, unsigned char *buf, size_t len) {
    ktm_tls_conn *c = p;
    ssize_t n = recv(c->fd, buf, len, 0);
    if (n >= 0) return (int)n;
    if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return MBEDTLS_ERR_SSL_WANT_READ;
    if (errno == ECONNRESET) return MBEDTLS_ERR_NET_CONN_RESET;
    return MBEDTLS_ERR_NET_RECV_FAILED;
}

/* ---- contexts ---- */

/* Postgres' verify-ca and friends: the chain is checked, not the name */
static int ktmTlsVerify(void *p, mbedtls_x509_crt *crt, int depth, uint32_t *flags) {
    ktm_tls_ctx *ctx = p;
    (void)crt;
    (void)depth;
    if (!ctx->check_host) *flags &= ~MBEDTLS_X509_BADCERT_CN_MISMATCH;
    return 0;
}

/* A new context; NULL (with *err set) on an error. verify: 0 none,
 * 1 the chain only, 2 the chain and the name. */
ktm_tls_ctx *ktm_tls_ctx_new(int is_server, int verify, char *err, int errlen) {
    if (!ktmTlsInit()) {
        snprintf(err, errlen, "tls: the crypto library didn't start");
        return NULL;
    }
    ktm_tls_ctx *ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        snprintf(err, errlen, "tls: out of memory");
        return NULL;
    }
    ctx->is_server = is_server;
    ctx->check_host = verify >= 2;
    mbedtls_ssl_config_init(&ctx->conf);
    mbedtls_entropy_init(&ctx->entropy);
    mbedtls_ctr_drbg_init(&ctx->drbg);
    mbedtls_x509_crt_init(&ctx->ca);
    mbedtls_x509_crt_init(&ctx->cert);
    mbedtls_pk_init(&ctx->key);
    int r = mbedtls_ctr_drbg_seed(&ctx->drbg, mbedtls_entropy_func, &ctx->entropy,
                                  (const unsigned char *)"ktm-tls", 7);
    if (r == 0) {
        r = mbedtls_ssl_config_defaults(&ctx->conf,
                                        is_server ? MBEDTLS_SSL_IS_SERVER : MBEDTLS_SSL_IS_CLIENT,
                                        MBEDTLS_SSL_TRANSPORT_STREAM, MBEDTLS_SSL_PRESET_DEFAULT);
    }
    if (r != 0) {
        char m[256];
        ktmTlsSetErr(m, "tls: setting up", r);
        snprintf(err, errlen, "%s", m);
        mbedtls_ssl_config_free(&ctx->conf);
        mbedtls_ctr_drbg_free(&ctx->drbg);
        mbedtls_entropy_free(&ctx->entropy);
        free(ctx);
        return NULL;
    }
    mbedtls_ssl_conf_rng(&ctx->conf, mbedtls_ctr_drbg_random, &ctx->drbg);
    mbedtls_ssl_conf_authmode(&ctx->conf, verify ? MBEDTLS_SSL_VERIFY_REQUIRED : MBEDTLS_SSL_VERIFY_NONE);
    mbedtls_ssl_conf_verify(&ctx->conf, ktmTlsVerify, ctx);
    /* TLS 1.2 at least */
    mbedtls_ssl_conf_min_tls_version(&ctx->conf, MBEDTLS_SSL_VERSION_TLS1_2);
    return ctx;
}

const char *ktm_tls_ctx_error(ktm_tls_ctx *ctx) {
    return ctx->err;
}

static int ktmTlsUseCa(ktm_tls_ctx *ctx) {
    mbedtls_ssl_conf_ca_chain(&ctx->conf, &ctx->ca, NULL);
    ctx->has_ca = 1;
    return 0;
}

/* Trusted CAs from a PEM (or DER) file: 0, or -1 with the error */
int ktm_tls_ctx_ca_file(ktm_tls_ctx *ctx, const char *path) {
    int r = mbedtls_x509_crt_parse_file(&ctx->ca, path);
    if (r < 0) {
        char what[200];
        snprintf(what, sizeof(what), "tls: reading the CAs in %s", path);
        ktmTlsSetErr(ctx->err, what, r);
        return -1;
    }
    return ktmTlsUseCa(ctx);
}

/* Trusted CAs from PEM text (len bytes, need not end in NUL) */
int ktm_tls_ctx_ca_pem(ktm_tls_ctx *ctx, const char *pem, size_t len) {
    char *z = malloc(len + 1);
    if (!z) return -1;
    memcpy(z, pem, len);
    z[len] = '\0';
    int r = mbedtls_x509_crt_parse(&ctx->ca, (const unsigned char *)z, len + 1);
    free(z);
    if (r < 0) {
        ktmTlsSetErr(ctx->err, "tls: reading the CA certificates", r);
        return -1;
    }
    return ktmTlsUseCa(ctx);
}

/* The system's CAs: the first bundle found where Linux distributions and
 * macOS keep one. -1 when there is none. */
int ktm_tls_ctx_system_ca(ktm_tls_ctx *ctx) {
    static const char *const paths[] = {
        "/etc/ssl/certs/ca-certificates.crt",                 /* Debian, Ubuntu, Arch, Alpine */
        "/etc/pki/tls/certs/ca-bundle.crt",                   /* Fedora, RHEL */
        "/etc/ssl/ca-bundle.pem",                             /* openSUSE */
        "/etc/pki/ca-trust/extracted/pem/tls-ca-bundle.pem",  /* RHEL 7+ */
        "/etc/ssl/cert.pem",                                  /* macOS, Alpine, BSDs */
        "/opt/homebrew/etc/ca-certificates/cert.pem",
        "/usr/local/etc/ca-certificates/cert.pem",
    };
    const char *env = getenv("SSL_CERT_FILE");
    if (env && *env) return ktm_tls_ctx_ca_file(ctx, env);
    for (size_t i = 0; i < sizeof(paths) / sizeof(paths[0]); ++i) {
        struct stat st;
        if (stat(paths[i], &st) == 0 && S_ISREG(st.st_mode)) {
            if (ktm_tls_ctx_ca_file(ctx, paths[i]) == 0) return 0;
        }
    }
    snprintf(ctx->err, sizeof(ctx->err),
             "tls: no system CA bundle found (set SSL_CERT_FILE, or give the CAs)");
    return -1;
}

/* This side's certificate chain and key, PEM text each */
int ktm_tls_ctx_cert(ktm_tls_ctx *ctx, const char *cert, size_t clen, const char *key, size_t klen,
                     const char *pass) {
    char *cz = malloc(clen + 1), *kz = malloc(klen + 1);
    if (!cz || !kz) {
        free(cz);
        free(kz);
        return -1;
    }
    memcpy(cz, cert, clen);
    cz[clen] = '\0';
    memcpy(kz, key, klen);
    kz[klen] = '\0';
    int r = mbedtls_x509_crt_parse(&ctx->cert, (const unsigned char *)cz, clen + 1);
    if (r < 0) {
        ktmTlsSetErr(ctx->err, "tls: reading the certificate", r);
    } else {
        r = mbedtls_pk_parse_key(&ctx->key, (const unsigned char *)kz, klen + 1,
                                 (const unsigned char *)pass, pass ? strlen(pass) : 0,
                                 mbedtls_ctr_drbg_random, &ctx->drbg);
        if (r < 0) ktmTlsSetErr(ctx->err, "tls: reading the private key", r);
    }
    if (r >= 0) {
        r = mbedtls_pk_check_pair(&ctx->cert.pk, &ctx->key, mbedtls_ctr_drbg_random, &ctx->drbg);
        if (r != 0) {
            snprintf(ctx->err, sizeof(ctx->err),
                     "tls: the private key doesn't belong to the certificate");
            r = -1;
        }
    }
    if (r >= 0) {
        r = mbedtls_ssl_conf_own_cert(&ctx->conf, &ctx->cert, &ctx->key);
        if (r < 0) ktmTlsSetErr(ctx->err, "tls: the certificate and the key", r);
    }
    memset(kz, 0, klen);
    free(cz);
    free(kz);
    if (r < 0) return -1;
    ctx->has_cert = 1;
    return 0;
}

/* ALPN protocols, a comma-separated list ("h2,http/1.1"); kept by ctx */
int ktm_tls_ctx_alpn(ktm_tls_ctx *ctx, const char *list) {
    free(ctx->alpn_buf);
    free(ctx->alpn);
    ctx->alpn_buf = strdup(list);
    ctx->alpn = calloc(9, sizeof(char *));
    if (!ctx->alpn_buf || !ctx->alpn) return -1;
    int n = 0;
    char *save = NULL;
    for (char *tok = strtok_r(ctx->alpn_buf, ",", &save); tok && n < 8; tok = strtok_r(NULL, ",", &save)) {
        ctx->alpn[n++] = tok;
    }
    int r = mbedtls_ssl_conf_alpn_protocols(&ctx->conf, ctx->alpn);
    if (r != 0) {
        ktmTlsSetErr(ctx->err, "tls: ALPN", r);
        return -1;
    }
    return 0;
}

void ktm_tls_ctx_free(ktm_tls_ctx *ctx) {
    if (!ctx) return;
    mbedtls_ssl_config_free(&ctx->conf);
    mbedtls_x509_crt_free(&ctx->ca);
    mbedtls_x509_crt_free(&ctx->cert);
    mbedtls_pk_free(&ctx->key);
    mbedtls_ctr_drbg_free(&ctx->drbg);
    mbedtls_entropy_free(&ctx->entropy);
    free(ctx->alpn_buf);
    free(ctx->alpn);
    free(ctx);
}

/* ---- connections ---- */

/* A session over fd. host: the name to send (SNI) and, as the context
 * says, to verify; NULL or "" for none. NULL on an error (err set). */
ktm_tls_conn *ktm_tls_new(ktm_tls_ctx *ctx, int fd, const char *host, char *err, int errlen) {
    ktm_tls_conn *c = calloc(1, sizeof(*c));
    if (!c) {
        snprintf(err, errlen, "tls: out of memory");
        return NULL;
    }
    c->ctx = ctx;
    c->fd = fd;
    mbedtls_ssl_init(&c->ssl);
    int r = mbedtls_ssl_setup(&c->ssl, &ctx->conf);
    if (r == 0 && host && *host && !ctx->is_server) {
        r = mbedtls_ssl_set_hostname(&c->ssl, host);
    }
    if (r == 0 && !ctx->is_server && (!host || !*host) && ctx->check_host) {
        /* nothing to check the name against */
        r = mbedtls_ssl_set_hostname(&c->ssl, NULL);
    }
    if (r != 0) {
        char m[256];
        ktmTlsSetErr(m, "tls", r);
        snprintf(err, errlen, "%s", m);
        mbedtls_ssl_free(&c->ssl);
        free(c);
        return NULL;
    }
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
    mbedtls_ssl_set_bio(&c->ssl, c, ktmTlsSend, ktmTlsRecv, NULL);
    return c;
}

static int ktmTlsWant(ktm_tls_conn *c, int r, const char *what) {
    if (r == MBEDTLS_ERR_SSL_WANT_READ) return KTM_TLS_WANT_READ;
    if (r == MBEDTLS_ERR_SSL_WANT_WRITE) return KTM_TLS_WANT_WRITE;
    /* TLS 1.3: a session ticket came in the middle of a read */
    if (r == MBEDTLS_ERR_SSL_RECEIVED_NEW_SESSION_TICKET) return KTM_TLS_WANT_READ;
    if (r == MBEDTLS_ERR_X509_CERT_VERIFY_FAILED) {
        char info[200];
        uint32_t flags = mbedtls_ssl_get_verify_result(&c->ssl);
        int n = mbedtls_x509_crt_verify_info(info, sizeof(info), "", flags);
        while (n > 0 && (info[n - 1] == '\n' || info[n - 1] == ' ')) info[--n] = '\0';
        for (char *p = info; *p; ++p) if (*p == '\n') *p = ';';
        snprintf(c->err, sizeof(c->err), "tls: the server's certificate isn't trusted: %s", info);
        return KTM_TLS_ERROR;
    }
    ktmTlsSetErr(c->err, what, r);
    return KTM_TLS_ERROR;
}

/* 0 when the handshake is done, else WANT_READ / WANT_WRITE / ERROR */
int ktm_tls_handshake(ktm_tls_conn *c) {
    if (c->done) return 0;
    int r = mbedtls_ssl_handshake(&c->ssl);
    if (r == 0) {
        c->done = 1;
        return 0;
    }
    return ktmTlsWant(c, r, "tls: handshake");
}

/* Bytes read (> 0), 0 at the end (the peer closed), or WANT_READ /
 * WANT_WRITE / ERROR */
long ktm_tls_read(ktm_tls_conn *c, void *buf, size_t len) {
    int r = mbedtls_ssl_read(&c->ssl, buf, len);
    if (r > 0) return r;
    if (r == 0 || r == MBEDTLS_ERR_SSL_PEER_CLOSE_NOTIFY) return 0;
    if (r == MBEDTLS_ERR_NET_CONN_RESET) {
        snprintf(c->err, sizeof(c->err), "tls: the connection was reset");
        return KTM_TLS_ERROR;
    }
    return ktmTlsWant(c, r, "tls: read");
}

/* Bytes written (> 0, maybe fewer than len), or WANT_* / ERROR */
long ktm_tls_write(ktm_tls_conn *c, const void *buf, size_t len) {
    int r = mbedtls_ssl_write(&c->ssl, buf, len);
    if (r >= 0) return r;
    return ktmTlsWant(c, r, "tls: write");
}

/* Bytes already decrypted and waiting: a read takes them without the
 * socket */
long ktm_tls_pending(ktm_tls_conn *c) {
    return (long)mbedtls_ssl_get_bytes_avail(&c->ssl);
}

/* Tells the peer the session ends (best effort, no waiting) */
void ktm_tls_close_notify(ktm_tls_conn *c) {
    if (c->done) mbedtls_ssl_close_notify(&c->ssl);
}

const char *ktm_tls_error(ktm_tls_conn *c) {
    return c->err;
}

const char *ktm_tls_version(ktm_tls_conn *c) {
    return mbedtls_ssl_get_version(&c->ssl);
}

const char *ktm_tls_cipher(ktm_tls_conn *c) {
    return mbedtls_ssl_get_ciphersuite(&c->ssl);
}

const char *ktm_tls_alpn(ktm_tls_conn *c) {
    const char *p = mbedtls_ssl_get_alpn_protocol(&c->ssl);
    return p ? p : "";
}

/* The peer's certificate (DER), NULL when it sent none. For channel
 * binding (tls-server-end-point, RFC 5929): hash it. *sig_md: the hash of
 * its signature (MBEDTLS_MD_SHA256 = 9, SHA384 = 10, SHA512 = 11, ...). */
const unsigned char *ktm_tls_peer_cert(ktm_tls_conn *c, size_t *len, int *sig_md) {
    const mbedtls_x509_crt *crt = mbedtls_ssl_get_peer_cert(&c->ssl);
    if (!crt) {
        *len = 0;
        return NULL;
    }
    *len = crt->raw.len;
    if (sig_md) *sig_md = (int)crt->MBEDTLS_PRIVATE(sig_md);
    return crt->raw.p;
}

void ktm_tls_free(ktm_tls_conn *c) {
    if (!c) return;
    mbedtls_ssl_free(&c->ssl);
    free(c);
}
