module crypto;
#link <ktmtls>
import coro, encoding.base64, mem, str;

/* Cryptography (v0.20.0), on mbedTLS's vetted primitives (built into
 * libktmtls, as net.tls is): secure random bytes, SHA-256/512, HMAC,
 * HKDF and PBKDF2, a constant-time compare, authenticated encryption
 * (AES-256-GCM, ChaCha20-Poly1305), ECDSA P-256 and RSA signatures, and
 * password hashing (Argon2id, bcrypt; ktm's own C, beside mbedTLS).
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
pub const i64 SIGNATURE_SIZE = 64;  /* a P-256 key's sign: r||s */

/* The authenticated encryption seal and open use */
pub enum Aead {
    aesGcm,             /* AES-256-GCM: fast where the CPU has AES instructions */
    chacha20Poly1305    /* ChaCha20-Poly1305: fast everywhere */
};

/* The digest a signature is made over */
pub enum Hash {
    sha256,
    sha384,
    sha512
};

/* What a Key is */
pub enum KeyKind {
    ec256,              /* ECDSA P-256 (JWT's ES256) */
    rsa                 /* RSA, 2048 bits or more, PKCS#1 v1.5 (JWT's RS256) */
};

/* A signing key, private or public: ECDSA P-256 or RSA */
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
extern "c" i32 ktm_crypto_sign_with(void *key, i32 bits, u8 *data, u64 len, u8 *sig, u64 cap, u64 *olen);
extern "c" i32 ktm_crypto_verify_with(void *key, i32 bits, u8 *data, u64 len, u8 *sig, u64 slen);
extern "c" void *ktm_crypto_rsa_generate(i32 bits, i32 *err);
extern "c" void *ktm_crypto_ec_public(u8 *x, u64 xlen, u8 *y, u64 ylen, i32 *err);
extern "c" void *ktm_crypto_rsa_public(u8 *n, u64 nlen, u8 *e, u64 elen, i32 *err);
extern "c" i32 ktm_crypto_key_kind(void *key);
extern "c" i64 ktm_crypto_key_bits(void *key);
extern "c" void ktm_crypto_key_free(void *key);
extern "c" i32 ktm_crypto_blake2b(u8 *in, u64 len, u8 *out, u64 outlen);
extern "c" i32 ktm_crypto_argon2id(u8 *pw, u64 plen, u8 *salt, u64 slen, u32 t, u32 m, u32 p, u8 *out, u64 olen);
extern "c" i32 ktm_crypto_bcrypt(u8 *pw, u64 plen, i32 cost, u8 *salt, u8 *out);
extern "c" i32 ktm_crypto_bcrypt_check(u8 *pw, u64 plen, u8 *hash, u64 hlen);

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

/* BLAKE2b of data into out (1 to 64 bytes: the digest's size) */
pub void blake2b(u8[] data, u8[] out) {
    if (ktm_crypto_blake2b(data.ptr, data.len, out.ptr, out.len) != 0) {
        throw(ERR_CRYPTO, "crypto: a BLAKE2b digest is 1 to 64 bytes, not %d", out.len);
    }
}

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

/* PBKDF2-HMAC-SHA256: a key of out.len bytes from a password (to store
 * passwords, hashPassword) */
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

/* ---- signatures: ECDSA P-256, RSA ---- */

static Key *keyOf(void *native, bool priv) {
    Key *k = mem.allocZero(sizeof(Key));
    k.native = native;
    k.priv = priv;
    return k;
}

/* A new random private P-256 key */
pub Key *generateKey() {
    i32 err = 0;
    void *n = ktm_crypto_key_generate(&err);
    if (!n) { fail("making a key", err); }
    return keyOf(n, true);
}

/* A new random private RSA key of bits bits (2048 or more; it takes a
 * moment: about a tenth of a second for 2048) */
pub Key *generateRsaKey(i64 bits = 2048) {
    if (bits < 2048 || bits > 8192) {
        throw(ERR_CRYPTO, "crypto: an RSA key has 2048 to 8192 bits, not %d", bits);
    }
    i32 err = 0;
    void *n = ktm_crypto_rsa_generate((i32)bits, &err);
    if (!n) { fail("making an RSA key", err); }
    return keyOf(n, true);
}

