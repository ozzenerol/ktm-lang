/* Password hashing for ktm's crypto module (v0.20.0): Argon2id (RFC 9106,
 * on BLAKE2b, RFC 7693) and bcrypt ($2b$), which mbedTLS doesn't have.
 * Written for ktm; checked against golang.org/x/crypto's argon2 and bcrypt
 * and RFC 9106's test vector. One thread: an Argon2 run with p lanes
 * computes them in turn (the result is the same as p threads'). */
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "ktmblowfish.h"

typedef uint8_t u8;
typedef uint32_t u32;
typedef uint64_t u64;

static void ktm_pw_wipe(void *p, size_t n) {
    volatile u8 *v = p;
    while (n--) *v++ = 0;
}

static u64 load64(const u8 *p) {
    u64 v = 0;
    for (int i = 7; i >= 0; --i) v = (v << 8) | p[i];
    return v;
}

static void store64(u8 *p, u64 v) {
    for (int i = 0; i < 8; ++i) p[i] = (u8)(v >> (8 * i));
}

static void store32(u8 *p, u32 v) {
    for (int i = 0; i < 4; ++i) p[i] = (u8)(v >> (8 * i));
}

/* ---- BLAKE2b ---- */

typedef struct {
    u64 h[8];
    u64 t[2];
    u8 buf[128];
    size_t buflen;
    size_t outlen;
} Blake2b;

static const u64 blake2b_iv[8] = {
    0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL, 0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL,
};

