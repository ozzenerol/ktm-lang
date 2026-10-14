import crypto, encoding.hex, str, test;

/* Password hashing (v0.20.0): Argon2id and BLAKE2b against
 * golang.org/x/crypto's and Python's outputs (several lanes, odd memory
 * sizes, long keys), PHC strings made elsewhere, bcrypt hashes from Go
 * ($2a$) and Python ($2b$, UTF-8, over 72 bytes), $2y$, hash then verify,
 * wrong passwords and malformed hashes, needsRehash, bad parameters. */

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

bool argon(i64 t, i64 m, i64 p, i64 n, u8[] want) {
    u8 out[128];
    crypto.argon2idKey("password", "somesalt12345678", m, t, p, out[0:n]);
    String h = hex.encode(out[0:n]);
    bool ok = str.equals(h[0:], want);
    if (!ok) { printf("\n  argon2id t=%d m=%d p=%d: %.*s", t, m, p, h.len, h.ptr); }
    h.free();
    return ok;
}

bool throws(u8[] pw, i64 cost) {
    try {
        String h = crypto.bcrypt(pw, cost);
        h.free();
    } catch (Error e) {
        return e.code == crypto.ERR_CRYPTO;
    }
    return false;
}

i32 main() {
    printf("Test - password hashing: ");
    u8 d[64];
    crypto.blake2b("abc", d[0:64]);
    String h = hex.encode(d[0:64]);
    check(str.equals(h[0:], "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1"
                            "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"), "BLAKE2b-512");
    h.free();

    check(argon(1, 64, 1, 32, "2a8bf2c64a9623c36221b9d54ec1c91ad59d6b4edac54aa825522e3e8b6a2027") &&
          argon(2, 19456, 1, 32, "0c4c0b6db219194b6006e078818a24eabea136f7af619a31930310e7f2d749a5") &&
          argon(3, 256, 4, 16, "bf525b9f25ae11a17f32f886baa3ec35") &&
          argon(4, 37, 1, 32, "63a7ca178fbf5443f7e3b938e932899cfa69f1cb684f7c0d9ba793d1700c9a24") &&
          argon(1, 1024, 2, 100, "895425567477c95eb97170c61796185313cb4969dbf89862aef35990ae5b19e1a5d5056bd20bab2a"
                                 "604cabc8a6fa8991b779b2ea64c0f372be2764035be7612071c95540bc75fec306acc1394e320f"
                                 "ae8be26c5fa6b5068fb87599e8761ad81a18e2b08b"), "Argon2id as Go's x/crypto");

    u8[] go1 = "$argon2id$v=19$m=19456,t=2,p=1$c29tZXNhbHQxMjM0NTY3OA$DEwLbbIZGUtgBuB4gYok6r6hNvevYZoxkwMQ5/LXSaU";
    u8[] go2 = "$argon2id$v=19$m=37,t=4,p=1$c29tZXNhbHQxMjM0NTY3OA$Y6fKF4+/VEP347k46TKJnPpp8ctoT3wNm6eT0XAMmiQ";
    check(crypto.verifyPassword("password", go1) && crypto.verifyPassword("password", go2) &&
          !crypto.verifyPassword("Password", go1) && !crypto.verifyPassword("", go2), "PHC strings made elsewhere");
    check(!crypto.needsRehash(go1) && crypto.needsRehash(go2), "needsRehash: the cost");

    String a = crypto.hashPassword("hunter2");
    String b = crypto.hashPassword("hunter2");
    check(str.startsWith(a[0:], "$argon2id$v=19$m=19456,t=2,p=1$") && !str.equals(a[0:], b[0:]) &&
          crypto.verifyPassword("hunter2", a[0:]) && crypto.verifyPassword("hunter2", b[0:]) &&
          !crypto.verifyPassword("hunter3", a[0:]), "hashPassword: salted, verifies");
    a.free(); b.free();
    String c = crypto.argon2id("pw", 256, 1, 2);
    check(str.startsWith(c[0:], "$argon2id$v=19$m=256,t=1,p=2$") && crypto.verifyPassword("pw", c[0:]) &&
          crypto.needsRehash(c[0:]), "argon2id with a cost of its own");
    c.free();

    check(crypto.verifyPassword("correct horse", "$2a$05$DXPf9UPJlB9wH1wHnSblUO3hL9YPMW//a9kLEP1YCiA58iz7bA5Ce") &&
          !crypto.verifyPassword("correct horsf", "$2a$05$DXPf9UPJlB9wH1wHnSblUO3hL9YPMW//a9kLEP1YCiA58iz7bA5Ce"),
          "bcrypt from Go");
    check(crypto.verifyPassword("p\xc3\xa4ssw\xc3\xb6rd", "$2b$04$OgeVM2wAnJTMU9N4nqkEb.4FjU4kWp53i7Wr25aYWD1mmMM5Vttr2") &&
          crypto.verifyPassword("p\xc3\xa4ssw\xc3\xb6rd", "$2y$04$OgeVM2wAnJTMU9N4nqkEb.4FjU4kWp53i7Wr25aYWD1mmMM5Vttr2"),
          "bcrypt from Python, UTF-8, $2y$");
    u8 xs[80];
    for (i64 i = 0; i < 80; i++) { xs[i] = 'x'; }
    check(crypto.verifyPassword(xs[0:80], "$2b$04$wzrV.OBCQB75bC5GXa4D..yg/jzBUwBavnCtv6SqgnCsHEbLXLLqO") &&
          crypto.verifyPassword(xs[0:72], "$2b$04$wzrV.OBCQB75bC5GXa4D..yg/jzBUwBavnCtv6SqgnCsHEbLXLLqO") &&
          !crypto.verifyPassword(xs[0:71], "$2b$04$wzrV.OBCQB75bC5GXa4D..yg/jzBUwBavnCtv6SqgnCsHEbLXLLqO"),
          "bcrypt reads 72 bytes");
    String bc = crypto.bcrypt("hunter2", 5);
    check(bc.len == 60 && str.startsWith(bc[0:], "$2b$05$") && crypto.verifyPassword("hunter2", bc[0:]) &&
          !crypto.verifyPassword("hunter", bc[0:]) && crypto.needsRehash(bc[0:]), "bcrypt: hash, verify");
    bc.free();
    check(throws(xs[0:73], 4) && throws("pw", 3) && throws("pw", 32), "bcrypt refuses >72 bytes and a bad cost");

    check(!crypto.verifyPassword("password", "") && !crypto.verifyPassword("password", "plain text") &&
          !crypto.verifyPassword("password", "$argon2i$v=19$m=64,t=1,p=1$c29tZXNhbHQxMjM0NTY3OA$KovyxkqWI8NiIbnVTsHJGtWda07axUqoJVIuPotqICc") &&
          !crypto.verifyPassword("password", "$argon2id$v=16$m=64,t=1,p=1$c29tZXNhbHQxMjM0NTY3OA$KovyxkqWI8NiIbnVTsHJGtWda07axUqoJVIuPotqICc") &&
          !crypto.verifyPassword("password", "$argon2id$v=19$m=64,t=0,p=1$c29tZXNhbHQxMjM0NTY3OA$KovyxkqWI8NiIbnVTsHJGtWda07axUqoJVIuPotqICc") &&
          !crypto.verifyPassword("password", "$argon2id$v=19$m=64,t=1,p=1$c29t!$KovyxkqWI8NiIbnVTsHJGtWda07axUqoJVIuPotqICc") &&
          !crypto.verifyPassword("password", "$argon2id$v=19$m=64,t=1,p=1$c29tZXNhbHQxMjM0NTY3OA") &&
          !crypto.verifyPassword("correct horse", "$2a$05$DXPf9UPJlB9wH1wHnSblUO3hL9YPMW//a9kLEP1YCiA58iz7bA5C") &&
          !crypto.verifyPassword("correct horse", "$2a$99$DXPf9UPJlB9wH1wHnSblUO3hL9YPMW//a9kLEP1YCiA58iz7bA5Ce") &&
          !crypto.verifyPassword("correct horse", "$2x$05$DXPf9UPJlB9wH1wHnSblUO3hL9YPMW//a9kLEP1YCiA58iz7bA5Ce"),
          "malformed hashes are false");
    check(crypto.verifyPassword("password", "$argon2id$v=19$m=64,t=1,p=1$c29tZXNhbHQxMjM0NTY3OA$KovyxkqWI8NiIbnVTsHJGtWda07axUqoJVIuPotqICc"),
          "the m=64 PHC string verifies");

    bool threw = false;
    try {
        u8 o[32];
        crypto.argon2idKey("pw", "short", 64, 1, 1, o[0:32]);
    } catch (Error e) { threw = e.code == crypto.ERR_CRYPTO; }
    check(threw, "argon2idKey: a salt under 8 bytes throws");
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
