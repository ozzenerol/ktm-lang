import crypto, encoding.hex, fs, proc, str, test;

/* What JWE needs from crypto: AES-128/192-GCM in seal/open, AES-CBC with
 * PKCS#7 padding, AES key wrap (RFC 3394), RSA-OAEP-256, ECDH. Published
 * vectors (GCM test case 4, SP 800-38A F.2.1, RFC 3394 4.1 and 4.6),
 * values from Python's cryptography (AES-192-GCM, CBC with padding), and
 * openssl's OAEP ciphertext and ECDH secret (crypto-files/). */

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

String h(u8[] text) {
    String out;
    hex.decode(str.trimEnd(text), &out);
    return out;
}

String hexFile(u8 *name) {
    ad String text = file(name);
    return h(text[0:]);
}

bool hexIs(u8[] bytes, u8[] want) {
    String got = hex.encode(bytes);
    bool ok = str.equals(got[0:], want);
    if (!ok) { printf("\n  got %.*s\n", got.len, got.ptr); }
    got.free();
    return ok;
}

bool throws(crypto.Key *a, crypto.Key *b) {
    try {
        String z = a.ecdh(b);
        z.free();
    } catch (Error e) {
        return e.code == crypto.ERR_CRYPTO;
    }
    return false;
}

/* AES-GCM with 128- and 192-bit keys */
void gcm() {
    ad String k128 = h("feffe9928665731c6d6a8f9467308308");
    ad String iv = h("cafebabefacedbaddecaf888");
    ad String plain = h("d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a721c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b39");
    ad String aad = h("feedfacedeadbeeffeedfacedeadbeefabaddad2");
    ad String sealed = crypto.seal(crypto.Aead.aesGcm, k128[0:], iv[0:], plain[0:], aad[0:]);
    check(hexIs(sealed[0:], "42831ec2217774244b7221b784d0d49ce3aa212f2c02a4e035c17e2329aca12e21d514b25466931c7d8f6a5aac84aa051ba30b396a0aac973d58e091"
                            "5bc94fbc3221a5db94fae95ae7121a47"), "AES-128-GCM, GCM test case 4");
    String back;
    check(crypto.open(crypto.Aead.aesGcm, k128[0:], iv[0:], sealed[0:], &back, aad[0:]) &&
          str.equals(back[0:], plain[0:]), "and it opens");
    sealed.ptr[0] ^= 1;
    check(!crypto.open(crypto.Aead.aesGcm, k128[0:], iv[0:], sealed[0:], &back, aad[0:]), "altered, it doesn't");
    u8 k192[24], iv12[12];
    for (i64 i = 0; i < 24; i++) { k192[i] = (u8)i; }
    for (i64 i = 0; i < 12; i++) { iv12[i] = (u8)i; }
    ad String s192 = crypto.seal(crypto.Aead.aesGcm, k192[0:24], iv12[0:12], "JWE content", "header");
    check(hexIs(s192[0:], "acae67bbfad6a77ab55da893a81da3756437799f624a14d0c95d01"), "AES-192-GCM");
    bool threw = false;
    try { crypto.seal(crypto.Aead.chacha20Poly1305, k128[0:], iv[0:], "x"); } catch (Error e) { threw = true; }
    check(threw, "ChaCha20-Poly1305 still needs 32 bytes");
    threw = false;
    try { crypto.seal(crypto.Aead.aesGcm, k192[0:20], iv[0:], "x"); } catch (Error e) { threw = true; }
    check(threw, "a 20-byte AES key is refused");
    back.free();
}

void cbc() {
    ad String ck = h("2b7e151628aed2a6abf7158809cf4f3c");
    ad String civ = h("000102030405060708090a0b0c0d0e0f");
    ad String cp = h("6bc1bee22e409f96e93d7e117393172a");
    ad String cc = crypto.aesCbcEncrypt(ck[0:], civ[0:], cp[0:]);
    check(cc.len == 32 && hexIs(cc[0:16], "7649abac8119b246cee98e9b12e9197d"), "AES-128-CBC, SP 800-38A F.2.1 (+ a padding block)");
    String cb;
    check(crypto.aesCbcDecrypt(ck[0:], civ[0:], cc[0:], &cb) && str.equals(cb[0:], cp[0:]), "and back");
    u8 pk[16], piv[16];
    for (i64 i = 0; i < 16; i++) { pk[i] = (u8)i; piv[i] = (u8)(16 + i); }
    ad String c5 = crypto.aesCbcEncrypt(pk[0:16], piv[0:16], "xxxxx");
    ad String c16 = crypto.aesCbcEncrypt(pk[0:16], piv[0:16], "xxxxxxxxxxxxxxxx");
    check(hexIs(c5[0:], "15abae84f34d30d705532aed9654ea8d") &&
          hexIs(c16[0:], "f1d731cc2eab6874762e04503efd43c313ef13aebded0dfbc30882876e12b8ea"), "PKCS#7 padding as cryptography's");
    c16.ptr[31] ^= 0x55;
    check(!crypto.aesCbcDecrypt(pk[0:16], piv[0:16], c16[0:], &cb) && cb.len == 0, "bad padding: false, nothing out");
    check(!crypto.aesCbcDecrypt(pk[0:16], piv[0:16], c16[0:20], &cb), "not whole blocks: false");
    cb.free();
}

