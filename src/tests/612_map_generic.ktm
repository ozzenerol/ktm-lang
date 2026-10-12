import cstr, map, mem, str, test, vec;

/* map.Map<K, V> (v0.10.0, replacing IntMap/StrMap): any capacity; 100000
 * keys; get/getOr/ref/has; delete and reinsert; iteration and keys()/
 * values() in insertion order with deleted keys skipped; clear then
 * reuse. u8[] keys are copied (changing the caller's buffer after set
 * doesn't change the key, an update keeps the first copy), may be cut
 * from a longer buffer (no NUL after them), empty, or hold a NUL; String
 * keys; pointer, i32 and struct (by its bytes) keys; sequential and
 * strided ints spread (no long probe chains). */

struct Pt { i64 x; i64 y; };

i32 main() {
    printf("Test - map.Map: ");
    i64 correct = 0, tests = 0;

    map.Map<i64, i64> zero = map.new<i64, i64>(0);
    map.Map<i64, i64> odd = map.new<i64, i64>(37);
    for (i64 i = 0; i < 100; i++) { zero.set(i, i); odd.set(i, -i); }
    tests++; if (zero.len == 100 && odd.getOr(99, 0) == -99) { correct++; }
    zero.free(); odd.free();

    ad map.Map<i64, i64> m = map.new<i64, i64>();
    for (i64 i = 0; i < 100000; i++) { m.set(i * 1024, i); }
    i64 got = 0;
    bool all = true;
    for (i64 i = 0; i < 100000 && all; i += 997) { all = m.get(i * 1024, &got) && got == i; }
    tests++; if (all && m.len == 100000 && !m.has(1) && m.getOr(5, -1) == -1) { correct++; }

    for (i64 i = 0; i < 100000; i += 2) { m.delete(i * 1024); }
    tests++; if (m.len == 50000 && !m.has(0) && m.has(1024) && !m.delete(0)) { correct++; }
    for (i64 i = 0; i < 100000; i += 2) { m.set(i * 1024, -i); }
    tests++; if (m.len == 100000 && m.getOr(2048, 0) == -2) { correct++; }
    *m.ref(1024) = 77;
    tests++; if (m.getOr(1024, 0) == 77 && m.ref(3) == null) { correct++; }

    ad map.Map<u8[], i64> sm = map.new<u8[], i64>();
    u8 buf[16];
    cstr.formatTo(buf, "alpha");
    sm.set(buf[0:5], 1);
    buf[0] = 'X';
    tests++; if (sm.getOr("alpha", 0) == 1 && !sm.has(buf[0:5])) { correct++; }
    u8[] longer = "keyboard";
    sm.set(longer[0:3], 3);                 /* "key", no NUL after it */
    sm.set("", 4);
    u8 nul[3] = {'a', 0, 'b'};
    sm.set(nul[0:3], 5);
    tests++; if (sm.getOr("key", 0) == 3 && sm.getOr("", 0) == 4 && sm.getOr(nul[0:3], 0) == 5 &&
                 !sm.has("a")) { correct++; }
    sm.set("key", 30);
    tests++; if (sm.getOr("key", 0) == 30 && sm.len == 4) { correct++; }

    sm.delete("");
    ad vec.Vec<u8[]> keys = sm.keys();
    ad vec.Vec<i64> vals = sm.values();
    tests++; if (keys.len == 3 && str.equals(keys.get(0), "alpha") && str.equals(keys.get(1), "key") &&
                 vals.get(0) == 1 && vals.get(1) == 30 && vals.get(2) == 5) { correct++; }
    i64 it = 0, n = 0, sum = 0;
    u8[] k;
    i64 val;
    while (sm.next(&it, &k, &val)) { n++; sum += val; }
    tests++; if (n == 3 && sum == 36) { correct++; }

    sm.clear();
    sm.set("again", 1);
    tests++; if (sm.len == 1 && sm.getOr("again", 0) == 1 && !sm.has("alpha")) { correct++; }

    ad map.Map<String, f64> strs = map.new<String, f64>();
    String a = str.from("pi");
    strs.set(a, 3.14);
    a.free();
    String b = str.from("pi");
    tests++; if (strs.getOr(b, 0.0) == 3.14) { correct++; }
    b.free();

    i64 x = 1, y = 2;
    ad map.Map<i64 *, u8 *> pm = map.new<i64 *, u8 *>();
    pm.set(&x, "x");
    pm.set(&y, "y");
    ad map.Map<i32, i64> im = map.new<i32, i64>();
    im.set((i32)-5, 5);
    ad map.Map<Pt, i64> ptm = map.new<Pt, i64>();
    Pt p = {1, 2}, q = {1, 2}, r = {2, 1};
    ptm.set(p, 12);
    tests++; if (pm.getOr(&y, "") [0] == 'y' && im.getOr((i32)-5, 0) == 5 &&
                 ptm.getOr(q, 0) == 12 && !ptm.has(r)) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
