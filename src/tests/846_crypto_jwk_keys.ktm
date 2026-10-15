import crypto, encoding.hex, fs, proc, str, test;

/* Public keys from their parts, as a JWK has them (x, y for P-256; n, e
 * for RSA): crypto read keys from PEM only. The parts of openssl's keys
 * (crypto-files/) give keys that verify openssl's signatures. */

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

/* the bytes of a hex file */
String bytes(u8 *name) {
    ad String text = file(name);
    String out;
    hex.decode(str.trimEnd(text[0:]), &out);
    return out;
}

/* does making the key throw ERR_CRYPTO? */
bool ecThrows(u8[] x, u8[] y) {
    try {
        crypto.Key *k = crypto.ecPublicKey(x, y);
        k.free();
    } catch (Error e) {
        return e.code == crypto.ERR_CRYPTO;
    }
    return false;
}

bool rsaThrows(u8[] n, u8[] e) {
    try {
        crypto.Key *k = crypto.rsaPublicKey(n, e);
        k.free();
    } catch (Error e) {
        return e.code == crypto.ERR_CRYPTO;
    }
    return false;
}

i32 main() {
    printf("Test - crypto keys from JWK parts: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }
    ad String msg = file("msg.txt");

    /* P-256 */
    ad String x = bytes("ec-x.hex");
    ad String y = bytes("ec-y.hex");
    ad String ecSig = bytes("sig.hex");
    crypto.Key *ec = crypto.ecPublicKey(x[0:], y[0:]);
    check(ec.kind() == crypto.KeyKind.ec256 && ec.bits() == 256, "x and y make a P-256 key");
    check(ec.verify(msg[0:], ecSig[0:]) && !ec.verify("other", ecSig[0:]), "it verifies openssl's signature");
    ad String ecPem = ec.publicPem();
    ad String filePem = file("pub.pem");
    check(str.equals(str.trimEnd(ecPem[0:]), str.trimEnd(filePem[0:])), "its PEM is openssl's");
    ad String y2 = bytes("ec-y.hex");
    y2.ptr[31] ^= 1;
    check(ecThrows(x[0:], y2[0:]), "a point off the curve is refused");
    check(ecThrows(x[0:31], y[0:]), "x of 31 bytes is refused");

    /* RSA */
    ad String n = bytes("rsa-n.hex");
    u8 e[3] = {1, 0, 1};               /* 65537, as a JWK's "AQAB" */
    ad String rsaSig = bytes("rsa-sig-sha256.hex");
    ad String rsaSig512 = bytes("rsa-sig-sha512.hex");
    crypto.Key *rk = crypto.rsaPublicKey(n[0:], e[0:3]);
    check(rk.kind() == crypto.KeyKind.rsa && rk.bits() == 2048, "n and e make a 2048-bit RSA key");
    check(rk.verify(msg[0:], rsaSig[0:]) && rk.verifyWith(crypto.Hash.sha512, msg[0:], rsaSig512[0:]) &&
          !rk.verify("other", rsaSig[0:]), "it verifies openssl's signatures");
    ad String rsaPem = rk.publicPem();
    ad String rsaFilePem = file("rsa-pub.pem");
    check(str.equals(str.trimEnd(rsaPem[0:]), str.trimEnd(rsaFilePem[0:])), "its PEM is openssl's");
    check(rsaThrows(n[0:128], e[0:3]), "a 1024-bit modulus is refused");
    check(rsaThrows(n[0:], ""), "no exponent is refused");
    bool threw = false;
    try { rk.sign("x"); } catch (Error err) { threw = err.code == crypto.ERR_CRYPTO; }
    check(threw, "a key from parts is public: it can't sign");

    ec.free();
    rk.free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
