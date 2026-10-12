import hash, str, test;

/* module hash (v0.6.0): SHA-256 against the FIPS 180-2 vectors, one-shot
 * and streamed in odd-sized chunks, CRC-32 (IEEE) chained, FNV-1a and
 * the constant-time compare. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; }
    else { printf("\n  failed: %s\n", what); }
}

bool sha256Is(u8[] data, u8[] want) {
    u8 d[32];
    hash.sha256(data, d);
    String h = hash.hex(d);
    defer h.free();
    return str.equals(h, want);
}

bool hexIs(u8[] digest, u8[] want) {
    String h = hash.hex(digest);
    defer h.free();
    return str.equals(h, want);
}

i64 finalShortCode() {
    hash.Sha256 s = hash.sha256New();
    u8 d[31];
    try { s.final(d); } catch (Error e) { return e.code; }
    return 0;
}

i32 main() {
    printf("Test - hash: ");

    u8 *msg448 = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq";
    u8[] want448 = "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1";

    check(sha256Is("abc", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
          "sha256 abc");
    check(sha256Is("", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
          "sha256 empty");
    check(sha256Is(msg448[0:56], want448), "sha256 448-bit message");
    /* 55 and 64 bytes: the padding fits in the block, and needs a second one */
    check(sha256Is("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "9f4390f8d30c2dd92ec9f095b65e2b9ae9b0a925a5258e241c9f1e910f734318"),
          "sha256 55 bytes");
    check(sha256Is("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                   "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb"),
          "sha256 64 bytes");

    /* the 448-bit message split at every point gives the same digest */
    hash.Sha256 s = hash.sha256New();
    u8 d[32];
    bool allSplits = true;
    for (i64 cut = 0; cut <= 56; cut++) {
        s.update(msg448[0:cut]);
        s.update(msg448[cut:56]);
        s.final(d);
        if (!hexIs(d, want448)) { allSplits = false; }
    }
    check(allSplits, "sha256 streamed at every split, final starts over");

    /* one million 'a', streamed in chunks of 1, 7, 13, ... bytes */
    u8 as[1000];
    for (i64 i = 0; i < 1000; i++) { as[i] = 'a'; }
    i64 left = 1000000, step = 1;
    while (left > 0) {
        i64 n = step;
        if (n > left) { n = left; }
        s.update(as[0:n]);
        left -= n;
        step = (step + 6) % 997 + 1;
    }
    s.final(d);
    check(hexIs(d, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"),
          "sha256 one million a, odd chunks");

    s.update("ab");
    s.reset();
    s.update("abc");
    s.final(d);
    check(hexIs(d, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
          "reset drops what was fed");
    check(finalShortCode() == ERR_BOUNDS, "final into 31 bytes throws ERR_BOUNDS");

    u8 two[2];
    two[0] = 0x0f; two[1] = 0xa0;
    check(hexIs(two, "0fa0"), "hex of any bytes");
    check(hexIs(two[0:0], ""), "hex of nothing");

    check(hash.crc32("123456789") == 0xCBF43926, "crc32 check value");
    check(hash.crc32("") == 0, "crc32 of nothing");
    check(hash.crc32("56789", hash.crc32("1234")) == 0xCBF43926, "crc32 chained");
    check(hash.crc32("The quick brown fox jumps over the lazy dog") == 0x414FA339,
          "crc32 fox");

    check(hash.fnv1a("") == 0xcbf29ce484222325, "fnv1a empty");
    check(hash.fnv1a("a") == 0xaf63dc4c8601ec8c, "fnv1a a");
    check(hash.fnv1a("foobar") == 0x85944171f73967e8, "fnv1a foobar");

    check(hash.equalCt("token", "token"), "equalCt same");
    check(!hash.equalCt("token", "tokem"), "equalCt last byte differs");
    check(!hash.equalCt("Token", "token"), "equalCt first byte differs");
    check(!hash.equalCt("tok", "token"), "equalCt lengths differ");
    check(hash.equalCt("", ""), "equalCt empty");

    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