void keyWrap() {
    ad String kek = h("000102030405060708090A0B0C0D0E0F");
    ad String key = h("00112233445566778899AABBCCDDEEFF");
    ad String w = crypto.aesKeyWrap(kek[0:], key[0:]);
    check(hexIs(w[0:], "1fa68b0a8112b447aef34bd8fb5a7b829d3e862371d2cfe5"), "RFC 3394 4.1");
    ad String kek256 = h("000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F");
    ad String key256 = h("00112233445566778899AABBCCDDEEFF000102030405060708090A0B0C0D0E0F");
    ad String w256 = crypto.aesKeyWrap(kek256[0:], key256[0:]);
    check(hexIs(w256[0:], "28c9f404c4b810f4cbccb35cfb87f8263f5786e2d80ed326cbc7f0e71a99f43bfb988b9b7a02dd21"), "RFC 3394 4.6");
    String uw;
    check(crypto.aesKeyUnwrap(kek256[0:], w256[0:], &uw) && str.equals(uw[0:], key256[0:]), "unwrapped");
    check(!crypto.aesKeyUnwrap(kek[0:], w256[0:], &uw) && uw.len == 0, "another kek: false");
    w256.ptr[9] ^= 1;
    check(!crypto.aesKeyUnwrap(kek256[0:], w256[0:], &uw), "altered: false");
    uw.free();
}

void oaep() {
    bool threw = false;
    ad String rsaPem = file("rsa-key.pem");
    ad String rsaPub = file("rsa-pub.pem");
    ad String msg = file("msg.txt");
    crypto.Key *rk = crypto.privateKey(rsaPem[0:]);
    crypto.Key *rp = crypto.publicKey(rsaPub[0:]);
    ad String oct = hexFile("rsa-oaep256.hex");
    String dec;
    check(rk.decryptOaep(crypto.Hash.sha256, oct[0:], &dec) && str.equals(dec[0:], msg[0:]), "openssl's RSA-OAEP-256 decrypts");
    check(!rk.decryptOaep(crypto.Hash.sha384, oct[0:], &dec) && dec.len == 0, "not with another hash");
    ad String mine = rp.encryptOaep(crypto.Hash.sha256, "a content key...");
    ad String mine2 = rp.encryptOaep(crypto.Hash.sha256, "a content key...");
    check(mine.len == 256 && !str.equals(mine[0:], mine2[0:]) && rk.decryptOaep(crypto.Hash.sha256, mine[0:], &dec) &&
          str.equals(dec[0:], "a content key..."), "ours decrypts (randomised)");
    mine.ptr[40] ^= 1;
    check(!rk.decryptOaep(crypto.Hash.sha256, mine[0:], &dec), "altered: false");
    threw = false;
    try { rp.decryptOaep(crypto.Hash.sha256, oct[0:], &dec); } catch (Error e) { threw = true; }
    check(threw, "a public key can't decrypt");
    dec.free();
    rk.free();
    rp.free();
}

void ecdh() {
    bool threw = false;
    ad String rsaPub = file("rsa-pub.pem");
    crypto.Key *rp = crypto.publicKey(rsaPub[0:]);
    ad String aPem = file("ecdh-a.pem");
    ad String bPem = file("ecdh-b.pem");
    ad String bPub = file("ecdh-b-pub.pem");
    ad String want = hexFile("ecdh-ab.hex");
    crypto.Key *a = crypto.privateKey(aPem[0:]);
    crypto.Key *b = crypto.privateKey(bPem[0:]);
    crypto.Key *bp = crypto.publicKey(bPub[0:]);
    ad String z1 = a.ecdh(bp);
    check(str.equals(z1[0:], want[0:]), "ECDH: openssl's secret");
    String ax, ay;
    a.ecPoint(&ax, &ay);
    crypto.Key *ap = crypto.ecPublicKey(ax[0:], ay[0:]);
    ad String z2 = b.ecdh(ap);
    check(str.equals(z2[0:], want[0:]), "both ways");
    crypto.Key *e384 = crypto.generateKey(crypto.KeyKind.ec384);
    crypto.Key *e384b = crypto.generateKey(crypto.KeyKind.ec384);
    ad String z3 = e384.ecdh(e384b);
    ad String z4 = e384b.ecdh(e384);
    check(z3.len == 48 && str.equals(z3[0:], z4[0:]), "P-384: 48 bytes, the same both ways");
    check(throws(a, e384), "another curve throws");
    check(throws(a, rp), "an RSA peer throws");
    check(throws(bp, a), "a public key can't do ECDH");
    ad String off = file("ec-offcurve-pub.pem");
    threw = false;
    try { crypto.Key *bad = crypto.publicKey(off[0:]); bad.free(); } catch (Error e) { threw = true; }
    check(threw, "a point off the curve is refused when read");
    ay.ptr[31] ^= 1;
    threw = false;
    try { crypto.Key *bad = crypto.ecPublicKey(ax[0:], ay[0:]); bad.free(); } catch (Error e) { threw = true; }
    check(threw, "and as x, y");
    ax.free(); ay.free();
    rp.free(); a.free(); b.free(); bp.free(); ap.free(); e384.free(); e384b.free();
}

i32 main() {
    printf("Test - crypto JWE primitives: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }
    gcm();
    cbc();
    keyWrap();
    oaep();
    ecdh();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
