import crypto, encoding.hex, fs, proc, str, test;

/* crypto (v0.20.0): published test vectors (SHA-512, RFC 4231 HMAC,
 * RFC 5869 HKDF, RFC 7914's PBKDF2-SHA256), random bytes, the
 * constant-time compare, AES-256-GCM and ChaCha20-Poly1305 round trips
 * and every way an open must fail, ECDSA P-256 sign/verify, PEM out and
 * in, and a signature made by openssl (crypto-files/) verified here. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

bool hexIs(u8[] bytes, u8[] want) {
    String h = hex.encode(bytes);
    bool ok = str.equals(h[0:], want);
    if (!ok) { printf("\n  got %.*s\n", h.len, h.ptr); }
    h.free();
    return ok;
}

String gDir;
String file(u8 *name) {
    ad String path = str.format("%s/crypto-files/%s", gDir, name);
    return fs.readAll(path[0:]);
}

i32 main() {
    printf("Test - crypto: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }

    u8 out64[64];
    u8 out32[32];
    crypto.sha512("abc", out64[0:]);
    check(hexIs(out64[0:], "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a"
                           "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"), "SHA-512 of abc");
    crypto.sha256("abc", out32[0:]);
    check(hexIs(out32[0:], "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 of abc");
    crypto.hmacSha256("Jefe", "what do ya want for nothing?", out32[0:]);
    check(hexIs(out32[0:], "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"), "HMAC-SHA256, RFC 4231 case 2");
    crypto.hmacSha512("Jefe", "what do ya want for nothing?", out64[0:]);
    check(hexIs(out64[0:], "164b7a7bfcf819e2e395fbe73b56e0a387bd64222e831fd610270cd7ea250554"
                           "9758bf75c05a994a6d034f65f8f0e6fdcaeab1a34d4a6b4b636e070a38bce737"), "HMAC-SHA512, RFC 4231 case 2");
    String ikm, salt, info;
    hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", &ikm);
    hex.decode("000102030405060708090a0b0c", &salt);
    hex.decode("f0f1f2f3f4f5f6f7f8f9", &info);
    u8 okm[42];
    crypto.hkdfSha256(ikm[0:], salt[0:], info[0:], okm[0:]);
    check(hexIs(okm[0:], "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
          "HKDF-SHA256, RFC 5869 case 1");
    ikm.free(); salt.free(); info.free();
    u8 dk[64];
    crypto.pbkdf2Sha256("passwd", "salt", 1, dk[0:]);
    check(hexIs(dk[0:], "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc"
                        "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783"), "PBKDF2-SHA256, RFC 7914");

    /* random */
    u8 a[32], b[32];
    crypto.random(a[0:]);
    crypto.random(b[0:]);
    check(!crypto.equal(a[0:], b[0:]), "two random draws differ");
    String r = crypto.randomBytes(1000);
    i64 zeros = 0;
    for (i64 i = 0; i < r.len; i++) { if (r[i] == 0) { zeros++; } }
    check(r.len == 1000 && zeros < 20, "randomBytes(1000)");
    r.free();
    check(crypto.equal("abc", "abc") && !crypto.equal("abc", "abd") && !crypto.equal("abc", "ab"), "equal");

    /* authenticated encryption, both algorithms */
    u8 key[32], nonce[12], other[32];
    crypto.random(key[0:]);
    crypto.random(nonce[0:]);
    crypto.random(other[0:]);
    for (i64 k = 0; k < 2; k++) {
        crypto.Aead alg = k == 0 ? crypto.Aead.aesGcm : crypto.Aead.chacha20Poly1305;
        String box = crypto.seal(alg, key[0:], nonce[0:], "attack at dawn", "header");
        String plain;
        bool opened = crypto.open(alg, key[0:], nonce[0:], box[0:], &plain, "header");
        check(box.len == 14 + crypto.TAG_SIZE && opened && str.equals(plain[0:], "attack at dawn"), "seal and open");
        check(!crypto.open(alg, other[0:], nonce[0:], box[0:], &plain, "header") && plain.len == 0, "another key fails");
        check(!crypto.open(alg, key[0:], nonce[0:], box[0:], &plain, "other header"), "another aad fails");
        box[3] = box[3] ^ 1;
        check(!crypto.open(alg, key[0:], nonce[0:], box[0:], &plain, "header"), "a changed byte fails");
        check(!crypto.open(alg, key[0:], nonce[0:], box[0:5], &plain), "a short text fails");
        String empty = crypto.seal(alg, key[0:], nonce[0:], "");
        check(empty.len == crypto.TAG_SIZE && crypto.open(alg, key[0:], nonce[0:], empty[0:], &plain) && plain.len == 0,
              "an empty text");
        box.free(); plain.free(); empty.free();
    }
    bool threw = false;
    try { crypto.seal(crypto.Aead.aesGcm, key[0:16], nonce[0:], "x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a 16-byte key is refused");

    /* ECDSA P-256 */
    crypto.Key *k = crypto.generateKey();
    String sig = k.sign("hello");
    check(sig.len == crypto.SIGNATURE_SIZE && k.verify("hello", sig[0:]) && !k.verify("hellO", sig[0:]), "sign and verify");
    String sig2 = k.sign("hello");
    check(str.equals(sig[0:], sig2[0:]), "deterministic signatures (RFC 6979)");
    String pubPem = k.publicPem();
    String priv = k.privatePem();
    crypto.Key *p = crypto.publicKey(pubPem[0:]);
    crypto.Key *q = crypto.privateKey(priv[0:]);
    check(str.startsWith(pubPem[0:], "-----BEGIN PUBLIC KEY-----") && p.verify("hello", sig[0:]), "the public key's PEM");
    String sig3 = q.sign("hello");
    check(k.verify("hello", sig3[0:]), "the private key's PEM");
    threw = false;
    try { p.sign("x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a public key can't sign");
    crypto.Key *other_k = crypto.generateKey();
    check(!other_k.verify("hello", sig[0:]), "another key's signature fails");
    /* openssl's DER signature, with openssl's key */
    String opub = file("pub.pem");
    String omsg = file("msg.txt");
    String ohex = file("sig.hex");
    String oder;
    hex.decode(str.trimEnd(ohex[0:]), &oder);
    crypto.Key *ok = crypto.publicKey(opub[0:]);
    check(ok.verify(omsg[0:], oder[0:]) && !ok.verify("other", oder[0:]), "openssl's signature verifies");
    String okey = file("key.pem");
    crypto.Key *oks = crypto.privateKey(okey[0:]);
    String mine = oks.sign(omsg[0:]);
    check(ok.verify(omsg[0:], mine[0:]), "openssl's private key signs");
    threw = false;
    try { crypto.publicKey("not a key"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a bad PEM throws");
    k.free(); p.free(); q.free(); other_k.free(); ok.free(); oks.free();
    sig.free(); sig2.free(); sig3.free(); pubPem.free(); priv.free(); mine.free();
    opub.free(); omsg.free(); ohex.free(); oder.free(); okey.free(); gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
