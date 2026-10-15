/* libktmtls's crypto layer: what the crypto module (src/std/crypto) calls,
 * over mbedTLS's primitives. Plain buffers in and out; the mbedTLS
 * structs stay on this side. 0 on success, a negative mbedTLS code
 * otherwise (ktm_crypto_error() says it in words). */
#define _DEFAULT_SOURCE
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "mbedtls/chachapoly.h"
#include "mbedtls/ctr_drbg.h"
#include "mbedtls/ecdsa.h"
#include "mbedtls/entropy.h"
#include "mbedtls/error.h"
#include "mbedtls/gcm.h"
#include "mbedtls/hkdf.h"
#include "mbedtls/md.h"
#include "mbedtls/pem.h"
#include "mbedtls/pk.h"
#include "mbedtls/pkcs5.h"
#include "mbedtls/rsa.h"
#include "mbedtls/sha256.h"
#include "mbedtls/sha512.h"
#include "psa/crypto.h"
#include "ktm_monocypher_names.h"
#include "monocypher-ed25519.h"

/* ---- random: one generator, seeded from the OS, locked (threading on) ---- */

static pthread_once_t ktm_crypto_once = PTHREAD_ONCE_INIT;
static mbedtls_entropy_context ktm_crypto_entropy;
static mbedtls_ctr_drbg_context ktm_crypto_drbg;
static int ktm_crypto_ready = 0;

static void ktmCryptoInit(void) {
    psa_crypto_init();
    mbedtls_entropy_init(&ktm_crypto_entropy);
    mbedtls_ctr_drbg_init(&ktm_crypto_drbg);
    ktm_crypto_ready = mbedtls_ctr_drbg_seed(&ktm_crypto_drbg, mbedtls_entropy_func, &ktm_crypto_entropy,
                                             (const unsigned char *)"ktm-crypto", 10) == 0;
}

static int ktmCryptoStart(void) {
    pthread_once(&ktm_crypto_once, ktmCryptoInit);
    return ktm_crypto_ready;
}

/* len cryptographically secure random bytes into buf */
int ktm_crypto_random(unsigned char *buf, size_t len) {
    if (!ktmCryptoStart()) return -1;
    while (len > 0) {
        size_t n = len > MBEDTLS_CTR_DRBG_MAX_REQUEST ? MBEDTLS_CTR_DRBG_MAX_REQUEST : len;
        int r = mbedtls_ctr_drbg_random(&ktm_crypto_drbg, buf, n);
        if (r != 0) return r;
        buf += n;
        len -= n;
    }
    return 0;
}

void ktm_crypto_error(int code, char *out, size_t len) {
    mbedtls_strerror(code, out, len);
}

/* ---- hashes ---- */

int ktm_crypto_sha256(const unsigned char *in, size_t len, unsigned char *out) {
    return mbedtls_sha256(in, len, out, 0);
}

int ktm_crypto_sha512(const unsigned char *in, size_t len, unsigned char *out) {
    return mbedtls_sha512(in, len, out, 0);
}

int ktm_crypto_sha384(const unsigned char *in, size_t len, unsigned char *out) {
    return mbedtls_sha512(in, len, out, 1);
}

/* md: 256, 384 or 512 */
static const mbedtls_md_info_t *ktmCryptoMd(int bits) {
    return mbedtls_md_info_from_type(bits == 512 ? MBEDTLS_MD_SHA512
                                     : bits == 384 ? MBEDTLS_MD_SHA384 : MBEDTLS_MD_SHA256);
}

int ktm_crypto_hmac(int bits, const unsigned char *key, size_t klen, const unsigned char *in, size_t len,
                    unsigned char *out) {
    return mbedtls_md_hmac(ktmCryptoMd(bits), key, klen, in, len, out);
}

int ktm_crypto_hkdf(int bits, const unsigned char *salt, size_t slen, const unsigned char *ikm, size_t ilen,
                    const unsigned char *info, size_t infolen, unsigned char *out, size_t olen) {
    return mbedtls_hkdf(ktmCryptoMd(bits), salt, slen, ikm, ilen, info, infolen, out, olen);
}

