import atomic, cstr, date, map, str, test, uuid;

/* uuid (v0.20.0): v4's version and variant bits, no repeats in 20000;
 * v7 ordered by time, strictly increasing from 4 threads at once, with
 * its timestamp; the canonical form and every form parse takes; the bad
 * inputs it refuses. */

extern "c" i32 pthread_create(void *t, void *attr, void *start, void *arg);
extern "c" i32 pthread_join(void *t, void **ret);

i64 gCorrect = 0, gTests = 0;
void check(bool ok, u8 *what) {
    gTests++;
    if (ok) { gCorrect++; } else { printf("\n  failed: %s\n", what); }
}

const i64 PER = 20000;
uuid.UUID gIds[4][PER];
i64 gBadOrder = 0;

void *maker(void *arg) {
    i64 t = (i64)arg;
    for (i64 i = 0; i < PER; i++) {
        gIds[t][i] = uuid.v7();
        if (i && gIds[t][i].compare(gIds[t][i - 1]) <= 0) { atomic.add(&gBadOrder, 1); }
    }
    return null;
}

i32 main() {
    printf("Test - uuid: ");
    uuid.UUID a = uuid.v4();
    String s = a.string();
    check(s.len == 36 && s[14] == '4' && (s[19] == '8' || s[19] == '9' || s[19] == 'a' || s[19] == 'b') &&
          a.version() == 4, "v4: version 4, RFC variant");
    map.Map<String, i64> seen = map.new<String, i64>();
    bool dup = false;
    for (i64 i = 0; i < 20000; i++) {
        String k = uuid.v4().string();
        if (seen.has(k)) { dup = true; }
        seen.set(k, 1);          /* the map keeps a copy of the key */
        k.free();
    }
    check(!dup && seen.len == 20000, "20000 v4s, no repeat");
    seen.free();

    i64 now = date.unixNow() * 1000;
    uuid.UUID b = uuid.v7();
    check(b.version() == 7 && b.unixMs() >= now - 2000 && b.unixMs() <= now + 2000, "v7: version 7, its time");
    uuid.UUID c = uuid.v7();
    check(c.compare(b) > 0, "a later v7 sorts after");
    void *t[4];
    for (i64 i = 0; i < 4; i++) { pthread_create(&t[i], null, &maker, (void *)i); }
    for (i64 i = 0; i < 4; i++) { pthread_join(t[i], null); }
    map.Map<String, i64> all = map.new<String, i64>();
    for (i64 i = 0; i < 4; i++) {
        for (i64 j = 0; j < PER; j++) {
            String k = gIds[i][j].string();
            all.set(k, 1);
            k.free();
        }
    }
    check(gBadOrder == 0 && all.len == 4 * PER, "80000 v7s from 4 threads: increasing, no repeat");
    all.free();

    uuid.UUID p;
    check(uuid.parse(s[0:], &p) && p.equals(a), "parse the canonical form");
    String up = str.from(s[0:]);
    up.toUpper();
    check(uuid.parse(up[0:], &p) && p.equals(a), "upper case");
    String braces = str.format("{%s}", s);
    String urn = str.format("urn:uuid:%s", s);
    String bare;
    for (i64 i = 0; i < s.len; i++) { if (s[i] != '-') { bare.appendByte(s[i]); } }
    check(uuid.parse(braces[0:], &p) && p.equals(a) && uuid.parse(urn[0:], &p) && p.equals(a) &&
          uuid.parse(bare[0:], &p) && p.equals(a), "braces, urn:uuid:, no hyphens");
    check(!uuid.parse("", &p) && !uuid.parse("not-a-uuid", &p) && p.isNil() &&
          !uuid.parse("01929b0e-7c4f-7d32-9a51-3f0c2b8d4e6", &p) &&
          !uuid.parse("01929b0e+7c4f-7d32-9a51-3f0c2b8d4e6a", &p) &&
          !uuid.parse("01929b0e-7c4f-7d32-9a51-3f0c2b8d4e6g", &p), "bad input is refused");
    check(uuid.nil().isNil() && !a.isNil() && uuid.nil().unixMs() == 0, "nil");
    u8 buf[37];
    a.format(buf);
    check(str.equals(cstr.view(buf), s[0:]), "format into a buffer");
    s.free(); up.free(); braces.free(); urn.free(); bare.free();
    test.printResult(gCorrect, gTests);
    printf("====\n");
    return 0;
}
