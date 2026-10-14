import encoding.base64, encoding.hex, str, test;

/* encoding.base64 and encoding.hex (v0.20.0): RFC 4648's test vectors,
 * the URL-safe alphabet without padding, every byte value, round trips
 * of every length up to 300, and the inputs decode refuses. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

bool enc(u8[] in, u8[] want) {
    String s = base64.encode(in);
    bool ok = str.equals(s[0:], want);
    s.free();
    return ok;
}

bool dec(u8[] in, u8[] want) {
    String s;
    bool ok = base64.decode(in, &s) && str.equals(s[0:], want);
    s.free();
    return ok;
}

bool bad(u8[] in) {
    String s;
    s.append("left over");
    bool ok = !base64.decode(in, &s) && s.len == 0;
    s.free();
    return ok;
}

i32 main() {
    printf("Test - base64 and hex: ");
    /* RFC 4648 section 10 */
    check(enc("", "") && enc("f", "Zg==") && enc("fo", "Zm8=") && enc("foo", "Zm9v") &&
          enc("foob", "Zm9vYg==") && enc("fooba", "Zm9vYmE=") && enc("foobar", "Zm9vYmFy"), "RFC 4648 encodings");
    check(dec("", "") && dec("Zg==", "f") && dec("Zm8=", "fo") && dec("Zm9v", "foo") &&
          dec("Zm9vYg==", "foob") && dec("Zm9vYmE=", "fooba") && dec("Zm9vYmFy", "foobar"), "RFC 4648 decodings");
    check(dec("Zg", "f") && dec("Zm8", "fo"), "no padding");
    check(dec("Zm9v\nYmFy\r\n", "foobar") && dec(" Zm9v YmFy ", "foobar"), "line breaks and blanks are skipped");
    u8 bin[3] = {0xFB, 0xFF, 0xBF};
    String u = base64.encodeUrl(bin[0:3]);
    String s = base64.encode(bin[0:3]);
    check(str.equals(u[0:], "-_-_") && str.equals(s[0:], "+/+/"), "the URL-safe alphabet");
    String back;
    check(base64.decode(u[0:], &back) && back.len == 3 && back[0] == 0xFB && back[2] == 0xBF, "URL-safe decodes");
    u.free(); s.free();
    String one = base64.encodeUrl("f");
    check(str.equals(one[0:], "Zg"), "URL-safe has no padding");
    one.free();
    check(bad("Z") && bad("Zg=") && bad("Zg===") && bad("Zm9v!") && bad("Zg==Zg==") && bad("===="), "bad input is refused");
    /* every byte value, every length */
    u8 all[300];
    for (i64 i = 0; i < 300; i++) { all[i] = (u8)(i * 7 + 3); }
    bool round = true;
    for (i64 n = 0; n <= 300 && round; n++) {
        String e = base64.encode(all[0:n]);
        String f = base64.encodeUrl(all[0:n]);
        String d1, d2;
        round = e.len == base64.encodedLen(n) && base64.decode(e[0:], &d1) && base64.decode(f[0:], &d2) &&
                str.equals(d1[0:], all[0:n]) && str.equals(d2[0:], all[0:n]);
        e.free(); f.free(); d1.free(); d2.free();
    }
    check(round, "round trips of every length up to 300");

    String h = hex.encode(all[0:4]);
    check(str.equals(h[0:], "030a1118"), "hex.encode");
    String raw;
    check(hex.decode("030A1118", &raw) && str.equals(raw[0:], all[0:4]), "hex.decode, either case");
    check(!hex.decode("0", &raw) && !hex.decode("0g", &raw) && raw.len == 0, "hex refuses odd lengths and non-digits");
    h.free(); raw.free(); back.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