int ktm_crypto_pbkdf2(int bits, const unsigned char *pw, size_t plen, const unsigned char *salt, size_t slen,
                      unsigned int iterations, unsigned char *out, size_t olen) {
    return mbedtls_pkcs5_pbkdf2_hmac_ext(bits == 512 ? MBEDTLS_MD_SHA512 : MBEDTLS_MD_SHA256, pw, plen, salt, slen,
                                         iterations, (uint32_t)olen, out);
}

/* ---- authenticated encryption: 32-byte key, 12-byte nonce, 16-byte tag ---- */

/* alg 0: AES-256-GCM, 1: ChaCha20-Poly1305. out: len bytes, then the tag */
int ktm_crypto_seal(int alg, const unsigned char *key, const unsigned char *nonce,
                    const unsigned char *aad, size_t alen, const unsigned char *in, size_t len,
                    unsigned char *out) {
    int r;
    if (alg == 0) {
        mbedtls_gcm_context g;
        mbedtls_gcm_init(&g);
        r = mbedtls_gcm_setkey(&g, MBEDTLS_CIPHER_ID_AES, key, 256);
        if (r == 0) r = mbedtls_gcm_crypt_and_tag(&g, MBEDTLS_GCM_ENCRYPT, len, nonce, 12, aad, alen,
                                                  in, out, 16, out + len);
        mbedtls_gcm_free(&g);
    } else {
        mbedtls_chachapoly_context c;
        mbedtls_chachapoly_init(&c);
        r = mbedtls_chachapoly_setkey(&c, key);
        if (r == 0) r = mbedtls_chachapoly_encrypt_and_tag(&c, len, nonce, aad, alen, in, out, out + len);
        mbedtls_chachapoly_free(&c);
    }
    return r;
}

/* in: len bytes of ciphertext then the 16-byte tag; out: len bytes.
 * MBEDTLS_ERR_GCM_AUTH_FAILED / MBEDTLS_ERR_CHACHAPOLY_AUTH_FAILED when
 * it was altered or the key, nonce or aad differ. */
int ktm_crypto_open(int alg, const unsigned char *key, const unsigned char *nonce,
                    const unsigned char *aad, size_t alen, const unsigned char *in, size_t len,
                    unsigned char *out) {
    int r;
    if (alg == 0) {
        mbedtls_gcm_context g;
        mbedtls_gcm_init(&g);
        r = mbedtls_gcm_setkey(&g, MBEDTLS_CIPHER_ID_AES, key, 256);
        if (r == 0) r = mbedtls_gcm_auth_decrypt(&g, len, nonce, 12, aad, alen, in + len, 16, in, out);
        mbedtls_gcm_free(&g);
    } else {
        mbedtls_chachapoly_context c;
        mbedtls_chachapoly_init(&c);
        r = mbedtls_chachapoly_setkey(&c, key);
        if (r == 0) r = mbedtls_chachapoly_auth_decrypt(&c, len, nonce, aad, alen, in + len, in, out);
        mbedtls_chachapoly_free(&c);
    }
    if (r != 0) memset(out, 0, len);
    return r;
}

/* ---- keys ---- */

/* What a crypto.Key points at: mbedTLS's key (first, so a KtmKey * is
 * also its mbedtls_pk_context *), or an Ed25519 key, which mbedTLS
 * doesn't have (Monocypher's, ktmed25519.c) */
typedef struct {
    mbedtls_pk_context pk;
    int ed;                     /* Ed25519: 1 public, 2 private; 0: pk */
    unsigned char ed_sk[64];    /* seed || public, as Monocypher has it */
    unsigned char ed_pub[32];
} KtmKey;

static mbedtls_pk_context *ktmKeyNew(void) {
    KtmKey *k = calloc(1, sizeof(KtmKey));
    mbedtls_pk_init(&k->pk);
    return &k->pk;
}

