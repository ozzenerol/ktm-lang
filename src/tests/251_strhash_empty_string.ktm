import test;

import mem, str;

/* StrHash("") read the byte after the terminating NUL (the x86_64 asm
 * loaded 1[RDI] first, the arm64 version started at index 1). It also had
 * no final mixing: h*31+c keeps similar keys ("key1", "key2", ...) in
 * neighbouring slots of a power-of-two table. It is now h*31+c from 0
 * followed by the murmur3 64-bit finaliser, the same on every target;
 * "" hashes to 0. */

u64 modelHash(u8 *s) {
    u64 h = 0;
    for (i64 i = 0; s[i]; ++i) { h = h * 31 + s[i]; }
    h ^= h >> 33;
    h *= 0xFF51AFD7ED558CCD;
    h ^= h >> 33;
    h *= 0xC4CEB9FE1A85EC53;
    h ^= h >> 33;
    return h;
}

i32 main() {
    i64 correct = 0, tests = 0;
    printf("Test - StrHash: ");

    /* "" on a 1-byte block: nothing past it is read (valgrind checks the
     * AOT binary), and the value is fixed */
    u8 *empty = mem.alloc(1);
    empty[0] = '\0';
    tests++; if (str.hash(empty) == 0 && str.hash("") == 0) { correct++; }
    mem.free(empty);

    /* Known values, worked out independently */
    u8 hi[4];
    hi[0] = 0xFF; hi[1] = 0x80; hi[2] = 'z'; hi[3] = '\0';
    tests++;
    if (str.hash("a") == 0x685FDF50E51FA977 && str.hash("ab") == 0xF0F422B68702A7AE &&
            str.hash("hello world") == 0x68C76B672FED4A5E &&
            str.hash("key12345") == 0x17528DBD2BD11F26 &&
            str.hash(hi) == 0x01A56E86E1D7631F) { correct++; }
    else { printf("StrHash(\"a\") = %x\n", str.hash("a")); }

    /* Same as the model for many strings (asm on x86_64, ktm on arm64) */
    bool ok = true;
    u8 buf[64];
    for (i64 i = 0; i < 2000; ++i) {
        str.formatTo(buf, "key%d/%x", i, i * 7919);
        if (str.hash(buf) != modelHash(buf)) { ok = false; }
    }
    tests++; if (ok) { correct++; }

    /* Spread: 100000 "key%d" in 262144 slots, linear probing like StrMap.
     * Without the finaliser this averaged 9.6 probes (max 288). */
    i64 cap = 262144, n = 100000, total = 0, worst = 0;
    bool *taken = mem.allocZero(cap);
    for (i64 i = 0; i < n; ++i) {
        str.formatTo(buf, "key%d", i);
        i64 idx = (i64)(str.hash(buf) & (cap - 1)), probes = 1;
        while (taken[idx]) { idx = (idx + 1) & (cap - 1); probes++; }
        taken[idx] = true;
        total += probes;
        if (probes > worst) { worst = probes; }
    }
    mem.free(taken);
    tests++; if (total < n * 2 && worst < 40) { correct++; }
    else { printf("avg probes %d/100, max %d\n", total * 100 / n, worst); }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
