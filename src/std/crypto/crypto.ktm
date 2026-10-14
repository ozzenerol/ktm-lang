module crypto;
#link <ktmtls>
import mem, str;

/* Cryptography (v0.20.0), on mbedTLS's vetted primitives (built into
 * libktmtls, as net.tls is): secure random bytes, SHA-256/512, HMAC,
 * HKDF and PBKDF2, a constant-time compare, authenticated encryption
 * (AES-256-GCM, ChaCha20-Poly1305) and ECDSA P-256 signatures.
 *
 *   u8 key[32];
 *   crypto.random(key[0:]);
 *   u8 nonce[12];
 *   crypto.random(nonce[0:]);
 *   String box = crypto.seal(crypto.Aead.aesGcm, key[0:], nonce[0:], "secret");
 *   String back;
 *   if (crypto.open(crypto.Aead.aesGcm, key[0:], nonce[0:], box, &back)) { ... }
 *
 * This module stays source: only programs that import it link
 * libktmtls. */

pub const i64 ERR_CRYPTO = 0x6B746D22;

pub const i64 SHA256_SIZE = 32;
pub const i64 SHA512_SIZE = 64;
pub const i64 KEY_SIZE = 32;        /* seal/open keys */
pub const i64 NONCE_SIZE = 12;      /* never twice with one key */
pub const i64 TAG_SIZE = 16;        /* what seal adds to the text */
pub const i64 SIGNATURE_SIZE = 64;  /* sign's r||s */

/* The authenticated encryption seal and open use */
pub enum Aead {
    aesGcm,             /* AES-256-GCM: fast where the CPU has AES instructions */
    chacha20Poly1305    /* ChaCha20-Poly1305: fast everywhere */
};

/* An ECDSA P-256 key, private or public */
pub struct Key {
    private void *native;
    private bool priv;
};

extern "c" i32 ktm_crypto_random(u8 *buf, u64 len);
extern "c" void ktm_crypto_error(i32 code, u8 *out, u64 len);
extern "c" i32 ktm_crypto_sha256(u8 *in, u64 len, u8 *out);
extern "c" i32 ktm_crypto_sha512(u8 *in, u64 len, u8 *out);
extern "c" i32 ktm_crypto_hmac(i32 bits, u8 *key, u64 klen, u8 *in, u64 len, u8 *out);
extern "c" i32 ktm_crypto_hkdf(i32 bits, u8 *salt, u64 slen, u8 *ikm, u64 ilen, u8 *info, u64 infolen,
                               u8 *out, u64 olen);
extern "c" i32 ktm_crypto_pbkdf2(i32 bits, u8 *pw, u64 plen, u8 *salt, u64 slen, u32 iterations,
                                 u8 *out, u64 olen);
extern "c" i32 ktm_crypto_seal(i32 alg, u8 *key, u8 *nonce, u8 *aad, u64 alen, u8 *in, u64 len, u8 *out);
extern "c" i32 ktm_crypto_open(i32 alg, u8 *key, u8 *nonce, u8 *aad, u64 alen, u8 *in, u64 len, u8 *out);
extern "c" void *ktm_crypto_key_generate(i32 *err);
extern "c" void *ktm_crypto_key_load(u8 *pem, u64 len, i32 priv, i32 *err);
extern "c" i64 ktm_crypto_key_pem(void *key, i32 priv, u8 *out, u64 outlen);
extern "c" i32 ktm_crypto_sign(void *key, u8 *data, u64 len, u8 *sig);
extern "c" i32 ktm_crypto_verify(void *key, u8 *data, u64 len, u8 *sig, u64 slen);
extern "c" void ktm_crypto_key_free(void *key);

static void fail(u8 *what, i32 code) {
    u8 msg[160];
    msg[0] = 0;
    ktm_crypto_error(code, msg, 160);
    throw(ERR_CRYPTO, "crypto: %s: %s", what, msg);
}

