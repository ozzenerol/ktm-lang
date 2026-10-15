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
    if (r == 0 && (!mbedtls_pk_can_do(pk, MBEDTLS_PK_ECKEY) ||
                   mbedtls_pk_ec(*pk)->MBEDTLS_PRIVATE(grp).id != MBEDTLS_ECP_DP_SECP256R1)) {
        r = MBEDTLS_ERR_PK_TYPE_MISMATCH;
    }
    if (r != 0) {
        mbedtls_pk_free(pk);
        free(pk);
        *err = r;
        return NULL;
    }
    return pk;
}

/* The key as PEM into out (outlen bytes): its length, or a negative code */
long ktm_crypto_key_pem(void *key, int priv, unsigned char *out, size_t outlen) {
    int r = priv ? mbedtls_pk_write_key_pem(key, out, outlen) : mbedtls_pk_write_pubkey_pem(key, out, outlen);
    if (r != 0) return r;
    return (long)strlen((char *)out);
}

/* A raw r||s signature (64 bytes, as JWT's ES256) of SHA-256(data) */
int ktm_crypto_sign(void *key, const unsigned char *data, size_t len, unsigned char *sig64) {
    unsigned char h[32];
    mbedtls_sha256(data, len, h, 0);
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*(mbedtls_pk_context *)key);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_ecdsa_sign_det_ext(&k->MBEDTLS_PRIVATE(grp), &r, &s, &k->MBEDTLS_PRIVATE(d), h, 32,
                                       MBEDTLS_MD_SHA256, mbedtls_ctr_drbg_random, &ktm_crypto_drbg);
    if (e == 0) e = mbedtls_mpi_write_binary(&r, sig64, 32);
    if (e == 0) e = mbedtls_mpi_write_binary(&s, sig64 + 32, 32);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    return e;
}

/* 0 when sig (64-byte r||s, or DER) is key's signature of data */
int ktm_crypto_verify(void *key, const unsigned char *data, size_t len, const unsigned char *sig, size_t slen) {
    unsigned char h[32];
    mbedtls_sha256(data, len, h, 0);
    if (slen != 64) {
        return mbedtls_pk_verify(key, MBEDTLS_MD_SHA256, h, 32, sig, slen);
    }
    mbedtls_ecp_keypair *k = mbedtls_pk_ec(*(mbedtls_pk_context *)key);
    mbedtls_mpi r, s;
    mbedtls_mpi_init(&r);
    mbedtls_mpi_init(&s);
    int e = mbedtls_mpi_read_binary(&r, sig, 32);
    if (e == 0) e = mbedtls_mpi_read_binary(&s, sig + 32, 32);
    if (e == 0) e = mbedtls_ecdsa_verify(&k->MBEDTLS_PRIVATE(grp), h, 32, &k->MBEDTLS_PRIVATE(Q), &r, &s);
    mbedtls_mpi_free(&r);
    mbedtls_mpi_free(&s);
    return e;
}

void ktm_crypto_key_free(void *key) {
    if (!key) return;
    mbedtls_pk_free(key);
    free(key);
}
