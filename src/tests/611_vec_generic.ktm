import test, vec;

/* vec.Vec<T> (v0.10.0, replacing IntVec/PtrVec/FloatVec): any capacity
 * (0 and negative start with room for one), push/get/set/at/last/pop,
 * insert and removeAt at both ends and in the middle, an index out of
 * range and pop/last of an empty Vec throw ERR_BOUNDS (with an `ok`
 * flag they don't), structs by value, range-for over the Vec itself
 * and over items(), clear keeping its room, `ad`. */

struct Pt { i64 x; i64 y; };

i64 gThrown = 0;
void expectBounds(vec.Vec<i64> *v, i64 what) {
    try {
        if (what == 0) { v.get(v.len); }
        if (what == 1) { v.set(-1, 0); }
        if (what == 2) { v.removeAt(v.len); }
        if (what == 3) { v.insert(v.len + 1, 0); }
        if (what == 4) { v.at(-5); }
    } catch (Error e) {
        if (e.code == ERR_BOUNDS) { gThrown++; }
    }
}

i32 main() {
    printf("Test - vec.Vec: ");
    i64 correct = 0, tests = 0;

    vec.Vec<i64> z = vec.new<i64>(0);
    vec.Vec<i64> neg = vec.new<i64>(-3);
    for (i64 i = 0; i < 1000; i++) { z.push(i); neg.push(-i); }
    tests++; if (z.len == 1000 && z.get(999) == 999 && neg.get(500) == -500 && z.cap >= 1000) { correct++; }
    z.free(); neg.free();

    ad vec.Vec<i64> v = vec.new<i64>();
    for (i64 i = 0; i < 10; i++) { v.push(i); }
    v.set(0, 100);
    *v.at(1) += 10;
    tests++; if (v.get(0) == 100 && v.get(1) == 11 && v.last() == 9) { correct++; }

    v.insert(0, -1);
    v.insert(v.len, 99);
    v.insert(5, 55);
    tests++; if (v.len == 13 && v.get(0) == -1 && v.get(5) == 55 && v.last() == 99) { correct++; }
    tests++; if (v.removeAt(5) == 55 && v.removeAt(0) == -1 && v.removeAt(v.len - 1) == 99 &&
                 v.len == 10 && v.get(0) == 100 && v.get(9) == 9) { correct++; }

    for (i64 w = 0; w < 5; w++) { expectBounds(&v, w); }
    tests++; if (gThrown == 5) { correct++; }

    vec.Vec<i64> empty = vec.new<i64>();
    bool ok = true;
    i64 got = empty.pop(&ok);
    bool okLast = true;
    empty.last(&okLast);
    i64 threw = 0;
    try { empty.pop(); } catch (Error e) { if (e.code == ERR_BOUNDS) { threw++; } }
    try { empty.last(); } catch (Error e) { if (e.code == ERR_BOUNDS) { threw++; } }
    tests++; if (!ok && got == 0 && !okLast && threw == 2) { correct++; }
    empty.push(7);
    tests++; if (empty.pop(&ok) == 7 && ok && empty.len == 0) { correct++; }
    empty.free();

    ad vec.Vec<Pt> pts = vec.new<Pt>(1);
    for (i64 i = 0; i < 100; i++) { Pt p = {i, i * 2}; pts.push(p); }
    i64 sum = 0;
    for (auto p : pts) { sum += p.y - p.x; }
    i64 sum2 = 0;
    for (Pt p : pts.items()) { sum2 += p.x; }
    tests++; if (sum == 4950 && sum2 == 4950 && pts.get(42).y == 84) { correct++; }

    i64 cap = v.cap;
    v.clear();
    v.push(1);
    tests++; if (v.len == 1 && v.cap == cap && v.get(0) == 1) { correct++; }

    test.printResult(correct, tests);
    printf("====\n");
    return 0;
}