static void need(u8[] buf, i64 n, u8 *what) {
    if (buf.len != n) { throw(ERR_CRYPTO, "crypto: %s is %d bytes, not %d", what, n, buf.len); }
}

/* ---- random ---- */

/* Fills buf with cryptographically secure random bytes (for keys,
 * nonces, tokens, salts) */
pub void random(u8[] buf) {
    i32 r = ktm_crypto_random(buf.ptr, buf.len);
    if (r != 0) { fail("random", r); }
}

/* n random bytes, as a new String (yours) */
pub String randomBytes(i64 n) {
    String s;
    s.reserve(n);
    for (i64 i = 0; i < n; i++) { s.appendByte(0); }
    random(s[0:]);
    return s;
}

/* ---- hashes and MACs ---- */

/* SHA-256 of data into out (32 bytes) */
pub void sha256(u8[] data, u8[] out) {
    need(out, SHA256_SIZE, "a SHA-256 output");
    ktm_crypto_sha256(data.ptr, data.len, out.ptr);
}

/* SHA-512 of data into out (64 bytes) */
pub void sha512(u8[] data, u8[] out) {
    need(out, SHA512_SIZE, "a SHA-512 output");
    ktm_crypto_sha512(data.ptr, data.len, out.ptr);
}

/* HMAC-SHA256 of data with key into out (32 bytes) */
pub void hmacSha256(u8[] key, u8[] data, u8[] out) {
    need(out, SHA256_SIZE, "an HMAC-SHA256 output");
    i32 r = ktm_crypto_hmac(256, key.ptr, key.len, data.ptr, data.len, out.ptr);
    if (r != 0) { fail("HMAC", r); }
}

/* HMAC-SHA512 of data with key into out (64 bytes) */
pub void hmacSha512(u8[] key, u8[] data, u8[] out) {
    need(out, SHA512_SIZE, "an HMAC-SHA512 output");
    i32 r = ktm_crypto_hmac(512, key.ptr, key.len, data.ptr, data.len, out.ptr);
    if (r != 0) { fail("HMAC", r); }
}

/* HKDF-SHA256 (RFC 5869): keys of out.len bytes from a secret */
pub void hkdfSha256(u8[] secret, u8[] salt, u8[] info, u8[] out) {
    i32 r = ktm_crypto_hkdf(256, salt.ptr, salt.len, secret.ptr, secret.len, info.ptr, info.len,
                            out.ptr, out.len);
    if (r != 0) { fail("HKDF", r); }
}

/* PBKDF2-HMAC-SHA256: a key of out.len bytes from a password (for
 * passwords prefer a slow, salted hash: iterations in the 600000s) */
pub void pbkdf2Sha256(u8[] password, u8[] salt, i64 iterations, u8[] out) {
    if (iterations < 1 || iterations > 0xFFFFFFFF) { throw(ERR_CRYPTO, "crypto: PBKDF2 iterations out of range"); }
    i32 r = ktm_crypto_pbkdf2(256, password.ptr, password.len, salt.ptr, salt.len, (u32)iterations,
                              out.ptr, out.len);
    if (r != 0) { fail("PBKDF2", r); }
}

/* a == b, taking as long whatever they hold (for MACs, tokens and
 * password hashes: an early exit tells an attacker how much matched) */
pub bool equal(u8[] a, u8[] b) {
    if (a.len != b.len) { return false; }
    u8 diff = 0;
    for (i64 i = 0; i < a.len; i++) { diff |= a[i] ^ b[i]; }
    return diff == 0;
}

/* ---- authenticated encryption ---- */

/* plain encrypted and authenticated with key (32 bytes) and nonce (12
 * bytes, never used twice with one key: crypto.random makes one), and aad
 * authenticated too (sent in the clear, as headers): the ciphertext and
 * its 16-byte tag, a new String (yours) */
