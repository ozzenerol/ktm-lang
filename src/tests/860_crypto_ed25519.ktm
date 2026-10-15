import crypto, encoding.base64, encoding.hex, fs, proc, str, test;

/* crypto.Key takes Ed25519 keys (RFC 8032, Monocypher's): generateKey(
 * KeyKind.ed25519), PKCS#8 / SPKI PEM in and out, ed25519PublicKey(x),
 * sign and verify. RFC 8032's test vectors 1-3 (section 7.1) and openssl's
 * key and signature of 1023 bytes (crypto-files/ed-*) check it; a PEM
 * that isn't exactly an Ed25519 key's is refused. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

String gDir;
String file(u8 *name) {
    ad String path = str.format("%s/crypto-files/%s", gDir, name);
    return fs.readAll(path[0:]);
}

String bytes(u8[] hexText) {
    String out;
    hex.decode(str.trimEnd(hexText), &out);
    return out;
}

/* the PEM of an Ed25519 private key with this seed (hex) */
String seedPem(u8[] seedHex) {
    ad String der = bytes("302e020100300506032b657004220420");
    ad String seed = bytes(seedHex);
    der.append(seed[0:]);
    String pem = str.from("-----BEGIN PRIVATE KEY-----\n");
    ad String body = base64.encode(der[0:]);
    pem.append(body[0:]);
    pem.append("\n-----END PRIVATE KEY-----\n");
    return pem;
}

/* RFC 8032 7.1: the key from the seed has this public key, and signs msg
 * as sig */
bool vector(u8[] seedHex, u8[] pubHex, u8[] msgHex, u8[] sigHex) {
    ad String pem = seedPem(seedHex);
    crypto.Key *k = crypto.privateKey(pem[0:]);
    ad String pubPem = k.publicPem();
    ad String pubDer;
    u8[] body = pubPem[0:];
    i64 a = str.find(body, "\n") + 1;
    i64 b = str.find(body, "-----END");
    base64.decode(body[a:b], &pubDer);
    ad String pubWant = bytes(pubHex);
    ad String msg = bytes(msgHex);
    ad String sigWant = bytes(sigHex);
    ad String sig = k.sign(msg[0:]);
    crypto.Key *p = crypto.ed25519PublicKey(pubWant[0:]);
    bool ok = k.kind() == crypto.KeyKind.ed25519 && pubDer.len == 44 &&
              str.equals(pubDer[12:], pubWant[0:]) && str.equals(sig[0:], sigWant[0:]) &&
              p.verify(msg[0:], sig[0:]) && k.verify(msg[0:], sig[0:]);
    k.free();
    p.free();
    return ok;
}

bool throws(u8[] pem, bool priv) {
    try {
        crypto.Key *k = priv ? crypto.privateKey(pem) : crypto.publicKey(pem);
        k.free();
    } catch (Error e) {
        return e.code == crypto.ERR_CRYPTO;
    }
    return false;
}

i32 main() {
    printf("Test - crypto Ed25519: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }

    check(vector("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                 "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
                 "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
          "RFC 8032 test 1");
    check(vector("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                 "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
                 "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
          "RFC 8032 test 2");
    check(vector("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                 "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
                 "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
          "RFC 8032 test 3");

    /* openssl's key and signature */
    ad String keyPem = file("ed-key.pem");
    ad String pubPem = file("ed-pub.pem");
    ad String msgHex = file("ed-msg.hex");
    ad String sigHex = file("ed-sig.hex");
    ad String msg = bytes(msgHex[0:]);
    ad String sig = bytes(sigHex[0:]);
    crypto.Key *ok = crypto.privateKey(keyPem[0:]);
    crypto.Key *op = crypto.publicKey(pubPem[0:]);
    check(op.kind() == crypto.KeyKind.ed25519 && op.bits() == 256, "openssl's public PEM is an Ed25519 key");
    check(op.verify(msg[0:], sig[0:]) && !op.verify(msg[0:1022], sig[0:]), "openssl's signature verifies");
    ad String mine = ok.sign(msg[0:]);
    check(str.equals(mine[0:], sig[0:]), "ours is openssl's (Ed25519 is deterministic)");
    ad String outPub = ok.publicPem();
    ad String outKey = ok.privatePem();
    check(str.equals(str.trimEnd(outPub[0:]), str.trimEnd(pubPem[0:])) &&
          str.equals(str.trimEnd(outKey[0:]), str.trimEnd(keyPem[0:])), "the PEM written is openssl's");
    mine.ptr[3] ^= 1;
    check(!op.verify(msg[0:], mine[0:]), "a changed signature fails");
    check(!op.verify(msg[0:], sig[0:63]), "a short signature fails");

    /* a new key */
    crypto.Key *k = crypto.generateKey(crypto.KeyKind.ed25519);
    ad String s = k.sign("hello");
    check(k.kind() == crypto.KeyKind.ed25519 && s.len == 64 && k.verify("hello", s[0:]) &&
          !op.verify("hello", s[0:]), "generateKey(KeyKind.ed25519)");
    crypto.Key *dflt = crypto.generateKey();
    check(dflt.kind() == crypto.KeyKind.ec256, "generateKey() is still P-256");

    /* what isn't taken */
    ad String trunc = file("ed-key-truncated.pem");
    ad String trailing = file("ed-key-trailing.pem");
    check(throws(trunc[0:], true), "a truncated PEM is refused");
    check(throws(trailing[0:], true), "a PEM with trailing garbage is refused");
    check(throws(pubPem[0:], true), "a public PEM isn't a private key");
    check(throws(keyPem[0:], false), "a private PEM isn't a public key");
    bool threw = false;
    try { op.sign("x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a public Ed25519 key can't sign");
    threw = false;
    try { k.signWith(crypto.Hash.sha512, "x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "signWith on Ed25519 throws (it hashes itself)");
    threw = false;
    try { crypto.ed25519PublicKey("short"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a public key of 5 bytes is refused");
    threw = false;
    try { crypto.generateKey(crypto.KeyKind.rsa); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "generateKey(KeyKind.rsa) points at generateRsaKey");

    ok.free(); op.free(); k.free(); dflt.free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