static int ktmKeyIsEd(void *key) {
    return ((KtmKey *)key)->ed != 0;
}

static int ktmEdLoad(const unsigned char *z, size_t len, int priv, void **out, int *err);


/* The curves crypto.Key takes, by size: 256, 384, 521 */
static mbedtls_ecp_group_id ktmCurve(int bits) {
    return bits == 521 ? MBEDTLS_ECP_DP_SECP521R1 : bits == 384 ? MBEDTLS_ECP_DP_SECP384R1
                                                                  : MBEDTLS_ECP_DP_SECP256R1;
}

/* An EC key's coordinate size in bytes: 32, 48 or 66 */
static size_t ktmEcBytes(mbedtls_pk_context *pk) {
    return (mbedtls_pk_get_bitlen(pk) + 7) / 8;
}

/* A new random private EC key on the curve of bits bits (256, 384, 521) */
void *ktm_crypto_ec_generate(int bits, int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    mbedtls_pk_context *pk = ktmKeyNew();
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    if (r == 0) r = mbedtls_ecp_gen_key(ktmCurve(bits), mbedtls_pk_ec(*pk),
                                        mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* A new random private P-256 key */
void *ktm_crypto_key_generate(int *err) {
    return ktm_crypto_ec_generate(256, err);
}

/* The keys crypto.Key takes: P-256, P-384, P-521, and RSA of 2048 bits
 * or more */
static int ktmCryptoKeyUsable(mbedtls_pk_context *pk) {
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA) return mbedtls_pk_get_bitlen(pk) >= 2048;
    if (!mbedtls_pk_can_do(pk, MBEDTLS_PK_ECKEY)) return 0;
    mbedtls_ecp_group_id id = mbedtls_pk_ec(*pk)->MBEDTLS_PRIVATE(grp).id;
    return id == MBEDTLS_ECP_DP_SECP256R1 || id == MBEDTLS_ECP_DP_SECP384R1 || id == MBEDTLS_ECP_DP_SECP521R1;
}

/* A PEM (or DER) key: private when priv, else public. NULL with *err. */
void *ktm_crypto_key_load(const unsigned char *pem, size_t len, int priv, int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    unsigned char *z = malloc(len + 1);
    memcpy(z, pem, len);
    z[len] = 0;
    void *ed = NULL;
    int edr = ktmEdLoad(z, len, priv, &ed, err);
    if (edr != 0) {
        free(z);
        return edr > 0 ? ed : NULL;
    }
    mbedtls_pk_context *pk = ktmKeyNew();
    int r = priv ? mbedtls_pk_parse_key(pk, z, len + 1, NULL, 0, mbedtls_ctr_drbg_random, &ktm_crypto_drbg)
                 : mbedtls_pk_parse_public_key(pk, z, len + 1);
    free(z);
    if (r == 0 && !ktmCryptoKeyUsable(pk)) r = MBEDTLS_ERR_PK_TYPE_MISMATCH;
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* A public EC key from its point's coordinates, as a JWK's x and y: 32
 * bytes each for P-256, 48 for P-384, 66 for P-521. NULL with *err. */
void *ktm_crypto_ec_public(const unsigned char *x, size_t xlen, const unsigned char *y, size_t ylen,
                           int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    if (xlen != ylen || (xlen != 32 && xlen != 48 && xlen != 66)) {
        *err = MBEDTLS_ERR_PK_INVALID_PUBKEY;
        return NULL;
    }
    unsigned char pt[1 + 2 * 66];
    pt[0] = 0x04;               /* uncompressed */
    memcpy(pt + 1, x, xlen);
    memcpy(pt + 1 + xlen, y, xlen);
    mbedtls_pk_context *pk = ktmKeyNew();
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    mbedtls_ecp_keypair *k = r == 0 ? mbedtls_pk_ec(*pk) : NULL;
    if (r == 0) r = mbedtls_ecp_group_load(&k->MBEDTLS_PRIVATE(grp),
                                           ktmCurve(xlen == 66 ? 521 : xlen == 48 ? 384 : 256));
    if (r == 0) r = mbedtls_ecp_point_read_binary(&k->MBEDTLS_PRIVATE(grp), &k->MBEDTLS_PRIVATE(Q), pt,
                                                  1 + 2 * xlen);
    if (r == 0) r = mbedtls_ecp_check_pubkey(&k->MBEDTLS_PRIVATE(grp), &k->MBEDTLS_PRIVATE(Q));
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* A public RSA key from its modulus and exponent (big-endian, as a JWK's
 * n and e), 2048 bits or more. NULL with *err. */
void *ktm_crypto_rsa_public(const unsigned char *n, size_t nlen, const unsigned char *e, size_t elen, int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    mbedtls_pk_context *pk = ktmKeyNew();
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA));
    mbedtls_rsa_context *rsa = r == 0 ? mbedtls_pk_rsa(*pk) : NULL;
    if (r == 0) r = mbedtls_rsa_import_raw(rsa, n, nlen, NULL, 0, NULL, 0, NULL, 0, e, elen);
    if (r == 0) r = mbedtls_rsa_complete(rsa);
    if (r == 0) r = mbedtls_rsa_check_pubkey(rsa);
    if (r == 0 && !ktmCryptoKeyUsable(pk)) r = MBEDTLS_ERR_PK_TYPE_MISMATCH;
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* A new RSA private key of bits bits (exponent 65537). NULL with *err. */
void *ktm_crypto_rsa_generate(int bits, int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    mbedtls_pk_context *pk = ktmKeyNew();
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_RSA));
    if (r == 0) r = mbedtls_rsa_gen_key(mbedtls_pk_rsa(*pk), mbedtls_ctr_drbg_random, &ktm_crypto_drbg,
                                        (unsigned int)bits, 65537);
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* 0: P-256, 1: RSA, 2: Ed25519, 3: P-384, 4: P-521 */
int ktm_crypto_key_kind(void *key) {
    if (ktmKeyIsEd(key)) return 2;
    if (mbedtls_pk_get_type(key) == MBEDTLS_PK_RSA) return 1;
    size_t bits = mbedtls_pk_get_bitlen(key);
    return bits == 521 ? 4 : bits == 384 ? 3 : 0;
}

/* An EC key's public point into x and y (66 bytes of room each), its
 * coordinate size in *n: -1 for another kind of key */
int ktm_crypto_ec_point(void *key, unsigned char *x, unsigned char *y, size_t *n) {
    if (ktmKeyIsEd(key) || mbedtls_pk_get_type(key) == MBEDTLS_PK_RSA) return -1;
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*(mbedtls_pk_context *)key);
    unsigned char pt[1 + 2 * 66];
    size_t len = 0;
    int r = mbedtls_ecp_point_write_binary(&k->MBEDTLS_PRIVATE(grp), &k->MBEDTLS_PRIVATE(Q),
                                           MBEDTLS_ECP_PF_UNCOMPRESSED, &len, pt, sizeof(pt));
    if (r != 0) return r;
    *n = (len - 1) / 2;
    memcpy(x, pt + 1, *n);
    memcpy(y, pt + 1 + *n, *n);
    return 0;
}