pub String seal(Aead alg, u8[] key, u8[] nonce, u8[] plain, u8[] aad = "") {
    need(key, KEY_SIZE, "the key");
    need(nonce, NONCE_SIZE, "the nonce");
    String out;
    out.reserve(plain.len + TAG_SIZE);
    for (i64 i = 0; i < plain.len + TAG_SIZE; i++) { out.appendByte(0); }
    i32 r = ktm_crypto_seal((i32)alg, key.ptr, nonce.ptr, aad.ptr, aad.len, plain.ptr, plain.len, out.ptr);
    if (r != 0) {
        out.free();
        fail("seal", r);
    }
    return out;
}

/* sealed (from seal) opened into *plain (replaced): false, with *plain
 * empty, when it was altered or the key, nonce or aad differ */
pub bool open(Aead alg, u8[] key, u8[] nonce, u8[] sealed, String *plain, u8[] aad = "") {
    need(key, KEY_SIZE, "the key");
    need(nonce, NONCE_SIZE, "the nonce");
    plain.clear();
    if (sealed.len < TAG_SIZE) { return false; }
    i64 n = sealed.len - TAG_SIZE;
    plain.reserve(n);
    for (i64 i = 0; i < n; i++) { plain.appendByte(0); }
    if (ktm_crypto_open((i32)alg, key.ptr, nonce.ptr, aad.ptr, aad.len, sealed.ptr, n, plain.ptr) != 0) {
        plain.clear();
        return false;
    }
    return true;
}

/* ---- ECDSA P-256 ---- */

static Key *keyOf(void *native, bool priv) {
    Key *k = mem.allocZero(sizeof(Key));
    k.native = native;
    k.priv = priv;
    return k;
}

/* A new random private key */
pub Key *generateKey() {
    i32 err = 0;
    void *n = ktm_crypto_key_generate(&err);
    if (!n) { fail("making a key", err); }
    return keyOf(n, true);
}

/* A private key from PEM ("-----BEGIN EC PRIVATE KEY-----" or PKCS#8) */
pub Key *privateKey(u8[] pem) {
    i32 err = 0;
    void *n = ktm_crypto_key_load(pem.ptr, pem.len, 1, &err);
    if (!n) { fail("reading the private key (a P-256 one)", err); }
    return keyOf(n, true);
}

/* A public key from PEM ("-----BEGIN PUBLIC KEY-----") */
pub Key *publicKey(u8[] pem) {
    i32 err = 0;
    void *n = ktm_crypto_key_load(pem.ptr, pem.len, 0, &err);
    if (!n) { fail("reading the public key (a P-256 one)", err); }
    return keyOf(n, false);
}

static String pemOf(Key *k, bool priv) {
    u8 buf[2048];
    i64 n = ktm_crypto_key_pem(k.native, priv ? 1 : 0, buf, 2048);
    if (n < 0) { fail("writing the key", (i32)n); }
    return str.from(buf[0:n]);
}

/* The private key as PEM (a new String, yours: keep it secret) */
pub String Key.privatePem() {
    if (!self.priv) { throw(ERR_CRYPTO, "crypto: a public key has no private part"); }
    return pemOf(self, true);
}

/* The public key as PEM (a new String, yours) */
pub String Key.publicPem() {
    return pemOf(self, false);
}

/* The signature of data (SHA-256, then ECDSA): 64 bytes r||s, as JWT's
 * ES256 has it, a new String (yours). Deterministic (RFC 6979). */
pub String Key.sign(u8[] data) {
    if (!self.priv) { throw(ERR_CRYPTO, "crypto: signing needs a private key"); }
    u8 sig[64];
    i32 r = ktm_crypto_sign(self.native, data.ptr, data.len, sig);
    if (r != 0) { fail("signing", r); }
    return str.from(sig[0:64]);
}

/* Is sig (64-byte r||s, or DER) this key's signature of data? */
pub bool Key.verify(u8[] data, u8[] sig) {
    return ktm_crypto_verify(self.native, data.ptr, data.len, sig.ptr, sig.len) == 0;
}

pub void Key.free() {
    ktm_crypto_key_free(self.native);
    mem.free(self);
}
