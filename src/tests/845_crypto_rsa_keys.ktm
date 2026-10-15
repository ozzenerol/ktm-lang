import crypto, encoding.hex, fs, proc, str, test;

/* crypto.Key took P-256 keys only. It now takes RSA keys too (2048 bits
 * or more), signs and verifies over SHA-256, -384 or -512 (signWith,
 * verifyWith: JWT's RS256/384/512 and ES256), says what it is (kind,
 * bits), and generateRsaKey makes one. openssl's key and signatures
 * (crypto-files/rsa-*) are checked both ways. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

String gDir;
String file(u8 *name) {
    scoped String path = str.format("%s/crypto-files/%s", gDir, name);
    return fs.readAll(path[0:]);
}

/* openssl's signature of msg.txt with h, from its hex file */
String opensslSig(u8 *name) {
    scoped String text = file(name);
    String sig;
    hex.decode(str.trimEnd(text[0:]), &sig);
    return sig;
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
    printf("Test - crypto RSA keys: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }

    scoped String keyPem = file("rsa-key.pem");
    scoped String pubPem = file("rsa-pub.pem");
    scoped String msg = file("msg.txt");
    crypto.Key *key = crypto.privateKey(keyPem[0:]);
    crypto.Key *pk = crypto.publicKey(pubPem[0:]);
    check(key.kind() == crypto.KeyKind.rsa && pk.kind() == crypto.KeyKind.rsa, "an RSA PEM is an RSA key");
    check(key.bits() == 2048 && pk.bits() == 2048, "of 2048 bits");

    /* openssl's signatures verify, with the right hash only */
    scoped String s256 = opensslSig("rsa-sig-sha256.hex");
    scoped String s384 = opensslSig("rsa-sig-sha384.hex");
    scoped String s512 = opensslSig("rsa-sig-sha512.hex");
    check(s256.len == 256, "a 2048-bit signature is 256 bytes");
    check(pk.verify(msg[0:], s256[0:]), "openssl's SHA-256 signature verifies (verify)");
    check(pk.verifyWith(crypto.Hash.sha384, msg[0:], s384[0:]), "openssl's SHA-384 signature verifies");
    check(pk.verifyWith(crypto.Hash.sha512, msg[0:], s512[0:]), "openssl's SHA-512 signature verifies");
    check(!pk.verifyWith(crypto.Hash.sha256, msg[0:], s384[0:]), "not with another hash");
    check(!pk.verify("another message", s256[0:]), "not of another message");

    /* PKCS#1 v1.5 is deterministic: ours equal openssl's */
    scoped String mine256 = key.sign(msg[0:]);
    scoped String mine512 = key.signWith(crypto.Hash.sha512, msg[0:]);
    check(str.equals(mine256[0:], s256[0:]) && str.equals(mine512[0:], s512[0:]),
          "our signatures are openssl's");
    mine256.ptr[10] ^= 1;
    check(!pk.verify(msg[0:], mine256[0:]), "a changed signature fails");

    /* PEM out and back in */
    scoped String outPub = key.publicPem();
    crypto.Key *again = crypto.publicKey(outPub[0:]);
    check(again.verifyWith(crypto.Hash.sha384, msg[0:], s384[0:]), "the public PEM written reads back");
    scoped String outKey = key.privatePem();
    crypto.Key *again2 = crypto.privateKey(outKey[0:]);
    scoped String sig2 = again2.sign("x");
    check(pk.verify("x", sig2[0:]), "the private PEM written reads back");

    /* a new key */
    crypto.Key *made = crypto.generateRsaKey();
    scoped String madeSig = made.signWith(crypto.Hash.sha384, "hello");
    check(made.kind() == crypto.KeyKind.rsa && made.bits() == 2048 && madeSig.len == 256 &&
          made.verifyWith(crypto.Hash.sha384, "hello", madeSig[0:]) &&
          !pk.verifyWith(crypto.Hash.sha384, "hello", madeSig[0:]), "generateRsaKey");
    bool threw = false;
    try { crypto.generateRsaKey(1024); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a 1024-bit RSA key is refused");

    /* P-256 keys: kind, and the other hashes */
    crypto.Key *ec = crypto.generateKey();
    scoped String es = ec.signWith(crypto.Hash.sha384, "data");
    check(ec.kind() == crypto.KeyKind.ec256 && ec.bits() == 256 && es.len == 64 &&
          ec.verifyWith(crypto.Hash.sha384, "data", es[0:]) &&
          !ec.verify("data", es[0:]), "a P-256 key signs over SHA-384 too");
    check(!ec.verify(msg[0:], s256[0:]) && !pk.verify("data", es[0:]), "an RSA signature isn't a P-256 one");

    /* what Key doesn't take */
    scoped String ed = file("k256-key.pem");
    check(throws(ed[0:], true), "a secp256k1 key is refused");
    threw = false;
    try { pk.sign("x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a public RSA key can't sign");

    key.free(); pk.free(); again.free(); again2.free(); made.free(); ec.free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