/* A private key from PEM: P-256 ("-----BEGIN EC PRIVATE KEY-----" or
 * PKCS#8) or RSA of 2048 bits or more ("-----BEGIN RSA PRIVATE KEY-----"
 * or PKCS#8) */
pub Key *privateKey(u8[] pem) {
    i32 err = 0;
    void *n = ktm_crypto_key_load(pem.ptr, pem.len, 1, &err);
    if (!n) { fail("reading the private key (P-256, or RSA of 2048 bits or more)", err); }
    return keyOf(n, true);
}

/* A public key from PEM ("-----BEGIN PUBLIC KEY-----"): P-256 or RSA */
pub Key *publicKey(u8[] pem) {
    i32 err = 0;
    void *n = ktm_crypto_key_load(pem.ptr, pem.len, 0, &err);
    if (!n) { fail("reading the public key (P-256, or RSA of 2048 bits or more)", err); }
    return keyOf(n, false);
}

/* A public P-256 key from its point: x and y, 32 bytes each, big-endian
 * (a JWK's "x" and "y", base64url-decoded) */
pub Key *ecPublicKey(u8[] x, u8[] y) {
    i32 err = 0;
    void *n = ktm_crypto_ec_public(x.ptr, x.len, y.ptr, y.len, &err);
    if (!n) { fail("making a P-256 key from x and y (32 bytes each, a point on the curve)", err); }
    return keyOf(n, false);
}

/* A public RSA key from its modulus n and exponent e, big-endian (a
 * JWK's "n" and "e", base64url-decoded); 2048 bits or more */
pub Key *rsaPublicKey(u8[] n, u8[] e) {
    i32 err = 0;
    void *k = ktm_crypto_rsa_public(n.ptr, n.len, e.ptr, e.len, &err);
    if (!k) { fail("making an RSA key from n and e (2048 bits or more)", err); }
    return keyOf(k, false);
}