long ktm_crypto_key_bits(void *key) {
    if (ktmKeyIsEd(key)) return 256;
    return (long)mbedtls_pk_get_bitlen(key);
}

static int ktmEdPem(KtmKey *k, int priv, unsigned char *out, size_t outlen);

/* The key as PEM into out (outlen bytes): its length, or a negative code */
long ktm_crypto_key_pem(void *key, int priv, unsigned char *out, size_t outlen) {
    if (ktmKeyIsEd(key)) {
        int r = ktmEdPem(key, priv, out, outlen);
        return r != 0 ? r : (long)strlen((char *)out);
    }
    int r = priv ? mbedtls_pk_write_key_pem(key, out, outlen) : mbedtls_pk_write_pubkey_pem(key, out, outlen);
    if (r != 0) return r;
    return (long)strlen((char *)out);
}

/* SHA-256, -384 or -512 (bits) of data into h: its length */
static size_t ktmCryptoDigest(int bits, const unsigned char *data, size_t len, unsigned char *h,
                              mbedtls_md_type_t *md) {
    *md = bits == 512 ? MBEDTLS_MD_SHA512 : bits == 384 ? MBEDTLS_MD_SHA384 : MBEDTLS_MD_SHA256;
    if (bits == 512 || bits == 384) {
        mbedtls_sha512(data, len, h, bits == 384);
        return bits / 8;
    }
    mbedtls_sha256(data, len, h, 0);
    return 32;
}

