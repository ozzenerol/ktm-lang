import crypto, encoding.hex, fs, proc, str, test;

/* crypto.Key takes P-384 and P-521 keys too (JWT's ES384, ES512):
 * generateKey(KeyKind.ec384 / ec521), PEM in and out, ecPublicKey(x, y)
 * by the coordinates' size, r||s of 96 and 132 bytes, and Key.ecPoint.
 * openssl's keys and signatures (crypto-files/p384-*, p521-*) check it. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

String gDir;
String file(u8[] name) {
    scoped String path = str.format("%s/crypto-files/%.*s", gDir, name.len, name.ptr);
    return fs.readAll(path[0:]);
}

String bytes(u8[] name) {
    scoped String text = file(name);
    String out;
    hex.decode(str.trimEnd(text[0:]), &out);
    return out;
}

/* one curve: name "p384" or "p521", its kind, size, hash */
void curve(u8[] name, crypto.KeyKind kind, i64 bits, i64 n, crypto.Hash h) {
    scoped String keyName = str.format("%.*s-key.pem", name.len, name.ptr);
    scoped String pubName = str.format("%.*s-pub.pem", name.len, name.ptr);
    scoped String keyPem = file(keyName[0:]);
    scoped String pubPem = file(pubName[0:]);
    scoped String msg = file("msg.txt");
    scoped String xName = str.format("%.*s-x.hex", name.len, name.ptr);
    scoped String yName = str.format("%.*s-y.hex", name.len, name.ptr);
    scoped String derName = str.format("%.*s-sig-der.hex", name.len, name.ptr);
    scoped String rawName = str.format("%.*s-sig-raw.hex", name.len, name.ptr);
    scoped String x = bytes(xName[0:]);
    scoped String y = bytes(yName[0:]);
    scoped String der = bytes(derName[0:]);
    scoped String raw = bytes(rawName[0:]);
    crypto.Key *k = crypto.privateKey(keyPem[0:]);
    crypto.Key *p = crypto.publicKey(pubPem[0:]);
    crypto.Key *q = crypto.ecPublicKey(x[0:], y[0:]);
    check(k.kind() == kind && p.kind() == kind && q.kind() == kind && k.bits() == bits, "kind and bits");
    check(raw.len == 2 * n, "raw r||s is twice the coordinate size");
    check(p.verifyWith(h, msg[0:], der[0:]) && p.verifyWith(h, msg[0:], raw[0:]) &&
          q.verifyWith(h, msg[0:], raw[0:]), "openssl's signature verifies, DER and raw");
    check(!p.verifyWith(crypto.Hash.sha256, msg[0:], raw[0:]) && !p.verifyWith(h, "other", raw[0:]),
          "not with another hash or message");
    scoped String mine = k.signWith(h, msg[0:]);
    check(mine.len == 2 * n && p.verifyWith(h, msg[0:], mine[0:]) && q.verifyWith(h, msg[0:], mine[0:]),
          "ours verifies with the public keys");
    mine.ptr[5] ^= 1;
    check(!p.verifyWith(h, msg[0:], mine[0:]), "a changed signature fails");
    String px, py;
    check(k.ecPoint(&px, &py) && str.equals(px[0:], x[0:]) && str.equals(py[0:], y[0:]), "ecPoint is openssl's point");
    px.free();
    py.free();
    scoped String outPub = q.publicPem();
    check(str.equals(str.trimEnd(outPub[0:]), str.trimEnd(pubPem[0:])), "the public PEM written is openssl's");
    scoped String outKey = k.privatePem();
    crypto.Key *back = crypto.privateKey(outKey[0:]);
    scoped String s2 = back.signWith(h, "x");
    check(back.kind() == kind && p.verifyWith(h, "x", s2[0:]), "the private PEM reads back");
    /* the point moved off the curve */
    y.ptr[y.len - 1] ^= 1;
    bool threw = false;
    try { crypto.Key *bad = crypto.ecPublicKey(x[0:], y[0:]); bad.free(); } catch (Error e) { threw = true; }
    check(threw, "a point off the curve is refused");
    k.free(); p.free(); q.free(); back.free();
}

i32 main() {
    printf("Test - crypto P-384 and P-521: ");
    gDir = proc.getenv("KTM_TEST_SRC");
    if (!gDir.len) { gDir.free(); gDir = str.from("."); }

    curve("p384", crypto.KeyKind.ec384, 384, 48, crypto.Hash.sha384);
    curve("p521", crypto.KeyKind.ec521, 521, 66, crypto.Hash.sha512);

    crypto.Key *a = crypto.generateKey(crypto.KeyKind.ec384);
    crypto.Key *b = crypto.generateKey(crypto.KeyKind.ec521);
    scoped String sa = a.signWith(crypto.Hash.sha384, "hello");
    scoped String sb = b.signWith(crypto.Hash.sha512, "hello");
    check(a.kind() == crypto.KeyKind.ec384 && sa.len == 96 && a.verifyWith(crypto.Hash.sha384, "hello", sa[0:]),
          "generateKey(KeyKind.ec384)");
    check(b.kind() == crypto.KeyKind.ec521 && sb.len == 132 && b.verifyWith(crypto.Hash.sha512, "hello", sb[0:]),
          "generateKey(KeyKind.ec521)");
    check(!a.verifyWith(crypto.Hash.sha384, "hello", sb[0:]) && !b.verifyWith(crypto.Hash.sha512, "hello", sa[0:]),
          "the curves don't take each other's signatures");

    /* P-256 is as before; RSA and Ed25519 have no point */
    crypto.Key *c = crypto.generateKey();
    scoped String sc = c.sign("hello");
    String x, y;
    check(c.kind() == crypto.KeyKind.ec256 && sc.len == 64 && c.ecPoint(&x, &y) && x.len == 32, "P-256 as before");
    crypto.Key *e = crypto.generateKey(crypto.KeyKind.ed25519);
    check(!e.ecPoint(&x, &y), "an Ed25519 key has no EC point");
    bool threw = false;
    scoped String x5 = str.from("12345");
    try { crypto.Key *bad = crypto.ecPublicKey(x5[0:], x5[0:]); bad.free(); } catch (Error err) { threw = true; }
    check(threw, "coordinates of 5 bytes are refused");
    x.free(); y.free();
    a.free(); b.free(); c.free(); e.free();
    gDir.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