static const u8 blake2b_sigma[12][16] = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
    {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
    {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
    {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
    {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
    {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
    {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
    {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
    {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
    {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
    {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
};

#define ROTR64(x, n) (((x) >> (n)) | ((x) << (64 - (n))))
#define B2G(a, b, c, d, x, y)              \
    do {                                   \
        a = a + b + x; d = ROTR64(d ^ a, 32); \
        c = c + d;     b = ROTR64(b ^ c, 24); \
        a = a + b + y; d = ROTR64(d ^ a, 16); \
        c = c + d;     b = ROTR64(b ^ c, 63); \
    } while (0)

static void blake2b_compress(Blake2b *s, const u8 *block, int last) {
    u64 m[16], v[16];
    for (int i = 0; i < 16; ++i) m[i] = load64(block + 8 * i);
    for (int i = 0; i < 8; ++i) {
        v[i] = s->h[i];
        v[i + 8] = blake2b_iv[i];
    }
    v[12] ^= s->t[0];
    v[13] ^= s->t[1];
    if (last) v[14] = ~v[14];
    for (int r = 0; r < 12; ++r) {
        const u8 *z = blake2b_sigma[r];
        B2G(v[0], v[4], v[8], v[12], m[z[0]], m[z[1]]);
        B2G(v[1], v[5], v[9], v[13], m[z[2]], m[z[3]]);
        B2G(v[2], v[6], v[10], v[14], m[z[4]], m[z[5]]);
        B2G(v[3], v[7], v[11], v[15], m[z[6]], m[z[7]]);
        B2G(v[0], v[5], v[10], v[15], m[z[8]], m[z[9]]);
        B2G(v[1], v[6], v[11], v[12], m[z[10]], m[z[11]]);
        B2G(v[2], v[7], v[8], v[13], m[z[12]], m[z[13]]);
        B2G(v[3], v[4], v[9], v[14], m[z[14]], m[z[15]]);
    }
    for (int i = 0; i < 8; ++i) s->h[i] ^= v[i] ^ v[i + 8];
}

static void blake2b_init(Blake2b *s, size_t outlen) {
    memset(s, 0, sizeof(*s));
    for (int i = 0; i < 8; ++i) s->h[i] = blake2b_iv[i];
    s->h[0] ^= 0x01010000ULL ^ (u64)outlen;
    s->outlen = outlen;
}

static void blake2b_update(Blake2b *s, const void *data, size_t len) {
    const u8 *in = data;
    while (len > 0) {
        if (s->buflen == 128) {
            s->t[0] += 128;
            if (s->t[0] < 128) s->t[1]++;
            blake2b_compress(s, s->buf, 0);
            s->buflen = 0;
        }
        size_t n = 128 - s->buflen;
        if (n > len) n = len;
        memcpy(s->buf + s->buflen, in, n);
        s->buflen += n;
        in += n;
        len -= n;
    }
}

static void blake2b_final(Blake2b *s, u8 *out) {
    s->t[0] += s->buflen;
    if (s->t[0] < s->buflen) s->t[1]++;
    memset(s->buf + s->buflen, 0, 128 - s->buflen);
    blake2b_compress(s, s->buf, 1);
    u8 full[64];
    for (int i = 0; i < 8; ++i) store64(full + 8 * i, s->h[i]);
    memcpy(out, full, s->outlen);
    ktm_pw_wipe(full, sizeof(full));
    ktm_pw_wipe(s, sizeof(*s));
}

/* BLAKE2b of data with an outlen-byte digest (1 to 64) */
int ktm_crypto_blake2b(const u8 *data, u64 len, u8 *out, u64 outlen) {
    if (outlen < 1 || outlen > 64) return -1;
    Blake2b s;
    blake2b_init(&s, outlen);
    blake2b_update(&s, data, len);
    blake2b_final(&s, out);
    return 0;
}

/* Argon2's H': a hash of any length, from BLAKE2b */
static void blake2b_long(u8 *out, u32 outlen, const u8 *in, size_t inlen) {
    u8 len[4];
    store32(len, outlen);
    Blake2b s;
    if (outlen <= 64) {
        blake2b_init(&s, outlen);
        blake2b_update(&s, len, 4);
        blake2b_update(&s, in, inlen);
        blake2b_final(&s, out);
        return;
    }
    u8 v[64];
    blake2b_init(&s, 64);
    blake2b_update(&s, len, 4);
    blake2b_update(&s, in, inlen);
    blake2b_final(&s, v);
    memcpy(out, v, 32);
    out += 32;
    u32 left = outlen - 32;
    while (left > 64) {
        blake2b_init(&s, 64);
        blake2b_update(&s, v, 64);
        blake2b_final(&s, v);
        memcpy(out, v, 32);
        out += 32;
        left -= 32;
    }
    blake2b_init(&s, left);
    blake2b_update(&s, v, 64);
    blake2b_final(&s, out);
    ktm_pw_wipe(v, sizeof(v));
}

/* ---- Argon2id ---- */

#define ARGON2_QWORDS 128           /* a block: 1 KiB */
#define ARGON2_SYNC 4               /* slices per pass */

typedef struct { u64 v[ARGON2_QWORDS]; } Block;

static inline u64 fBlaMka(u64 x, u64 y) {
    return x + y + 2 * (u64)(u32)x * (u64)(u32)y;
}

#define GB(a, b, c, d)                                    \
    do {                                                  \
        a = fBlaMka(a, b); d = ROTR64(d ^ a, 32);         \
        c = fBlaMka(c, d); b = ROTR64(b ^ c, 24);         \
        a = fBlaMka(a, b); d = ROTR64(d ^ a, 16);         \
        c = fBlaMka(c, d); b = ROTR64(b ^ c, 63);         \
    } while (0)

#define BLAMKA_ROUND(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15) \
    do {                                                                                  \
        GB(v0, v4, v8, v12); GB(v1, v5, v9, v13);                                         \
        GB(v2, v6, v10, v14); GB(v3, v7, v11, v15);                                       \
        GB(v0, v5, v10, v15); GB(v1, v6, v11, v12);                                       \
        GB(v2, v7, v8, v13); GB(v3, v4, v9, v14);                                         \
    } while (0)

/* out = G(x, y), or out ^= G(x, y) */
static void argon2_g(Block *out, const Block *x, const Block *y, int with_xor) {
    Block r, t;
    for (int i = 0; i < ARGON2_QWORDS; ++i) r.v[i] = x->v[i] ^ y->v[i];
    t = r;
    u64 *q = t.v;
    for (int i = 0; i < 8; ++i) {
        u64 *z = q + 16 * i;
        BLAMKA_ROUND(z[0], z[1], z[2], z[3], z[4], z[5], z[6], z[7],
                     z[8], z[9], z[10], z[11], z[12], z[13], z[14], z[15]);
    }
    for (int i = 0; i < 8; ++i) {
        u64 *z = q + 2 * i;
        BLAMKA_ROUND(z[0], z[1], z[16], z[17], z[32], z[33], z[48], z[49],
                     z[64], z[65], z[80], z[81], z[96], z[97], z[112], z[113]);
    }
    if (with_xor) {
        for (int i = 0; i < ARGON2_QWORDS; ++i) out->v[i] ^= t.v[i] ^ r.v[i];
    } else {
        for (int i = 0; i < ARGON2_QWORDS; ++i) out->v[i] = t.v[i] ^ r.v[i];
    }
}

static u32 argon2_index(u64 rnd, u32 lane_len, u32 seg_len, u32 lanes,
                        u32 pass, u32 slice, u32 lane, u32 index) {
    u32 ref_lane = (u32)((rnd >> 32) % lanes);
    if (pass == 0 && slice == 0) ref_lane = lane;
    u64 m = 3 * (u64)seg_len, s = (u64)((slice + 1) % ARGON2_SYNC) * seg_len;
    if (lane == ref_lane) m += index;
    if (pass == 0) {
        m = (u64)slice * seg_len;
        s = 0;
        if (slice == 0 || lane == ref_lane) m += index;
    }
    if (index == 0 || lane == ref_lane) m--;
    u64 p = rnd & 0xFFFFFFFF;
    p = (p * p) >> 32;
    p = (p * m) >> 32;
    return ref_lane * lane_len + (u32)((s + m - (p + 1)) % lane_len);
}

/* Argon2id: t passes over m KiB in p lanes; out gets outlen bytes (4 or
 * more). 0, or -1 for bad parameters, -2 without the memory. */
int ktm_crypto_argon2id(const u8 *pw, u64 pwlen, const u8 *salt, u64 saltlen,
                        u32 t, u32 m, u32 p, u8 *out, u64 outlen) {
    if (t < 1 || p < 1 || p > 0xFFFFFF || outlen < 4 || outlen > 0xFFFFFFFF ||
        saltlen < 8 || pwlen > 0xFFFFFFFF || saltlen > 0xFFFFFFFF ||
        m < 8 * p) {
        return -1;
    }
    u32 mem = m / (ARGON2_SYNC * p) * (ARGON2_SYNC * p);
    u32 lane_len = mem / p, seg_len = lane_len / ARGON2_SYNC;
    Block *B = calloc(mem, sizeof(Block));
    if (!B) return -2;

    /* H0 */
    u8 h0[72], w[4];
    Blake2b s;
    blake2b_init(&s, 64);
    store32(w, p); blake2b_update(&s, w, 4);
    store32(w, (u32)outlen); blake2b_update(&s, w, 4);
    store32(w, m); blake2b_update(&s, w, 4);
    store32(w, t); blake2b_update(&s, w, 4);
    store32(w, 0x13); blake2b_update(&s, w, 4);
    store32(w, 2); blake2b_update(&s, w, 4);   /* Argon2id */
    store32(w, (u32)pwlen); blake2b_update(&s, w, 4);
    blake2b_update(&s, pw, pwlen);
    store32(w, (u32)saltlen); blake2b_update(&s, w, 4);
    blake2b_update(&s, salt, saltlen);
    store32(w, 0); blake2b_update(&s, w, 4);   /* no secret */
    store32(w, 0); blake2b_update(&s, w, 4);   /* no associated data */
    blake2b_final(&s, h0);

    u8 bytes[1024];
    for (u32 l = 0; l < p; ++l) {
        for (u32 j = 0; j < 2; ++j) {
            store32(h0 + 64, j);
            store32(h0 + 68, l);
            blake2b_long(bytes, 1024, h0, 72);
            Block *b = &B[l * lane_len + j];
            for (int i = 0; i < ARGON2_QWORDS; ++i) b->v[i] = load64(bytes + 8 * i);
        }
    }

    Block zero, input, addr;
    memset(&zero, 0, sizeof(zero));
    for (u32 pass = 0; pass < t; ++pass) {
        for (u32 slice = 0; slice < ARGON2_SYNC; ++slice) {
            for (u32 lane = 0; lane < p; ++lane) {
                int data_indep = pass == 0 && slice < ARGON2_SYNC / 2;
                memset(&input, 0, sizeof(input));
                if (data_indep) {
                    input.v[0] = pass;
                    input.v[1] = lane;
                    input.v[2] = slice;
                    input.v[3] = mem;
                    input.v[4] = t;
                    input.v[5] = 2;
                }
                u32 index = 0;
                if (pass == 0 && slice == 0) {
                    index = 2;
                    if (data_indep) {
                        input.v[6]++;
                        argon2_g(&addr, &zero, &input, 0);
                        argon2_g(&addr, &zero, &addr, 0);
                    }
                }
                u32 offset = lane * lane_len + slice * seg_len + index;
                for (; index < seg_len; ++index, ++offset) {
                    u32 prev = offset - 1;
                    if (index == 0 && slice == 0) prev += lane_len;
                    u64 rnd;
                    if (data_indep) {
                        if (index % ARGON2_QWORDS == 0) {
                            input.v[6]++;
                            argon2_g(&addr, &zero, &input, 0);
                            argon2_g(&addr, &zero, &addr, 0);
                        }
                        rnd = addr.v[index % ARGON2_QWORDS];
                    } else {
                        rnd = B[prev].v[0];
                    }
                    u32 ref = argon2_index(rnd, lane_len, seg_len, p, pass, slice, lane, index);
                    argon2_g(&B[offset], &B[prev], &B[ref], pass > 0);
                }
            }
        }
    }

    Block final = B[lane_len - 1];
    for (u32 l = 1; l < p; ++l) {
        const Block *b = &B[l * lane_len + lane_len - 1];
        for (int i = 0; i < ARGON2_QWORDS; ++i) final.v[i] ^= b->v[i];
    }
    for (int i = 0; i < ARGON2_QWORDS; ++i) store64(bytes + 8 * i, final.v[i]);
    blake2b_long(out, (u32)outlen, bytes, 1024);

    ktm_pw_wipe(B, (size_t)mem * sizeof(Block));
    free(B);
    ktm_pw_wipe(bytes, sizeof(bytes));
    ktm_pw_wipe(h0, sizeof(h0));
    ktm_pw_wipe(&final, sizeof(final));
    return 0;
}

/* ---- bcrypt ---- */

typedef struct {
    u32 p[18];
    u32 s[4][256];
} Blowfish;

#define BF_F(c, x) \
    ((((c)->s[0][(x) >> 24] + (c)->s[1][((x) >> 16) & 0xff]) ^ (c)->s[2][((x) >> 8) & 0xff]) + (c)->s[3][(x) & 0xff])

static void bf_encrypt(const Blowfish *c, u32 *xl, u32 *xr) {
    u32 l = *xl, r = *xr;
    l ^= c->p[0];
    for (int i = 1; i <= 16; i += 2) {
        r ^= BF_F(c, l) ^ c->p[i];
        l ^= BF_F(c, r) ^ c->p[i + 1];
    }
    r ^= c->p[17];
    *xl = r;
    *xr = l;
}

/* The next 32 bits of data, cyclically */
static u32 bf_word(const u8 *data, size_t len, size_t *pos) {
    u32 w = 0;
    for (int i = 0; i < 4; ++i) {
        w = (w << 8) | data[*pos];
        *pos = (*pos + 1) % len;
    }
    return w;
}

/* Blowfish's key schedule, with the salt mixed in (salt null: none) */
static void bf_expand(Blowfish *c, const u8 *salt, const u8 *key, size_t keylen) {
    size_t kp = 0, sp = 0;
    for (int i = 0; i < 18; ++i) c->p[i] ^= bf_word(key, keylen, &kp);
    u32 l = 0, r = 0;
    for (int i = 0; i < 18; i += 2) {
        if (salt) {
            l ^= bf_word(salt, 16, &sp);
            r ^= bf_word(salt, 16, &sp);
        }
        bf_encrypt(c, &l, &r);
        c->p[i] = l;
        c->p[i + 1] = r;
    }
    for (int b = 0; b < 4; ++b) {
        for (int i = 0; i < 256; i += 2) {
            if (salt) {
                l ^= bf_word(salt, 16, &sp);
                r ^= bf_word(salt, 16, &sp);
            }
            bf_encrypt(c, &l, &r);
            c->s[b][i] = l;
            c->s[b][i + 1] = r;
        }
    }
}

static const char bcrypt_alphabet[] =
    "./ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

static void bcrypt_b64(char *out, const u8 *in, size_t len) {
    size_t i = 0;
    while (i < len) {
        u32 c1 = in[i++];
        *out++ = bcrypt_alphabet[c1 >> 2];
        c1 = (c1 & 0x03) << 4;
        if (i >= len) { *out++ = bcrypt_alphabet[c1]; break; }
        u32 c2 = in[i++];
        c1 |= c2 >> 4;
        *out++ = bcrypt_alphabet[c1];
        c1 = (c2 & 0x0f) << 2;
        if (i >= len) { *out++ = bcrypt_alphabet[c1]; break; }
        c2 = in[i++];
        c1 |= c2 >> 6;
        *out++ = bcrypt_alphabet[c1];
        *out++ = bcrypt_alphabet[c2 & 0x3f];
    }
    *out = 0;
}

static int bcrypt_unb64_char(char c) {
    const char *p = c ? strchr(bcrypt_alphabet, c) : NULL;
    return p ? (int)(p - bcrypt_alphabet) : -1;
}

/* len bytes from text (as bcrypt_b64 writes them); -1 when it isn't */
static int bcrypt_unb64(u8 *out, size_t len, const char *text) {
    size_t o = 0;
    while (o < len) {
        int c1 = bcrypt_unb64_char(*text++), c2 = bcrypt_unb64_char(*text++);
        if (c1 < 0 || c2 < 0) return -1;
        out[o++] = (u8)((c1 << 2) | ((c2 & 0x30) >> 4));
        if (o >= len) break;
        int c3 = bcrypt_unb64_char(*text++);
        if (c3 < 0) return -1;
        out[o++] = (u8)(((c2 & 0x0f) << 4) | ((c3 & 0x3c) >> 2));
        if (o >= len) break;
        int c4 = bcrypt_unb64_char(*text++);
        if (c4 < 0) return -1;
        out[o++] = (u8)(((c3 & 0x03) << 6) | c4);
    }
    return 0;
}

/* The 23-byte bcrypt hash of pw (its first 72 bytes) */
static void bcrypt_raw(const u8 *pw, size_t pwlen, int cost, const u8 salt[16], u8 out[23]) {
    u8 key[73];
    if (pwlen > 72) pwlen = 72;
    memcpy(key, pw, pwlen);
    key[pwlen] = 0;
    size_t keylen = pwlen < 72 ? pwlen + 1 : 72;

    Blowfish *c = malloc(sizeof(Blowfish));
    memcpy(c->p, ktm_bf_p, sizeof(c->p));
    memcpy(c->s[0], ktm_bf_s0, sizeof(c->s[0]));
    memcpy(c->s[1], ktm_bf_s1, sizeof(c->s[1]));
    memcpy(c->s[2], ktm_bf_s2, sizeof(c->s[2]));
    memcpy(c->s[3], ktm_bf_s3, sizeof(c->s[3]));
    bf_expand(c, salt, key, keylen);
    u64 rounds = (u64)1 << cost;
    for (u64 i = 0; i < rounds; ++i) {
        bf_expand(c, NULL, key, keylen);
        bf_expand(c, NULL, salt, 16);
    }
    static const u8 magic[24] = "OrpheanBeholderScryDoubt";
    u32 cdata[6];
    size_t mp = 0;
    for (int i = 0; i < 6; ++i) cdata[i] = bf_word(magic, 24, &mp);
    for (int i = 0; i < 64; ++i) {
        for (int j = 0; j < 6; j += 2) bf_encrypt(c, &cdata[j], &cdata[j + 1]);
    }
    u8 bytes[24];
    for (int i = 0; i < 6; ++i) {
        bytes[4 * i] = (u8)(cdata[i] >> 24);
        bytes[4 * i + 1] = (u8)(cdata[i] >> 16);
        bytes[4 * i + 2] = (u8)(cdata[i] >> 8);
        bytes[4 * i + 3] = (u8)cdata[i];
    }
    memcpy(out, bytes, 23);
    ktm_pw_wipe(c, sizeof(*c));
    free(c);
    ktm_pw_wipe(key, sizeof(key));
    ktm_pw_wipe(bytes, sizeof(bytes));
}

/* "$2b$<cost>$<22 salt><31 hash>" into out (61 bytes with the NUL): 0, or
 * -1 for a cost outside 4..31 */
int ktm_crypto_bcrypt(const u8 *pw, u64 pwlen, int cost, const u8 *salt, char *out) {
    if (cost < 4 || cost > 31) return -1;
    u8 hash[23];
    bcrypt_raw(pw, pwlen, cost, salt, hash);
    out[0] = '$'; out[1] = '2'; out[2] = 'b'; out[3] = '$';
    out[4] = (char)('0' + cost / 10);
    out[5] = (char)('0' + cost % 10);
    out[6] = '$';
    bcrypt_b64(out + 7, salt, 16);
    bcrypt_b64(out + 29, hash, 23);
    ktm_pw_wipe(hash, sizeof(hash));
    return 0;
}

/* 1 when pw matches the bcrypt string ($2a$, $2b$ or $2y$), 0 when it
 * doesn't, -1 when the string isn't one; the compare is constant-time */
int ktm_crypto_bcrypt_check(const u8 *pw, u64 pwlen, const char *hash, u64 hashlen) {
    if (hashlen != 60 || hash[0] != '$' || hash[1] != '2' ||
        (hash[2] != 'a' && hash[2] != 'b' && hash[2] != 'y') || hash[3] != '$' ||
        hash[4] < '0' || hash[4] > '9' || hash[5] < '0' || hash[5] > '9' || hash[6] != '$') {
        return -1;
    }
    int cost = (hash[4] - '0') * 10 + (hash[5] - '0');
    if (cost < 4 || cost > 31) return -1;
    char text[61];
    memcpy(text, hash, 60);
    text[60] = 0;
    u8 salt[16], want[23], got[23];
    if (bcrypt_unb64(salt, 16, text + 7) || bcrypt_unb64(want, 23, text + 29)) return -1;
    bcrypt_raw(pw, pwlen, cost, salt, got);
    u8 diff = 0;
    for (int i = 0; i < 23; ++i) diff |= got[i] ^ want[i];
    ktm_pw_wipe(got, sizeof(got));
    return diff == 0;
}