/* The signature of the bits-bit SHA-2 digest of data into sig (cap
 * bytes), its length in *olen: ECDSA as raw r||s (64, 96 or 132 bytes
 * for P-256, P-384, P-521, as JWT's ES256/384/512 have it,
 * deterministic: RFC 6979), RSA as PKCS#1 v1.5 (the key's size, as
 * RS256) */
int ktm_crypto_sign_with(void *key, int bits, const unsigned char *data, size_t len, unsigned char *sig,
                         size_t cap, size_t *olen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    if (ktmKeyIsEd(key)) return MBEDTLS_ERR_PK_TYPE_MISMATCH;     /* Ed25519 hashes itself */
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    mbedtls_pk_context *pk = key;
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA) {
        return mbedtls_pk_sign(pk, md, h, hlen, sig, cap, olen, mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    }
    size_t n = ktmEcBytes(pk);
    if (cap < 2 * n) return MBEDTLS_ERR_PK_BUFFER_TOO_SMALL;
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*pk);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_ecdsa_sign_det_ext(&k->MBEDTLS_PRIVATE(grp), &r, &s, &k->MBEDTLS_PRIVATE(d), h, hlen,
                                       md, mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    if (e == 0) e = mbedtls_mpi_write_binary(&r, sig, n);
    if (e == 0) e = mbedtls_mpi_write_binary(&s, sig + n, n);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    *olen = 2 * n;
    return e;
}

/* 0 when sig is key's signature of the bits-bit SHA-2 digest of data
 * (ECDSA: raw r||s of twice the coordinate size, or DER; RSA: PKCS#1
 * v1.5) */
int ktm_crypto_verify_with(void *key, int bits, const unsigned char *data, size_t len,
                           const unsigned char *sig, size_t slen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    if (ktmKeyIsEd(key)) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    mbedtls_pk_context *pk = key;
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA || slen != 2 * ktmEcBytes(pk)) {
        return mbedtls_pk_verify(pk, md, h, hlen, sig, slen);
    }
    size_t n = slen / 2;
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*pk);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_mpi_read_binary(&r, sig, n);
    if (e == 0) e = mbedtls_mpi_read_binary(&s, sig + n, n);
    if (e == 0) e = mbedtls_ecdsa_verify(&k->MBEDTLS_PRIVATE(grp), h, hlen, &k->MBEDTLS_PRIVATE(Q), &r, &s);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    return e;
}

/* ---- RSA-PSS (PKCS#1 v2.1): MGF1 with the same hash, salt as long as
 * the hash, as JWT's PS256/384/512 have it ---- */

/* The PSS signature of the bits-bit SHA-2 digest of data (the key's size) */
int ktm_crypto_sign_pss(void *key, int bits, const unsigned char *data, size_t len, unsigned char *sig,
                        size_t cap, size_t *olen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    if (ktmKeyIsEd(key) || mbedtls_pk_get_type(key) != MBEDTLS_PK_RSA) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
    mbedtls_rsa_context *rsa = mbedtls_pk_rsa(*(mbedtls_pk_context *)key);
    size_t n = mbedtls_rsa_get_len(rsa);
    if (cap < n) return MBEDTLS_ERR_PK_BUFFER_TOO_SMALL;
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    /* a copy set to PSS: the key's own context stays PKCS#1 v1.5 for
     * signWith, and other threads may be signing with it */
    mbedtls_rsa_context tmp;
    mbedtls_rsa_init(&tmp);
    int r = mbedtls_rsa_copy(&tmp, rsa);
    if (r == 0) r = mbedtls_rsa_set_padding(&tmp, MBEDTLS_RSA_PKCS_V21, md);
    if (r == 0) r = mbedtls_rsa_rsassa_pss_sign_ext(&tmp, mbedtls_ctr_drbg_random, &ktm_crypto_drbg, md,
                                                    (unsigned int)hlen, h, (int)hlen, sig);
    mbedtls_rsa_free(&tmp);
    if (r == 0) *olen = n;
    return r;
}

