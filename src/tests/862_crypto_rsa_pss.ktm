import crypto, encoding.hex, fs, proc, str, test;

/* RSA-PSS (JWT's PS256/384/512): Key.signPss and verifyPss, MGF1 with the
 * digest's hash, a salt as long as the digest. openssl's PSS signatures
 * (crypto-files/rsa-pss-*) verify; one with the longest salt doesn't (JWA
 * fixes the salt's length); signing PSS leaves the key's PKCS#1 v1.5
 * signatures as they were. */

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

String bytes(u8 *name) {
    scoped String text = file(name);
    String out;
    hex.decode(str.trimEnd(text[0:]), &out);
    return out;
}

i32 main() {
    printf("Test - crypto RSA-PSS: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }
    scoped String keyPem = file("rsa-key.pem");
    scoped String pubPem = file("rsa-pub.pem");
    scoped String msg = file("msg.txt");
    crypto.Key *k = crypto.privateKey(keyPem[0:]);
    crypto.Key *p = crypto.publicKey(pubPem[0:]);

    scoped String s256 = bytes("rsa-pss-sha256.hex");
    scoped String s384 = bytes("rsa-pss-sha384.hex");
    scoped String s512 = bytes("rsa-pss-sha512.hex");
    scoped String maxSalt = bytes("rsa-pss-sha256-maxsalt.hex");
    check(p.verifyPss(crypto.Hash.sha256, msg[0:], s256[0:]), "openssl's PSS SHA-256 verifies");
    check(p.verifyPss(crypto.Hash.sha384, msg[0:], s384[0:]), "openssl's PSS SHA-384 verifies");
    check(p.verifyPss(crypto.Hash.sha512, msg[0:], s512[0:]), "openssl's PSS SHA-512 verifies");
    check(!p.verifyPss(crypto.Hash.sha384, msg[0:], s256[0:]) && !p.verifyPss(crypto.Hash.sha256, "x", s256[0:]),
          "not with another hash or message");
    check(!p.verifyPss(crypto.Hash.sha256, msg[0:], maxSalt[0:]), "a salt longer than the hash is refused");
    check(!p.verify(msg[0:], s256[0:]), "a PSS signature isn't a PKCS#1 v1.5 one");

    /* PKCS#1 v1.5 before and after PSS signing: the same bytes */
    scoped String v15a = k.sign(msg[0:]);
    scoped String mine = k.signPss(crypto.Hash.sha256, msg[0:]);
    scoped String mine2 = k.signPss(crypto.Hash.sha256, msg[0:]);
    scoped String v15b = k.sign(msg[0:]);
    check(mine.len == 256 && p.verifyPss(crypto.Hash.sha256, msg[0:], mine[0:]), "ours verifies");
    check(!str.equals(mine[0:], mine2[0:]) && p.verifyPss(crypto.Hash.sha256, msg[0:], mine2[0:]),
          "PSS is randomised: two signatures differ, both verify");
    check(str.equals(v15a[0:], v15b[0:]) && p.verify(msg[0:], v15b[0:]), "PKCS#1 v1.5 is unchanged by PSS signing");
    check(!p.verifyPss(crypto.Hash.sha256, msg[0:], v15a[0:]), "a PKCS#1 v1.5 signature isn't a PSS one");
    scoped String m512 = k.signPss(crypto.Hash.sha512, "data");
    check(p.verifyPss(crypto.Hash.sha512, "data", m512[0:]), "SHA-512");
    mine.ptr[100] ^= 1;
    check(!p.verifyPss(crypto.Hash.sha256, msg[0:], mine[0:]), "a changed signature fails");
    check(!p.verifyPss(crypto.Hash.sha256, msg[0:], s256[0:255]), "a short signature fails");

    /* only RSA keys */
    crypto.Key *ec = crypto.generateKey();
    bool threw = false;
    try { ec.signPss(crypto.Hash.sha256, "x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a P-256 key can't sign PSS");
    threw = false;
    try { p.signPss(crypto.Hash.sha256, "x"); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a public key can't sign");
    threw = false;
    try { ec.verifyPss(crypto.Hash.sha256, "x", s256[0:]); } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "a P-256 key can't verify PSS");

    k.free(); p.free(); ec.free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
