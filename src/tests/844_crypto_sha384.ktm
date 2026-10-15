import crypto, encoding.hex, str, test;

/* SHA-384 and HMAC-SHA384 (for JWT's HS384): crypto had SHA-256 and
 * SHA-512 only. Published vectors: FIPS 180-2 ("abc", the two-block
 * message) and RFC 4231 cases 1, 2 and 6 (a key longer than a block). */

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

i32 main() {
    printf("Test - crypto SHA-384: ");
    u8 out[48];
    crypto.sha384("abc", out[0:]);
    check(hexIs(out[0:], "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed"
                         "8086072ba1e7cc2358baeca134c825a7"), "SHA-384 of abc");
    crypto.sha384("abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmn"
                  "hijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu", out[0:]);
    check(hexIs(out[0:], "09330c33f71147e83d192fc782cd1b4753111b173b3b05d22fa08086e3b0f712"
                         "fcc7c71a557e2db966c3e9fa91746039"), "SHA-384, two blocks");

    String key;
    hex.decode("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b", &key);
    crypto.hmacSha384(key[0:], "Hi There", out[0:]);
    check(hexIs(out[0:], "afd03944d84895626b0825f4ab46907f15f9dadbe4101ec682aa034c7cebc59c"
                         "faea9ea9076ede7f4af152e8b2fa9cb6"), "HMAC-SHA384, RFC 4231 case 1");
    key.free();
    crypto.hmacSha384("Jefe", "what do ya want for nothing?", out[0:]);
    check(hexIs(out[0:], "af45d2e376484031617f78d2b58a6b1b9c7ef464f5a01b47e42ec3736322445e"
                         "8e2240ca5e69e2c78b3239ecfab21649"), "HMAC-SHA384, RFC 4231 case 2");
    u8 longKey[131];
    for (i64 i = 0; i < 131; i++) { longKey[i] = 0xaa; }
    crypto.hmacSha384(longKey[0:], "Test Using Larger Than Block-Size Key - Hash Key First", out[0:]);
    check(hexIs(out[0:], "4ece084485813e9088d2c63a041bc5b44f9ef1012a2b588f3cd11f05033ac4c6"
                         "0c2ef6ab4030fe8296248df163f44952"), "HMAC-SHA384, RFC 4231 case 6");

    /* a wrong-sized out throws */
    bool threw = false;
    try {
        crypto.hmacSha384("k", "d", out[0:32]);
    } catch (Error e) {
        threw = e.code == crypto.ERR_CRYPTO;
    }
    check(threw, "a 32-byte out for HMAC-SHA384 throws");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