/* 0 when sig is key's PSS signature of the bits-bit SHA-2 digest of data,
 * with a salt as long as the hash */
int ktm_crypto_verify_pss(void *key, int bits, const unsigned char *data, size_t len, const unsigned char *sig,
                          size_t slen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    if (ktmKeyIsEd(key) || mbedtls_pk_get_type(key) != MBEDTLS_PK_RSA) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
    mbedtls_rsa_context *rsa = mbedtls_pk_rsa(*(mbedtls_pk_context *)key);
    if (slen != mbedtls_rsa_get_len(rsa)) return MBEDTLS_ERR_RSA_VERIFY_FAILED;
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    return mbedtls_rsa_rsassa_pss_verify_ext(rsa, md, (unsigned int)hlen, h, md, (int)hlen, sig);
}

void ktm_crypto_key_free(void *key) {
    if (!key) return;
    mbedtls_pk_free(key);
    mbedtls_platform_zeroize(key, sizeof(KtmKey));
    free(key);
}

/* ---- Ed25519 (RFC 8032), Monocypher's ---- */

/* The DER an Ed25519 key is in PEM, before its 32 bytes: PKCS#8 for the
 * private key's seed (RFC 8410), SubjectPublicKeyInfo for the public key.
 * Read only exactly so: anything else isn't taken. */
static const unsigned char ktm_ed_priv_der[16] = {
    0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20 };
static const unsigned char ktm_ed_pub_der[12] = {
    0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00 };

static KtmKey *ktmEdNew(const unsigned char *seed, const unsigned char *pub) {
    KtmKey *k = (KtmKey *)ktmKeyNew();
    if (seed) {
        unsigned char s[32];
        memcpy(s, seed, 32);
        crypto_ed25519_key_pair(k->ed_sk, k->ed_pub, s);   /* wipes s */
        k->ed = 2;
    } else {
        memcpy(k->ed_pub, pub, 32);
        k->ed = 1;
    }
    return k;
}

/* A new random private Ed25519 key. NULL with *err. */
void *ktm_crypto_ed25519_generate(int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    unsigned char seed[32];
    int r = mbedtls_ctr_drbg_random(&ktm_crypto_drbg, seed, 32);
    if (r != 0) { *err = r; return NULL; }
    KtmKey *k = ktmEdNew(seed, NULL);
    mbedtls_platform_zeroize(seed, 32);
    return k;
}

/* A public Ed25519 key from its 32 bytes (a JWK's OKP "x") */
void *ktm_crypto_ed25519_public(const unsigned char *x, size_t len, int *err) {
    if (len != 32) { *err = MBEDTLS_ERR_PK_INVALID_PUBKEY; return NULL; }
    return ktmEdNew(NULL, x);
}

/* Is the PEM text (len bytes, NUL after them) an Ed25519 key? 1 with
 * *out made, 0 when it is another kind (mbedTLS reads it), -1 with *err
 * when it says Ed25519 but isn't exactly one */