static String pemOf(Key *k, bool priv) {
    u8 buf[8192];
    i64 n = ktm_crypto_key_pem(k.native, priv ? 1 : 0, buf, 8192);
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

/* What the key is: KeyKind.ec256 or KeyKind.rsa */
pub KeyKind Key.kind() {
    return ktm_crypto_key_kind(self.native) == 1 ? KeyKind.rsa : KeyKind.ec256;
}

/* Its size in bits: 256 for P-256, the modulus's for RSA */
pub i64 Key.bits() {
    return ktm_crypto_key_bits(self.native);
}

static i32 hashBits(Hash h) {
    if (h == Hash.sha384) { return 384; }
    if (h == Hash.sha512) { return 512; }
    return 256;
}

/* The signature of data's h digest, a new String (yours): for P-256 the
 * 64 bytes r||s, as JWT's ES256 has it, deterministic (RFC 6979); for
 * RSA PKCS#1 v1.5, the key's size in bytes (JWT's RS256, RS384, RS512) */
pub String Key.signWith(Hash h, u8[] data) {
    if (!self.priv) { throw(ERR_CRYPTO, "crypto: signing needs a private key"); }
    u8 sig[1024];
    u64 n = 0;
    i32 r = ktm_crypto_sign_with(self.native, hashBits(h), data.ptr, data.len, sig, 1024, &n);
    if (r != 0) { fail("signing", r); }
    return str.from(sig[0:n]);
}

/* Is sig this key's signature of data's h digest? (P-256: 64-byte r||s,
 * or DER; RSA: PKCS#1 v1.5) */
pub bool Key.verifyWith(Hash h, u8[] data, u8[] sig) {
    return ktm_crypto_verify_with(self.native, hashBits(h), data.ptr, data.len, sig.ptr, sig.len) == 0;
}

/* signWith(Hash.sha256, data): JWT's ES256 for a P-256 key, RS256 for RSA */
pub String Key.sign(u8[] data) {
    return self.signWith(Hash.sha256, data);
}

/* verifyWith(Hash.sha256, data, sig) */
pub bool Key.verify(u8[] data, u8[] sig) {
    return self.verifyWith(Hash.sha256, data, sig);
}

pub void Key.free() {
    ktm_crypto_key_free(self.native);
    mem.free(self);
}

/* ---- passwords ---- */

/* The slow calls: inside a loop coroutine they run on coro.offload's
 * worker threads, so the loop keeps serving while a password hashes */
struct SlowCall {
    i32 kind;           /* 0: argon2id, 1: bcrypt, 2: bcrypt check */
    u8[] pw;
    u8[] salt;
    u8[] hash;
    u32 t, m, p;
    i32 cost;
    u8 *out;
    u64 outlen;
    i32 r;
};

static void slowRun(void *arg) {
    SlowCall *c = arg;
    if (c.kind == 0) {
        c.r = ktm_crypto_argon2id(c.pw.ptr, c.pw.len, c.salt.ptr, c.salt.len, c.t, c.m, c.p, c.out, c.outlen);
    } else if (c.kind == 1) {
        c.r = ktm_crypto_bcrypt(c.pw.ptr, c.pw.len, c.cost, c.salt.ptr, c.out);
    } else {
        c.r = ktm_crypto_bcrypt_check(c.pw.ptr, c.pw.len, c.hash.ptr, c.hash.len);
    }
}

static i32 slow(SlowCall *c) {
    coro.offload(&slowRun, c);     /* inline outside a loop */
    return c.r;
}

static i32 argon2Call(u8[] pw, u8[] salt, u32 t, u32 m, u32 p, u8 *out, u64 outlen) {
    SlowCall c;
    c.kind = 0;
    c.pw = pw;
    c.salt = salt;
    c.t = t;
    c.m = m;
    c.p = p;
    c.out = out;
    c.outlen = outlen;
    return slow(&c);
}

/* hashPassword's Argon2id cost (OWASP's: 19 MiB, 2 passes, 1 lane):
 * about 20 ms on a laptop core */
pub const i64 ARGON2_MEMORY_KIB = 19456;
pub const i64 ARGON2_ITERATIONS = 2;
pub const i64 ARGON2_PARALLELISM = 1;
pub const i64 BCRYPT_COST = 12;

/* Argon2id as a key derivation: out.len bytes (4 or more) from password
 * and salt (8 bytes or more), memoryKiB of memory, iterations passes */
pub void argon2idKey(u8[] password, u8[] salt, i64 memoryKiB, i64 iterations, i64 parallelism, u8[] out) {
    if (memoryKiB < 8 * parallelism || memoryKiB > 0xFFFFFFFF || iterations < 1 || iterations > 0xFFFFFFFF ||
        parallelism < 1 || parallelism > 0xFFFFFF || salt.len < 8 || out.len < 4) {
        throw(ERR_CRYPTO, "crypto: Argon2id needs memory >= 8 KiB per lane, 1+ iterations, a salt of 8+ bytes "
                          "and 4+ bytes out");
    }
    i32 r = argon2Call(password, salt, (u32)iterations, (u32)memoryKiB, (u32)parallelism, out.ptr, out.len);
    if (r == -2) { throw(ERR_CRYPTO, "crypto: Argon2id: out of memory (%d KiB)", memoryKiB); }
    if (r != 0) { throw(ERR_CRYPTO, "crypto: Argon2id: bad parameters"); }
}

static void b64raw(String *o, u8[] data) {
    String b = base64.encode(data);
    o.append(str.trimEnd(b[0:], "="));
    b.free();
}

/* password's Argon2id hash as a PHC string, with a new random salt:
 * $argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash> (yours). Store it whole:
 * verifyPassword reads the cost from it. */
pub String argon2id(u8[] password, i64 memoryKiB = ARGON2_MEMORY_KIB, i64 iterations = ARGON2_ITERATIONS,
                    i64 parallelism = ARGON2_PARALLELISM) {
    u8 salt[16];
    random(salt[0:]);
    u8 hash[32];
    argon2idKey(password, salt[0:], memoryKiB, iterations, parallelism, hash[0:]);
    String o = str.format("$argon2id$v=19$m=%d,t=%d,p=%d$", memoryKiB, iterations, parallelism);
    b64raw(&o, salt[0:]);
    o.appendByte('$');
    b64raw(&o, hash[0:]);
    mem.set(hash, 0, 32);
    return o;
}

/* password's bcrypt hash, $2b$<cost>$... (60 characters, yours). bcrypt
 * reads 72 bytes at most: a longer password throws. */
pub String bcrypt(u8[] password, i64 cost = BCRYPT_COST) {
    if (password.len > 72) { throw(ERR_CRYPTO, "crypto: bcrypt takes 72 bytes of password at most, not %d", password.len); }
    if (cost < 4 || cost > 31) { throw(ERR_CRYPTO, "crypto: a bcrypt cost is 4 to 31, not %d", cost); }
    u8 salt[16];
    random(salt[0:]);
    u8 out[61];
    SlowCall c;
    c.kind = 1;
    c.pw = password;
    c.salt = salt[0:16];
    c.cost = (i32)cost;
    c.out = out;
    slow(&c);
    return str.from(out[0:60]);
}

/* A password to store: Argon2id at the default cost (see argon2id) */
pub String hashPassword(u8[] password) {
    return argon2id(password);
}

/* A number after `name=` up to `,` or the end; -1 when there isn't one */
static i64 phcParam(u8[] params, u8[] name) {
    u8[] rest = params;
    while (rest.len) {
        i64 c = str.find(rest, ",");
        u8[] part = c < 0 ? rest : rest[0:c];
        rest = c < 0 ? rest[rest.len:] : rest[c + 1:];
        if (part.len > name.len + 1 && str.startsWith(part, name) && part[name.len] == '=') {
            i64 v;
            if (str.toInt(part[name.len + 1:], &v) && v >= 0) { return v; }
            return -1;
        }
    }
    return -1;
}

/* A PHC Argon2id string's parts: false when it isn't one */
static bool parseArgon2(u8[] hash, i64 *m, i64 *t, i64 *p, String *salt, String *want) {
    if (!str.startsWith(hash, "$argon2id$v=19$")) { return false; }
    u8[] rest = hash[15:];
    i64 d1 = str.find(rest, "$");
    if (d1 < 0) { return false; }
    u8[] params = rest[0:d1];
    rest = rest[d1 + 1:];
    i64 d2 = str.find(rest, "$");
    if (d2 < 0) { return false; }
    *m = phcParam(params, "m");
    *t = phcParam(params, "t");
    *p = phcParam(params, "p");
    if (*m < 0 || *t < 1 || *p < 1 || *m > 4194304 || *t > 100000) { return false; }
    if (!base64.decode(rest[0:d2], salt) || !base64.decode(rest[d2 + 1:], want)) { return false; }
    return salt.len >= 8 && want.len >= 4 && want.len <= 1024;
}

/* Does password match hash, an Argon2id PHC string or a bcrypt one
 * ($2a$, $2b$, $2y$)? false for a wrong password and for a hash it can't
 * read. The compare takes the same time wherever they differ. */
pub bool verifyPassword(u8[] password, u8[] hash) {
    if (str.startsWith(hash, "$2")) {
        SlowCall c;
        c.kind = 2;
        c.pw = password;
        c.hash = hash;
        return slow(&c) == 1;
    }
    i64 m, t, p;
    String salt, want;
    defer salt.free();
    defer want.free();
    if (!parseArgon2(hash, &m, &t, &p, &salt, &want)) { return false; }
    String got = str.from(want[0:]);
    defer got.free();
    i32 r = argon2Call(password, salt[0:], (u32)t, (u32)m, (u32)p, got.ptr, got.len);
    bool ok = r == 0 && equal(got[0:], want[0:]);
    mem.set(got.ptr, 0, got.len);
    return ok;
}

/* Should a hash that verified be replaced by hashPassword's (after the
 * user logs in, while the password is at hand)? true for bcrypt, for a
 * hash it can't read, and for Argon2id at another cost than the default */
pub bool needsRehash(u8[] hash) {
    i64 m, t, p;
    String salt, want;
    defer salt.free();
    defer want.free();
    if (!parseArgon2(hash, &m, &t, &p, &salt, &want)) { return true; }
    return m != ARGON2_MEMORY_KIB || t != ARGON2_ITERATIONS || p != ARGON2_PARALLELISM;
}
