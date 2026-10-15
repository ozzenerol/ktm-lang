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
#include "mbedtls/pk.h"
#include "mbedtls/pkcs5.h"
#include "mbedtls/rsa.h"
#include "mbedtls/sha256.h"
#include "mbedtls/sha512.h"
#include "psa/crypto.h"

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

/* md: 256 or 512 */
static const mbedtls_md_info_t *ktmCryptoMd(int bits) {
    return mbedtls_md_info_from_type(bits == 512 ? MBEDTLS_MD_SHA512 : MBEDTLS_MD_SHA256);
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

/* ---- ECDSA P-256 keys ---- */

void *ktm_crypto_key_generate(int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    mbedtls_pk_context *pk = calloc(1, sizeof(*pk));
    mbedtls_pk_init(pk);
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    if (r == 0) r = mbedtls_ecp_gen_key(MBEDTLS_ECP_DP_SECP256R1, mbedtls_pk_ec(*pk),
                                        mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* The keys crypto.Key takes: P-256, and RSA of 2048 bits or more */
static int ktmCryptoKeyUsable(mbedtls_pk_context *pk) {
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA) return mbedtls_pk_get_bitlen(pk) >= 2048;
    return mbedtls_pk_can_do(pk, MBEDTLS_PK_ECKEY) &&
           mbedtls_pk_ec(*pk)->MBEDTLS_PRIVATE(grp).id == MBEDTLS_ECP_DP_SECP256R1;
}

/* A PEM (or DER) key: private when priv, else public. NULL with *err. */
void *ktm_crypto_key_load(const unsigned char *pem, size_t len, int priv, int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    unsigned char *z = malloc(len + 1);
    memcpy(z, pem, len);
    z[len] = 0;
    mbedtls_pk_context *pk = calloc(1, sizeof(*pk));
    mbedtls_pk_init(pk);
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

/* A public P-256 key from its point's coordinates (32 bytes each, as a
 * JWK's x and y). NULL with *err. */
void *ktm_crypto_ec_public(const unsigned char *x, size_t xlen, const unsigned char *y, size_t ylen,
                           int *err) {
    if (!ktmCryptoStart()) { *err = -1; return NULL; }
    if (xlen != 32 || ylen != 32) { *err = MBEDTLS_ERR_PK_INVALID_PUBKEY; return NULL; }
    unsigned char pt[65];
    pt[0] = 0x04;               /* uncompressed */
    memcpy(pt + 1, x, 32);
    memcpy(pt + 33, y, 32);
    mbedtls_pk_context *pk = calloc(1, sizeof(*pk));
    mbedtls_pk_init(pk);
    int r = mbedtls_pk_setup(pk, mbedtls_pk_info_from_type(MBEDTLS_PK_ECKEY));
    mbedtls_ecp_keypair *k = r == 0 ? mbedtls_pk_ec(*pk) : NULL;
    if (r == 0) r = mbedtls_ecp_group_load(&k->MBEDTLS_PRIVATE(grp), MBEDTLS_ECP_DP_SECP256R1);
    if (r == 0) r = mbedtls_ecp_point_read_binary(&k->MBEDTLS_PRIVATE(grp), &k->MBEDTLS_PRIVATE(Q), pt, 65);
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
    mbedtls_pk_context *pk = calloc(1, sizeof(*pk));
    mbedtls_pk_init(pk);
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
    mbedtls_pk_context *pk = calloc(1, sizeof(*pk));
    mbedtls_pk_init(pk);
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

/* 0: P-256, 1: RSA */
int ktm_crypto_key_kind(void *key) {
    return mbedtls_pk_get_type(key) == MBEDTLS_PK_RSA ? 1 : 0;
}

long ktm_crypto_key_bits(void *key) {
    return (long)mbedtls_pk_get_bitlen(key);
}

/* The key as PEM into out (outlen bytes): its length, or a negative code */
long ktm_crypto_key_pem(void *key, int priv, unsigned char *out, size_t outlen) {
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
 * bytes), its length in *olen: P-256 ECDSA as raw r||s (64 bytes, as
 * JWT's ES256 has it, deterministic: RFC 6979), RSA as PKCS#1 v1.5
 * (the key's size, as RS256) */
int ktm_crypto_sign_with(void *key, int bits, const unsigned char *data, size_t len, unsigned char *sig,
                         size_t cap, size_t *olen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    mbedtls_pk_context *pk = key;
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA) {
        return mbedtls_pk_sign(pk, md, h, hlen, sig, cap, olen, mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    }
    if (cap < 64) return MBEDTLS_ERR_PK_BUFFER_TOO_SMALL;
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*pk);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_ecdsa_sign_det_ext(&k->MBEDTLS_PRIVATE(grp), &r, &s, &k->MBEDTLS_PRIVATE(d), h, hlen,
                                       md, mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    if (e == 0) e = mbedtls_mpi_write_binary(&r, sig, 32);
    if (e == 0) e = mbedtls_mpi_write_binary(&s, sig + 32, 32);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    *olen = 64;
    return e;
}

/* 0 when sig is key's signature of the bits-bit SHA-2 digest of data
 * (P-256: 64-byte r||s or DER; RSA: PKCS#1 v1.5) */
int ktm_crypto_verify_with(void *key, int bits, const unsigned char *data, size_t len,
                           const unsigned char *sig, size_t slen) {
    unsigned char h[64];
    mbedtls_md_type_t md;
    size_t hlen = ktmCryptoDigest(bits, data, len, h, &md);
    mbedtls_pk_context *pk = key;
    if (mbedtls_pk_get_type(pk) == MBEDTLS_PK_RSA || slen != 64) {
        return mbedtls_pk_verify(pk, md, h, hlen, sig, slen);
    }
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*pk);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_mpi_read_binary(&r, sig, 32);
    if (e == 0) e = mbedtls_mpi_read_binary(&s, sig + 32, 32);
    if (e == 0) e = mbedtls_ecdsa_verify(&k->MBEDTLS_PRIVATE(grp), h, hlen, &k->MBEDTLS_PRIVATE(Q), &r, &s);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    return e;
}

void ktm_crypto_key_free(void *key) {
    if (!key) return;
    mbedtls_pk_free(key);
    free(key);
}