static int ktmEdLoad(const unsigned char *z, size_t len, int priv, void **out, int *err) {
    mbedtls_pem_context pem;
    size_t used = 0;
    const char *head = priv ? "-----BEGIN PRIVATE KEY-----" : "-----BEGIN PUBLIC KEY-----";
    const char *foot = priv ? "-----END PRIVATE KEY-----" : "-----END PUBLIC KEY-----";
    mbedtls_pem_init(&pem);
    if (mbedtls_pem_read_buffer(&pem, head, foot, z, NULL, 0, &used) != 0) {
        mbedtls_pem_free(&pem);
        return 0;
    }
    size_t n;
    const unsigned char *der = mbedtls_pem_get_buffer(&pem, &n);
    /* the algorithm's OID (1.3.101.112) where an Ed25519 key has it */
    static const unsigned char oid[5] = { 0x06, 0x03, 0x2b, 0x65, 0x70 };
    size_t at = priv ? 7 : 4;
    if (n < at + 5 || memcmp(der + at, oid, 5) != 0) {
        mbedtls_pem_free(&pem);
        return 0;
    }
    const unsigned char *want = priv ? ktm_ed_priv_der : ktm_ed_pub_der;
    size_t wlen = priv ? sizeof(ktm_ed_priv_der) : sizeof(ktm_ed_pub_der);
    /* nothing but blanks around the PEM block */
    int ok = n == wlen + 32 && memcmp(der, want, wlen) == 0;
    const unsigned char *start = (const unsigned char *)strstr((const char *)z, head);
    for (const unsigned char *p = z; ok && p < start; p++) ok = *p == ' ' || *p == '\n' || *p == '\r' || *p == '\t';
    for (const unsigned char *p = start + used; ok && p < z + len; p++) {
        ok = *p == ' ' || *p == '\n' || *p == '\r' || *p == '\t';
    }
    if (!ok) {
        mbedtls_pem_free(&pem);
        *err = MBEDTLS_ERR_PK_KEY_INVALID_FORMAT;
        return -1;
    }
    *out = priv ? ktmEdNew(der + wlen, NULL) : ktmEdNew(NULL, der + wlen);
    mbedtls_pem_free(&pem);
    return 1;
}

/* An Ed25519 key as PEM into out: 0, or a negative code */
static int ktmEdPem(KtmKey *k, int priv, unsigned char *out, size_t outlen) {
    unsigned char der[48];
    size_t n;
    if (priv) {
        if (k->ed != 2) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
        memcpy(der, ktm_ed_priv_der, sizeof(ktm_ed_priv_der));
        memcpy(der + sizeof(ktm_ed_priv_der), k->ed_sk, 32);       /* the seed */
        n = sizeof(ktm_ed_priv_der) + 32;
    } else {
        memcpy(der, ktm_ed_pub_der, sizeof(ktm_ed_pub_der));
        memcpy(der + sizeof(ktm_ed_pub_der), k->ed_pub, 32);
        n = sizeof(ktm_ed_pub_der) + 32;
    }
    size_t olen = 0;
    int r = mbedtls_pem_write_buffer(priv ? "-----BEGIN PRIVATE KEY-----\n" : "-----BEGIN PUBLIC KEY-----\n",
                                     priv ? "-----END PRIVATE KEY-----\n" : "-----END PUBLIC KEY-----\n",
                                     der, n, out, outlen, &olen);
    mbedtls_platform_zeroize(der, sizeof(der));
    return r;
}

/* The 64-byte Ed25519 signature of data (a private key's) */
int ktm_crypto_ed25519_sign(void *key, const unsigned char *data, size_t len, unsigned char *sig) {
    KtmKey *k = key;
    if (k->ed != 2) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
    crypto_ed25519_sign(sig, k->ed_sk, data, len);
    return 0;
}

/* 0 when sig is key's Ed25519 signature of data */
int ktm_crypto_ed25519_verify(void *key, const unsigned char *data, size_t len, const unsigned char *sig,
                              size_t slen) {
    KtmKey *k = key;
    if (!k->ed || slen != 64) return MBEDTLS_ERR_PK_TYPE_MISMATCH;
    return crypto_ed25519_check(sig, k->ed_pub, data, len) == 0 ? 0 : MBEDTLS_ERR_ECP_VERIFY_FAILED;
}
